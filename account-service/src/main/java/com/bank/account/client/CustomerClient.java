package com.bank.account.client;

import com.bank.common.web.BusinessException;
import com.bank.common.web.DownstreamServiceException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Synchronous call to customer-service (Account Service -> Customer Service in the architecture).
 * Retries transient failures, opens a circuit when customer-service is unhealthy, and fails closed:
 * if we cannot confirm the customer may transact, no money moves.
 */
@Component
public class CustomerClient {

    static final String NAME = "customer-service";

    private final RestClient restClient;

    public CustomerClient(RestClient customerServiceRestClient) {
        this.restClient = customerServiceRestClient;
    }

    @Retry(name = NAME, fallbackMethod = "eligibilityFallback")
    @CircuitBreaker(name = NAME)
    public CustomerEligibility eligibility(String customerId) {
        return restClient.get()
                .uri("/internal/v1/customers/{customerId}/eligibility", customerId)
                .retrieve()
                .body(CustomerEligibility.class);
    }

    @SuppressWarnings("unused")
    private CustomerEligibility eligibilityFallback(String customerId, Throwable t) {
        if (t instanceof HttpClientErrorException.NotFound) {
            throw new BusinessException("CUSTOMER_NOT_FOUND", "Customer " + customerId + " not found");
        }
        if (t instanceof HttpClientErrorException e) {
            throw new BusinessException("CUSTOMER_CHECK_REJECTED",
                    "Customer verification was rejected (" + e.getStatusCode().value() + ")", HttpStatus.BAD_GATEWAY);
        }
        throw new DownstreamServiceException(NAME, t);
    }
}
