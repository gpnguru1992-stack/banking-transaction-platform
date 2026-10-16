package com.bank.account.api;

import com.bank.account.api.AccountDtos.AccountResponse;
import com.bank.account.api.AccountDtos.BalanceResponse;
import com.bank.account.api.AccountDtos.ChangeAccountStatusRequest;
import com.bank.account.api.AccountDtos.DepositRequest;
import com.bank.account.api.AccountDtos.OpenAccountRequest;
import com.bank.account.domain.Account;
import com.bank.account.service.AccountService;
import com.bank.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
@Validated
@Tag(name = "Accounts")
public class AccountController {

    private final AccountService accountService;
    private final Clock clock;

    public AccountController(AccountService accountService, Clock clock) {
        this.accountService = accountService;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "Accounts of the logged-in customer")
    public List<AccountResponse> myAccounts() {
        return accountService.listForCustomer(CurrentUser.customerId()).stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/{accountNumber}")
    @Operation(summary = "Account details (owner or staff)")
    public AccountResponse get(@PathVariable String accountNumber) {
        return AccountResponse.from(accountService.getForCurrentUser(accountNumber));
    }

    @GetMapping("/{accountNumber}/balance")
    @Operation(summary = "Current balance (owner or staff)")
    public BalanceResponse balance(@PathVariable String accountNumber) {
        Account a = accountService.getForCurrentUser(accountNumber);
        return new BalanceResponse(a.getAccountNumber(), a.getBalance(), a.getCurrency(), clock.instant());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS')")
    @Operation(summary = "Open an account for a KYC-verified customer (bank staff)")
    public AccountResponse open(@Valid @RequestBody OpenAccountRequest request) {
        return AccountResponse.from(accountService.open(request));
    }

    @PostMapping("/{accountNumber}/deposits")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS')")
    @Operation(summary = "Deposit funds (bank staff). Idempotent per Idempotency-Key")
    public AccountResponse deposit(@PathVariable String accountNumber,
                                   @RequestHeader("Idempotency-Key")
                                   @Pattern(regexp = "^[A-Za-z0-9-]{8,64}$") String idempotencyKey,
                                   @Valid @RequestBody DepositRequest request) {
        return AccountResponse.from(accountService.deposit(accountNumber, request.amount(), request.reference(), idempotencyKey));
    }

    @PutMapping("/{accountNumber}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Freeze, unfreeze or close an account (admin)")
    public AccountResponse changeStatus(@PathVariable String accountNumber,
                                        @Valid @RequestBody ChangeAccountStatusRequest request) {
        return AccountResponse.from(accountService.changeStatus(accountNumber, request.status(), request.reason()));
    }
}
