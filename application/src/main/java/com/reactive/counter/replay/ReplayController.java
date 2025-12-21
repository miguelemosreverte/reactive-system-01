package com.reactive.counter.replay;

import com.reactive.counter.domain.CounterState;
import com.reactive.platform.replay.*;
import com.reactive.platform.tracing.Tracing;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Event replay API for debugging.
 *
 * POST /api/replay/session/{id}          - Replay with full tracing
 * GET  /api/replay/session/{id}/events   - List stored events
 * GET  /api/replay/session/{id}/history  - State after each event
 */
@RestController
@RequestMapping("/api/replay")
public class ReplayController {

    @Value("${spring.kafka.bootstrap-servers:kafka:29092}")
    private String kafkaBootstrap;

    @Value("${app.kafka.topics.events:counter-events}")
    private String eventsTopic;

    private final CounterFSMAdapter fsm = new CounterFSMAdapter();
    private ReplayService<CounterState> replay;
    private EventStore store;

    @PostConstruct
    void init() {
        store = KafkaEventStore.builder()
                .bootstrapServers(kafkaBootstrap)
                .topic(eventsTopic)
                .aggregateIdField(fsm.aggregateIdField())
                .eventIdField(fsm.eventIdField())
                .build();

        replay = ReplayService.<CounterState>builder()
                .eventStore(store)
                .fsmAdapter(fsm)
                .tracing(Tracing.create("replay"))
                .captureSnapshots(true)
                .build();
    }

    @PostMapping("/session/{sessionId}")
    public ResponseEntity<?> replaySession(
            @PathVariable String sessionId,
            @RequestParam(required = false) String upToEvent) {

        return replay.replay(sessionId, upToEvent).fold(
                e -> ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())),
                r -> ResponseEntity.ok(Map.of(
                        "success", true,
                        "sessionId", sessionId,
                        "eventsReplayed", r.eventsReplayed(),
                        "replayTraceId", r.replayTraceId() != null ? r.replayTraceId() : "",
                        "durationMs", r.replayDurationMs(),
                        "initialState", fsm.stateToMap(r.initialState()),
                        "finalState", fsm.stateToMap(r.finalState())
                ))
        );
    }

    @GetMapping("/session/{sessionId}/events")
    public ResponseEntity<?> getEvents(@PathVariable String sessionId) {
        return store.getEventsByAggregate(sessionId).fold(
                e -> ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())),
                events -> ResponseEntity.ok(Map.of(
                        "sessionId", sessionId,
                        "count", events.size(),
                        "events", events.stream().map(ev -> Map.of(
                                "eventId", ev.eventId() != null ? ev.eventId() : "",
                                "action", ev.eventType() != null ? ev.eventType() : "",
                                "timestamp", ev.timestamp().toString(),
                                "traceId", ev.traceId() != null ? ev.traceId() : ""
                        )).toList()
                ))
        );
    }

    @GetMapping("/session/{sessionId}/history")
    public ResponseEntity<?> getHistory(@PathVariable String sessionId) {
        return replay.getStateHistory(sessionId).fold(
                e -> ResponseEntity.internalServerError().body(Map.of("error", e.getMessage())),
                snapshots -> ResponseEntity.ok(Map.of(
                        "sessionId", sessionId,
                        "transitions", snapshots.stream().map(s -> Map.of(
                                "eventId", s.event().eventId() != null ? s.event().eventId() : "",
                                "stateBefore", fsm.stateToMap(s.stateBefore()),
                                "stateAfter", fsm.stateToMap(s.stateAfter())
                        )).toList()
                ))
        );
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of(
                "status", store.isHealthy() ? "UP" : "DOWN",
                "topic", eventsTopic
        ));
    }
}
