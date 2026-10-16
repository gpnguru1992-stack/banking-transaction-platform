package com.bank.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferCompletedEvent(
        UUID transferId,
        String fromAccount,
        String fromCustomerId,
        String toAccount,
        String toCustomerId,
        BigDecimal amount,
        String currency,
        BigDecimal fromBalanceAfter,
        BigDecimal toBalanceAfter,
        String reference,
        Instant completedAt) {
}
