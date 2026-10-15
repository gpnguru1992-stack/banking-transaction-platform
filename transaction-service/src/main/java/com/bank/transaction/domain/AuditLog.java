package com.bank.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Immutable audit trail of every business event on the platform, kept with its full payload. */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    @Column(nullable = false, length = 100)
    private String producer;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected AuditLog() {
    }

    public AuditLog(UUID eventId, String eventType, String aggregateType, String aggregateId, String producer,
                    String correlationId, String topic, String payload, Instant occurredAt, Instant recordedAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.producer = producer;
        this.correlationId = correlationId;
        this.topic = topic;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
    }

    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getProducer() { return producer; }
    public String getCorrelationId() { return correlationId; }
    public String getTopic() { return topic; }
    public String getPayload() { return payload; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getRecordedAt() { return recordedAt; }
}
