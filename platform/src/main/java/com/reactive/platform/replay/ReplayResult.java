package com.reactive.platform.replay;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Result of replaying events through an FSM.
 *
 * Contains the rebuilt state, trace information, and replay metadata.
 *
 * @param aggregateId The aggregate that was replayed
 * @param targetEventId The event that triggered the replay (if specific)
 * @param initialState State before any events
 * @param finalState State after replaying all events
 * @param eventsReplayed Number of events replayed
 * @param replayedEvents List of events that were replayed (for debugging)
 * @param stateSnapshots State after each event (if requested)
 * @param replayTraceId The trace ID for this replay operation
 * @param replayDurationMs How long the replay took
 * @param replayedAt When the replay was performed
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
        long replayDurationMs,
        Instant replayedAt
) {

    /**
     * Snapshot of state after applying an event.
     */
    public record StateSnapshot<S>(
            StoredEvent event,
            S stateBefore,
            S stateAfter
    ) {}

    /**
     * Convert to a map for JSON serialization.
     */
    public Map<String, Object> toMap(java.util.function.Function<S, Map<String, Object>> stateSerializer) {
        return Map.of(
                "aggregateId", aggregateId,
                "targetEventId", targetEventId != null ? targetEventId : "",
                "initialState", stateSerializer.apply(initialState),
                "finalState", stateSerializer.apply(finalState),
                "eventsReplayed", eventsReplayed,
                "replayTraceId", replayTraceId != null ? replayTraceId : "",
                "replayDurationMs", replayDurationMs,
                "replayedAt", replayedAt.toString()
        );
    }

    /**
     * Builder for ReplayResult.
     */
    public static <S> Builder<S> builder() {
        return new Builder<>();
    }

    public static class Builder<S> {
        private String aggregateId;
        private String targetEventId;
        private S initialState;
        private S finalState;
        private int eventsReplayed;
        private List<StoredEvent> replayedEvents = List.of();
        private List<StateSnapshot<S>> stateSnapshots = List.of();
        private String replayTraceId;
        private long replayDurationMs;
        private Instant replayedAt = Instant.now();

        public Builder<S> aggregateId(String id) {
            this.aggregateId = id;
            return this;
        }

        public Builder<S> targetEventId(String id) {
            this.targetEventId = id;
            return this;
        }

        public Builder<S> initialState(S state) {
            this.initialState = state;
            return this;
        }

        public Builder<S> finalState(S state) {
            this.finalState = state;
            return this;
        }

        public Builder<S> eventsReplayed(int count) {
            this.eventsReplayed = count;
            return this;
        }

        public Builder<S> replayedEvents(List<StoredEvent> events) {
            this.replayedEvents = events;
            return this;
        }

        public Builder<S> stateSnapshots(List<StateSnapshot<S>> snapshots) {
            this.stateSnapshots = snapshots;
            return this;
        }

        public Builder<S> replayTraceId(String traceId) {
            this.replayTraceId = traceId;
            return this;
        }

        public Builder<S> replayDurationMs(long ms) {
            this.replayDurationMs = ms;
            return this;
        }

        public Builder<S> replayedAt(Instant instant) {
            this.replayedAt = instant;
            return this;
        }

        public ReplayResult<S> build() {
            return new ReplayResult<>(
                    aggregateId,
                    targetEventId,
                    initialState,
                    finalState,
                    eventsReplayed,
                    replayedEvents,
                    stateSnapshots,
                    replayTraceId,
                    replayDurationMs,
                    replayedAt
            );
        }
    }
}
