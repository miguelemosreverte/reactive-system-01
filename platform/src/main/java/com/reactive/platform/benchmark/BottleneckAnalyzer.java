package com.reactive.platform.benchmark;

import com.reactive.platform.benchmark.BenchmarkTypes.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Automatic bottleneck detection from trace analysis.
 *
 * Analyzes spans to identify which component is the bottleneck
 * and provides actionable recommendations.
 */
public class BottleneckAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(BottleneckAnalyzer.class);

    // ========================================================================
    // Result Types
    // ========================================================================

    /** Analysis result for a single trace. */
    public record TraceAnalysis(
            String traceId,
            long totalDurationUs,
            Map<String, Long> durationByService,
            Map<String, Double> percentByService,
            String bottleneck,
            double bottleneckPercent,
            List<String> recommendations
    ) {
        public static TraceAnalysis empty(String traceId) {
            return new TraceAnalysis(traceId, 0, Map.of(), Map.of(), "unknown", 0, List.of());
        }
    }

    /** Aggregated analysis from multiple traces. */
    public record AggregateAnalysis(
            int traceCount,
            Map<String, Double> avgPercentByService,
            String primaryBottleneck,
            double bottleneckConfidence,
            Map<String, List<String>> bottleneckCounts,
            List<String> recommendations
    ) {}

    // ========================================================================
    // Trace Analysis
    // ========================================================================

    /** Analyze a single trace to identify bottleneck. */
    public TraceAnalysis analyzeTrace(JaegerTrace trace) {
        if (trace == null || trace.spans() == null || trace.spans().isEmpty()) {
            return TraceAnalysis.empty(trace != null ? trace.traceId() : "unknown");
        }

        // Calculate duration by service
        Map<String, Long> durationByService = new HashMap<>();
        long totalDuration = 0;

        for (JaegerSpan span : trace.spans()) {
            String service = getServiceName(trace, span);
            long duration = span.duration();

            durationByService.merge(service, duration, Long::max); // Use max to avoid double-counting nested spans
            totalDuration = Math.max(totalDuration, getSpanEndTime(span) - getEarliestStartTime(trace));
        }

        if (totalDuration == 0) {
            return TraceAnalysis.empty(trace.traceId());
        }

        // Calculate percentages
        Map<String, Double> percentByService = new HashMap<>();
        for (var entry : durationByService.entrySet()) {
            percentByService.put(entry.getKey(), (entry.getValue() * 100.0) / totalDuration);
        }

        // Find bottleneck (highest percentage)
        String bottleneck = durationByService.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("unknown");

        double bottleneckPercent = percentByService.getOrDefault(bottleneck, 0.0);

        // Generate recommendations
        List<String> recommendations = generateRecommendations(bottleneck, percentByService, durationByService);

        return new TraceAnalysis(
                trace.traceId(),
                totalDuration,
                Map.copyOf(durationByService),
                Map.copyOf(percentByService),
                bottleneck,
                bottleneckPercent,
                recommendations
        );
    }

    /** Analyze multiple traces to get aggregate statistics. */
    public AggregateAnalysis analyzeTraces(List<JaegerTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return new AggregateAnalysis(0, Map.of(), "unknown", 0, Map.of(), List.of("No traces available for analysis"));
        }

        Map<String, List<Double>> percentsByService = new HashMap<>();
        Map<String, Integer> bottleneckCounts = new HashMap<>();

        for (JaegerTrace trace : traces) {
            TraceAnalysis analysis = analyzeTrace(trace);

            for (var entry : analysis.percentByService().entrySet()) {
                percentsByService.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                        .add(entry.getValue());
            }

            bottleneckCounts.merge(analysis.bottleneck(), 1, Integer::sum);
        }

        // Calculate averages
        Map<String, Double> avgPercentByService = new HashMap<>();
        for (var entry : percentsByService.entrySet()) {
            double avg = entry.getValue().stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0);
            avgPercentByService.put(entry.getKey(), avg);
        }

        // Find primary bottleneck (most frequent)
        String primaryBottleneck = bottleneckCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("unknown");

        double confidence = (double) bottleneckCounts.getOrDefault(primaryBottleneck, 0) / traces.size() * 100;

        // Generate aggregate recommendations
        List<String> recommendations = generateAggregateRecommendations(
                primaryBottleneck, avgPercentByService, confidence);

        // Convert bottleneck counts to string list for JSON serialization
        Map<String, List<String>> bottleneckDetails = new HashMap<>();
        for (var entry : bottleneckCounts.entrySet()) {
            bottleneckDetails.put(entry.getKey(), List.of(
                    String.format("Count: %d (%.1f%%)", entry.getValue(), (entry.getValue() * 100.0) / traces.size())
            ));
        }

        return new AggregateAnalysis(
                traces.size(),
                Map.copyOf(avgPercentByService),
                primaryBottleneck,
                confidence,
                bottleneckDetails,
                recommendations
        );
    }

    /** Analyze sample events and their traces. */
    public AggregateAnalysis analyzeSampleEvents(List<SampleEvent> events) {
        List<JaegerTrace> traces = events.stream()
                .filter(e -> e.traceData() != null && e.traceData().trace() != null)
                .map(e -> e.traceData().trace())
                .collect(Collectors.toList());

        return analyzeTraces(traces);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private String getServiceName(JaegerTrace trace, JaegerSpan span) {
        JaegerProcess process = trace.processes().get(span.processId());
        return process != null ? process.serviceName() : "unknown";
    }

    private long getSpanEndTime(JaegerSpan span) {
        return span.startTime() + span.duration();
    }

    private long getEarliestStartTime(JaegerTrace trace) {
        return trace.spans().stream()
                .mapToLong(JaegerSpan::startTime)
                .min()
                .orElse(0);
    }

    private List<String> generateRecommendations(
            String bottleneck,
            Map<String, Double> percentByService,
            Map<String, Long> durationByService
    ) {
        List<String> recommendations = new ArrayList<>();

        switch (bottleneck.toLowerCase()) {
            case "counter-application", "gateway" -> {
                double percent = percentByService.getOrDefault(bottleneck, 0.0);
                if (percent > 50) {
                    recommendations.add("Gateway is the bottleneck (" + String.format("%.1f%%", percent) + " of time)");
                    recommendations.add("Consider: Increase connection pool size");
                    recommendations.add("Consider: Enable HTTP/2 for better multiplexing");
                    recommendations.add("Consider: Add caching for repeated requests");
                }
            }
            case "kafka", "reactive-kafka" -> {
                double percent = percentByService.getOrDefault(bottleneck, 0.0);
                if (percent > 40) {
                    recommendations.add("Kafka is the bottleneck (" + String.format("%.1f%%", percent) + " of time)");
                    recommendations.add("Consider: Use async acks (acks=1 instead of acks=all)");
                    recommendations.add("Consider: Batch more messages");
                    recommendations.add("Consider: Increase producer buffer size");
                }
            }
            case "flink-taskmanager", "flink" -> {
                double percent = percentByService.getOrDefault(bottleneck, 0.0);
                if (percent > 40) {
                    recommendations.add("Flink is the bottleneck (" + String.format("%.1f%%", percent) + " of time)");
                    recommendations.add("Consider: Increase parallelism (currently limited by task slots)");
                    recommendations.add("Consider: Add more taskmanagers");
                    recommendations.add("Consider: Optimize async I/O capacity");
                }
            }
            case "drools", "reactive-drools" -> {
                double percent = percentByService.getOrDefault(bottleneck, 0.0);
                if (percent > 30) {
                    recommendations.add("Drools is the bottleneck (" + String.format("%.1f%%", percent) + " of time)");
                    recommendations.add("Consider: Cache rule sessions");
                    recommendations.add("Consider: Use parallel rule evaluation");
                    recommendations.add("Consider: Profile specific rules for optimization");
                }
            }
            default -> {
                recommendations.add("Bottleneck identified: " + bottleneck);
                recommendations.add("Investigate this component for optimization opportunities");
            }
        }

        return recommendations;
    }

    private List<String> generateAggregateRecommendations(
            String primaryBottleneck,
            Map<String, Double> avgPercentByService,
            double confidence
    ) {
        List<String> recommendations = new ArrayList<>();

        if (confidence < 50) {
            recommendations.add("Warning: Low bottleneck confidence (" + String.format("%.1f%%", confidence) + ")");
            recommendations.add("The bottleneck varies between traces - consider running longer benchmarks");
            return recommendations;
        }

        double avgPercent = avgPercentByService.getOrDefault(primaryBottleneck, 0.0);
        recommendations.add(String.format("Primary bottleneck: %s (%.1f%% of trace time, %.1f%% confidence)",
                primaryBottleneck, avgPercent, confidence));

        // Add specific recommendations based on bottleneck
        switch (primaryBottleneck.toLowerCase()) {
            case "counter-application", "gateway" -> {
                recommendations.add("→ Optimize HTTP handler performance");
                recommendations.add("→ Check connection pool exhaustion");
            }
            case "kafka", "reactive-kafka" -> {
                recommendations.add("→ Consider linger.ms > 0 for batching");
                recommendations.add("→ Check broker replication lag");
            }
            case "flink-taskmanager", "flink" -> {
                recommendations.add("→ Increase flink taskmanager slots");
                recommendations.add("→ Consider adding more taskmanagers");
            }
            case "drools", "reactive-drools" -> {
                recommendations.add("→ Profile rule evaluation time");
                recommendations.add("→ Consider rule optimization");
            }
        }

        return recommendations;
    }

    // ========================================================================
    // Convenience Methods
    // ========================================================================

    /** Create a summary string for logging. */
    public String formatSummary(AggregateAnalysis analysis) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== Bottleneck Analysis ===\n");
        sb.append(String.format("Traces analyzed: %d\n", analysis.traceCount()));
        sb.append(String.format("Primary bottleneck: %s (%.1f%% confidence)\n",
                analysis.primaryBottleneck(), analysis.bottleneckConfidence()));

        sb.append("\nTime distribution by service:\n");
        analysis.avgPercentByService().entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> sb.append(String.format("  %s: %.1f%%\n", e.getKey(), e.getValue())));

        sb.append("\nRecommendations:\n");
        for (String rec : analysis.recommendations()) {
            sb.append("  " + rec + "\n");
        }

        return sb.toString();
    }
}
