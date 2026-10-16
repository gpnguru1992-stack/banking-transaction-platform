package com.bank.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Payment record (one per transfer), the payments view of money movement for customers and settlement. */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @Column(name = "transfer_id")
    private UUID transferId;

    @Column(name = "customer_id", nullable = false, length = 20)
    private String customerId;

    @Column(name = "beneficiary_customer_id", length = 20)
    private String beneficiaryCustomerId;

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
    private PaymentStatus status;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected Payment() {
    }

    public static Payment completed(UUID transferId, String customerId, String beneficiaryCustomerId, String from,
                                    String to, BigDecimal amount, String currency, String reference,
                                    LocalDate businessDate, Instant occurredAt, Instant now) {
        Payment p = base(transferId, customerId, from, to, amount, currency, businessDate, occurredAt, now);
        p.beneficiaryCustomerId = beneficiaryCustomerId;
        p.reference = reference;
        p.status = PaymentStatus.COMPLETED;
        return p;
    }

    public static Payment failed(UUID transferId, String customerId, String from, String to, BigDecimal amount,
                                 String currency, String failureCode, String failureReason, LocalDate businessDate,
                                 Instant occurredAt, Instant now) {
        Payment p = base(transferId, customerId, from, to, amount, currency, businessDate, occurredAt, now);
        p.status = PaymentStatus.FAILED;
        p.failureCode = failureCode;
        p.failureReason = failureReason;
        return p;
    }

    private static Payment base(UUID transferId, String customerId, String from, String to, BigDecimal amount,
                                String currency, LocalDate businessDate, Instant occurredAt, Instant now) {
        Payment p = new Payment();
        p.transferId = transferId;
        p.customerId = customerId;
        p.fromAccount = from;
        p.toAccount = to;
        p.amount = amount;
        p.currency = currency;
        p.businessDate = businessDate;
        p.occurredAt = occurredAt;
        p.recordedAt = now;
        return p;
    }

    public UUID getTransferId() { return transferId; }
    public String getCustomerId() { return customerId; }
    public String getBeneficiaryCustomerId() { return beneficiaryCustomerId; }
    public String getFromAccount() { return fromAccount; }
    public String getToAccount() { return toAccount; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getReference() { return reference; }
    public PaymentStatus getStatus() { return status; }
    public String getFailureCode() { return failureCode; }
    public String getFailureReason() { return failureReason; }
    public LocalDate getBusinessDate() { return businessDate; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getRecordedAt() { return recordedAt; }
}
