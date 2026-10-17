package com.bank.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Payload of InterbankTransferInitiated / Settled / Refunded on account-events.
 * {@code balanceAfter} is the remitter's balance after the debit (initiated) or refund (refunded);
 * {@code utr} is set once the clearing network settles; {@code reasonCode}/{@code reason} on refund.
 */
public record InterbankTransferEvent(
        UUID transferId,
        String customerId,
        String fromAccount,
        String beneficiaryName,
        String beneficiaryAccount,
        String beneficiaryIfsc,
        PaymentRail rail,
        BigDecimal amount,
        String currency,
        BigDecimal balanceAfter,
        String reference,
        String utr,
        String reasonCode,
        String reason,
        Instant occurredAt) {
}
