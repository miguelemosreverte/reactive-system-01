package com.reactive.platform.replay;

import com.reactive.platform.replay.ReplayResult.StateSnapshot;
import com.reactive.platform.serialization.Result;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.reactive.platform.tracing.Tracing.span;

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
    private static final String SERVICE_NAME = "replay-service";

    /** Create replay service. */
    public static <S> ReplayService<S> create(EventStore eventStore, FSMAdapter<S> fsm) {
        return new ReplayService<>(eventStore, fsm, false);
    }

    /** Create replay service with snapshot capture. */
    public static <S> ReplayService<S> withSnapshots(EventStore eventStore, FSMAdapter<S> fsm) {
        return new ReplayService<>(eventStore, fsm, true);
    }

    private final EventStore eventStore;
    private final FSMAdapter<S> fsm;
    private final boolean captureSnapshots;
    private final Tracer tracer;

    private ReplayService(EventStore eventStore, FSMAdapter<S> fsm, boolean captureSnapshots) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore required");
        this.fsm = Objects.requireNonNull(fsm, "fsm required");
        this.captureSnapshots = captureSnapshots;
        this.tracer = GlobalOpenTelemetry.get().getTracer(SERVICE_NAME);
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

        Span replaySpan = tracer.spanBuilder("replay.aggregate")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("replay.aggregateId", aggregateId)
                .setAttribute("replay.upToEventId", upToEventId != null ? upToEventId : "latest")
                .startSpan();

        String traceId = replaySpan.getSpanContext().getTraceId();

        try (Scope scope = replaySpan.makeCurrent()) {
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
                    replaySpan.setAttribute("replay.eventsFound", 0);
                    return Result.success(ReplayResult.empty(aggregateId, fsm.initialState(), traceId, durationMs));
                }

                replaySpan.setAttribute("replay.eventsFound", events.size());
                log.info("Replaying {} events", events.size());

                var result = replayEvents(aggregateId, events, upToEventId, traceId, durationMs);

                replaySpan.setAttribute("replay.durationMs", result.replayDurationMs());
                replaySpan.setStatus(StatusCode.OK);
                log.info("Replay completed: {} events in {}ms", events.size(), durationMs);

                return Result.success(result);
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
        }
    }

    /**
     * Get state history for an aggregate.
     */
    public Result<List<StateSnapshot<S>>> getStateHistory(String aggregateId) {
        return ReplayService.withSnapshots(eventStore, fsm)
                .replay(aggregateId)
                .map(ReplayResult::stateSnapshots);
    }

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
        Span eventSpan = tracer.spanBuilder("replay.event")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("event.index", index)
                .setAttribute("event.total", total)
                .setAttribute("event.id", event.eventId())
                .setAttribute("event.type", event.eventType() != null ? event.eventType() : "unknown")
                .startSpan();

        try (Scope scope = eventSpan.makeCurrent()) {
            MDC.put("eventId", event.eventId());

            Map<String, Object> stateMap = fsm.stateToMap(currentState);
            log.debug("Before event {}: {}", event.eventId(), stateMap);

            S newState = span(SERVICE_NAME, "fsm.apply", s -> {
                s.setAttribute("fsm.payload", event.payload().toString());
                return fsm.apply(currentState, event.payload());
            });

            Map<String, Object> newStateMap = fsm.stateToMap(newState);
            log.debug("After event {}: {}", event.eventId(), newStateMap);

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
        }
    }
}
