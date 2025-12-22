package com.reactive.platform.benchmark;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;
import java.util.Map;

/**
 * Benchmark types - Scala-style records with static factories.
 *
 * JSON-compatible with Go benchmark types for report generation.
 */
public final class BenchmarkTypes {

    private BenchmarkTypes() {}

    // ========================================================================
    // Component ID
    // ========================================================================

    public enum ComponentId {
        HTTP("http", "HTTP Benchmark", "Tests gateway HTTP endpoint latency"),
        KAFKA("kafka", "Kafka Benchmark", "Tests Kafka produce/consume round-trip"),
        FLINK("flink", "Flink Benchmark", "Tests Kafka + Flink processing"),
        DROOLS("drools", "Drools Benchmark", "Tests Drools rule evaluation"),
        GATEWAY("gateway", "Gateway Benchmark", "Tests HTTP + Kafka publish"),
        FULL("full", "Full End-to-End", "Complete pipeline: HTTP → Kafka → Flink → Drools");

        private final String id;
        private final String name;
        private final String description;

        ComponentId(String id, String name, String description) {
            this.id = id;
            this.name = name;
            this.description = description;
        }

        @JsonValue
        public String id() { return id; }
        public String displayName() { return name; }
        public String description() { return description; }

        public static ComponentId fromString(String id) {
            for (var c : values()) {
                if (c.id.equalsIgnoreCase(id)) return c;
            }
            throw new IllegalArgumentException("Unknown component: " + id);
        }
    }

    // ========================================================================
    // Configuration
    // ========================================================================

    public record Config(
            long durationMs,
            int targetEventCount,
            long warmupMs,
            long cooldownMs,
            int concurrency,
            int batchSize,
            String gatewayUrl,
            String droolsUrl,
            String kafkaBrokers,
            boolean skipEnrichment,
            boolean quickMode
    ) {
        public static Config defaults() {
            return new Config(
                    30_000L,  // 30 seconds
                    0,        // no event limit
                    3_000L,   // 3s warmup
                    2_000L,   // 2s cooldown
                    8,        // concurrency
                    100,      // batch size
                    "http://gateway:3000",
                    "http://drools:8080",
                    "kafka:29092",
                    false,    // skipEnrichment
                    false     // quickMode
            );
        }

        /** Quick mode preset: 5s duration, 1s warmup, no cooldown, skip enrichment */
        public static Config quick() {
            return new Config(
                    5_000L,   // 5 seconds
                    0,        // no event limit
                    1_000L,   // 1s warmup
                    0L,       // no cooldown
                    8,        // concurrency
                    100,      // batch size
                    "http://gateway:3000",
                    "http://drools:8080",
                    "kafka:29092",
                    true,     // skipEnrichment
                    true      // quickMode
            );
        }

        public static Builder builder() { return new Builder(); }

        public static class Builder {
            private long durationMs = 30_000L;
            private int targetEventCount = 0;
            private long warmupMs = 3_000L;
            private long cooldownMs = 2_000L;
            private int concurrency = 8;
            private int batchSize = 100;
            private String gatewayUrl = "http://gateway:3000";
            private String droolsUrl = "http://drools:8080";
            private String kafkaBrokers = "kafka:29092";
            private boolean skipEnrichment = false;
            private boolean quickMode = false;

            public Builder durationMs(long v) { this.durationMs = v; return this; }
            public Builder targetEventCount(int v) { this.targetEventCount = v; return this; }
            public Builder warmupMs(long v) { this.warmupMs = v; return this; }
            public Builder cooldownMs(long v) { this.cooldownMs = v; return this; }
            public Builder concurrency(int v) { this.concurrency = v; return this; }
            public Builder batchSize(int v) { this.batchSize = v; return this; }
            public Builder gatewayUrl(String v) { this.gatewayUrl = v; return this; }
            public Builder droolsUrl(String v) { this.droolsUrl = v; return this; }
            public Builder kafkaBrokers(String v) { this.kafkaBrokers = v; return this; }
            public Builder skipEnrichment(boolean v) { this.skipEnrichment = v; return this; }
            public Builder quickMode(boolean v) { this.quickMode = v; return this; }

            /** Apply quick mode preset */
            public Builder quick() {
                this.durationMs = 5_000L;
                this.warmupMs = 1_000L;
                this.cooldownMs = 0L;
                this.skipEnrichment = true;
                this.quickMode = true;
                return this;
            }

            public Config build() {
                return new Config(durationMs, targetEventCount, warmupMs, cooldownMs,
                        concurrency, batchSize, gatewayUrl, droolsUrl, kafkaBrokers,
                        skipEnrichment, quickMode);
            }
        }
    }

    // ========================================================================
    // Latency Statistics
    // ========================================================================

    public record LatencyStats(
            long min,
            long max,
            long avg,
            long p50,
            long p95,
            long p99
    ) {
        public static LatencyStats empty() {
            return new LatencyStats(0, 0, 0, 0, 0, 0);
        }

        public static LatencyStats from(long[] sortedLatencies) {
            if (sortedLatencies == null || sortedLatencies.length == 0) {
                return empty();
            }
            long sum = 0;
            for (long l : sortedLatencies) sum += l;
            return new LatencyStats(
                    sortedLatencies[0],
                    sortedLatencies[sortedLatencies.length - 1],
                    sum / sortedLatencies.length,
                    percentile(sortedLatencies, 50),
                    percentile(sortedLatencies, 95),
                    percentile(sortedLatencies, 99)
            );
        }

        private static long percentile(long[] sorted, int p) {
            int index = Math.min(p * sorted.length / 100, sorted.length - 1);
            return sorted[Math.max(0, index)];
        }
    }

    // ========================================================================
    // Component Timing
    // ========================================================================

    public record ComponentTiming(
            long gatewayMs,
            long kafkaMs,
            long flinkMs,
            long droolsMs
    ) {
        public static ComponentTiming empty() {
            return new ComponentTiming(0, 0, 0, 0);
        }

        public long totalMs() {
            return gatewayMs + kafkaMs + flinkMs + droolsMs;
        }
    }

    // ========================================================================
    // Jaeger Types (field names match Go JSON tags)
    // ========================================================================

    public record Span(
            @JsonProperty("traceID") String traceId,
            @JsonProperty("spanID") String spanId,
            String operationName,
            long startTime,
            long duration,
            @JsonProperty("processID") String processId,
            List<Map<String, Object>> tags,
            List<Map<String, Object>> references
    ) {}

    public record Service(String serviceName) {}

    public record Trace(
            String traceId,
            List<Span> spans,
            Map<String, Service> processes
    ) {
        public static Trace empty() {
            return new Trace("", List.of(), Map.of());
        }

        public static Trace empty(String traceId) {
            return new Trace(traceId, List.of(), Map.of());
        }

        public boolean isEmpty() {
            return spans.isEmpty();
        }

        public boolean hasSpans() {
            return !spans.isEmpty();
        }
    }

    // ========================================================================
    // Loki Log Entry
    // ========================================================================

    public record LogEntry(
            String timestamp,
            String line,
            Map<String, String> labels,
            Map<String, Object> fields
    ) {}

    // ========================================================================
    // Trace Data (combined Jaeger + Loki)
    // ========================================================================

    public record TraceData(
            Trace trace,
            List<LogEntry> logs
    ) {
        public static TraceData empty() {
            return new TraceData(Trace.empty(), List.of());
        }

        public static TraceData withTrace(Trace trace) {
            return new TraceData(trace, List.of());
        }

        public static TraceData withLogs(List<LogEntry> logs) {
            return new TraceData(Trace.empty(), logs);
        }

        public boolean hasTrace() {
            return trace.hasSpans();
        }

        public boolean hasLogs() {
            return !logs.isEmpty();
        }

        public boolean isEmpty() {
            return trace.isEmpty() && logs.isEmpty();
        }
    }

    // ========================================================================
    // Sample Event
    // ========================================================================

    public enum EventStatus {
        SUCCESS("success"),
        ERROR("error"),
        TIMEOUT("timeout");

        private final String value;
        EventStatus(String value) { this.value = value; }

        @JsonValue
        public String value() { return value; }
    }

    public record SampleEvent(
            String id,
            String traceId,
            String otelTraceId,
            long timestamp,  // epoch millis (matches Go int64)
            long latencyMs,
            EventStatus status,
            String error,
            ComponentTiming componentTiming,
            TraceData traceData
    ) {
        public static SampleEvent success(String id, String traceId, String otelTraceId, long latencyMs) {
            return new SampleEvent(id, traceId, otelTraceId, System.currentTimeMillis(), latencyMs,
                    EventStatus.SUCCESS, "", ComponentTiming.empty(), TraceData.empty());
        }

        public static SampleEvent error(String id, String traceId, String otelTraceId, long latencyMs, String error) {
            return new SampleEvent(id, traceId, otelTraceId, System.currentTimeMillis(), latencyMs,
                    EventStatus.ERROR, error, ComponentTiming.empty(), TraceData.empty());
        }

        public static SampleEvent timeout(String id, String traceId, String otelTraceId, long latencyMs) {
            return new SampleEvent(id, traceId, otelTraceId, System.currentTimeMillis(), latencyMs,
                    EventStatus.TIMEOUT, "Request timed out", ComponentTiming.empty(), TraceData.empty());
        }

        public SampleEvent withTiming(ComponentTiming timing) {
            return new SampleEvent(id, traceId, otelTraceId, timestamp, latencyMs,
                    status, error, timing, traceData);
        }

        public SampleEvent withTraceData(TraceData data) {
            return new SampleEvent(id, traceId, otelTraceId, timestamp, latencyMs,
                    status, error, componentTiming, data);
        }
    }

    // ========================================================================
    // Progress (for real-time updates)
    // ========================================================================

    public record Progress(
            long operationsCompleted,
            long currentThroughput,
            long elapsedMs,
            long remainingMs,
            int percentComplete,
            double currentCpu,
            double currentMemory
    ) {}
}
