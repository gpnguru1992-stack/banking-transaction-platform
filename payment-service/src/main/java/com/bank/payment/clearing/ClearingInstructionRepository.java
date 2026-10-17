package com.bank.payment.clearing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ClearingInstructionRepository extends JpaRepository<ClearingInstruction, UUID> {

    /** Claims one due instruction; SKIP LOCKED lets several payment-service replicas dispatch in parallel. */
    @Query(value = """
            SELECT * FROM clearing_instruction
            WHERE status IN ('QUEUED', 'UNKNOWN') AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<ClearingInstruction> claimNextDue(@Param("now") Instant now);
}
