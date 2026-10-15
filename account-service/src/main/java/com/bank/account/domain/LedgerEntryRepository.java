package com.bank.account.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByReferenceId(UUID referenceId);

    @Query("""
            SELECT COALESCE(SUM(CASE WHEN e.entryType = com.bank.account.domain.EntryType.CREDIT THEN e.amount ELSE -e.amount END), 0)
            FROM LedgerEntry e WHERE e.accountNumber = :accountNumber
            """)
    BigDecimal ledgerBalance(@Param("accountNumber") String accountNumber);
}
