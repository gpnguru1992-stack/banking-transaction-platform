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
 * Immutable double-entry ledger line. A transfer always writes one DEBIT and one CREDIT with the same
 * {@code referenceId}, so the sum of all entries per account reconciles to its balance.
 */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {

    @Id
    private UUID id;

    @Column(name = "account_number", nullable = false, length = 20)
    private String accountNumber;

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EntrySource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 10)
    private EntryType entryType;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(length = 140)
    private String narrative;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    public LedgerEntry(String accountNumber, UUID referenceId, EntrySource source, EntryType entryType,
                       BigDecimal amount, BigDecimal balanceAfter, String currency, String narrative, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.accountNumber = accountNumber;
        this.referenceId = referenceId;
        this.source = source;
        this.entryType = entryType;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.currency = currency;
        this.narrative = narrative;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getAccountNumber() { return accountNumber; }
    public UUID getReferenceId() { return referenceId; }
    public EntrySource getSource() { return source; }
    public EntryType getEntryType() { return entryType; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public String getCurrency() { return currency; }
    public String getNarrative() { return narrative; }
    public Instant getCreatedAt() { return createdAt; }
}
