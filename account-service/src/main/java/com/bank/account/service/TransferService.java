package com.bank.account.service;

import com.bank.account.api.TransferDtos.TransferRequest;
import com.bank.account.client.CustomerClient;
import com.bank.account.client.CustomerEligibility;
import com.bank.account.config.TransferProperties;
import com.bank.account.domain.Account;
import com.bank.account.domain.AccountRepository;
import com.bank.account.domain.AccountRuleViolation;
import com.bank.account.domain.EntrySource;
import com.bank.account.domain.EntryType;
import com.bank.account.domain.LedgerEntry;
import com.bank.account.domain.LedgerEntryRepository;
import com.bank.account.domain.Transfer;
import com.bank.account.domain.TransferRepository;
import com.bank.account.domain.TransferStatus;
import com.bank.common.event.EventTypes;
import com.bank.common.event.Topics;
import com.bank.common.event.TransferCompletedEvent;
import com.bank.common.event.TransferFailedEvent;
import com.bank.common.messaging.EventPublisher;
import com.bank.common.web.BusinessException;
import com.bank.common.web.ResourceNotFoundException;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Moves money between two accounts held in this service's ledger.
 * <ol>
 *   <li>Duplicate check on (customer, Idempotency-Key): replay the stored outcome.</li>
 *   <li>Static validation (same account, amount limit, currency).</li>
 *   <li>Customer check via customer-service (ACTIVE + KYC VERIFIED), fail closed.</li>
 *   <li>One ACID transaction: lock both rows in a fixed order (no deadlocks), check ownership, status,
 *       currency, daily limit and balance, debit + credit, two ledger lines, transfer row, outbox event.</li>
 * </ol>
 * A business rejection is stored as a FAILED transfer with a TransferFailed event, so it is audited
 * and an identical retry gets the same answer.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final CustomerClient customerClient;
    private final IdempotencyGuard idempotencyGuard;
    private final EventPublisher eventPublisher;
    private final TransferProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final Timer latency;

    public TransferService(AccountRepository accountRepository, TransferRepository transferRepository,
                           LedgerEntryRepository ledgerEntryRepository, CustomerClient customerClient,
                           IdempotencyGuard idempotencyGuard, EventPublisher eventPublisher,
                           TransferProperties properties, PlatformTransactionManager transactionManager,
                           Clock clock, MeterRegistry meterRegistry) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.customerClient = customerClient;
        this.idempotencyGuard = idempotencyGuard;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.latency = Timer.builder("banking.transfer.latency").publishPercentileHistogram().register(meterRegistry);
    }

    public TransferResult transfer(String customerId, String idempotencyKey, TransferRequest request) {
        String requestHash = hash(request);
        var existing = transferRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }
        validate(request);

        try (IdempotencyGuard.Lock ignored = idempotencyGuard.acquire(customerId, idempotencyKey)) {
            existing = transferRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
            if (existing.isPresent()) {
                return replay(existing.get(), requestHash);
            }
            requireEligible(customerId);
            return latency.record(() -> execute(customerId, idempotencyKey, requestHash, request));
        }
    }

    private TransferResult execute(String customerId, String idempotencyKey, String requestHash, TransferRequest request) {
        UUID transferId = UUID.randomUUID();
        try {
            Transfer transfer = transactionTemplate.execute(status ->
                    moveMoney(transferId, customerId, idempotencyKey, requestHash, request));
            record("COMPLETED", "NONE", request);
            log.info("Transfer {} completed: {} {} from {} to {}", transferId, request.amount(), request.currency(),
                    request.fromAccount(), request.toAccount());
            return new TransferResult(transfer, false);
        } catch (AccountRuleViolation violation) {
            transactionTemplate.executeWithoutResult(status ->
                    recordFailure(transferId, customerId, idempotencyKey, requestHash, request, violation));
            record("FAILED", violation.getCode(), request);
            log.info("Transfer {} rejected: {} ({})", transferId, violation.getCode(), violation.getMessage());
            throw new BusinessException(violation.getCode(), violation.getMessage());
        } catch (DataIntegrityViolationException e) {
            // Lost a race with an identical request (only possible when Redis was unavailable).
            return transferRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey)
                    .map(t -> replay(t, requestHash))
                    .orElseThrow(() -> e);
        }
    }

    private Transfer moveMoney(UUID transferId, String customerId, String idempotencyKey, String requestHash,
                               TransferRequest request) {
        Instant now = clock.instant();
        boolean fromFirst = request.fromAccount().compareTo(request.toAccount()) < 0;
        Account first = lock(fromFirst ? request.fromAccount() : request.toAccount());
        Account second = lock(fromFirst ? request.toAccount() : request.fromAccount());
        Account from = fromFirst ? first : second;
        Account to = fromFirst ? second : first;

        if (!from.isOwnedBy(customerId)) {
            throw new BusinessException("ACCOUNT_NOT_OWNED", "Source account does not belong to the customer", HttpStatus.FORBIDDEN);
        }
        if (!from.getCurrency().equals(request.currency()) || !to.getCurrency().equals(request.currency())) {
            throw new AccountRuleViolation("CURRENCY_MISMATCH", "Transfer currency must match both account currencies");
        }
        BigDecimal sentToday = transferRepository.sumCompletedFromAccountSince(from.getAccountNumber(), startOfToday());
        if (sentToday.add(request.amount()).compareTo(properties.dailyLimit()) > 0) {
            throw new AccountRuleViolation("DAILY_LIMIT_EXCEEDED",
                    "Daily transfer limit of " + properties.dailyLimit() + " " + request.currency() + " exceeded");
        }

        from.debit(request.amount(), now);
        to.credit(request.amount(), now);

        String narrative = request.reference() == null ? "Transfer" : request.reference();
        ledgerEntryRepository.save(new LedgerEntry(from.getAccountNumber(), transferId, EntrySource.TRANSFER,
                EntryType.DEBIT, request.amount(), from.getBalance(), request.currency(), narrative, now));
        ledgerEntryRepository.save(new LedgerEntry(to.getAccountNumber(), transferId, EntrySource.TRANSFER,
                EntryType.CREDIT, request.amount(), to.getBalance(), request.currency(), narrative, now));

        Transfer transfer = transferRepository.saveAndFlush(Transfer.completed(transferId, customerId, idempotencyKey,
                requestHash, from.getAccountNumber(), to.getAccountNumber(), request.amount(), request.currency(),
                request.reference(), now));

        eventPublisher.publish(Topics.ACCOUNT_EVENTS, from.getAccountNumber(), EventTypes.TRANSFER_COMPLETED,
                "Transfer", transferId.toString(),
                new TransferCompletedEvent(transferId, from.getAccountNumber(), from.getCustomerId(),
                        to.getAccountNumber(), to.getCustomerId(), request.amount(), request.currency(),
                        from.getBalance(), to.getBalance(), request.reference(), now));
        return transfer;
    }

    private void recordFailure(UUID transferId, String customerId, String idempotencyKey, String requestHash,
                               TransferRequest request, AccountRuleViolation violation) {
        Instant now = clock.instant();
        transferRepository.saveAndFlush(Transfer.failed(transferId, customerId, idempotencyKey, requestHash,
                request.fromAccount(), request.toAccount(), request.amount(), request.currency(), request.reference(),
                violation.getCode(), violation.getMessage(), now));
        eventPublisher.publish(Topics.ACCOUNT_EVENTS, request.fromAccount(), EventTypes.TRANSFER_FAILED,
                "Transfer", transferId.toString(),
                new TransferFailedEvent(transferId, customerId, request.fromAccount(), request.toAccount(),
                        request.amount(), request.currency(), violation.getCode(), violation.getMessage(), now));
    }

    private TransferResult replay(Transfer existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new BusinessException("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was already used with a different request body");
        }
        meterRegistry.counter("banking.transfers.replayed").increment();
        if (existing.getStatus() == TransferStatus.FAILED) {
            throw new BusinessException(existing.getFailureCode(), existing.getFailureReason());
        }
        return new TransferResult(existing, true);
    }

    private void validate(TransferRequest request) {
        if (request.fromAccount().equals(request.toAccount())) {
            throw new BusinessException("SAME_ACCOUNT", "Source and destination accounts must differ", HttpStatus.BAD_REQUEST);
        }
        if (!properties.supportedCurrencies().contains(request.currency())) {
            throw new BusinessException("UNSUPPORTED_CURRENCY", "Currency " + request.currency() + " is not supported");
        }
        if (request.amount().compareTo(properties.maxAmount()) > 0) {
            throw new BusinessException("AMOUNT_LIMIT_EXCEEDED",
                    "Maximum amount per transfer is " + properties.maxAmount() + " " + request.currency());
        }
    }

    private void requireEligible(String customerId) {
        CustomerEligibility eligibility = customerClient.eligibility(customerId);
        if (eligibility == null || !eligibility.eligible()) {
            String reason = eligibility == null ? "unknown" : eligibility.reason();
            throw new BusinessException("CUSTOMER_NOT_ELIGIBLE", "Customer cannot transact: " + reason, HttpStatus.FORBIDDEN);
        }
    }

    private Account lock(String accountNumber) {
        return accountRepository.lockByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountNumber));
    }

    private Instant startOfToday() {
        return LocalDate.now(clock.withZone(properties.zone())).atStartOfDay(properties.zone()).toInstant();
    }

    private void record(String outcome, String code, TransferRequest request) {
        meterRegistry.counter("banking.transfers", "outcome", outcome, "code", code, "currency", request.currency())
                .increment();
        if ("COMPLETED".equals(outcome)) {
            DistributionSummary.builder("banking.transfer.amount").baseUnit(request.currency())
                    .tag("currency", request.currency()).register(meterRegistry)
                    .record(request.amount().doubleValue());
        }
    }

    static String hash(TransferRequest r) {
        String canonical = String.join("|", r.fromAccount(), r.toAccount(), r.amount().stripTrailingZeros().toPlainString(),
                r.currency(), Objects.toString(r.reference(), ""));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Transfer get(UUID transferId) {
        return transferRepository.findById(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer", transferId.toString()));
    }
}
