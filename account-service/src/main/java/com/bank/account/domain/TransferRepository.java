package com.bank.account.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByCustomerIdAndIdempotencyKey(String customerId, String idempotencyKey);

    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM Transfer t
            WHERE t.fromAccount = :fromAccount
              AND t.status = com.bank.account.domain.TransferStatus.COMPLETED
              AND t.createdAt >= :since
            """)
    BigDecimal sumCompletedFromAccountSince(@Param("fromAccount") String fromAccount, @Param("since") Instant since);
}
