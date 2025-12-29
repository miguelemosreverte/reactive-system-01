package com.reactive.platform.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.reactive.platform.base.Result;

import static com.reactive.platform.observe.Log.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Generates benchmark reports in JSON format.
 *
 * Output structure:
 *   {outputDir}/{component}/benchmark-result.json
 */
public class BenchmarkReportGenerator {

    private static final ObjectMapper mapper = createMapper();

    private final Path outputDir;
    private final ObservabilityFetcher observability;

    // ========================================================================
    // Static Factories
    // ========================================================================

    public static BenchmarkReportGenerator create(Path outputDir) {
        return new BenchmarkReportGenerator(outputDir, ObservabilityFetcher.create());
    }

    public static BenchmarkReportGenerator withObservability(Path outputDir, ObservabilityFetcher fetcher) {
        return new BenchmarkReportGenerator(outputDir, fetcher);
    }

    private BenchmarkReportGenerator(Path outputDir, ObservabilityFetcher observability) {
        this.outputDir = outputDir;
        this.observability = observability;
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
        return mapper;
    }

    // ========================================================================
    // Report Generation
    // ========================================================================

    /**
     * Generate report for a single benchmark result.
     * Enriches sample events with trace/log data before saving.
     */
    public Result<Path> generate(BenchmarkResult result) {
        return Result.of(() -> {
            // Enrich sample events with observability data
            BenchmarkResult enrichedResult = enrichWithObservability(result);

            // Create output directory
            Path componentDir = outputDir.resolve(result.component().id());
            Files.createDirectories(componentDir);

            // Write JSON
            Path jsonPath = componentDir.resolve("benchmark-result.json");
            mapper.writeValue(jsonPath.toFile(), enrichedResult);

            info("Generated report: {}", jsonPath);
            return jsonPath;
        });
    }

    /**
     * Generate reports for multiple benchmark results.
     * Returns the path to the generated index file.
     */
    public Result<Path> generateAll(List<BenchmarkResult> results) {
        return Result.of(() -> {
            results.forEach(result -> generate(result).getOrThrow());
            return generateIndex(results).getOrThrow();
        });
    }

    /**
     * Generate index.json listing all component results.
     */
    public Result<Path> generateIndex(List<BenchmarkResult> results) {
        return Result.of(() -> {
            Files.createDirectories(outputDir);
            Path indexPath = outputDir.resolve("index.json");

            var summary = results.stream()
                    .map(r -> new ComponentSummary(
                            r.component().id(),
                            r.component().displayName(),
                            r.status(),
                            r.totalOperations(),
                            r.successfulOperations(),
                            r.failedOperations(),
                            r.avgThroughput(),
                            r.latency().p50(),
                            r.latency().p99(),
                            r.durationMs()
                    ))
                    .toList();

            var index = new IndexData(
                    Instant.now(),
                    results.size(),
                    summary
            );

            mapper.writeValue(indexPath.toFile(), index);
            info("Generated index: {}", indexPath);
            return indexPath;
        });
    }

    // ========================================================================
    // Enrichment
    // ========================================================================

    private BenchmarkResult enrichWithObservability(BenchmarkResult result) {
        if (result.sampleEvents().isEmpty()) {
            return result;
        }

        try {
            var enrichedEvents = observability.enrichSampleEvents(
                    result.sampleEvents(),
                    result.startTime(),
                    result.endTime()
            );

            // Create new result with enriched events
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
                    enrichedEvents,
                    result.status(),
                    result.errorMessage()
            );
        } catch (Exception e) {
            warn("Failed to enrich sample events: {}", e.getMessage());
            return result;
        }
    }

    // ========================================================================
    // DTOs
    // ========================================================================

    public record ComponentSummary(
            String id,
            String name,
            String status,
            long totalOperations,
            long successfulOperations,
            long failedOperations,
            long avgThroughput,
            long latencyP50,
            long latencyP99,
            long durationMs
    ) {}

    public record IndexData(
            Instant generatedAt,
            int componentCount,
            List<ComponentSummary> components
    ) {}
}
