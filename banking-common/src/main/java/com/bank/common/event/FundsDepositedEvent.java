package com.bank.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record FundsDepositedEvent(
        UUID depositId,
        String accountNumber,
        String customerId,
        BigDecimal amount,
        String currency,
        BigDecimal balanceAfter,
        String reference,
        Instant depositedAt) {
}
