package com.bank.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A message to one customer on one channel. Created in the same transaction that consumes the
 * Kafka event and delivered asynchronously by {@code NotificationDispatcher} with retries, so a
 * slow or failing SMTP/SMS provider never blocks event consumption.
 */
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "customer_id", nullable = false, length = 20)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Channel channel;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(nullable = false, length = 1000)
    private String body;

    @Column(length = 254)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private NotificationStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected Notification() {
    }

    public Notification(UUID eventId, String customerId, Channel channel, String subject, String body,
                        String correlationId, Instant now) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.customerId = customerId;
        this.channel = channel;
        this.subject = subject;
        this.body = body;
        this.correlationId = correlationId;
        this.status = NotificationStatus.PENDING;
        this.createdAt = now;
        this.nextAttemptAt = now;
    }

    public void markSent(String recipient, Instant now) {
        this.recipient = recipient;
        this.status = NotificationStatus.SENT;
        this.attempts++;
        this.sentAt = now;
        this.lastError = null;
    }

    public void markSkipped(String reason) {
        this.status = NotificationStatus.SKIPPED;
        this.lastError = reason;
    }

    public void markAttemptFailed(String error, int maxAttempts, Instant nextAttemptAt) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
        if (attempts >= maxAttempts) {
            this.status = NotificationStatus.FAILED;
        } else {
            this.nextAttemptAt = nextAttemptAt;
        }
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public String getCustomerId() { return customerId; }
    public Channel getChannel() { return channel; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public String getRecipient() { return recipient; }
    public NotificationStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public String getCorrelationId() { return correlationId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getSentAt() { return sentAt; }
}
