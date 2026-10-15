package com.bank.transaction.api;

import com.bank.common.security.CurrentUser;
import com.bank.transaction.domain.AuditLog;
import com.bank.transaction.domain.AuditLogRepository;
import com.bank.transaction.domain.TransactionRecord;
import com.bank.transaction.domain.TransactionRecordRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

@RestController
@Validated
@Tag(name = "Transactions & Audit")
public class TransactionController {

    private final TransactionRecordRepository transactions;
    private final AuditLogRepository auditLogs;

    public TransactionController(TransactionRecordRepository transactions, AuditLogRepository auditLogs) {
        this.transactions = transactions;
        this.auditLogs = auditLogs;
    }

    @GetMapping("/api/v1/accounts/{accountNumber}/transactions")
    @Operation(summary = "Account statement (owner or staff), newest first")
    public PageResponse<TransactionResponse> history(
            @PathVariable String accountNumber,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        if (!CurrentUser.isStaff() && !transactions.existsByAccountNumberAndCustomerId(accountNumber, CurrentUser.customerId())) {
            throw new AccessDeniedException("Not your account");
        }
        Instant start = from == null ? Instant.EPOCH : from;
        Instant end = to == null ? Instant.now() : to;
        return PageResponse.of(transactions.findByAccountNumberAndOccurredAtBetweenOrderByOccurredAtDesc(
                accountNumber, start, end, PageRequest.of(page, size)), TransactionResponse::from);
    }

    @GetMapping("/api/v1/audit")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS')")
    @Operation(summary = "Audit trail, filtered by aggregate id (account, transfer, customer) or correlation id")
    public PageResponse<AuditLog> audit(@RequestParam(required = false) String aggregateId,
                                        @RequestParam(required = false) String correlationId,
                                        @RequestParam(defaultValue = "0") @Min(0) int page,
                                        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<AuditLog> result;
        if (aggregateId != null) {
            result = auditLogs.findByAggregateIdOrderByOccurredAtDesc(aggregateId, pageable);
        } else if (correlationId != null) {
            result = auditLogs.findByCorrelationIdOrderByOccurredAt(correlationId, pageable);
        } else {
            result = auditLogs.findAllByOrderByOccurredAtDesc(pageable);
        }
        return PageResponse.of(result, Function.identity());
    }

    public record TransactionResponse(UUID id, UUID referenceId, String accountNumber, String counterpartyAccount,
                                      String type, String direction, BigDecimal amount, String currency,
                                      BigDecimal balanceAfter, String status, String description, Instant occurredAt) {
        static TransactionResponse from(TransactionRecord t) {
            return new TransactionResponse(t.getId(), t.getReferenceId(), t.getAccountNumber(), t.getCounterpartyAccount(),
                    t.getType(), t.getDirection(), t.getAmount(), t.getCurrency(), t.getBalanceAfter(), t.getStatus(),
                    t.getDescription(), t.getOccurredAt());
        }
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements) {
        static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
            return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                    page.getTotalElements());
        }
    }
}
