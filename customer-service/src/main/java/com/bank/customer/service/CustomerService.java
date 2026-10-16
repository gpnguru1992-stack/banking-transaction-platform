package com.bank.customer.service;

import com.bank.common.event.CustomerEvent;
import com.bank.common.event.EventTypes;
import com.bank.common.event.Topics;
import com.bank.common.messaging.EventPublisher;
import com.bank.common.security.CurrentUser;
import com.bank.common.web.BusinessException;
import com.bank.common.web.ResourceNotFoundException;
import com.bank.customer.api.CustomerDtos.CreateCustomerRequest;
import com.bank.customer.api.CustomerDtos.CustomerResponse;
import com.bank.customer.api.CustomerDtos.UpdateContactRequest;
import com.bank.customer.domain.Customer;
import com.bank.customer.domain.CustomerRepository;
import com.bank.customer.domain.CustomerStatus;
import com.bank.customer.domain.KycStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class CustomerService {

    public static final String CACHE = "customers";
    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    private final CustomerRepository repository;
    private final EventPublisher eventPublisher;
    private final Clock clock;

    public CustomerService(CustomerRepository repository, EventPublisher eventPublisher, Clock clock) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public CustomerResponse register(CreateCustomerRequest request) {
        if (repository.existsByEmailIgnoreCase(request.email())) {
            throw new BusinessException("CUSTOMER_ALREADY_EXISTS", "A customer with this email already exists", HttpStatus.CONFLICT);
        }
        String customerId = "CUST" + repository.nextCustomerNumber();
        Customer customer = new Customer(customerId, request.firstName(), request.lastName(),
                request.email().toLowerCase(), request.phone(), request.dateOfBirth(),
                mask(request.nationalId()), clock.instant());
        repository.save(customer);
        publish(customer, EventTypes.CUSTOMER_REGISTERED);
        log.info("Customer {} registered by {}", customerId, CurrentUser.username());
        return CustomerResponse.from(customer);
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CACHE, key = "#customerId")
    public CustomerResponse get(String customerId) {
        return CustomerResponse.from(find(customerId));
    }

    @Transactional
    @CachePut(cacheNames = CACHE, key = "#customerId")
    public CustomerResponse updateKyc(String customerId, KycStatus kycStatus, String remarks) {
        Customer customer = find(customerId);
        customer.updateKyc(kycStatus, clock.instant());
        publish(customer, EventTypes.CUSTOMER_UPDATED);
        log.info("KYC of {} set to {} by {} ({})", customerId, kycStatus, CurrentUser.username(), remarks);
        return CustomerResponse.from(customer);
    }

    @Transactional
    @CachePut(cacheNames = CACHE, key = "#customerId")
    public CustomerResponse changeStatus(String customerId, CustomerStatus status, String reason) {
        Customer customer = find(customerId);
        try {
            customer.changeStatus(status, clock.instant());
        } catch (IllegalStateException e) {
            throw new BusinessException("INVALID_STATUS_TRANSITION", e.getMessage());
        }
        publish(customer, EventTypes.CUSTOMER_UPDATED);
        log.info("Status of {} set to {} by {} ({})", customerId, status, CurrentUser.username(), reason);
        return CustomerResponse.from(customer);
    }

    @Transactional
    @CachePut(cacheNames = CACHE, key = "#customerId")
    public CustomerResponse updateContact(String customerId, UpdateContactRequest request) {
        Customer customer = find(customerId);
        if (request.email() != null && !request.email().equalsIgnoreCase(customer.getEmail())
                && repository.existsByEmailIgnoreCase(request.email())) {
            throw new BusinessException("EMAIL_IN_USE", "Email is already used by another customer", HttpStatus.CONFLICT);
        }
        customer.updateContact(request.email() == null ? null : request.email().toLowerCase(), request.phone(),
                request.emailOptIn(), request.smsOptIn(), clock.instant());
        publish(customer, EventTypes.CUSTOMER_UPDATED);
        return CustomerResponse.from(customer);
    }

    private Customer find(String customerId) {
        return repository.findByCustomerId(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", customerId));
    }

    private void publish(Customer c, String eventType) {
        eventPublisher.publish(Topics.CUSTOMER_EVENTS, c.getCustomerId(), eventType, "Customer", c.getCustomerId(),
                new CustomerEvent(c.getCustomerId(), c.fullName(), c.getEmail(), c.getPhone(), c.getStatus().name(),
                        c.getKycStatus().name(), c.isEmailOptIn(), c.isSmsOptIn(), clock.instant()));
    }

    static String mask(String nationalId) {
        if (nationalId == null || nationalId.length() < 4) {
            return null;
        }
        return "*".repeat(nationalId.length() - 4) + nationalId.substring(nationalId.length() - 4);
    }
}
