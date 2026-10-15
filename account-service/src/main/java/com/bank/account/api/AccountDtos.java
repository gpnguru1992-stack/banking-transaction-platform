package com.bank.account.api;

import com.bank.account.domain.Account;
import com.bank.account.domain.AccountStatus;
import com.bank.account.domain.AccountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public final class AccountDtos {

    private AccountDtos() {
    }

    public record OpenAccountRequest(
            @NotBlank @Pattern(regexp = "^CUST[0-9]{4,16}$") String customerId,
            @NotNull AccountType accountType,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency) {
    }

    public record DepositRequest(
            @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 140) String reference) {
    }

    public record ChangeAccountStatusRequest(@NotNull AccountStatus status, @NotBlank @Size(max = 500) String reason) {
    }

    public record AccountResponse(
            String accountNumber,
            String customerId,
            AccountType accountType,
            String currency,
            BigDecimal balance,
            AccountStatus status,
            Instant createdAt) {

        public static AccountResponse from(Account a) {
            return new AccountResponse(a.getAccountNumber(), a.getCustomerId(), a.getAccountType(), a.getCurrency(),
                    a.getBalance(), a.getStatus(), a.getCreatedAt());
        }
    }

    public record BalanceResponse(String accountNumber, BigDecimal balance, String currency, Instant asOf) {
    }
}
