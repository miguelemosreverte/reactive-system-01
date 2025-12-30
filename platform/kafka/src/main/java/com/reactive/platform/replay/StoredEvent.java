package com.reactive.platform.replay;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents an event stored in the event store.
 *
 * This is a platform-agnostic representation of events
 * that can be replayed through FSMs for debugging.
 *
 * @param aggregateId The aggregate/session this event belongs to
 * @param eventId Unique identifier for this event
 * @param eventType The type of event (e.g., "increment", "decrement")
 * @param payload The event payload as a map (decoded from JSON/Avro)
 * @param timestamp When the event was created
 * @param offset Kafka offset (for ordering)
 * @param traceId Original trace ID (if present)
 * @param headers Original message headers
 */
public record StoredEvent(
        String aggregateId,
        String eventId,
        String eventType,
        Map<String, Object> payload,
        Instant timestamp,
        long offset,
        String traceId,
        Map<String, String> headers
) {
    public StoredEvent {
        Objects.requireNonNull(aggregateId, "aggregateId cannot be null");
        Objects.requireNonNull(eventId, "eventId cannot be null");
        Objects.requireNonNull(payload, "payload cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        // Normalize optional fields to non-null
        eventType = eventType != null ? eventType : "";
        traceId = traceId != null ? traceId : "";
        headers = headers != null ? headers : Map.of();
    }

    /**
     * Create a StoredEvent with minimal required fields.
     */
    public static StoredEvent of(String aggregateId, String eventId, Map<String, Object> payload) {
        return new StoredEvent(
                aggregateId,
                eventId,
                "",
                payload,
                Instant.now(),
                -1,
                "",
                Map.of()
        );
    }

    /**
     * Get a payload value with type coercion.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) payload.get(key);
    }

    /**
     * Get a payload value with default.
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(String key, T defaultValue) {
        Object value = payload.get(key);
        return value != null ? (T) value : defaultValue;
    }

    /**
     * Check if this event has a specific header.
     */
    public boolean hasHeader(String key) {
        return headers.containsKey(key);
    }

    /**
     * Get a header value.
     */
    public Optional<String> getHeader(String key) {
        return Optional.ofNullable(headers.get(key));
    }
}
