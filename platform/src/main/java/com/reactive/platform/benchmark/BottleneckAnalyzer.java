package com.reactive.platform.benchmark;

import com.reactive.platform.benchmark.BenchmarkTypes.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Automatic bottleneck detection from trace analysis.
 *
 * Analyzes spans to identify which component is the bottleneck
 * and provides actionable recommendations.
 */
public class BottleneckAnalyzer {

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

    /** Individual operation timing from a span. */
    public record OperationTiming(
            String service,
            String operation,
            long durationUs,
            String spanId,
            String parentSpanId,
            Map<String, String> tags
    ) {}

    /** Aggregated statistics for an operation. */
    public record OperationStats(
            String service,
            String operation,
            int count,
            long minUs,
            long maxUs,
            long avgUs,
            long p50Us,
            long p99Us,
            double percentOfTotal
    ) {
        public String fullName() {
            return service + ":" + operation;
        }
    }

    /** Hot path - the slowest operation chain in the trace. */
    public record HotPath(
            List<OperationTiming> operations,
            long totalDurationUs,
            double percentOfTrace
    ) {}

    /** Comparison with previous benchmark run. */
    public record BenchmarkComparison(
            long previousThroughput,
            long currentThroughput,
            double changePercent,
            String trend,  // IMPROVED, DEGRADED, STABLE
            String summary
    ) {
        public static BenchmarkComparison create(long previous, long current) {
            if (previous == 0) {
                return new BenchmarkComparison(0, current, 0, "BASELINE", "First benchmark run - establishing baseline");
            }

            double change = ((double) current - previous) / previous * 100;
            String trend;
            String summary;

            if (change > 10) {
                trend = "IMPROVED";
                summary = String.format("↑ Throughput improved by %.1f%% (%d → %d ops/s)", change, previous, current);
            } else if (change < -10) {
                trend = "DEGRADED";
                summary = String.format("↓ Throughput degraded by %.1f%% (%d → %d ops/s)", -change, previous, current);
            } else {
                trend = "STABLE";
                summary = String.format("≈ Throughput stable (%.1f%% change, %d → %d ops/s)", change, previous, current);
            }

            return new BenchmarkComparison(previous, current, change, trend, summary);
        }
    }

    /** Complete diagnostic report with rich information. */
    public record DiagnosticReport(
            int traceCount,
            long avgTotalDurationUs,
            List<ComponentHealth> componentHealth,
            List<OperationStats> operationStats,     // NEW: per-operation breakdown
            List<OperationTiming> slowestOperations, // NEW: top 5 slowest operations
            String primaryBottleneck,
            double bottleneckPercent,
            double confidence,
            String diagnosis,
            List<String> recommendations,
            String severityLevel,  // INFO, WARNING, CRITICAL
            String oneLiner,       // Single-line summary for quick scanning
            String verdict         // What to do next
    ) {
        /** Format as a detailed console report. */
        public String toConsoleReport() {
            StringBuilder sb = new StringBuilder();

            // One-liner summary at the top
            String severityIcon = switch (severityLevel) {
                case "CRITICAL" -> "🔴";
                case "WARNING" -> "🟡";
                default -> "🟢";
            };
            sb.append("\n").append(severityIcon).append(" ").append(oneLiner).append("\n");

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

            sb.append("╠══════════════════════════════════════════════════════════════╣\n");
            sb.append("║ NEXT STEP                                                     ║\n");
            sb.append(String.format("║ ▶ %-60s ║\n", truncate(verdict, 60)));

            // Operation breakdown (if available)
            if (operationStats != null && !operationStats.isEmpty()) {
                sb.append("╠══════════════════════════════════════════════════════════════╣\n");
                sb.append("║ OPERATION BREAKDOWN (Top 5 by time)                          ║\n");
                sb.append("╠══════════════════════════════════════════════════════════════╣\n");

                operationStats.stream()
                        .sorted((a, b) -> Long.compare(b.avgUs(), a.avgUs()))
                        .limit(5)
                        .forEach(op -> {
                            String name = truncate(op.operation(), 25);
                            String svc = truncate(op.service(), 10);
                            sb.append(String.format("║ %-10s %-25s avg:%4dµs p99:%5dµs ║\n",
                                    svc, name, op.avgUs(), op.p99Us()));
                        });
            }

            // Slowest individual spans
            if (slowestOperations != null && !slowestOperations.isEmpty()) {
                sb.append("╠══════════════════════════════════════════════════════════════╣\n");
                sb.append("║ SLOWEST SPANS (investigate these first)                      ║\n");
                sb.append("╠══════════════════════════════════════════════════════════════╣\n");

                slowestOperations.stream()
                        .limit(3)
                        .forEach(span -> {
                            String op = truncate(span.operation(), 30);
                            sb.append(String.format("║ ⚠ %-30s %6dµs (%.1fms)        ║\n",
                                    op, span.durationUs(), span.durationUs() / 1000.0));
                        });
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
    public TraceAnalysis analyzeTrace(Trace trace) {
        if (trace == null || trace.spans() == null || trace.spans().isEmpty()) {
            return TraceAnalysis.empty(trace != null ? trace.traceId() : "unknown");
        }

        // Calculate duration by service (max per service to avoid double-counting nested spans)
        Map<String, Long> durationByService = trace.spans().stream()
            .collect(Collectors.toMap(
                span -> getServiceName(trace, span),
                Span::duration,
                Long::max
            ));

        long earliestStart = getEarliestStartTime(trace);
        long totalDuration = trace.spans().stream()
            .mapToLong(span -> getSpanEndTime(span) - earliestStart)
            .max()
            .orElse(0);

        if (totalDuration == 0) {
            return TraceAnalysis.empty(trace.traceId());
        }

        // Calculate percentages
        final long duration = totalDuration;
        Map<String, Double> percentByService = durationByService.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> (e.getValue() * 100.0) / duration
            ));

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
    public AggregateAnalysis analyzeTraces(List<Trace> traces) {
        if (traces == null || traces.isEmpty()) {
            return new AggregateAnalysis(0, Map.of(), "unknown", 0, Map.of(), List.of("No traces available for analysis"));
        }

        List<TraceAnalysis> analyses = traces.stream()
            .map(this::analyzeTrace)
            .toList();

        // Group all percentages by service
        Map<String, List<Double>> percentsByService = analyses.stream()
            .flatMap(a -> a.percentByService().entrySet().stream())
            .collect(Collectors.groupingBy(
                Map.Entry::getKey,
                Collectors.mapping(Map.Entry::getValue, Collectors.toList())
            ));

        // Count bottlenecks
        Map<String, Integer> bottleneckCounts = analyses.stream()
            .collect(Collectors.groupingBy(
                TraceAnalysis::bottleneck,
                Collectors.summingInt(a -> 1)
            ));

        // Calculate averages
        Map<String, Double> avgPercentByService = percentsByService.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0)
            ));

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
        List<Trace> traces = events.stream()
                .filter(e -> e.traceData().hasTrace())
                .map(e -> e.traceData().trace())
                .collect(Collectors.toList());

        return analyzeTraces(traces);
    }

    /** Generate a comprehensive diagnostic report from sample events. */
    public DiagnosticReport generateDiagnosticReport(List<SampleEvent> events) {
        List<Trace> traces = events.stream()
                .filter(e -> e.traceData().hasTrace())
                .map(e -> e.traceData().trace())
                .collect(Collectors.toList());

        if (traces.isEmpty()) {
            return new DiagnosticReport(
                    0, 0, List.of(), List.of(), List.of(), "unknown", 0, 0,
                    "No trace data available for analysis",
                    List.of("Enable trace enrichment (remove --quick flag)"),
                    "INFO",
                    "No traces - run without --quick for detailed analysis",
                    "Re-run benchmark without --quick flag"
            );
        }

        // Calculate per-service statistics
        Map<String, List<Long>> durationsByService = new HashMap<>();
        List<Long> totalDurations = new ArrayList<>();
        Map<String, Integer> bottleneckCounts = new HashMap<>();

        for (Trace trace : traces) {
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
        final long finalAvgTotalDuration = avgTotalDuration;
        List<ComponentHealth> componentHealth = durationsByService.entrySet().stream()
            .map(entry -> {
                String service = entry.getKey();
                long avgDuration = (long) entry.getValue().stream()
                    .mapToLong(Long::longValue).average().orElse(0);
                double percent = finalAvgTotalDuration > 0 ? (avgDuration * 100.0) / finalAvgTotalDuration : 0;
                HealthStatus status = HealthStatus.fromPercent(percent);
                String diagnosis = generateComponentDiagnosis(service, percent, status);
                return new ComponentHealth(service, avgDuration, percent, status, diagnosis);
            })
            .sorted((a, b) -> Double.compare(b.percentOfTotal(), a.percentOfTotal()))
            .collect(Collectors.toList());

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

        // Generate one-liner and verdict
        String oneLiner = generateOneLiner(primaryBottleneck, bottleneckPercent, severity);
        String verdict = generateVerdict(primaryBottleneck, bottleneckPercent);

        // NEW: Calculate operation-level statistics
        List<OperationStats> operationStats = calculateOperationStats(traces);
        List<OperationTiming> slowestOperations = findSlowestOperations(traces, 5);

        return new DiagnosticReport(
                traces.size(),
                avgTotalDuration,
                componentHealth,
                operationStats,       // NEW
                slowestOperations,    // NEW
                primaryBottleneck,
                bottleneckPercent,
                confidence,
                diagnosis,
                recommendations,
                severity,
                oneLiner,
                verdict
        );
    }

    private String generateOneLiner(String bottleneck, double percent, String severity) {
        String componentName = switch (bottleneck.toLowerCase()) {
            case "flink-taskmanager" -> "Flink";
            case "counter-application" -> "Gateway";
            case "drools", "reactive-drools" -> "Drools";
            case "kafka" -> "Kafka";
            default -> bottleneck;
        };

        return switch (severity) {
            case "CRITICAL" -> String.format("CRITICAL: %s consuming %.0f%% of latency - immediate action required", componentName, percent);
            case "WARNING" -> String.format("WARNING: %s at %.0f%% - optimization recommended", componentName, percent);
            default -> String.format("HEALTHY: System balanced, %s leads at %.0f%%", componentName, percent);
        };
    }

    private String generateVerdict(String bottleneck, double percent) {
        if (percent < 30) {
            return "System is balanced - scale horizontally for more throughput";
        }

        return switch (bottleneck.toLowerCase()) {
            case "flink-taskmanager" -> "Run: docker compose up -d --scale flink-taskmanager=2";
            case "counter-application", "gateway" -> "Increase gateway connection pool size in application.yml";
            case "kafka" -> "Set linger.ms=5 and batch.size=32768 in producer config";
            case "drools", "reactive-drools" -> "Enable KieBase caching in Drools service";
            default -> "Profile " + bottleneck + " for optimization opportunities";
        };
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

    private String getServiceName(Trace trace, Span span) {
        Service process = trace.processes().get(span.processId());
        return process != null ? process.serviceName() : "unknown";
    }

    private long getSpanEndTime(Span span) {
        return span.startTime() + span.duration();
    }

    private long getEarliestStartTime(Trace trace) {
        return trace.spans().stream()
                .mapToLong(Span::startTime)
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

    // ========================================================================
    // Operation-Level Analysis
    // ========================================================================

    /** Extract all operation timings from a trace. */
    public List<OperationTiming> extractOperationTimings(Trace trace) {
        if (trace.isEmpty()) {
            return List.of();
        }

        return trace.spans().stream()
            .map(span -> {
                String service = getServiceName(trace, span);

                // Extract relevant tags from Map<String, Object>
                Map<String, String> tags = span.tags().stream()
                    .filter(tag -> {
                        String key = String.valueOf(tag.get("key"));
                        return key.startsWith("http.") || key.startsWith("db.") ||
                               key.startsWith("messaging.") || key.equals("error");
                    })
                    .collect(Collectors.toMap(
                        tag -> String.valueOf(tag.get("key")),
                        tag -> String.valueOf(tag.get("value")),
                        (a, b) -> b
                    ));

                // Find parent span ID from references
                String parentSpanId = span.references().stream()
                    .filter(ref -> "CHILD_OF".equals(String.valueOf(ref.get("refType"))))
                    .map(ref -> String.valueOf(ref.get("spanID")))
                    .findFirst()
                    .orElse("");

                return new OperationTiming(
                    service,
                    span.operationName(),
                    span.duration(),
                    span.spanId(),
                    parentSpanId,
                    tags
                );
            })
            .collect(Collectors.toList());
    }

    /** Calculate aggregate statistics for each operation across multiple traces. */
    public List<OperationStats> calculateOperationStats(List<Trace> traces) {
        if (traces == null || traces.isEmpty()) {
            return List.of();
        }

        // Collect all timings by operation
        Map<String, List<Long>> durationsByOperation = new HashMap<>();
        long totalDuration = 0;

        for (Trace trace : traces) {
            for (OperationTiming timing : extractOperationTimings(trace)) {
                String key = timing.service() + ":" + timing.operation();
                durationsByOperation.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(timing.durationUs());
                totalDuration += timing.durationUs();
            }
        }

        final long finalTotalDuration = totalDuration;

        // Calculate stats for each operation
        return durationsByOperation.entrySet().stream()
                .map(entry -> {
                    String[] parts = entry.getKey().split(":", 2);
                    String service = parts[0];
                    String operation = parts.length > 1 ? parts[1] : "unknown";
                    List<Long> durations = entry.getValue();

                    // Sort for percentiles
                    durations.sort(Long::compare);

                    long sum = durations.stream().mapToLong(Long::longValue).sum();
                    long min = durations.get(0);
                    long max = durations.get(durations.size() - 1);
                    long avg = sum / durations.size();
                    long p50 = percentileLong(durations, 50);
                    long p99 = percentileLong(durations, 99);
                    double percent = finalTotalDuration > 0 ? (sum * 100.0) / finalTotalDuration : 0;

                    return new OperationStats(service, operation, durations.size(),
                            min, max, avg, p50, p99, percent);
                })
                .sorted((a, b) -> Long.compare(b.avgUs(), a.avgUs())) // Sort by avg duration descending
                .collect(Collectors.toList());
    }

    /** Find the slowest individual operations across all traces. */
    public List<OperationTiming> findSlowestOperations(List<Trace> traces, int limit) {
        if (traces == null || traces.isEmpty()) {
            return List.of();
        }

        return traces.stream()
                .flatMap(trace -> extractOperationTimings(trace).stream())
                .sorted((a, b) -> Long.compare(b.durationUs(), a.durationUs()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    private long percentileLong(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
