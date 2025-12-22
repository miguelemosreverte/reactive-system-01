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

    /** Health status for a component. */
    public enum HealthStatus {
        HEALTHY("✓", "green"),      // < 20% of trace time
        WARNING("⚠", "yellow"),     // 20-50% of trace time
        CRITICAL("✗", "red");       // > 50% of trace time

        private final String symbol;
        private final String color;

        HealthStatus(String symbol, String color) {
            this.symbol = symbol;
            this.color = color;
        }

        public String symbol() { return symbol; }
        public String color() { return color; }

        public static HealthStatus fromPercent(double percent) {
            if (percent > 50) return CRITICAL;
            if (percent > 20) return WARNING;
            return HEALTHY;
        }
    }

    /** Component health assessment. */
    public record ComponentHealth(
            String service,
            long avgDurationUs,
            double percentOfTotal,
            HealthStatus status,
            String diagnosis
    ) {}

    /** Complete diagnostic report with rich information. */
    public record DiagnosticReport(
            int traceCount,
            long avgTotalDurationUs,
            List<ComponentHealth> componentHealth,
            String primaryBottleneck,
            double bottleneckPercent,
            double confidence,
            String diagnosis,
            List<String> recommendations,
            String severityLevel  // INFO, WARNING, CRITICAL
    ) {
        /** Format as a detailed console report. */
        public String toConsoleReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("\n╔══════════════════════════════════════════════════════════════╗\n");
            sb.append("║                    DIAGNOSTIC REPORT                          ║\n");
            sb.append("╠══════════════════════════════════════════════════════════════╣\n");

            sb.append(String.format("║ Traces Analyzed: %-3d    Avg E2E Latency: %-6.2fms           ║\n",
                    traceCount, avgTotalDurationUs / 1000.0));
            sb.append("╠══════════════════════════════════════════════════════════════╣\n");
            sb.append("║ COMPONENT BREAKDOWN                                           ║\n");
            sb.append("╠══════════════════════════════════════════════════════════════╣\n");

            for (ComponentHealth ch : componentHealth) {
                String bar = "█".repeat(Math.min((int)(ch.percentOfTotal / 5), 10));
                String spaces = " ".repeat(10 - bar.length());
                sb.append(String.format("║ %s %-20s %6.1f%% %s%s      ║\n",
                        ch.status.symbol(), ch.service, ch.percentOfTotal, bar, spaces));
            }

            sb.append("╠══════════════════════════════════════════════════════════════╣\n");
            sb.append(String.format("║ BOTTLENECK: %-20s (%.1f%% confidence)       ║\n",
                    primaryBottleneck, confidence));
            sb.append("╠══════════════════════════════════════════════════════════════╣\n");
            sb.append("║ DIAGNOSIS                                                     ║\n");
            sb.append(String.format("║ %-62s ║\n", truncate(diagnosis, 62)));
            sb.append("╠══════════════════════════════════════════════════════════════╣\n");
            sb.append("║ RECOMMENDATIONS                                               ║\n");

            for (String rec : recommendations) {
                sb.append(String.format("║   → %-57s ║\n", truncate(rec, 57)));
            }

            sb.append("╚══════════════════════════════════════════════════════════════╝\n");
            return sb.toString();
        }

        private static String truncate(String s, int len) {
            if (s == null) return "";
            return s.length() <= len ? s : s.substring(0, len - 3) + "...";
        }
    }

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

    /** Generate a comprehensive diagnostic report from sample events. */
    public DiagnosticReport generateDiagnosticReport(List<SampleEvent> events) {
        List<JaegerTrace> traces = events.stream()
                .filter(e -> e.traceData() != null && e.traceData().trace() != null)
                .map(e -> e.traceData().trace())
                .collect(Collectors.toList());

        if (traces.isEmpty()) {
            return new DiagnosticReport(
                    0, 0, List.of(), "unknown", 0, 0,
                    "No trace data available for analysis",
                    List.of("Enable trace enrichment (remove --quick flag)"),
                    "INFO"
            );
        }

        // Calculate per-service statistics
        Map<String, List<Long>> durationsByService = new HashMap<>();
        List<Long> totalDurations = new ArrayList<>();
        Map<String, Integer> bottleneckCounts = new HashMap<>();

        for (JaegerTrace trace : traces) {
            TraceAnalysis analysis = analyzeTrace(trace);
            totalDurations.add(analysis.totalDurationUs());

            for (var entry : analysis.durationByService().entrySet()) {
                durationsByService.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                        .add(entry.getValue());
            }

            bottleneckCounts.merge(analysis.bottleneck(), 1, Integer::sum);
        }

        long avgTotalDuration = (long) totalDurations.stream()
                .mapToLong(Long::longValue).average().orElse(0);

        // Build component health list
        List<ComponentHealth> componentHealth = new ArrayList<>();
        for (var entry : durationsByService.entrySet()) {
            String service = entry.getKey();
            long avgDuration = (long) entry.getValue().stream()
                    .mapToLong(Long::longValue).average().orElse(0);
            double percent = avgTotalDuration > 0 ? (avgDuration * 100.0) / avgTotalDuration : 0;
            HealthStatus status = HealthStatus.fromPercent(percent);
            String diagnosis = generateComponentDiagnosis(service, percent, status);

            componentHealth.add(new ComponentHealth(service, avgDuration, percent, status, diagnosis));
        }

        // Sort by percentage (highest first)
        componentHealth.sort((a, b) -> Double.compare(b.percentOfTotal(), a.percentOfTotal()));

        // Find primary bottleneck
        String primaryBottleneck = bottleneckCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("unknown");

        double confidence = (double) bottleneckCounts.getOrDefault(primaryBottleneck, 0) / traces.size() * 100;
        double bottleneckPercent = componentHealth.stream()
                .filter(ch -> ch.service().equals(primaryBottleneck))
                .findFirst()
                .map(ComponentHealth::percentOfTotal)
                .orElse(0.0);

        // Generate diagnosis and recommendations
        String diagnosis = generateOverallDiagnosis(primaryBottleneck, bottleneckPercent, confidence);
        List<String> recommendations = generateDetailedRecommendations(primaryBottleneck, bottleneckPercent);

        // Determine severity
        String severity = bottleneckPercent > 80 ? "CRITICAL" :
                          bottleneckPercent > 50 ? "WARNING" : "INFO";

        return new DiagnosticReport(
                traces.size(),
                avgTotalDuration,
                componentHealth,
                primaryBottleneck,
                bottleneckPercent,
                confidence,
                diagnosis,
                recommendations,
                severity
        );
    }

    private String generateComponentDiagnosis(String service, double percent, HealthStatus status) {
        return switch (status) {
            case CRITICAL -> service + " is consuming " + String.format("%.0f%%", percent) + " of latency - needs immediate attention";
            case WARNING -> service + " at " + String.format("%.0f%%", percent) + " - consider optimization";
            case HEALTHY -> service + " performing well at " + String.format("%.0f%%", percent);
        };
    }

    private String generateOverallDiagnosis(String bottleneck, double percent, double confidence) {
        if (confidence < 50) {
            return "Inconsistent bottleneck pattern - need more traces for reliable analysis";
        }

        String componentName = switch (bottleneck.toLowerCase()) {
            case "flink-taskmanager" -> "Flink stream processing";
            case "counter-application" -> "Gateway HTTP handling";
            case "drools", "reactive-drools" -> "Drools rule evaluation";
            case "kafka" -> "Kafka message passing";
            default -> bottleneck;
        };

        if (percent > 80) {
            return componentName + " is severely limiting throughput at " + String.format("%.0f%%", percent) + " of latency";
        } else if (percent > 50) {
            return componentName + " is the primary bottleneck at " + String.format("%.0f%%", percent) + " of latency";
        } else {
            return "System is relatively balanced, " + componentName + " leads at " + String.format("%.0f%%", percent);
        }
    }

    private List<String> generateDetailedRecommendations(String bottleneck, double percent) {
        List<String> recs = new ArrayList<>();

        if (percent < 30) {
            recs.add("System is well-balanced - consider horizontal scaling for throughput gains");
            return recs;
        }

        switch (bottleneck.toLowerCase()) {
            case "flink-taskmanager" -> {
                recs.add("Add second Flink taskmanager (docker-compose scale)");
                recs.add("Increase taskmanager.numberOfTaskSlots to match Kafka partitions");
                recs.add("Tune parallelism.default to utilize all slots");
                if (percent > 80) {
                    recs.add("URGENT: Flink is severely backpressured - scale immediately");
                }
            }
            case "counter-application", "gateway" -> {
                recs.add("Increase HTTP thread pool size");
                recs.add("Enable connection keepalive");
                recs.add("Profile slow endpoints with async-profiler");
            }
            case "kafka" -> {
                recs.add("Set linger.ms=5 for producer batching");
                recs.add("Increase batch.size for larger batches");
                recs.add("Consider acks=1 for non-critical data");
            }
            case "drools", "reactive-drools" -> {
                recs.add("Enable KieBase caching between requests");
                recs.add("Profile individual rules for optimization");
                recs.add("Consider parallel rule execution");
            }
            default -> {
                recs.add("Profile " + bottleneck + " for optimization opportunities");
            }
        }

        return recs;
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
