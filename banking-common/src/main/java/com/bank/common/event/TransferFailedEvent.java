package com.bank.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferFailedEvent(
        UUID transferId,
        String customerId,
        String fromAccount,
        String toAccount,
        BigDecimal amount,
        String currency,
        String reasonCode,
        String reason,
        Instant failedAt) {
}
