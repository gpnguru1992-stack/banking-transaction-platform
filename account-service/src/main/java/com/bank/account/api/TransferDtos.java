package com.bank.account.api;

import com.bank.account.domain.Transfer;
import com.bank.account.domain.TransferStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class TransferDtos {

    public static final String ACCOUNT_NUMBER_REGEX = "^ACC[0-9]{4,16}$";

    private TransferDtos() {
    }

    public record TransferRequest(
            @NotBlank @Pattern(regexp = ACCOUNT_NUMBER_REGEX, message = "must be a valid account number") String fromAccount,
            @NotBlank @Pattern(regexp = ACCOUNT_NUMBER_REGEX, message = "must be a valid account number") String toAccount,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO-4217 currency code") String currency,
            @Size(max = 140) String reference) {
    }

    public record TransferResponse(
            UUID transferId,
            TransferStatus status,
            String fromAccount,
            String toAccount,
            BigDecimal amount,
            String currency,
            String reference,
            String failureCode,
            String failureReason,
            Instant createdAt,
            Instant completedAt) {

        public static TransferResponse from(Transfer t) {
            return new TransferResponse(t.getId(), t.getStatus(), t.getFromAccount(), t.getToAccount(), t.getAmount(),
                    t.getCurrency(), t.getReference(), t.getFailureCode(), t.getFailureReason(), t.getCreatedAt(),
                    t.getCompletedAt());
        }
    }
}
