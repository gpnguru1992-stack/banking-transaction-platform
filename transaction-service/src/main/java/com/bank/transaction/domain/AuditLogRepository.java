package com.bank.transaction.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    Page<AuditLog> findByAggregateIdOrderByOccurredAtDesc(String aggregateId, Pageable pageable);

    Page<AuditLog> findByCorrelationIdOrderByOccurredAt(String correlationId, Pageable pageable);

    Page<AuditLog> findAllByOrderByOccurredAtDesc(Pageable pageable);
}
