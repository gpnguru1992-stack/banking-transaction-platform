package com.bank.common.event;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Standard wrapper for every message on Kafka. {@code eventId} is the idempotency key consumers
 * de-duplicate on; {@code correlationId} ties the message back to the originating API request.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int schemaVersion,
        String aggregateType,
        String aggregateId,
        String correlationId,
        String producer,
        Instant occurredAt,
        JsonNode payload) {
}
