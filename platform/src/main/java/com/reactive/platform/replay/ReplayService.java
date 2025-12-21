package com.reactive.platform.replay;

import com.reactive.platform.serialization.Result;
import com.reactive.platform.tracing.Tracing;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Service for replaying events through an FSM with full tracing.
 *
 * This enables debugging by replaying historical events:
 * 1. Fetch events from EventStore for an aggregate
 * 2. Start with initial state
 * 3. Apply each event through the FSM, recording state transitions
 * 4. Force full tracing so every step is captured in Jaeger
 *
 * Usage:
 *   ReplayService<CounterState> replay = ReplayService.builder()
 *       .eventStore(kafkaEventStore)
 *       .fsmAdapter(counterFSMAdapter)
 *       .tracing(tracing)
 *       .build();
 *
 *   ReplayResult<CounterState> result = replay.replay("session-123").getOrThrow();
 *
 * @param <S> The state type
 */
public class ReplayService<S> {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    private final EventStore eventStore;
    private final FSMAdapter<S> fsmAdapter;
    private final Tracing tracing;
    private final boolean captureSnapshots;

    private ReplayService(Builder<S> builder) {
        this.eventStore = Objects.requireNonNull(builder.eventStore, "eventStore required");
        this.fsmAdapter = Objects.requireNonNull(builder.fsmAdapter, "fsmAdapter required");
        this.tracing = Objects.requireNonNull(builder.tracing, "tracing required");
        this.captureSnapshots = builder.captureSnapshots;
    }

    /**
     * Replay all events for an aggregate.
     */
    public Result<ReplayResult<S>> replay(String aggregateId) {
        return replay(aggregateId, null);
    }

    /**
     * Replay events up to a specific event.
     */
    public Result<ReplayResult<S>> replay(String aggregateId, String upToEventId) {
        long startTime = System.currentTimeMillis();

        // Create a root span for the entire replay operation
        Span replaySpan = tracing.tracer().spanBuilder("replay.aggregate")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("replay.aggregateId", aggregateId)
                .setAttribute("replay.upToEventId", upToEventId != null ? upToEventId : "latest")
                .setAttribute("replay.mode", "debug")
                .startSpan();

        String replayTraceId = replaySpan.getSpanContext().getTraceId();

        try (Scope scope = replaySpan.makeCurrent()) {
            // Set MDC for log correlation
            MDC.put("traceId", replayTraceId);
            MDC.put("aggregateId", aggregateId);
            MDC.put("replayMode", "true");

            log.info("Starting replay for aggregate: {}, upToEvent: {}", aggregateId, upToEventId);

            // Fetch events
            Result<List<StoredEvent>> eventsResult = upToEventId != null
                    ? eventStore.getEventsUpTo(aggregateId, upToEventId)
                    : eventStore.getEventsByAggregate(aggregateId);

            return eventsResult.flatMap(events -> {
                if (events.isEmpty()) {
                    log.warn("No events found for aggregate: {}", aggregateId);
                    replaySpan.setAttribute("replay.eventsFound", 0);
                    return Result.success(ReplayResult.<S>builder()
                            .aggregateId(aggregateId)
                            .targetEventId(upToEventId)
                            .initialState(fsmAdapter.initialState())
                            .finalState(fsmAdapter.initialState())
                            .eventsReplayed(0)
                            .replayedEvents(events)
                            .replayTraceId(replayTraceId)
                            .replayDurationMs(System.currentTimeMillis() - startTime)
                            .build());
                }

                replaySpan.setAttribute("replay.eventsFound", events.size());
                log.info("Found {} events to replay", events.size());

                // Replay events
                S initialState = fsmAdapter.initialState();
                S currentState = initialState;
                List<ReplayResult.StateSnapshot<S>> snapshots = captureSnapshots ? new ArrayList<>() : List.of();

                for (int i = 0; i < events.size(); i++) {
                    StoredEvent event = events.get(i);
                    S stateBefore = currentState;

                    // Create a child span for each event replay
                    currentState = replayEvent(event, currentState, i, events.size());

                    if (captureSnapshots) {
                        snapshots.add(new ReplayResult.StateSnapshot<>(event, stateBefore, currentState));
                    }
                }

                long durationMs = System.currentTimeMillis() - startTime;
                replaySpan.setAttribute("replay.durationMs", durationMs);
                replaySpan.setStatus(StatusCode.OK);

                log.info("Replay completed: {} events in {}ms", events.size(), durationMs);

                return Result.success(ReplayResult.<S>builder()
                        .aggregateId(aggregateId)
                        .targetEventId(upToEventId)
                        .initialState(initialState)
                        .finalState(currentState)
                        .eventsReplayed(events.size())
                        .replayedEvents(events)
                        .stateSnapshots(snapshots)
                        .replayTraceId(replayTraceId)
                        .replayDurationMs(durationMs)
                        .build());
            });

        } catch (Exception e) {
            log.error("Replay failed for aggregate: {}", aggregateId, e);
            replaySpan.setStatus(StatusCode.ERROR, e.getMessage());
            replaySpan.recordException(e);
            return Result.failure(e);
        } finally {
            replaySpan.end();
            MDC.remove("traceId");
            MDC.remove("aggregateId");
            MDC.remove("replayMode");
        }
    }

    /**
     * Replay a single event with detailed tracing.
     */
    private S replayEvent(StoredEvent event, S currentState, int index, int total) {
        Span eventSpan = tracing.tracer().spanBuilder("replay.event")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("replay.eventIndex", index)
                .setAttribute("replay.eventTotal", total)
                .setAttribute("event.id", event.eventId())
                .setAttribute("event.type", event.eventType() != null ? event.eventType() : "unknown")
                .setAttribute("event.offset", event.offset())
                .setAttribute("event.timestamp", event.timestamp().toString())
                .setAttribute("event.originalTraceId", event.traceId() != null ? event.traceId() : "none")
                .startSpan();

        try (Scope scope = eventSpan.makeCurrent()) {
            MDC.put("eventId", event.eventId());
            MDC.put("eventIndex", String.valueOf(index));

            // Log state before
            Map<String, Object> stateMap = fsmAdapter.stateToMap(currentState);
            log.debug("Before event {}: state={}", event.eventId(), stateMap);

            // Apply event through FSM
            S newState = tracing.span("fsm.apply", span -> {
                span.setAttribute("fsm.eventPayload", event.payload().toString());
                return fsmAdapter.apply(currentState, event.payload());
            });

            // Log state after
            Map<String, Object> newStateMap = fsmAdapter.stateToMap(newState);
            log.debug("After event {}: state={}", event.eventId(), newStateMap);

            // Add state change to span
            eventSpan.setAttribute("state.before", stateMap.toString());
            eventSpan.setAttribute("state.after", newStateMap.toString());
            eventSpan.setStatus(StatusCode.OK);

            return newState;

        } catch (Exception e) {
            log.error("Failed to replay event {}: {}", event.eventId(), e.getMessage());
            eventSpan.setStatus(StatusCode.ERROR, e.getMessage());
            eventSpan.recordException(e);
            throw e;
        } finally {
            eventSpan.end();
            MDC.remove("eventId");
            MDC.remove("eventIndex");
        }
    }

    /**
     * Get detailed state history for an aggregate.
     *
     * This replays all events and returns the state after each one.
     */
    public Result<List<ReplayResult.StateSnapshot<S>>> getStateHistory(String aggregateId) {
        // Temporarily enable snapshots
        ReplayService<S> withSnapshots = ReplayService.<S>builder()
                .eventStore(eventStore)
                .fsmAdapter(fsmAdapter)
                .tracing(tracing)
                .captureSnapshots(true)
                .build();

        return withSnapshots.replay(aggregateId).map(ReplayResult::stateSnapshots);
    }

    // ========================================================================
    // Builder
    // ========================================================================

    public static <S> Builder<S> builder() {
        return new Builder<>();
    }

    public static class Builder<S> {
        private EventStore eventStore;
        private FSMAdapter<S> fsmAdapter;
        private Tracing tracing;
        private boolean captureSnapshots = false;

        public Builder<S> eventStore(EventStore store) {
            this.eventStore = store;
            return this;
        }

        public Builder<S> fsmAdapter(FSMAdapter<S> adapter) {
            this.fsmAdapter = adapter;
            return this;
        }

        public Builder<S> tracing(Tracing tracing) {
            this.tracing = tracing;
            return this;
        }

        public Builder<S> captureSnapshots(boolean capture) {
            this.captureSnapshots = capture;
            return this;
        }

        public ReplayService<S> build() {
            return new ReplayService<>(this);
        }
    }
}
