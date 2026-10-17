package com.bank.account.domain;

import com.bank.common.event.PaymentRail;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** An outgoing payment to another bank. State machine: PENDING -> SETTLED | REFUNDED, or FAILED up front. */
@Entity
@Table(name = "interbank_transfer")
public class InterbankTransfer {

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
    @Column(name = "beneficiary_name", nullable = false, length = 100)
    private String beneficiaryName;
    @Column(name = "beneficiary_account", nullable = false, length = 18)
    private String beneficiaryAccount;
    @Column(name = "beneficiary_ifsc", nullable = false, length = 11)
    private String beneficiaryIfsc;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PaymentRail rail;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(length = 140)
    private String reference;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InterbankStatus status;
    @Column(length = 40)
    private String utr;
    @Column(name = "failure_code", length = 50)
    private String failureCode;
    @Column(name = "failure_reason", length = 500)
    private String failureReason;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Version
    private long version;

    protected InterbankTransfer() {
    }

    public InterbankTransfer(UUID id, String customerId, String idempotencyKey, String requestHash, String fromAccount,
                             String beneficiaryName, String beneficiaryAccount, String beneficiaryIfsc, PaymentRail rail,
                             BigDecimal amount, String currency, String reference, Instant now) {
        this.id = id;
        this.customerId = customerId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.fromAccount = fromAccount;
        this.beneficiaryName = beneficiaryName;
        this.beneficiaryAccount = beneficiaryAccount;
        this.beneficiaryIfsc = beneficiaryIfsc;
        this.rail = rail;
        this.amount = amount;
        this.currency = currency;
        this.reference = reference;
        this.status = InterbankStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void settle(String utr, Instant now) {
        requirePending();
        this.status = InterbankStatus.SETTLED;
        this.utr = utr;
        this.updatedAt = now;
        this.completedAt = now;
    }

    public void refund(String code, String reason, Instant now) {
        requirePending();
        this.status = InterbankStatus.REFUNDED;
        this.failureCode = code;
        this.failureReason = reason;
        this.updatedAt = now;
        this.completedAt = now;
    }

    public void fail(String code, String reason) {
        this.status = InterbankStatus.FAILED;
        this.failureCode = code;
        this.failureReason = reason;
    }

    private void requirePending() {
        if (status != InterbankStatus.PENDING) {
            throw new IllegalStateException("Interbank transfer " + id + " is already " + status);
        }
    }

    public UUID getId() { return id; }
    public String getCustomerId() { return customerId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public String getFromAccount() { return fromAccount; }
    public String getBeneficiaryName() { return beneficiaryName; }
    public String getBeneficiaryAccount() { return beneficiaryAccount; }
    public String getBeneficiaryIfsc() { return beneficiaryIfsc; }
    public PaymentRail getRail() { return rail; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getReference() { return reference; }
    public InterbankStatus getStatus() { return status; }
    public String getUtr() { return utr; }
    public String getFailureCode() { return failureCode; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
