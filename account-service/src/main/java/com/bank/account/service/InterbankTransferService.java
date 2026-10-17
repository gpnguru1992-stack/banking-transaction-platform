package com.bank.account.service;

import com.bank.account.api.InterbankTransferDtos.InterbankTransferRequest;
import com.bank.account.client.CustomerClient;
import com.bank.account.client.CustomerEligibility;
import com.bank.account.config.InterbankProperties;
import com.bank.account.config.TransferProperties;
import com.bank.account.domain.Account;
import com.bank.account.domain.AccountRepository;
import com.bank.account.domain.AccountRuleViolation;
import com.bank.account.domain.EntrySource;
import com.bank.account.domain.EntryType;
import com.bank.account.domain.InterbankStatus;
import com.bank.account.domain.InterbankTransfer;
import com.bank.account.domain.InterbankTransferRepository;
import com.bank.account.domain.LedgerEntry;
import com.bank.account.domain.LedgerEntryRepository;
import com.bank.account.domain.TransferRepository;
import com.bank.common.event.ClearingResultEvent;
import com.bank.common.event.EventTypes;
import com.bank.common.event.InterbankTransferEvent;
import com.bank.common.event.PaymentRail;
import com.bank.common.event.Topics;
import com.bank.common.event.TransferFailedEvent;
import com.bank.common.messaging.EventPublisher;
import com.bank.common.web.BusinessException;
import com.bank.common.web.ResourceNotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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
 * Outgoing transfers to other banks. The customer is debited at once and the money is held in the
 * {@value #SUSPENSE_ACCOUNT} ledger account until the clearing network answers (via payment-service):
 * <pre>
 *  initiate:  customer  -> SUSPENSE   status PENDING, event InterbankTransferInitiated
 *  settled:   SUSPENSE  -> CLEARED    status SETTLED, event InterbankTransferSettled (UTR)
 *  rejected:  SUSPENSE  -> customer   status REFUNDED, event InterbankTransferRefunded
 * </pre>
 * A timeout is never treated as a rejection; payment-service keeps asking the network until it gets a final answer.
 */
@Service
public class InterbankTransferService {

    public static final String SUSPENSE_ACCOUNT = "GL-IBT-SUSPENSE";
    public static final String CLEARED_ACCOUNT = "GL-IBT-CLEARED";
    private static final Logger log = LoggerFactory.getLogger(InterbankTransferService.class);

    public record InterbankResult(InterbankTransfer transfer, boolean replayed) {
    }

    private final AccountRepository accounts;
    private final InterbankTransferRepository transfers;
    private final TransferRepository internalTransfers;
    private final LedgerEntryRepository ledger;
    private final CustomerClient customerClient;
    private final IdempotencyGuard idempotencyGuard;
    private final EventPublisher eventPublisher;
    private final TransferProperties transferProperties;
    private final InterbankProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public InterbankTransferService(AccountRepository accounts, InterbankTransferRepository transfers,
                                    TransferRepository internalTransfers, LedgerEntryRepository ledger,
                                    CustomerClient customerClient, IdempotencyGuard idempotencyGuard,
                                    EventPublisher eventPublisher, TransferProperties transferProperties,
                                    InterbankProperties properties, PlatformTransactionManager transactionManager,
                                    Clock clock, MeterRegistry meterRegistry) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.internalTransfers = internalTransfers;
        this.ledger = ledger;
        this.customerClient = customerClient;
        this.idempotencyGuard = idempotencyGuard;
        this.eventPublisher = eventPublisher;
        this.transferProperties = transferProperties;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    public InterbankResult initiate(String customerId, String idempotencyKey, InterbankTransferRequest request) {
        String requestHash = hash(request);
        var existing = transfers.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }
        validate(request);
        try (IdempotencyGuard.Lock ignored = idempotencyGuard.acquire(customerId, "interbank:" + idempotencyKey)) {
            existing = transfers.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
            if (existing.isPresent()) {
                return replay(existing.get(), requestHash);
            }
            requireEligible(customerId);
            return execute(customerId, idempotencyKey, requestHash, request);
        }
    }

    private InterbankResult execute(String customerId, String idempotencyKey, String requestHash,
                                    InterbankTransferRequest request) {
        UUID id = UUID.randomUUID();
        try {
            InterbankTransfer t = transactionTemplate.execute(status ->
                    holdFunds(id, customerId, idempotencyKey, requestHash, request));
            count("initiated", "NONE", request.rail());
            log.info("Interbank transfer {} initiated: {} {} {} from {} to {}/{}", id, request.rail(), request.amount(),
                    request.currency(), request.fromAccount(), request.beneficiaryIfsc(), request.beneficiaryAccount());
            return new InterbankResult(t, false);
        } catch (AccountRuleViolation violation) {
            transactionTemplate.executeWithoutResult(status ->
                    recordFailure(id, customerId, idempotencyKey, requestHash, request, violation));
            count("failed", violation.getCode(), request.rail());
            throw new BusinessException(violation.getCode(), violation.getMessage());
        } catch (DataIntegrityViolationException e) {
            return transfers.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey)
                    .map(t -> replay(t, requestHash))
                    .orElseThrow(() -> e);
        }
    }

    private InterbankTransfer holdFunds(UUID id, String customerId, String idempotencyKey, String requestHash,
                                        InterbankTransferRequest request) {
        Instant now = clock.instant();
        Account[] locked = lockInOrder(request.fromAccount(), SUSPENSE_ACCOUNT);
        Account from = locked[0];
        Account suspense = locked[1];

        if (!from.isOwnedBy(customerId)) {
            throw new BusinessException("ACCOUNT_NOT_OWNED", "Source account does not belong to the customer", HttpStatus.FORBIDDEN);
        }
        if (!from.getCurrency().equals(request.currency())) {
            throw new AccountRuleViolation("CURRENCY_MISMATCH", "Transfer currency must match the account currency");
        }
        Instant since = startOfToday();
        BigDecimal sentToday = internalTransfers.sumCompletedFromAccountSince(from.getAccountNumber(), since)
                .add(transfers.sumActiveFromAccountSince(from.getAccountNumber(), since));
        if (sentToday.add(request.amount()).compareTo(transferProperties.dailyLimit()) > 0) {
            throw new AccountRuleViolation("DAILY_LIMIT_EXCEEDED",
                    "Daily transfer limit of " + transferProperties.dailyLimit() + " " + request.currency() + " exceeded");
        }

        from.debit(request.amount(), now);
        suspense.credit(request.amount(), now);
        String narrative = request.rail() + " to " + request.beneficiaryIfsc() + "/" + request.beneficiaryAccount();
        ledger.save(new LedgerEntry(from.getAccountNumber(), id, EntrySource.INTERBANK, EntryType.DEBIT,
                request.amount(), from.getBalance(), request.currency(), narrative, now));
        ledger.save(new LedgerEntry(suspense.getAccountNumber(), id, EntrySource.INTERBANK, EntryType.CREDIT,
                request.amount(), suspense.getBalance(), request.currency(), narrative, now));

        InterbankTransfer transfer = transfers.saveAndFlush(new InterbankTransfer(id, customerId, idempotencyKey,
                requestHash, from.getAccountNumber(), request.beneficiaryName(), request.beneficiaryAccount(),
                request.beneficiaryIfsc(), request.rail(), request.amount(), request.currency(), request.reference(), now));
        publish(EventTypes.INTERBANK_TRANSFER_INITIATED, transfer, from.getBalance(), now);
        return transfer;
    }

    /** Called by the payment-events consumer inside its transaction; a second result for the same transfer is ignored. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyClearingResult(ClearingResultEvent result, boolean settled) {
        InterbankTransfer transfer = transfers.lockById(result.transferId())
                .orElseThrow(() -> new IllegalStateException("Unknown interbank transfer " + result.transferId()));
        if (transfer.getStatus() != InterbankStatus.PENDING) {
            log.info("Ignoring clearing result for {}: already {}", transfer.getId(), transfer.getStatus());
            return;
        }
        Instant now = clock.instant();
        BigDecimal amount = transfer.getAmount();
        if (settled) {
            Account[] locked = lockInOrder(SUSPENSE_ACCOUNT, CLEARED_ACCOUNT);
            Account suspense = locked[0];
            Account cleared = locked[1];
            suspense.debit(amount, now);
            cleared.credit(amount, now);
            String narrative = "Settled UTR " + result.utr();
            ledger.save(new LedgerEntry(suspense.getAccountNumber(), transfer.getId(), EntrySource.INTERBANK,
                    EntryType.DEBIT, amount, suspense.getBalance(), transfer.getCurrency(), narrative, now));
            ledger.save(new LedgerEntry(cleared.getAccountNumber(), transfer.getId(), EntrySource.INTERBANK,
                    EntryType.CREDIT, amount, cleared.getBalance(), transfer.getCurrency(), narrative, now));
            transfer.settle(result.utr(), now);
            publish(EventTypes.INTERBANK_TRANSFER_SETTLED, transfer, null, now);
            count("settled", "NONE", transfer.getRail());
            log.info("Interbank transfer {} settled, UTR {}", transfer.getId(), result.utr());
        } else {
            Account[] locked = lockInOrder(transfer.getFromAccount(), SUSPENSE_ACCOUNT);
            Account from = locked[0];
            Account suspense = locked[1];
            suspense.debit(amount, now);
            from.refund(amount, now);
            String narrative = "Refund: " + result.reasonCode();
            ledger.save(new LedgerEntry(suspense.getAccountNumber(), transfer.getId(), EntrySource.INTERBANK,
                    EntryType.DEBIT, amount, suspense.getBalance(), transfer.getCurrency(), narrative, now));
            ledger.save(new LedgerEntry(from.getAccountNumber(), transfer.getId(), EntrySource.INTERBANK,
                    EntryType.CREDIT, amount, from.getBalance(), transfer.getCurrency(), narrative, now));
            transfer.refund(result.reasonCode(), result.reason(), now);
            publish(EventTypes.INTERBANK_TRANSFER_REFUNDED, transfer, from.getBalance(), now);
            count("refunded", result.reasonCode(), transfer.getRail());
            log.info("Interbank transfer {} rejected ({}), refunded to {}", transfer.getId(), result.reasonCode(),
                    transfer.getFromAccount());
        }
    }

    private void recordFailure(UUID id, String customerId, String idempotencyKey, String requestHash,
                               InterbankTransferRequest request, AccountRuleViolation violation) {
        Instant now = clock.instant();
        InterbankTransfer transfer = new InterbankTransfer(id, customerId, idempotencyKey, requestHash,
                request.fromAccount(), request.beneficiaryName(), request.beneficiaryAccount(), request.beneficiaryIfsc(),
                request.rail(), request.amount(), request.currency(), request.reference(), now);
        transfer.fail(violation.getCode(), violation.getMessage());
        transfers.saveAndFlush(transfer);
        eventPublisher.publish(Topics.ACCOUNT_EVENTS, request.fromAccount(), EventTypes.TRANSFER_FAILED,
                "InterbankTransfer", id.toString(),
                new TransferFailedEvent(id, customerId, request.fromAccount(), request.beneficiaryAccount(),
                        request.amount(), request.currency(), violation.getCode(), violation.getMessage(), now));
    }

    private void publish(String eventType, InterbankTransfer t, BigDecimal balanceAfter, Instant now) {
        eventPublisher.publish(Topics.ACCOUNT_EVENTS, t.getFromAccount(), eventType, "InterbankTransfer",
                t.getId().toString(),
                new InterbankTransferEvent(t.getId(), t.getCustomerId(), t.getFromAccount(), t.getBeneficiaryName(),
                        t.getBeneficiaryAccount(), t.getBeneficiaryIfsc(), t.getRail(), t.getAmount(), t.getCurrency(),
                        balanceAfter, t.getReference(), t.getUtr(), t.getFailureCode(), t.getFailureReason(), now));
    }

    private void validate(InterbankTransferRequest r) {
        if (r.beneficiaryIfsc().startsWith(properties.ownBankCode())) {
            throw new BusinessException("USE_INTERNAL_TRANSFER",
                    "Beneficiary is a customer of this bank; use /api/v1/transfers", HttpStatus.BAD_REQUEST);
        }
        if (!transferProperties.supportedCurrencies().contains(r.currency())) {
            throw new BusinessException("UNSUPPORTED_CURRENCY", "Currency " + r.currency() + " is not supported");
        }
        if (r.amount().compareTo(properties.maxAmount()) > 0) {
            throw new BusinessException("AMOUNT_LIMIT_EXCEEDED",
                    "Maximum amount per interbank transfer is " + properties.maxAmount() + " " + r.currency());
        }
        if (r.rail() == PaymentRail.IMPS && r.amount().compareTo(properties.impsMaxAmount()) > 0) {
            throw new BusinessException("RAIL_LIMIT_EXCEEDED",
                    "IMPS allows at most " + properties.impsMaxAmount() + " " + r.currency() + "; use NEFT or RTGS");
        }
        if (r.rail() == PaymentRail.RTGS && r.amount().compareTo(properties.rtgsMinAmount()) < 0) {
            throw new BusinessException("RAIL_MINIMUM_NOT_MET",
                    "RTGS requires at least " + properties.rtgsMinAmount() + " " + r.currency() + "; use IMPS or NEFT");
        }
    }

    private InterbankResult replay(InterbankTransfer existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new BusinessException("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was already used with a different request body");
        }
        meterRegistry.counter("banking.interbank.replayed").increment();
        if (existing.getStatus() == InterbankStatus.FAILED) {
            throw new BusinessException(existing.getFailureCode(), existing.getFailureReason());
        }
        return new InterbankResult(existing, true);
    }

    private void requireEligible(String customerId) {
        CustomerEligibility eligibility = customerClient.eligibility(customerId);
        if (eligibility == null || !eligibility.eligible()) {
            String reason = eligibility == null ? "unknown" : eligibility.reason();
            throw new BusinessException("CUSTOMER_NOT_ELIGIBLE", "Customer cannot transact: " + reason, HttpStatus.FORBIDDEN);
        }
    }

    /** Locks two accounts in account-number order, the same global order internal transfers use. Returns them as given. */
    private Account[] lockInOrder(String a, String b) {
        boolean aFirst = a.compareTo(b) < 0;
        Account first = lock(aFirst ? a : b);
        Account second = lock(aFirst ? b : a);
        return aFirst ? new Account[]{first, second} : new Account[]{second, first};
    }

    private Account lock(String accountNumber) {
        return accounts.lockByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountNumber));
    }

    private Instant startOfToday() {
        return LocalDate.now(clock.withZone(transferProperties.zone())).atStartOfDay(transferProperties.zone()).toInstant();
    }

    private void count(String outcome, String code, PaymentRail rail) {
        meterRegistry.counter("banking.interbank.transfers", "outcome", outcome, "code", code, "rail", rail.name())
                .increment();
    }

    static String hash(InterbankTransferRequest r) {
        String canonical = String.join("|", r.fromAccount(), r.beneficiaryName(), r.beneficiaryAccount(),
                r.beneficiaryIfsc(), r.rail().name(), r.amount().stripTrailingZeros().toPlainString(), r.currency(),
                Objects.toString(r.reference(), ""));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public InterbankTransfer get(UUID id) {
        return transfers.findById(id).orElseThrow(() -> new ResourceNotFoundException("InterbankTransfer", id.toString()));
    }
}
