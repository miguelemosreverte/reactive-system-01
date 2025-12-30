package com.reactive.platform.replay;

import com.reactive.platform.base.Result;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Interface for reading events from the event store.
 *
 * This enables event replay for debugging: load historical events
 * for an aggregate, replay them through the FSM to rebuild state,
 * then apply the target event with full tracing.
 *
 * Follows the Event Sourcing pattern - events are immutable facts
 * that can be replayed to reconstruct state at any point in time.
 *
 * Usage:
 *   EventStore store = ...;
 *   List<StoredEvent> events = store.getEventsByAggregate("session-123").getOrThrow();
 *   // Replay through FSM to rebuild state
 *   State state = events.stream().reduce(State.initial(), FSM::transition, (a,b) -> b);
 */
public interface EventStore {

    /**
     * Fetch all events for an aggregate, ordered by offset.
     *
     * @param aggregateId The aggregate identifier (e.g., sessionId)
     * @return List of events in order, or failure
     */
    Result<List<StoredEvent>> getEventsByAggregate(String aggregateId);

    /**
     * Fetch events for an aggregate within a time range.
     *
     * @param aggregateId The aggregate identifier
     * @param from Start time (inclusive)
     * @param to End time (inclusive)
     * @return List of events in order, or failure
     */
    Result<List<StoredEvent>> getEventsByAggregate(String aggregateId, Instant from, Instant to);

    /**
     * Fetch a specific event by its ID.
     *
     * @param eventId The unique event identifier
     * @return The event if found, or failure
     */
    Result<Optional<StoredEvent>> getEventById(String eventId);

    /**
     * Fetch events up to and including a specific event.
     *
     * Useful for replaying to a specific point in time.
     *
     * @param aggregateId The aggregate identifier
     * @param upToEventId Stop at this event (inclusive)
     * @return List of events in order up to the target
     */
    Result<List<StoredEvent>> getEventsUpTo(String aggregateId, String upToEventId);

    /**
     * Get the latest event for an aggregate.
     *
     * @param aggregateId The aggregate identifier
     * @return The latest event if any exist
     */
    Result<Optional<StoredEvent>> getLatestEvent(String aggregateId);

    /**
     * Count events for an aggregate.
     *
     * @param aggregateId The aggregate identifier
     * @return Number of events
     */
    Result<Long> countEvents(String aggregateId);

    /**
     * Check if the event store is healthy/connected.
     */
    boolean isHealthy();
}
