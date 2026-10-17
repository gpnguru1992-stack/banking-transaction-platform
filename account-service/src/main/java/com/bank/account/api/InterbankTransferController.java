package com.bank.account.api;

import com.bank.account.api.InterbankTransferDtos.InterbankTransferRequest;
import com.bank.account.api.InterbankTransferDtos.InterbankTransferResponse;
import com.bank.account.domain.InterbankTransfer;
import com.bank.account.service.InterbankTransferService;
import com.bank.account.service.InterbankTransferService.InterbankResult;
import com.bank.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/interbank-transfers")
@Validated
@Tag(name = "Interbank transfers")
public class InterbankTransferController {

    private final InterbankTransferService service;

    public InterbankTransferController(InterbankTransferService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Send money to an account at another bank (IMPS / NEFT / RTGS)",
            description = "Debits the customer immediately and returns 202 with status PENDING. The clearing network "
                    + "result arrives asynchronously: SETTLED (with UTR) or REFUNDED (money returned). Poll GET /{id}.")
    @ApiResponse(responseCode = "202", description = "Accepted and debited; settlement pending")
    @ApiResponse(responseCode = "422", description = "Rejected by a business rule; no money moved")
    public ResponseEntity<InterbankTransferResponse> send(
            @RequestHeader(TransferController.IDEMPOTENCY_KEY)
            @Pattern(regexp = "^[A-Za-z0-9-]{8,64}$", message = "must be 8-64 letters, digits or dashes") String idempotencyKey,
            @Valid @RequestBody InterbankTransferRequest request) {
        InterbankResult result = service.initiate(CurrentUser.customerId(), idempotencyKey, request);
        InterbankTransfer t = result.transfer();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/interbank-transfers/" + t.getId()))
                .header(TransferController.REPLAYED_HEADER, String.valueOf(result.replayed()))
                .body(InterbankTransferResponse.from(t));
    }

    @GetMapping("/{transferId}")
    @Operation(summary = "Get an interbank transfer and its settlement status")
    public InterbankTransferResponse get(@PathVariable UUID transferId) {
        InterbankTransfer t = service.get(transferId);
        if (!CurrentUser.isStaff() && !t.getCustomerId().equals(CurrentUser.customerId())) {
            throw new AccessDeniedException("Not your transfer");
        }
        return InterbankTransferResponse.from(t);
    }
}
