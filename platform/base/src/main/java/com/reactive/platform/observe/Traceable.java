package com.reactive.platform.observe;

/**
 * Interface for domain objects that carry tracing context.
 *
 * Implement this on DTOs/events that flow through the system.
 * The Log API will automatically extract these IDs for span attributes.
 */
public interface Traceable {

    /** Correlation ID for this request. */
    String requestId();

    /** Customer/tenant ID for multi-tenancy. */
    String customerId();

    /** Unique event ID. */
    String eventId();

    /** Session/entity instance ID. */
    String sessionId();
}
