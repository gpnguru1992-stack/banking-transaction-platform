package com.bank.payment.clearing;

import com.bank.common.event.PaymentRail;
import com.bank.common.security.CurrentUser;
import com.bank.common.web.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments/interbank")
@Tag(name = "Interbank payments")
public class InterbankPaymentController {

    public record ClearingView(UUID transferId, PaymentRail rail, ClearingStatus status, int attempts, String utr,
                               String failureCode, String failureReason, String beneficiaryIfsc, BigDecimal amount,
                               String currency, Instant createdAt, Instant completedAt) {
    }

    private final ClearingInstructionRepository repository;

    public InterbankPaymentController(ClearingInstructionRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{transferId}")
    @Operation(summary = "Clearing-network status of an interbank transfer")
    public ClearingView get(@PathVariable UUID transferId) {
        ClearingInstruction i = repository.findById(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("ClearingInstruction", transferId.toString()));
        if (!CurrentUser.isStaff() && !i.getCustomerId().equals(CurrentUser.customerId())) {
            throw new AccessDeniedException("Not your payment");
        }
        return new ClearingView(i.getTransferId(), i.getRail(), i.getStatus(), i.getAttempts(), i.getUtr(),
                i.getFailureCode(), i.getFailureReason(), i.getBeneficiaryIfsc(), i.getAmount(), i.getCurrency(),
                i.getCreatedAt(), i.getCompletedAt());
    }
}
