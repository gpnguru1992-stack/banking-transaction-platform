package com.bank.customer.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByCustomerId(String customerId);

    boolean existsByEmailIgnoreCase(String email);

    @Query(value = "SELECT nextval('customer_number_seq')", nativeQuery = true)
    long nextCustomerNumber();
}
