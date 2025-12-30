package com.reactive.platform.replay;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Result of replaying events through an FSM.
 *
 * @param aggregateId The aggregate that was replayed
 * @param targetEventId The event that triggered the replay (if specific)
 * @param initialState State before any events
 * @param finalState State after replaying all events
 * @param eventsReplayed Number of events replayed
 * @param replayedEvents List of events that were replayed
 * @param stateSnapshots State after each event (if requested)
 * @param replayTraceId The trace ID for this replay operation
 * @param replayDurationMs How long the replay took
 */
public record ReplayResult<S>(
        String aggregateId,
        String targetEventId,
        S initialState,
        S finalState,
        int eventsReplayed,
        List<StoredEvent> replayedEvents,
        List<StateSnapshot<S>> stateSnapshots,
        String replayTraceId,
        long replayDurationMs
) {
    /**
     * Snapshot of state after applying an event.
     */
    public record StateSnapshot<S>(StoredEvent event, S stateBefore, S stateAfter) {}

    /**
     * Create a replay result with all events replayed.
     */
    public static <S> ReplayResult<S> of(
            String aggregateId,
            S initialState,
            S finalState,
            List<StoredEvent> events,
            String traceId,
            long durationMs
    ) {
        return new ReplayResult<>(
                aggregateId, "", initialState, finalState,
                events.size(), events, List.of(), traceId, durationMs
        );
    }

    /**
     * Create a replay result with snapshots.
     */
    public static <S> ReplayResult<S> withSnapshots(
            String aggregateId,
            String targetEventId,
            S initialState,
            S finalState,
            List<StoredEvent> events,
            List<StateSnapshot<S>> snapshots,
            String traceId,
            long durationMs
    ) {
        return new ReplayResult<>(
                aggregateId, targetEventId, initialState, finalState,
                events.size(), events, snapshots, traceId, durationMs
        );
    }

    /**
     * Create an empty result (no events found).
     */
    public static <S> ReplayResult<S> empty(String aggregateId, S initialState, String traceId, long durationMs) {
        return new ReplayResult<>(
                aggregateId, "", initialState, initialState,
                0, List.of(), List.of(), traceId, durationMs
        );
    }

    /**
     * Convert to a map for JSON serialization.
     */
    public Map<String, Object> toMap(Function<S, Map<String, Object>> stateSerializer) {
        return Map.of(
                "aggregateId", aggregateId,
                "targetEventId", targetEventId,
                "initialState", stateSerializer.apply(initialState),
                "finalState", stateSerializer.apply(finalState),
                "eventsReplayed", eventsReplayed,
                "replayTraceId", replayTraceId,
                "replayDurationMs", replayDurationMs
        );
    }
}
