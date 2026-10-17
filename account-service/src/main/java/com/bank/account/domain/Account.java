package com.bank.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_number", nullable = false, unique = true, length = 20)
    private String accountNumber;

    @Column(name = "customer_id", nullable = false, length = 20)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Account() {
    }

    public Account(String accountNumber, String customerId, AccountType accountType, String currency, Instant now) {
        this.accountNumber = accountNumber;
        this.customerId = customerId;
        this.accountType = accountType;
        this.currency = currency;
        this.balance = BigDecimal.ZERO.setScale(2);
        this.status = AccountStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void debit(BigDecimal amount, Instant now) {
        requirePositive(amount);
        requireActive();
        if (balance.compareTo(amount) < 0) {
            throw new AccountRuleViolation("INSUFFICIENT_FUNDS", "Insufficient balance in account " + accountNumber);
        }
        balance = balance.subtract(amount);
        updatedAt = now;
    }

    public void credit(BigDecimal amount, Instant now) {
        requirePositive(amount);
        requireActive();
        balance = balance.add(amount);
        updatedAt = now;
    }

    /** Returns money for a failed outgoing payment; allowed on frozen accounts, never on closed ones. */
    public void refund(BigDecimal amount, Instant now) {
        requirePositive(amount);
        if (status == AccountStatus.CLOSED) {
            throw new AccountRuleViolation("ACCOUNT_CLOSED", "Account " + accountNumber + " is closed");
        }
        balance = balance.add(amount);
        updatedAt = now;
    }

    public void changeStatus(AccountStatus newStatus, Instant now) {
        if (status == AccountStatus.CLOSED) {
            throw new AccountRuleViolation("ACCOUNT_CLOSED", "Account " + accountNumber + " is closed");
        }
        if (newStatus == AccountStatus.CLOSED && balance.signum() != 0) {
            throw new AccountRuleViolation("BALANCE_NOT_ZERO", "Account must have zero balance before closing");
        }
        status = newStatus;
        updatedAt = now;
    }

    public boolean isOwnedBy(String customerId) {
        return this.customerId.equals(customerId);
    }

    private void requireActive() {
        if (status != AccountStatus.ACTIVE) {
            throw new AccountRuleViolation("ACCOUNT_NOT_ACTIVE", "Account " + accountNumber + " is " + status);
        }
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }

    public Long getId() { return id; }
    public String getAccountNumber() { return accountNumber; }
    public String getCustomerId() { return customerId; }
    public AccountType getAccountType() { return accountType; }
    public String getCurrency() { return currency; }
    public BigDecimal getBalance() { return balance; }
    public AccountStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
