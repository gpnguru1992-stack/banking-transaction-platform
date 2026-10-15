package com.bank.customer.api;

import com.bank.common.security.CurrentUser;
import com.bank.customer.api.CustomerDtos.CreateCustomerRequest;
import com.bank.customer.api.CustomerDtos.CustomerResponse;
import com.bank.customer.api.CustomerDtos.UpdateContactRequest;
import com.bank.customer.api.CustomerDtos.UpdateKycRequest;
import com.bank.customer.api.CustomerDtos.UpdateStatusRequest;
import com.bank.customer.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS')")
    @Operation(summary = "Onboard a new customer (bank staff)")
    public CustomerResponse register(@Valid @RequestBody CreateCustomerRequest request) {
        return customerService.register(request);
    }

    @GetMapping("/me")
    @Operation(summary = "Profile of the logged-in customer")
    public CustomerResponse me() {
        return customerService.get(CurrentUser.customerId());
    }

    @PatchMapping("/me/contact")
    @Operation(summary = "Update own contact details and notification preferences")
    public CustomerResponse updateMyContact(@Valid @RequestBody UpdateContactRequest request) {
        return customerService.updateContact(CurrentUser.customerId(), request);
    }

    @GetMapping("/{customerId}")
    @Operation(summary = "Get a customer (own profile, or any profile for staff)")
    public CustomerResponse get(@PathVariable String customerId) {
        if (!CurrentUser.isStaff() && !customerId.equals(CurrentUser.customerId())) {
            throw new AccessDeniedException("Not your profile");
        }
        return customerService.get(customerId);
    }

    @PutMapping("/{customerId}/kyc")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS')")
    @Operation(summary = "Record a KYC decision (bank staff)")
    public CustomerResponse updateKyc(@PathVariable String customerId, @Valid @RequestBody UpdateKycRequest request) {
        return customerService.updateKyc(customerId, request.kycStatus(), request.remarks());
    }

    @PutMapping("/{customerId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Suspend, re-activate or close a customer (admin)")
    public CustomerResponse changeStatus(@PathVariable String customerId, @Valid @RequestBody UpdateStatusRequest request) {
        return customerService.changeStatus(customerId, request.status(), request.reason());
    }
}
