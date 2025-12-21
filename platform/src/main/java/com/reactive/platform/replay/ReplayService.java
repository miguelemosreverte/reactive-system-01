package com.reactive.platform.replay;

import com.reactive.platform.replay.ReplayResult.StateSnapshot;
import com.reactive.platform.serialization.Result;
import com.reactive.platform.tracing.Tracing;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Service for replaying events through an FSM with full tracing.
 *
 * Enables debugging by replaying historical events:
 * 1. Fetch events from EventStore
 * 2. Apply each event through the FSM
 * 3. Capture full trace in Jaeger
 *
 * @param <S> The state type
 */
public class ReplayService<S> {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    // ========================================================================
    // Static factories (Scala-style)
    // ========================================================================

    /** Create replay service. */
    public static <S> ReplayService<S> create(EventStore eventStore, FSMAdapter<S> fsm, Tracing tracing) {
        return new ReplayService<>(eventStore, fsm, tracing, false);
    }

    /** Create replay service with snapshot capture. */
    public static <S> ReplayService<S> withSnapshots(EventStore eventStore, FSMAdapter<S> fsm, Tracing tracing) {
        return new ReplayService<>(eventStore, fsm, tracing, true);
    }

    /** @deprecated Use create() instead */
    @Deprecated
    public static <S> Builder<S> builder() {
        return new Builder<>();
    }

    // ========================================================================
    // Internal state
    // ========================================================================

    private final EventStore eventStore;
    private final FSMAdapter<S> fsm;
    private final Tracing tracing;
    private final boolean captureSnapshots;

    private ReplayService(EventStore eventStore, FSMAdapter<S> fsm, Tracing tracing, boolean captureSnapshots) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore required");
        this.fsm = Objects.requireNonNull(fsm, "fsm required");
        this.tracing = Objects.requireNonNull(tracing, "tracing required");
        this.captureSnapshots = captureSnapshots;
    }

    /** @deprecated Use create() instead */
    @Deprecated
    public static class Builder<S> {
        private EventStore eventStore;
        private FSMAdapter<S> fsmAdapter;
        private Tracing tracing;
        private boolean captureSnapshots = false;

        public Builder<S> eventStore(EventStore s) { this.eventStore = s; return this; }
        public Builder<S> fsmAdapter(FSMAdapter<S> f) { this.fsmAdapter = f; return this; }
        public Builder<S> tracing(Tracing t) { this.tracing = t; return this; }
        public Builder<S> captureSnapshots(boolean c) { this.captureSnapshots = c; return this; }

        public ReplayService<S> build() {
            return captureSnapshots
                    ? ReplayService.withSnapshots(eventStore, fsmAdapter, tracing)
                    : ReplayService.create(eventStore, fsmAdapter, tracing);
        }
    }

    // ========================================================================
    // Public API
    // ========================================================================

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

        Span span = tracing.tracer().spanBuilder("replay.aggregate")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("replay.aggregateId", aggregateId)
                .setAttribute("replay.upToEventId", upToEventId != null ? upToEventId : "latest")
                .startSpan();

        String traceId = span.getSpanContext().getTraceId();

        try (Scope scope = span.makeCurrent()) {
            MDC.put("traceId", traceId);
            MDC.put("aggregateId", aggregateId);

            log.info("Replaying aggregate: {}, upTo: {}", aggregateId, upToEventId);

            var eventsResult = upToEventId != null
                    ? eventStore.getEventsUpTo(aggregateId, upToEventId)
                    : eventStore.getEventsByAggregate(aggregateId);

            return eventsResult.flatMap(events -> {
                long durationMs = System.currentTimeMillis() - startTime;

                if (events.isEmpty()) {
                    log.warn("No events for aggregate: {}", aggregateId);
                    span.setAttribute("replay.eventsFound", 0);
                    return Result.success(ReplayResult.empty(aggregateId, fsm.initialState(), traceId, durationMs));
                }

                span.setAttribute("replay.eventsFound", events.size());
                log.info("Replaying {} events", events.size());

                var result = replayEvents(aggregateId, events, upToEventId, traceId, durationMs);

                span.setAttribute("replay.durationMs", result.replayDurationMs());
                span.setStatus(StatusCode.OK);
                log.info("Replay completed: {} events in {}ms", events.size(), durationMs);

                return Result.success(result);
            });

        } catch (Exception e) {
            log.error("Replay failed for aggregate: {}", aggregateId, e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            return Result.failure(e);
        } finally {
            span.end();
            MDC.remove("traceId");
            MDC.remove("aggregateId");
        }
    }

    /**
     * Get state history for an aggregate.
     */
    public Result<List<StateSnapshot<S>>> getStateHistory(String aggregateId) {
        return ReplayService.withSnapshots(eventStore, fsm, tracing)
                .replay(aggregateId)
                .map(ReplayResult::stateSnapshots);
    }

    // ========================================================================
    // Internal
    // ========================================================================

    private ReplayResult<S> replayEvents(String aggregateId, List<StoredEvent> events,
                                          String targetEventId, String traceId, long durationMs) {
        S initialState = fsm.initialState();
        S currentState = initialState;
        List<StateSnapshot<S>> snapshots = captureSnapshots ? new ArrayList<>() : List.of();

        for (int i = 0; i < events.size(); i++) {
            StoredEvent event = events.get(i);
            S stateBefore = currentState;
            currentState = replayEvent(event, currentState, i, events.size());

            if (captureSnapshots) {
                snapshots.add(new StateSnapshot<>(event, stateBefore, currentState));
            }
        }

        return captureSnapshots
                ? ReplayResult.withSnapshots(aggregateId, targetEventId,
                        initialState, currentState, events, snapshots, traceId, durationMs)
                : ReplayResult.of(aggregateId, initialState, currentState,
                        events, traceId, durationMs);
    }

    private S replayEvent(StoredEvent event, S currentState, int index, int total) {
        Span span = tracing.tracer().spanBuilder("replay.event")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("event.index", index)
                .setAttribute("event.total", total)
                .setAttribute("event.id", event.eventId())
                .setAttribute("event.type", event.eventType() != null ? event.eventType() : "unknown")
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            MDC.put("eventId", event.eventId());

            Map<String, Object> stateMap = fsm.stateToMap(currentState);
            log.debug("Before event {}: {}", event.eventId(), stateMap);

            S newState = tracing.span("fsm.apply", s -> {
                s.setAttribute("fsm.payload", event.payload().toString());
                return fsm.apply(currentState, event.payload());
            });

            Map<String, Object> newStateMap = fsm.stateToMap(newState);
            log.debug("After event {}: {}", event.eventId(), newStateMap);

            span.setAttribute("state.before", stateMap.toString());
            span.setAttribute("state.after", newStateMap.toString());
            span.setStatus(StatusCode.OK);

            return newState;

        } catch (Exception e) {
            log.error("Failed to replay event {}: {}", event.eventId(), e.getMessage());
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
            MDC.remove("eventId");
        }
    }
}
