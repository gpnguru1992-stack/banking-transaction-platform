package com.bank.transaction.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface TransactionRecordRepository extends JpaRepository<TransactionRecord, UUID> {

    Page<TransactionRecord> findByAccountNumberAndOccurredAtBetweenOrderByOccurredAtDesc(
            String accountNumber, Instant from, Instant to, Pageable pageable);

    boolean existsByAccountNumberAndCustomerId(String accountNumber, String customerId);
}
