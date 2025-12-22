package com.reactive.platform.benchmark;

import com.reactive.platform.benchmark.BenchmarkTypes.*;

import java.lang.management.ManagementFactory;

import static com.reactive.platform.observe.Log.*;
import java.lang.management.OperatingSystemMXBean;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base benchmark with common tracking logic.
 *
 * Subclasses implement runBenchmarkLoop() for component-specific logic.
 */
public abstract class BaseBenchmark implements Benchmark {

    private final ComponentId componentId;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Tracking
    private final List<Long> latencies = new CopyOnWriteArrayList<>();
    private final List<Long> throughputSamples = new CopyOnWriteArrayList<>();
    private final List<Double> cpuSamples = new CopyOnWriteArrayList<>();
    private final List<Double> memorySamples = new CopyOnWriteArrayList<>();
    private final AtomicLong successCount = new AtomicLong(0);
    private final AtomicLong failCount = new AtomicLong(0);
    private final List<SampleEvent> sampleEvents = Collections.synchronizedList(new ArrayList<>());

    private volatile Instant startTime;
    private volatile String errorMessage = "";

    protected BaseBenchmark(ComponentId componentId) {
        this.componentId = componentId;
    }

    // ========================================================================
    // Benchmark interface
    // ========================================================================

    @Override
    public ComponentId id() { return componentId; }

    @Override
    public String name() { return componentId.displayName(); }

    @Override
    public String description() { return componentId.description(); }

    @Override
    public boolean isRunning() { return running.get(); }

    @Override
    public void stop() {
        running.set(false);
    }

    @Override
    public BenchmarkResult run(Config config) {
        if (!running.compareAndSet(false, true)) {
            return BenchmarkResult.error(componentId, Instant.now(), "Benchmark already running");
        }

        try {
            reset();
            startTime = Instant.now();
            info("Starting {} benchmark (duration={}ms)", componentId.id(), config.durationMs());

            // Start resource sampling thread
            Thread resourceSampler = startResourceSampler(config);

            // Run warmup
            if (config.warmupMs() > 0) {
                info("Warmup phase: {}ms", config.warmupMs());
                warmup(config);
            }

            // Run the main benchmark loop
            runBenchmarkLoop(config);

            // Cooldown
            if (config.cooldownMs() > 0) {
                info("Cooldown phase: {}ms", config.cooldownMs());
                Thread.sleep(config.cooldownMs());
            }

            // Stop resource sampler
            resourceSampler.interrupt();
            resourceSampler.join(1000);

            // Build result
            return buildResult("completed", config);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return buildResult("stopped", config);
        } catch (Exception e) {
            error("Benchmark error", e);
            this.errorMessage = e.getMessage();
            return buildResult("error", config);
        } finally {
            running.set(false);
        }
    }

    // ========================================================================
    // Abstract method - subclasses implement
    // ========================================================================

    /** Run the main benchmark loop. Should check isRunning() periodically. */
    protected abstract void runBenchmarkLoop(Config config) throws Exception;

    /** Optional warmup phase. Default runs a quick version of the benchmark. */
    protected void warmup(Config config) throws Exception {
        long deadline = System.currentTimeMillis() + config.warmupMs();
        while (System.currentTimeMillis() < deadline && isRunning()) {
            Thread.sleep(10);
        }
    }

    // ========================================================================
    // Recording methods - call these from subclasses
    // ========================================================================

    protected void recordLatency(long latencyMs) {
        latencies.add(latencyMs);
    }

    protected void recordSuccess() {
        successCount.incrementAndGet();
    }

    protected void recordFailure() {
        failCount.incrementAndGet();
    }

    protected void recordThroughputSample(long throughput) {
        throughputSamples.add(throughput);
    }

    protected void addSampleEvent(SampleEvent event) {
        synchronized (sampleEvents) {
            if (event.status() == EventStatus.SUCCESS) {
                // Keep only the last 2 success samples
                var successSamples = sampleEvents.stream()
                        .filter(e -> e.status() == EventStatus.SUCCESS)
                        .toList();
                if (successSamples.size() >= 2) {
                    sampleEvents.remove(successSamples.get(0));
                }
            } else {
                // Keep only the last 10 error samples
                var errorSamples = sampleEvents.stream()
                        .filter(e -> e.status() != EventStatus.SUCCESS)
                        .toList();
                if (errorSamples.size() >= 10) {
                    sampleEvents.remove(errorSamples.get(0));
                }
            }
            sampleEvents.add(event);
        }
    }

    protected long getOperationCount() {
        return successCount.get() + failCount.get();
    }

    // ========================================================================
    // Resource sampling
    // ========================================================================

    private Thread startResourceSampler(Config config) {
        Thread sampler = new Thread(() -> {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            Runtime runtime = Runtime.getRuntime();

            long prevOps = 0;
            while (!Thread.currentThread().isInterrupted() && isRunning()) {
                try {
                    // CPU (system load average as percentage estimate)
                    double cpuLoad = osBean.getSystemLoadAverage();
                    if (cpuLoad >= 0) {
                        cpuSamples.add(cpuLoad * 100 / Runtime.getRuntime().availableProcessors());
                    }

                    // Memory
                    long used = runtime.totalMemory() - runtime.freeMemory();
                    long max = runtime.maxMemory();
                    memorySamples.add((double) used / max * 100);

                    // Throughput
                    long currentOps = getOperationCount();
                    throughputSamples.add(currentOps - prevOps);
                    prevOps = currentOps;

                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "benchmark-resource-sampler");
        sampler.setDaemon(true);
        sampler.start();
        return sampler;
    }

    // ========================================================================
    // Result building
    // ========================================================================

    private void reset() {
        latencies.clear();
        throughputSamples.clear();
        cpuSamples.clear();
        memorySamples.clear();
        successCount.set(0);
        failCount.set(0);
        sampleEvents.clear();
        errorMessage = "";
    }

    private BenchmarkResult buildResult(String status, Config config) {
        Instant endTime = Instant.now();
        long totalOps = successCount.get() + failCount.get();

        // Calculate latency stats
        long[] sortedLatencies = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        LatencyStats latencyStats = LatencyStats.from(sortedLatencies);

        // Calculate throughput stability (coefficient of variation)
        double throughputStability = calculateThroughputStability();

        // Enrich sample events with traces and logs (skip in quick mode)
        List<SampleEvent> enrichedEvents;
        if (config.skipEnrichment()) {
            info("Skipping trace/log enrichment (quick mode)");
            enrichedEvents = List.copyOf(sampleEvents);
        } else {
            enrichedEvents = enrichSampleEvents(List.copyOf(sampleEvents), startTime, endTime);
        }

        if ("error".equals(status)) {
            return BenchmarkResult.error(componentId, startTime, errorMessage);
        } else if ("stopped".equals(status)) {
            return BenchmarkResult.stopped(
                    componentId, startTime, totalOps, successCount.get(), failCount.get(),
                    latencyStats, List.copyOf(throughputSamples),
                    List.copyOf(cpuSamples), List.copyOf(memorySamples),
                    enrichedEvents, throughputStability
            );
        } else {
            return BenchmarkResult.completed(
                    componentId, startTime, endTime, totalOps, successCount.get(), failCount.get(),
                    latencyStats, List.copyOf(throughputSamples),
                    List.copyOf(cpuSamples), List.copyOf(memorySamples),
                    enrichedEvents, throughputStability
            );
        }
    }

    /** Calculate throughput stability as coefficient of variation (lower = more stable) */
    private double calculateThroughputStability() {
        if (throughputSamples.size() < 2) return 0.0;

        double mean = throughputSamples.stream().mapToLong(Long::longValue).average().orElse(0);
        if (mean == 0) return 0.0;

        double variance = throughputSamples.stream()
                .mapToDouble(t -> Math.pow(t - mean, 2))
                .average().orElse(0);
        double stdDev = Math.sqrt(variance);

        return stdDev / mean; // Coefficient of variation
    }

    /** Enrich sample events with trace and log data from Jaeger/Loki. */
    private List<SampleEvent> enrichSampleEvents(List<SampleEvent> events, Instant start, Instant end) {
        try {
            // Get Jaeger/Loki URLs from environment or use defaults for Docker
            String jaegerUrl = System.getenv().getOrDefault("JAEGER_QUERY_URL", "http://host.docker.internal:16686");
            String lokiUrl = System.getenv().getOrDefault("LOKI_URL", "http://host.docker.internal:3100");

            info("Enriching {} sample events with traces/logs (jaeger={}, loki={})",
                    events.size(), jaegerUrl, lokiUrl);

            ObservabilityFetcher fetcher = ObservabilityFetcher.withUrls(jaegerUrl, lokiUrl);
            List<SampleEvent> enriched = fetcher.enrichSampleEvents(events, start, end);

            long tracesFound = enriched.stream()
                    .filter(e -> e.traceData().hasTrace())
                    .count();
            long logsFound = enriched.stream()
                    .filter(e -> e.traceData().hasLogs())
                    .count();

            info("Enrichment complete: {} traces, {} logs found", tracesFound, logsFound);
            return enriched;
        } catch (Exception e) {
            warn("Failed to enrich sample events: {}", e.getMessage());
            return events;
        }
    }

    // ========================================================================
    // Utility
    // ========================================================================

    protected boolean shouldContinue(Config config, Instant loopStart) {
        if (!isRunning()) return false;
        if (config.targetEventCount() > 0 && getOperationCount() >= config.targetEventCount()) return false;
        return System.currentTimeMillis() - loopStart.toEpochMilli() < config.durationMs();
    }
}
