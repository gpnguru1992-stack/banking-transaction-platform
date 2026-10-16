package com.bank.common.event;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountOpenedEvent(
        String accountNumber,
        String customerId,
        String accountType,
        String currency,
        BigDecimal openingBalance,
        Instant openedAt) {
}
