package com.bank.account.service;

import com.bank.account.api.AccountDtos.OpenAccountRequest;
import com.bank.account.client.CustomerClient;
import com.bank.account.client.CustomerEligibility;
import com.bank.account.config.TransferProperties;
import com.bank.account.domain.Account;
import com.bank.account.domain.AccountRepository;
import com.bank.account.domain.AccountRuleViolation;
import com.bank.account.domain.AccountStatus;
import com.bank.account.domain.EntrySource;
import com.bank.account.domain.EntryType;
import com.bank.account.domain.LedgerEntry;
import com.bank.account.domain.LedgerEntryRepository;
import com.bank.common.event.AccountOpenedEvent;
import com.bank.common.event.EventTypes;
import com.bank.common.event.FundsDepositedEvent;
import com.bank.common.event.Topics;
import com.bank.common.messaging.EventPublisher;
import com.bank.common.security.CurrentUser;
import com.bank.common.web.BusinessException;
import com.bank.common.web.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final CustomerClient customerClient;
    private final EventPublisher eventPublisher;
    private final TransferProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public AccountService(AccountRepository accountRepository, LedgerEntryRepository ledgerEntryRepository,
                          CustomerClient customerClient, EventPublisher eventPublisher,
                          TransferProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.customerClient = customerClient;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.clock = clock;
    }

    /** Opening an account requires an ACTIVE, KYC-verified customer (checked in customer-service). */
    public Account open(OpenAccountRequest request) {
        if (!properties.supportedCurrencies().contains(request.currency())) {
            throw new BusinessException("UNSUPPORTED_CURRENCY", "Currency " + request.currency() + " is not supported");
        }
        CustomerEligibility eligibility = customerClient.eligibility(request.customerId());
        if (eligibility == null || !eligibility.eligible()) {
            throw new BusinessException("CUSTOMER_NOT_ELIGIBLE",
                    "Cannot open account: " + (eligibility == null ? "unknown" : eligibility.reason()));
        }
        return transactionTemplate.execute(status -> openInternal(request));
    }

    private Account openInternal(OpenAccountRequest request) {
        Instant now = clock.instant();
        String number = "ACC" + accountRepository.nextAccountNumber();
        Account account = accountRepository.save(new Account(number, request.customerId(), request.accountType(),
                request.currency(), now));
        eventPublisher.publish(Topics.ACCOUNT_EVENTS, number, EventTypes.ACCOUNT_OPENED, "Account", number,
                new AccountOpenedEvent(number, request.customerId(), request.accountType().name(), request.currency(),
                        account.getBalance(), now));
        log.info("Account {} opened for {} by {}", number, request.customerId(), CurrentUser.username());
        return account;
    }

    /**
     * Branch/ops deposit. The Idempotency-Key is turned into a deterministic reference id so a retried
     * deposit is recognised and not credited twice.
     */
    @Transactional
    public Account deposit(String accountNumber, BigDecimal amount, String reference, String idempotencyKey) {
        UUID depositId = UUID.nameUUIDFromBytes(("deposit:" + accountNumber + ":" + idempotencyKey)
                .getBytes(StandardCharsets.UTF_8));
        Account account = accountRepository.lockByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountNumber));
        if (!ledgerEntryRepository.findByReferenceId(depositId).isEmpty()) {
            return account;
        }
        Instant now = clock.instant();
        try {
            account.credit(amount, now);
        } catch (AccountRuleViolation v) {
            throw new BusinessException(v.getCode(), v.getMessage());
        }
        ledgerEntryRepository.save(new LedgerEntry(accountNumber, depositId, EntrySource.DEPOSIT, EntryType.CREDIT,
                amount, account.getBalance(), account.getCurrency(), reference, now));
        eventPublisher.publish(Topics.ACCOUNT_EVENTS, accountNumber, EventTypes.FUNDS_DEPOSITED, "Account", accountNumber,
                new FundsDepositedEvent(depositId, accountNumber, account.getCustomerId(), amount, account.getCurrency(),
                        account.getBalance(), reference, now));
        log.info("Deposit {} of {} into {} by {}", depositId, amount, accountNumber, CurrentUser.username());
        return account;
    }

    @Transactional
    public Account changeStatus(String accountNumber, AccountStatus status, String reason) {
        Account account = accountRepository.lockByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountNumber));
        try {
            account.changeStatus(status, clock.instant());
        } catch (AccountRuleViolation v) {
            throw new BusinessException(v.getCode(), v.getMessage(), HttpStatus.CONFLICT);
        }
        log.info("Account {} set to {} by {} ({})", accountNumber, status, CurrentUser.username(), reason);
        return account;
    }

    @Transactional(readOnly = true)
    public Account getForCurrentUser(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountNumber));
        if (!CurrentUser.isStaff() && !account.isOwnedBy(CurrentUser.customerId())) {
            // Same response as "not found" would leak existence; 403 is explicit and audited.
            throw new AccessDeniedException("Not your account");
        }
        return account;
    }

    @Transactional(readOnly = true)
    public List<Account> listForCustomer(String customerId) {
        return accountRepository.findByCustomerIdOrderByCreatedAt(customerId);
    }
}
