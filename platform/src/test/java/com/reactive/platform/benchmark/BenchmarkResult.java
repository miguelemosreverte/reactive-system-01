package com.reactive.platform.benchmark;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Benchmark result data.
 */
public record BenchmarkResult(
        String component,
        Instant startTime,
        Instant endTime,
        long durationMs,

        // Throughput
        long totalOperations,
        long successfulOperations,
        long failedOperations,
        double peakThroughput,
        double avgThroughput,
        List<Double> throughputTimeline,

        // Latency (milliseconds)
        LatencyStats latency,

        // Resources
        List<Double> cpuTimeline,
        List<Double> memoryTimeline,
        double peakCpu,
        double peakMemory,

        // Sample events
        List<SampleEvent> sampleEvents,

        // Status
        Status status,
        String message
) {
    public enum Status {
        COMPLETED,
        STOPPED,
        ERROR
    }

    public record LatencyStats(
            double min,
            double max,
            double avg,
            double p50,
            double p95,
            double p99
    ) {
        public static LatencyStats calculate(List<Long> latencies) {
            if (latencies.isEmpty()) {
                return new LatencyStats(0, 0, 0, 0, 0, 0);
            }

            List<Long> sorted = latencies.stream().sorted().toList();
            int size = sorted.size();

            double min = sorted.get(0);
            double max = sorted.get(size - 1);
            double avg = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
            double p50 = percentile(sorted, 50);
            double p95 = percentile(sorted, 95);
            double p99 = percentile(sorted, 99);

            return new LatencyStats(min, max, avg, p50, p95, p99);
        }

        private static double percentile(List<Long> sorted, int p) {
            int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
        }
    }

    public record SampleEvent(
            String id,
            String traceId,
            long latencyMs,
            EventStatus status,
            String error,
            Map<String, Long> componentTiming
    ) {
        public enum EventStatus {
            SUCCESS, ERROR, TIMEOUT
        }
    }

    /**
     * Builder for creating benchmark results.
     */
    public static Builder builder(String component) {
        return new Builder(component);
    }

    public static class Builder {
        private final String component;
        private Instant startTime = Instant.now();
        private Instant endTime;
        private long totalOperations;
        private long successfulOperations;
        private long failedOperations;
        private double peakThroughput;
        private double avgThroughput;
        private List<Double> throughputTimeline = List.of();
        private LatencyStats latency = new LatencyStats(0, 0, 0, 0, 0, 0);
        private List<Double> cpuTimeline = List.of();
        private List<Double> memoryTimeline = List.of();
        private double peakCpu;
        private double peakMemory;
        private List<SampleEvent> sampleEvents = List.of();
        private Status status = Status.COMPLETED;
        private String message = "";

        private Builder(String component) {
            this.component = component;
        }

        public Builder startTime(Instant startTime) {
            this.startTime = startTime;
            return this;
        }

        public Builder endTime(Instant endTime) {
            this.endTime = endTime;
            return this;
        }

        public Builder totalOperations(long total) {
            this.totalOperations = total;
            return this;
        }

        public Builder successfulOperations(long successful) {
            this.successfulOperations = successful;
            return this;
        }

        public Builder failedOperations(long failed) {
            this.failedOperations = failed;
            return this;
        }

        public Builder peakThroughput(double peak) {
            this.peakThroughput = peak;
            return this;
        }

        public Builder avgThroughput(double avg) {
            this.avgThroughput = avg;
            return this;
        }

        public Builder throughputTimeline(List<Double> timeline) {
            this.throughputTimeline = timeline;
            return this;
        }

        public Builder latency(LatencyStats latency) {
            this.latency = latency;
            return this;
        }

        public Builder latency(List<Long> latencies) {
            this.latency = LatencyStats.calculate(latencies);
            return this;
        }

        public Builder cpuTimeline(List<Double> timeline) {
            this.cpuTimeline = timeline;
            this.peakCpu = timeline.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            return this;
        }

        public Builder memoryTimeline(List<Double> timeline) {
            this.memoryTimeline = timeline;
            this.peakMemory = timeline.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            return this;
        }

        public Builder sampleEvents(List<SampleEvent> events) {
            this.sampleEvents = events;
            return this;
        }

        public Builder status(Status status) {
            this.status = status;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public BenchmarkResult build() {
            if (endTime == null) {
                endTime = Instant.now();
            }
            long durationMs = endTime.toEpochMilli() - startTime.toEpochMilli();

            return new BenchmarkResult(
                    component,
                    startTime,
                    endTime,
                    durationMs,
                    totalOperations,
                    successfulOperations,
                    failedOperations,
                    peakThroughput,
                    avgThroughput,
                    throughputTimeline,
                    latency,
                    cpuTimeline,
                    memoryTimeline,
                    peakCpu,
                    peakMemory,
                    sampleEvents,
                    status,
                    message
            );
        }
    }
}
