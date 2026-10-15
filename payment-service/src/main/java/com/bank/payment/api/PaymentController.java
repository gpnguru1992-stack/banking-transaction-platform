package com.bank.payment.api;

import com.bank.common.security.CurrentUser;
import com.bank.common.web.ResourceNotFoundException;
import com.bank.payment.domain.Payment;
import com.bank.payment.domain.PaymentRepository;
import com.bank.payment.domain.PaymentStatus;
import com.bank.payment.domain.SettlementLine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@Validated
@Tag(name = "Payments")
public class PaymentController {

    private final PaymentRepository repository;

    public PaymentController(PaymentRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Operation(summary = "Payments made by the logged-in customer, newest first")
    public PageResponse<PaymentResponse> myPayments(@RequestParam(defaultValue = "0") @Min(0) int page,
                                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = repository.findByCustomerIdOrderByOccurredAtDesc(CurrentUser.customerId(), PageRequest.of(page, size));
        return new PageResponse<>(result.map(PaymentResponse::from).getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements());
    }

    @GetMapping("/{transferId}")
    @Operation(summary = "One payment (payer or staff)")
    public PaymentResponse get(@PathVariable UUID transferId) {
        Payment p = repository.findById(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", transferId.toString()));
        if (!CurrentUser.isStaff() && !p.getCustomerId().equals(CurrentUser.customerId())) {
            throw new AccessDeniedException("Not your payment");
        }
        return PaymentResponse.from(p);
    }

    @GetMapping("/settlement")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS')")
    @Operation(summary = "Daily settlement totals by currency and status (bank staff)")
    public List<SettlementLine> settlement(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return repository.settlement(date);
    }

    public record PaymentResponse(UUID transferId, String customerId, String fromAccount, String toAccount,
                                  BigDecimal amount, String currency, String reference, PaymentStatus status,
                                  String failureCode, String failureReason, LocalDate businessDate, Instant occurredAt) {
        static PaymentResponse from(Payment p) {
            return new PaymentResponse(p.getTransferId(), p.getCustomerId(), p.getFromAccount(), p.getToAccount(),
                    p.getAmount(), p.getCurrency(), p.getReference(), p.getStatus(), p.getFailureCode(),
                    p.getFailureReason(), p.getBusinessDate(), p.getOccurredAt());
        }
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements) {
    }
}
