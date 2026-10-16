package com.bank.account.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Set;

/**
 * Business limits for transfers. Daily limits reset at midnight in the bank's {@code zone}.
 */
@ConfigurationProperties(prefix = "banking.transfer")
public record TransferProperties(
        BigDecimal maxAmount,
        BigDecimal dailyLimit,
        Set<String> supportedCurrencies,
        ZoneId zone,
        Duration idempotencyLockTtl) {

    public TransferProperties {
        maxAmount = maxAmount == null ? new BigDecimal("200000.00") : maxAmount;
        dailyLimit = dailyLimit == null ? new BigDecimal("500000.00") : dailyLimit;
        supportedCurrencies = supportedCurrencies == null || supportedCurrencies.isEmpty() ? Set.of("INR") : supportedCurrencies;
        zone = zone == null ? ZoneId.of("Asia/Kolkata") : zone;
        idempotencyLockTtl = idempotencyLockTtl == null ? Duration.ofSeconds(30) : idempotencyLockTtl;
    }
}
