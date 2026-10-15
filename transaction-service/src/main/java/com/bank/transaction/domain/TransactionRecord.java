package com.bank.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One line in an account's statement. A transfer produces a DEBIT line for the payer and a CREDIT line for the payee. */
@Entity
@Table(name = "transaction_record")
public class TransactionRecord {

    @Id
    private UUID id;

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    @Column(name = "account_number", nullable = false, length = 20)
    private String accountNumber;

    @Column(name = "customer_id", nullable = false, length = 20)
    private String customerId;

    @Column(name = "counterparty_account", length = 20)
    private String counterpartyAccount;

    @Column(nullable = false, length = 20)
    private String type;

    @Column(nullable = false, length = 10)
    private String direction;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "balance_after", precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 500)
    private String description;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected TransactionRecord() {
    }

    public TransactionRecord(UUID referenceId, String accountNumber, String customerId, String counterpartyAccount,
                             String type, String direction, BigDecimal amount, String currency, BigDecimal balanceAfter,
                             String status, String description, Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.referenceId = referenceId;
        this.accountNumber = accountNumber;
        this.customerId = customerId;
        this.counterpartyAccount = counterpartyAccount;
        this.type = type;
        this.direction = direction;
        this.amount = amount;
        this.currency = currency;
        this.balanceAfter = balanceAfter;
        this.status = status;
        this.description = description;
        this.occurredAt = occurredAt;
    }

    public UUID getId() { return id; }
    public UUID getReferenceId() { return referenceId; }
    public String getAccountNumber() { return accountNumber; }
    public String getCustomerId() { return customerId; }
    public String getCounterpartyAccount() { return counterpartyAccount; }
    public String getType() { return type; }
    public String getDirection() { return direction; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public String getStatus() { return status; }
    public String getDescription() { return description; }
    public Instant getOccurredAt() { return occurredAt; }
}
