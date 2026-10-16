package com.bank.common.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "banking.outbox")
public record OutboxProperties(
        Boolean enabled,
        Integer batchSize,
        Integer maxAttempts,
        Duration sendTimeout,
        Duration retention) {

    public OutboxProperties {
        enabled = enabled == null || enabled;
        batchSize = batchSize == null ? 100 : batchSize;
        maxAttempts = maxAttempts == null ? 10 : maxAttempts;
        sendTimeout = sendTimeout == null ? Duration.ofSeconds(10) : sendTimeout;
        retention = retention == null ? Duration.ofDays(7) : retention;
    }
}
