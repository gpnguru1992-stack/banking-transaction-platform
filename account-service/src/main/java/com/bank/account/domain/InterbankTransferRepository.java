package com.bank.account.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface InterbankTransferRepository extends JpaRepository<InterbankTransfer, UUID> {

    Optional<InterbankTransfer> findByCustomerIdAndIdempotencyKey(String customerId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM InterbankTransfer t WHERE t.id = :id")
    Optional<InterbankTransfer> lockById(@Param("id") UUID id);

    /** Money that has left (or is leaving) the account today; refunded and failed transfers do not count. */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM InterbankTransfer t
            WHERE t.fromAccount = :fromAccount
              AND t.status IN (com.bank.account.domain.InterbankStatus.PENDING, com.bank.account.domain.InterbankStatus.SETTLED)
              AND t.createdAt >= :since
            """)
    BigDecimal sumActiveFromAccountSince(@Param("fromAccount") String fromAccount, @Param("since") Instant since);
}
