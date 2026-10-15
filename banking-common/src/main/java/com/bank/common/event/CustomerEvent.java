package com.bank.common.event;

import java.time.Instant;

/**
 * Event-carried state transfer of the customer contact profile so that consumers
 * (e.g. notification-service) do not need synchronous calls to customer-service.
 */
public record CustomerEvent(
        String customerId,
        String fullName,
        String email,
        String phone,
        String status,
        String kycStatus,
        boolean emailOptIn,
        boolean smsOptIn,
        Instant occurredAt) {
}
