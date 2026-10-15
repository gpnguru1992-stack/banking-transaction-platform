package com.bank.customer.api;

import com.bank.customer.api.CustomerDtos.CustomerEligibilityResponse;
import com.bank.customer.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service API. Not routed by the API gateway and blocked by a Kubernetes NetworkPolicy
 * for anything except account-service; still requires a valid JWT.
 */
@RestController
@RequestMapping("/internal/v1/customers")
@Tag(name = "Internal")
public class InternalCustomerController {

    private final CustomerService customerService;

    public InternalCustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping("/{customerId}/eligibility")
    @Operation(summary = "Can this customer move money? (status ACTIVE and KYC VERIFIED)")
    public CustomerEligibilityResponse eligibility(@PathVariable String customerId) {
        return CustomerEligibilityResponse.from(customerService.get(customerId));
    }
}
