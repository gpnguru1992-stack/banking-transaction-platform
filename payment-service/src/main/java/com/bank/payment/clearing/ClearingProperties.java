package com.bank.payment.clearing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Connection to the clearing network. {@code neftBatchInterval} is 30 minutes in production (RBI NEFT batches);
 * the local stack uses seconds so the demo finishes quickly.
 */
@ConfigurationProperties(prefix = "banking.clearing")
public record ClearingProperties(
        String url,
        Duration connectTimeout,
        Duration readTimeout,
        Duration neftBatchInterval,
        Integer maxAttempts,
        Integer batchSize) {

    public ClearingProperties {
        url = url == null ? "http://localhost:8090" : url;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(3) : readTimeout;
        neftBatchInterval = neftBatchInterval == null ? Duration.ofMinutes(30) : neftBatchInterval;
        maxAttempts = maxAttempts == null ? 10 : maxAttempts;
        batchSize = batchSize == null ? 20 : batchSize;
    }
}
