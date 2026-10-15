package com.bank.account.service;

import com.bank.account.config.TransferProperties;
import com.bank.common.web.BusinessException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Short-lived Redis lock per (customer, Idempotency-Key) so that two identical requests arriving at
 * the same time (double click, client retry) cannot both run. The database unique constraint remains
 * the source of truth; if Redis is down we degrade to the constraint instead of failing the payment.
 */
@Component
public class IdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final TransferProperties properties;
    private final Counter redisUnavailable;

    public IdempotencyGuard(StringRedisTemplate redis, TransferProperties properties, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.properties = properties;
        this.redisUnavailable = Counter.builder("banking.idempotency.redis.unavailable").register(meterRegistry);
    }

    public Lock acquire(String customerId, String idempotencyKey) {
        String key = "account-service:idem:transfer:" + customerId + ":" + idempotencyKey;
        String token = UUID.randomUUID().toString();
        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent(key, token, properties.idempotencyLockTtl());
        } catch (DataAccessException e) {
            redisUnavailable.increment();
            log.warn("Redis unavailable for idempotency lock, relying on database constraint: {}", e.getMessage());
            return () -> { };
        }
        if (Boolean.FALSE.equals(acquired)) {
            throw new BusinessException("REQUEST_IN_PROGRESS",
                    "A request with this Idempotency-Key is already being processed", HttpStatus.CONFLICT);
        }
        return () -> {
            try {
                redis.execute(RELEASE, List.of(key), token);
            } catch (DataAccessException e) {
                log.warn("Could not release idempotency lock {}, it will expire: {}", key, e.getMessage());
            }
        };
    }

    @FunctionalInterface
    public interface Lock extends AutoCloseable {
        @Override
        void close();
    }
}
