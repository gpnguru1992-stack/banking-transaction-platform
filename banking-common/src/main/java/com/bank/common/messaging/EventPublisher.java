package com.bank.common.messaging;

import com.bank.common.event.EventEnvelope;
import com.bank.common.observability.CorrelationId;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Stores domain events in the outbox. Must be called inside the business transaction so the event
 * is committed atomically with the state change (or not at all).
 */
public class EventPublisher {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String producer;

    public EventPublisher(OutboxRepository outboxRepository, ObjectMapper objectMapper, Clock clock, String producer) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.producer = producer;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String topic, String messageKey, String eventType, String aggregateType,
                        String aggregateId, Object payload) {
        UUID eventId = UUID.randomUUID();
        Instant now = clock.instant();
        EventEnvelope envelope = new EventEnvelope(
                eventId, eventType, 1, aggregateType, aggregateId,
                CorrelationId.currentOrNew(), producer, now, objectMapper.valueToTree(payload));
        try {
            String json = objectMapper.writeValueAsString(envelope);
            outboxRepository.save(new OutboxEvent(eventId, topic, messageKey, eventType, aggregateType,
                    aggregateId, json, now));
            return eventId;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize event " + eventType, e);
        }
    }
}
