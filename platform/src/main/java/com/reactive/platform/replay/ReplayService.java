package com.reactive.platform.replay;

import com.reactive.platform.replay.ReplayResult.StateSnapshot;
import com.reactive.platform.base.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.reactive.platform.observe.Log.*;

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

    private ReplayService(EventStore eventStore, FSMAdapter<S> fsm, boolean captureSnapshots) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore required");
        this.fsm = Objects.requireNonNull(fsm, "fsm required");
        this.captureSnapshots = captureSnapshots;
    }

    /**
     * Replay all events for an aggregate.
     */
    public Result<ReplayResult<S>> replay(String aggregateId) {
        return replay(aggregateId, "");
    }

    /**
     * Replay events up to a specific event.
     */
    public Result<ReplayResult<S>> replay(String aggregateId, String upToEventId) {
        long startTime = System.currentTimeMillis();

        return traced("replay.aggregate", () -> {
            attr("replay.aggregateId", aggregateId);
            attr("replay.upToEventId", upToEventId.isEmpty() ? "latest" : upToEventId);

            String currentTraceId = traceId();

            info("Replaying aggregate: {}, upTo: {}", aggregateId,
                    upToEventId.isEmpty() ? "latest" : upToEventId);

            var eventsResult = upToEventId.isEmpty()
                    ? eventStore.getEventsByAggregate(aggregateId)
                    : eventStore.getEventsUpTo(aggregateId, upToEventId);

            return eventsResult.flatMap(events -> {
                long durationMs = System.currentTimeMillis() - startTime;

                if (events.isEmpty()) {
                    warn("No events for aggregate: {}", aggregateId);
                    attr("replay.eventsFound", 0);
                    return Result.success(ReplayResult.empty(
                            aggregateId, fsm.initialState(), currentTraceId, durationMs));
                }

                attr("replay.eventsFound", events.size());
                info("Replaying {} events", events.size());

                var result = replayEvents(aggregateId, events, upToEventId, currentTraceId, durationMs);

                attr("replay.durationMs", result.replayDurationMs());
                info("Replay completed: {} events in {}ms", events.size(), durationMs);

                return Result.success(result);
            });
        });
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
                                          String targetEventId, String currentTraceId, long durationMs) {
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
                        initialState, currentState, events, snapshots, currentTraceId, durationMs)
                : ReplayResult.of(aggregateId, initialState, currentState,
                        events, currentTraceId, durationMs);
    }

    private S replayEvent(StoredEvent event, S currentState, int index, int total) {
        return traced("replay.event", () -> {
            attr("event.index", index);
            attr("event.total", total);
            attr("event.id", event.eventId());
            attr("event.type", event.eventType().isEmpty() ? "unknown" : event.eventType());

            Map<String, Object> stateMap = fsm.stateToMap(currentState);

            S newState = traced("fsm.apply", () -> {
                attr("fsm.payload", event.payload().toString());
                return fsm.apply(currentState, event.payload());
            });

            Map<String, Object> newStateMap = fsm.stateToMap(newState);

            attr("state.before", stateMap.toString());
            attr("state.after", newStateMap.toString());

            return newState;
        });
    }
}
