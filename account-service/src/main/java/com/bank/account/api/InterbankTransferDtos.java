package com.bank.account.api;

import com.bank.account.domain.InterbankStatus;
import com.bank.account.domain.InterbankTransfer;
import com.bank.common.event.PaymentRail;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class InterbankTransferDtos {

    public static final String IFSC_REGEX = "^[A-Z]{4}0[A-Z0-9]{6}$";

    private InterbankTransferDtos() {
    }

    public record InterbankTransferRequest(
            @NotBlank @Pattern(regexp = TransferDtos.ACCOUNT_NUMBER_REGEX, message = "must be a valid account number") String fromAccount,
            @NotBlank @Size(max = 100) @Pattern(regexp = "^[A-Za-z][A-Za-z .'-]*$", message = "must be a person or business name") String beneficiaryName,
            @NotBlank @Pattern(regexp = "^[0-9]{9,18}$", message = "must be 9-18 digits") String beneficiaryAccount,
            @NotBlank @Pattern(regexp = IFSC_REGEX, message = "must be a valid IFSC, e.g. HDFC0001234") String beneficiaryIfsc,
            @NotNull PaymentRail rail,
            @NotNull @DecimalMin(value = "1.00") @Digits(integer = 15, fraction = 2) BigDecimal amount,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO-4217 currency code") String currency,
            @Size(max = 140) String reference) {
    }

    public record InterbankTransferResponse(
            UUID transferId,
            InterbankStatus status,
            PaymentRail rail,
            String fromAccount,
            String beneficiaryName,
            String beneficiaryAccount,
            String beneficiaryIfsc,
            BigDecimal amount,
            String currency,
            String reference,
            String utr,
            String failureCode,
            String failureReason,
            Instant createdAt,
            Instant completedAt) {

        public static InterbankTransferResponse from(InterbankTransfer t) {
            return new InterbankTransferResponse(t.getId(), t.getStatus(), t.getRail(), t.getFromAccount(),
                    t.getBeneficiaryName(), t.getBeneficiaryAccount(), t.getBeneficiaryIfsc(), t.getAmount(),
                    t.getCurrency(), t.getReference(), t.getUtr(), t.getFailureCode(), t.getFailureReason(),
                    t.getCreatedAt(), t.getCompletedAt());
        }
    }
}
