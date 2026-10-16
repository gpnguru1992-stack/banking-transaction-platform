package com.bank.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One transfer request. (customer_id, idempotency_key) is unique, which is the source of truth for
 * duplicate prevention: a retried request returns the stored outcome instead of moving money twice.
 */
@Entity
@Table(name = "transfer")
public class Transfer {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false, length = 20)
    private String customerId;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "from_account", nullable = false, length = 20)
    private String fromAccount;

    @Column(name = "to_account", nullable = false, length = 20)
    private String toAccount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(length = 140)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Transfer() {
    }

    private Transfer(UUID id, String customerId, String idempotencyKey, String requestHash, String fromAccount,
                     String toAccount, BigDecimal amount, String currency, String reference, Instant createdAt) {
        this.id = id;
        this.customerId = customerId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.fromAccount = fromAccount;
        this.toAccount = toAccount;
        this.amount = amount;
        this.currency = currency;
        this.reference = reference;
        this.createdAt = createdAt;
    }

    public static Transfer completed(UUID id, String customerId, String idempotencyKey, String requestHash,
                                     String fromAccount, String toAccount, BigDecimal amount, String currency,
                                     String reference, Instant now) {
        Transfer t = new Transfer(id, customerId, idempotencyKey, requestHash, fromAccount, toAccount, amount,
                currency, reference, now);
        t.status = TransferStatus.COMPLETED;
        t.completedAt = now;
        return t;
    }

    public static Transfer failed(UUID id, String customerId, String idempotencyKey, String requestHash,
                                  String fromAccount, String toAccount, BigDecimal amount, String currency,
                                  String reference, String failureCode, String failureReason, Instant now) {
        Transfer t = new Transfer(id, customerId, idempotencyKey, requestHash, fromAccount, toAccount, amount,
                currency, reference, now);
        t.status = TransferStatus.FAILED;
        t.failureCode = failureCode;
        t.failureReason = failureReason;
        return t;
    }

    public UUID getId() { return id; }
    public String getCustomerId() { return customerId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public String getFromAccount() { return fromAccount; }
    public String getToAccount() { return toAccount; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getReference() { return reference; }
    public TransferStatus getStatus() { return status; }
    public String getFailureCode() { return failureCode; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
