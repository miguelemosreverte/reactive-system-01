package com.reactive.gateway.controller;

import com.reactive.diagnostic.DiagnosticCollector;
import com.reactive.diagnostic.DiagnosticSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Exposes diagnostic data for AI-assisted performance analysis.
 *
 * Endpoints:
 * - GET /api/diagnostics - JSON snapshot for AI consumption
 * - GET /api/diagnostics?format=json - Same as above (explicit)
 *
 * The diagnostic data is pure metrics without recommendations.
 * AI interprets this data to provide insights and recommendations.
 */
@RestController
@RequestMapping("/api/diagnostics")
@RequiredArgsConstructor
public class DiagnosticController {

    private final DiagnosticCollector diagnosticCollector;

    /**
     * Get current diagnostic snapshot.
     *
     * Headers:
     * - Accept: application/json (default)
     *
     * This endpoint returns a comprehensive snapshot of:
     * - Throughput metrics (events/sec, batches, capacity)
     * - Latency breakdown (per-stage, percentiles)
     * - Resource saturation (heap, threads, connections)
     * - Trends and rates of change
     * - GC analysis
     * - Error patterns
     * - Dependency health
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DiagnosticSnapshot> getDiagnostics() {
        DiagnosticSnapshot snapshot = diagnosticCollector.collect();
        return ResponseEntity.ok(snapshot);
    }

    /**
     * Get diagnostic summary for quick health checks.
     * Returns a minimal subset focused on key indicators.
     */
    @GetMapping(value = "/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DiagnosticSummary> getSummary() {
        DiagnosticSnapshot snapshot = diagnosticCollector.collect();
        DiagnosticSummary summary = DiagnosticSummary.from(snapshot);
        return ResponseEntity.ok(summary);
    }

    /**
     * Minimal diagnostic summary for monitoring dashboards.
     */
    public record DiagnosticSummary(
            String component,
            String instanceId,
            long timestampMs,
            long uptimeMs,
            double eventsPerSecond,
            double capacityUtilizationPercent,
            double latencyP50Ms,
            double latencyP99Ms,
            double heapUsedPercent,
            double gcOverheadPercent,
            long totalErrors,
            double errorRatePercent,
            boolean oomRisk,
            boolean gcThrashing,
            boolean deadlockDetected
    ) {
        public static DiagnosticSummary from(DiagnosticSnapshot s) {
            return new DiagnosticSummary(
                    s.component(),
                    s.instanceId(),
                    s.timestampMs(),
                    s.uptimeMs(),
                    s.throughput().eventsPerSecond(),
                    s.throughput().capacityUtilizationPercent(),
                    s.latency().totalMsP50(),
                    s.latency().totalMsP99(),
                    s.saturation().heapUsedPercent(),
                    s.gc().gcOverheadPercent(),
                    s.errors().totalErrorCount(),
                    s.errors().errorRatePercent(),
                    s.errors().crashIndicators().oomRisk(),
                    s.errors().crashIndicators().gcThrashing(),
                    s.contention().deadlockDetected()
            );
        }
    }
}
