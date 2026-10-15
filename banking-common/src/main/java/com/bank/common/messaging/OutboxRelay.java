package com.bank.common.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Polls the outbox and publishes pending events to Kafka in creation order. A failed send stops the
 * batch so that per-key ordering is preserved; the row is retried on the next tick.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final OutboxProperties properties;
    private final Clock clock;
    private final Counter published;
    private final Counter failed;

    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                       TransactionTemplate transactionTemplate, OutboxProperties properties, Clock clock,
                       MeterRegistry meterRegistry) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.clock = clock;
        this.published = Counter.builder("outbox.events.published").register(meterRegistry);
        this.failed = Counter.builder("outbox.events.failed").register(meterRegistry);
        Gauge.builder("outbox.events.pending", repository, r -> r.countByStatus(OutboxStatus.PENDING))
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${banking.outbox.poll-interval:500}")
    public void relay() {
        if (!properties.enabled()) {
            return;
        }
        Integer sent;
        do {
            sent = transactionTemplate.execute(status -> publishBatch());
        } while (sent != null && sent == properties.batchSize());
    }

    private int publishBatch() {
        List<OutboxEvent> batch = repository.lockPendingBatch(properties.batchSize());
        int sent = 0;
        for (OutboxEvent event : batch) {
            try {
                ProducerRecord<String, String> record =
                        new ProducerRecord<>(event.getTopic(), event.getMessageKey(), event.getPayload());
                record.headers().add("eventType", event.getEventType().getBytes(StandardCharsets.UTF_8));
                record.headers().add("eventId", event.getId().toString().getBytes(StandardCharsets.UTF_8));
                kafkaTemplate.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                event.markPublished(clock.instant());
                published.increment();
                sent++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                event.markAttemptFailed("interrupted", properties.maxAttempts());
                break;
            } catch (Exception e) {
                failed.increment();
                event.markAttemptFailed(e.getMessage(), properties.maxAttempts());
                log.warn("Outbox publish failed for event {} ({}), attempt {}: {}",
                        event.getId(), event.getEventType(), event.getAttempts(), e.getMessage());
                break;
            }
        }
        return sent;
    }

    @Scheduled(cron = "${banking.outbox.cleanup-cron:0 0 * * * *}")
    public void purgePublished() {
        Integer deleted = transactionTemplate.execute(
                status -> repository.deletePublishedBefore(clock.instant().minus(properties.retention())));
        if (deleted != null && deleted > 0) {
            log.info("Purged {} published outbox events", deleted);
        }
    }
}
