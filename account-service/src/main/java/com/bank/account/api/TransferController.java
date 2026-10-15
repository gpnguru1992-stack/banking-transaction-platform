package com.bank.account.api;

import com.bank.account.api.TransferDtos.TransferRequest;
import com.bank.account.api.TransferDtos.TransferResponse;
import com.bank.account.domain.Transfer;
import com.bank.account.service.TransferResult;
import com.bank.account.service.TransferService;
import com.bank.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
@RequestMapping("/api/v1/transfers")
@Validated
@Tag(name = "Transfers")
public class TransferController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Transfer money between accounts",
            description = "Synchronous, atomic debit + credit. Send a unique Idempotency-Key per transfer; "
                    + "retrying with the same key and body returns the original result and never moves money twice.")
    @ApiResponse(responseCode = "201", description = "Transfer completed")
    @ApiResponse(responseCode = "422", description = "Business rule rejected the transfer (e.g. INSUFFICIENT_FUNDS)")
    @ApiResponse(responseCode = "409", description = "Same Idempotency-Key is in flight")
    public ResponseEntity<TransferResponse> transfer(
            @Parameter(description = "Client-generated unique key, e.g. a UUID")
            @RequestHeader(IDEMPOTENCY_KEY)
            @Pattern(regexp = "^[A-Za-z0-9-]{8,64}$", message = "must be 8-64 letters, digits or dashes") String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        TransferResult result = transferService.transfer(CurrentUser.customerId(), idempotencyKey, request);
        Transfer t = result.transfer();
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/transfers/" + t.getId()))
                .header(REPLAYED_HEADER, String.valueOf(result.replayed()))
                .body(TransferResponse.from(t));
    }

    @GetMapping("/{transferId}")
    @Operation(summary = "Get a transfer initiated by the logged-in customer")
    public TransferResponse get(@PathVariable UUID transferId) {
        Transfer t = transferService.get(transferId);
        if (!CurrentUser.isStaff() && !t.getCustomerId().equals(CurrentUser.customerId())) {
            throw new AccessDeniedException("Not your transfer");
        }
        return TransferResponse.from(t);
    }
}
