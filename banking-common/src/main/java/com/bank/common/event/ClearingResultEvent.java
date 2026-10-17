package com.bank.common.event;

import java.time.Instant;
import java.util.UUID;

/** Payload of InterbankClearingSettled / InterbankClearingRejected on payment-events. */
public record ClearingResultEvent(
        UUID transferId,
        String utr,
        String reasonCode,
        String reason,
        Instant decidedAt) {
}
