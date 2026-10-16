package com.bank.payment.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Page<Payment> findByCustomerIdOrderByOccurredAtDesc(String customerId, Pageable pageable);

    @Query("""
            SELECT new com.bank.payment.domain.SettlementLine(p.currency, p.status, COUNT(p), COALESCE(SUM(p.amount), 0))
            FROM Payment p WHERE p.businessDate = :date
            GROUP BY p.currency, p.status
            ORDER BY p.currency, p.status
            """)
    List<SettlementLine> settlement(@Param("date") LocalDate date);
}
