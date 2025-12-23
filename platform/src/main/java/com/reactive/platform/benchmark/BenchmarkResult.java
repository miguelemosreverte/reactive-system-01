package com.reactive.platform.benchmark;

import com.reactive.platform.benchmark.BenchmarkTypes.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Benchmark result - immutable record with static factories.
 */
public record BenchmarkResult(
        ComponentId component,
        String name,
        String description,
        Instant startTime,
        Instant endTime,
        long durationMs,

        // Throughput
        long totalOperations,
        long successfulOperations,
        long failedOperations,
        long peakThroughput,
        long avgThroughput,
        List<Long> throughputTimeline,

        // Latency
        LatencyStats latency,

        // Throughput stability (coefficient of variation - lower = more stable)
        double throughputStability,

        // Resources
        List<Double> cpuTimeline,
        List<Double> memoryTimeline,
        double peakCpu,
        double peakMemory,
        double avgCpu,
        double avgMemory,

        // Component timing (for full benchmark)
        ComponentTiming componentTiming,

        // Sample events
        List<SampleEvent> sampleEvents,

        // Status
        String status,
        String errorMessage
) {
    // Compact constructor - normalize nulls to empty collections/defaults
    public BenchmarkResult {
        throughputTimeline = throughputTimeline != null ? throughputTimeline : List.of();
        cpuTimeline = cpuTimeline != null ? cpuTimeline : List.of();
        memoryTimeline = memoryTimeline != null ? memoryTimeline : List.of();
        sampleEvents = sampleEvents != null ? sampleEvents : List.of();
        status = status != null ? status : "unknown";
        errorMessage = errorMessage != null ? errorMessage : "";
    }

    // ========================================================================
    // Static Factories
    // ========================================================================

    public static BenchmarkResult completed(
            ComponentId component,
            Instant startTime,
            Instant endTime,
            long totalOps,
            long successOps,
            long failedOps,
            LatencyStats latency,
            List<Long> throughputTimeline,
            List<Double> cpuTimeline,
            List<Double> memoryTimeline,
            List<SampleEvent> samples,
            double throughputStability
    ) {
        long durationMs = endTime.toEpochMilli() - startTime.toEpochMilli();
        long avgThroughput = durationMs > 0 ? (totalOps * 1000) / durationMs : 0;
        long peakThroughput = throughputTimeline.stream().mapToLong(Long::longValue).max().orElse(avgThroughput);
        double peakCpu = cpuTimeline.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double peakMemory = memoryTimeline.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double avgCpu = cpuTimeline.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double avgMemory = memoryTimeline.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        return new BenchmarkResult(
                component,
                component.displayName(),
                component.description(),
                startTime,
                endTime,
                durationMs,
                totalOps,
                successOps,
                failedOps,
                peakThroughput,
                avgThroughput,
                List.copyOf(throughputTimeline),
                latency,
                throughputStability,
                List.copyOf(cpuTimeline),
                List.copyOf(memoryTimeline),
                peakCpu,
                peakMemory,
                avgCpu,
                avgMemory,
                ComponentTiming.empty(),
                List.copyOf(samples),
                "completed",
                ""
        );
    }

    public static BenchmarkResult error(ComponentId component, Instant startTime, String errorMessage) {
        return new BenchmarkResult(
                component,
                component.displayName(),
                component.description(),
                startTime,
                Instant.now(),
                0,
                0, 0, 0, 0, 0,
                List.of(),
                LatencyStats.empty(),
                0.0, // throughputStability
                List.of(),
                List.of(),
                0, 0, 0, 0,
                ComponentTiming.empty(),
                List.of(),
                "error",
                errorMessage
        );
    }

    public static BenchmarkResult stopped(
            ComponentId component,
            Instant startTime,
            long totalOps,
            long successOps,
            long failedOps,
            LatencyStats latency,
            List<Long> throughputTimeline,
            List<Double> cpuTimeline,
            List<Double> memoryTimeline,
            List<SampleEvent> samples,
            double throughputStability
    ) {
        var result = completed(component, startTime, Instant.now(),
                totalOps, successOps, failedOps, latency,
                throughputTimeline, cpuTimeline, memoryTimeline, samples, throughputStability);

        return new BenchmarkResult(
                result.component(),
                result.name(),
                result.description(),
                result.startTime(),
                result.endTime(),
                result.durationMs(),
                result.totalOperations(),
                result.successfulOperations(),
                result.failedOperations(),
                result.peakThroughput(),
                result.avgThroughput(),
                result.throughputTimeline(),
                result.latency(),
                result.throughputStability(),
                result.cpuTimeline(),
                result.memoryTimeline(),
                result.peakCpu(),
                result.peakMemory(),
                result.avgCpu(),
                result.avgMemory(),
                result.componentTiming(),
                result.sampleEvents(),
                "stopped",
                ""
        );
    }

    // ========================================================================
    // Derived values
    // ========================================================================

    public double successRate() {
        return totalOperations > 0 ? (double) successfulOperations / totalOperations * 100 : 0;
    }

    public double failureRate() {
        return totalOperations > 0 ? (double) failedOperations / totalOperations * 100 : 0;
    }

    public boolean isSuccess() {
        return "completed".equals(status) && failedOperations == 0;
    }

    /** Analyze sample events to detect bottlenecks. */
    public Optional<BottleneckAnalyzer.AggregateAnalysis> analyzeBottlenecks() {
        if (sampleEvents.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new BottleneckAnalyzer().analyzeSampleEvents(sampleEvents));
    }

    /** Generate a comprehensive diagnostic report with component health and recommendations. */
    public Optional<BottleneckAnalyzer.DiagnosticReport> generateDiagnosticReport() {
        if (sampleEvents.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new BottleneckAnalyzer().generateDiagnosticReport(sampleEvents));
    }

    public BenchmarkResult withComponentTiming(ComponentTiming timing) {
        return new BenchmarkResult(
                component, name, description, startTime, endTime, durationMs,
                totalOperations, successfulOperations, failedOperations,
                peakThroughput, avgThroughput, throughputTimeline,
                latency, throughputStability, cpuTimeline, memoryTimeline,
                peakCpu, peakMemory, avgCpu, avgMemory,
                timing, sampleEvents, status, errorMessage
        );
    }
}
