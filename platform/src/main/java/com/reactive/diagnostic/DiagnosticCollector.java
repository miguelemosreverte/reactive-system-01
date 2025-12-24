package com.reactive.diagnostic;

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects diagnostic metrics from the JVM and application.
 *
 * Usage:
 * 1. Create a collector for your component
 * 2. Register pipeline stages
 * 3. Call recordEvent() for throughput tracking
 * 4. Call collect() to get a DiagnosticSnapshot
 */
public class DiagnosticCollector {

    private final String component;
    private final String instanceId;
    private final String version;
    private final long startTimeMs;

    // Throughput counters
    private final LongAdder eventCount = new LongAdder();
    private final LongAdder byteCount = new LongAdder();
    private final LongAdder batchCount = new LongAdder();
    private final LongAdder batchSizeSum = new LongAdder();
    private final LongAdder rejectedCount = new LongAdder();
    private final LongAdder droppedCount = new LongAdder();

    // Latency tracking (reservoir sampling for percentiles)
    private final LatencyHistogram latencyHistogram = new LatencyHistogram();

    // Error tracking
    private final LongAdder errorCount = new LongAdder();
    private final Map<String, LongAdder> errorsByType = new ConcurrentHashMap<>();
    private final List<ErrorInfo> recentErrors = Collections.synchronizedList(new LinkedList<>());
    private static final int MAX_RECENT_ERRORS = 10;

    // Pipeline stage tracking
    private final Map<String, StageMetrics> stages = new ConcurrentHashMap<>();

    // Dependency tracking
    private final Map<String, DependencyMetrics> dependencies = new ConcurrentHashMap<>();

    // Historical data
    private final List<HistoricalBucket> history = Collections.synchronizedList(new LinkedList<>());
    private static final int MAX_HISTORY_BUCKETS = 60;
    private long lastBucketTime = System.currentTimeMillis();
    private double bucketEventCount = 0;
    private double bucketLatencySum = 0;
    private double bucketMaxLatency = 0;
    private long bucketErrorCount = 0;

    // Configuration
    private double theoreticalMaxThroughput = 10000.0;
    private int maxParallelism = Runtime.getRuntime().availableProcessors();
    private long queueCapacity = 10000;

    // Last collection values for trend calculation
    private long lastCollectTime = System.currentTimeMillis();
    private long lastEventCount = 0;
    private long lastHeapUsed = 0;
    private long lastOldGenUsed = 0;
    private double lastQueueDepth = 0;
    private long lastErrorCount = 0;

    public DiagnosticCollector(String component, String instanceId, String version) {
        this.component = component;
        this.instanceId = instanceId;
        this.version = version;
        this.startTimeMs = System.currentTimeMillis();
    }

    // ==================== Event Recording ====================

    public void recordEvent(long bytes, double latencyMs) {
        eventCount.increment();
        byteCount.add(bytes);
        latencyHistogram.record(latencyMs);

        bucketEventCount++;
        bucketLatencySum += latencyMs;
        bucketMaxLatency = Math.max(bucketMaxLatency, latencyMs);
    }

    public void recordBatch(int size, long bytes, double latencyMs) {
        eventCount.add(size);
        byteCount.add(bytes);
        batchCount.increment();
        batchSizeSum.add(size);
        latencyHistogram.record(latencyMs);

        bucketEventCount += size;
        bucketLatencySum += latencyMs;
        bucketMaxLatency = Math.max(bucketMaxLatency, latencyMs);
    }

    public void recordRejected() {
        rejectedCount.increment();
    }

    public void recordDropped() {
        droppedCount.increment();
    }

    public void recordError(String type, String message) {
        errorCount.increment();
        bucketErrorCount++;
        errorsByType.computeIfAbsent(type, k -> new LongAdder()).increment();

        synchronized (recentErrors) {
            if (recentErrors.size() >= MAX_RECENT_ERRORS) {
                recentErrors.remove(0);
            }
            recentErrors.add(ErrorInfo.of(type, message, System.currentTimeMillis(),
                Integer.toHexString(message.hashCode())));
        }
    }

    // ==================== Stage Recording ====================

    public void registerStage(String name) {
        stages.putIfAbsent(name, new StageMetrics(name));
    }

    public void recordStageEvent(String name, double latencyMs) {
        StageMetrics stage = stages.get(name);
        if (stage != null) {
            stage.recordEvent(latencyMs);
        }
    }

    public void recordStageError(String name) {
        StageMetrics stage = stages.get(name);
        if (stage != null) {
            stage.recordError();
        }
    }

    public void setStageQueueDepth(String name, long depth) {
        StageMetrics stage = stages.get(name);
        if (stage != null) {
            stage.setQueueDepth(depth);
        }
    }

    // ==================== Dependency Recording ====================

    public void registerDependency(String name, String type) {
        dependencies.putIfAbsent(name, new DependencyMetrics(name, type));
    }

    public void recordDependencyCall(String name, double latencyMs, boolean success) {
        DependencyMetrics dep = dependencies.get(name);
        if (dep != null) {
            dep.recordCall(latencyMs, success);
        }
    }

    public void setDependencyCircuitState(String name, String state) {
        DependencyMetrics dep = dependencies.get(name);
        if (dep != null) {
            dep.setCircuitState(state);
        }
    }

    // ==================== Collection ====================

    public DiagnosticSnapshot collect() {
        long now = System.currentTimeMillis();
        long elapsedMs = now - lastCollectTime;
        double elapsedSec = elapsedMs / 1000.0;

        // Roll history bucket if needed
        if (now - lastBucketTime >= 60000) {
            rollHistoryBucket(now);
        }

        // Calculate throughput
        long currentEventCount = eventCount.sum();
        double eventsPerSecond = (currentEventCount - lastEventCount) / elapsedSec;
        long bytesPerSecond = (long) (byteCount.sum() / ((now - startTimeMs) / 1000.0));

        double batchSizeAvg = batchCount.sum() > 0 ?
            (double) batchSizeSum.sum() / batchCount.sum() : 0;
        double batchesPerSecond = batchCount.sum() / ((now - startTimeMs) / 1000.0);

        // Get JVM metrics
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();

        long heapUsed = heapUsage.getUsed();
        long heapMax = heapUsage.getMax();
        double heapPercent = heapMax > 0 ? (double) heapUsed / heapMax * 100 : 0;

        // Get old gen stats
        long oldGenUsed = 0;
        long oldGenMax = 0;
        List<MemoryPool> memoryPools = new ArrayList<>();

        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage usage = pool.getUsage();
            String poolName = pool.getName();

            memoryPools.add(MemoryPool.of(
                poolName,
                usage.getUsed(),
                usage.getMax() > 0 ? usage.getMax() : usage.getCommitted(),
                usage.getMax() > 0 ? (double) usage.getUsed() / usage.getMax() * 100 : 0
            ));

            if (poolName.contains("Old") || poolName.contains("Tenured")) {
                oldGenUsed = usage.getUsed();
                oldGenMax = usage.getMax() > 0 ? usage.getMax() : usage.getCommitted();
            }
        }

        // GC metrics
        long youngGcCount = 0, youngGcTime = 0, oldGcCount = 0, oldGcTime = 0;
        String gcAlgorithm = "Unknown";

        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getName().contains("Young") || gc.getName().contains("G1 Young") ||
                gc.getName().contains("ParNew") || gc.getName().contains("Copy")) {
                youngGcCount = gc.getCollectionCount();
                youngGcTime = gc.getCollectionTime();
            } else {
                oldGcCount = gc.getCollectionCount();
                oldGcTime = gc.getCollectionTime();
            }
            gcAlgorithm = gc.getName();
        }

        double gcOverhead = (youngGcTime + oldGcTime) / (double) (now - startTimeMs) * 100;

        // Thread metrics
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        int threadCount = threadBean.getThreadCount();
        int blockedCount = 0;
        int waitingCount = 0;

        ThreadInfo[] threadInfos = threadBean.getThreadInfo(threadBean.getAllThreadIds());
        for (ThreadInfo info : threadInfos) {
            if (info != null) {
                if (info.getThreadState() == Thread.State.BLOCKED) blockedCount++;
                if (info.getThreadState() == Thread.State.WAITING ||
                    info.getThreadState() == Thread.State.TIMED_WAITING) waitingCount++;
            }
        }

        // Calculate trends
        double throughputTrend = elapsedSec > 0 ?
            (eventsPerSecond - (lastEventCount / elapsedSec)) / Math.max(1, eventsPerSecond) : 0;
        long heapGrowth = (long) ((heapUsed - lastHeapUsed) / elapsedSec);
        long oldGenGrowth = (long) ((oldGenUsed - lastOldGenUsed) / elapsedSec);

        // Estimate exhaustion time
        long heapExhaustion = heapGrowth > 0 ? (heapMax - heapUsed) / heapGrowth : -1;
        long oldGenExhaustion = oldGenGrowth > 0 && oldGenMax > 0 ? (oldGenMax - oldGenUsed) / oldGenGrowth : -1;

        // Crash indicators
        boolean oomRisk = heapPercent > 85 || (oldGenMax > 0 && (double) oldGenUsed / oldGenMax > 0.9);
        boolean gcThrashing = gcOverhead > 10;
        boolean memoryLeak = heapGrowth > 10 * 1024 * 1024; // 10MB/s growth
        boolean threadExhaustion = blockedCount > maxParallelism / 2;

        // Build pipeline stages
        List<PipelineStage> pipelineStages = new ArrayList<>();
        for (StageMetrics stage : stages.values()) {
            pipelineStages.add(stage.toStage());
        }

        // Build dependencies
        List<DependencyAnalysis> deps = new ArrayList<>();
        for (DependencyMetrics dep : dependencies.values()) {
            deps.add(dep.toDependencyAnalysis());
        }

        // Build error analysis
        Map<String, Long> errorsByTypeMap = new HashMap<>();
        errorsByType.forEach((type, counter) -> errorsByTypeMap.put(type, counter.sum()));

        long currentErrorCount = errorCount.sum();

        // Update last values for next collection
        lastCollectTime = now;
        lastEventCount = currentEventCount;
        lastHeapUsed = heapUsed;
        lastOldGenUsed = oldGenUsed;
        lastErrorCount = currentErrorCount;

        return DiagnosticSnapshot.builder()
            .component(component)
            .instanceId(instanceId)
            .timestampMs(now)
            .uptimeMs(now - startTimeMs)
            .version(version)
            .throughput(ThroughputMetrics.builder()
                .eventsPerSecond(eventsPerSecond)
                .bytesPerSecond(bytesPerSecond)
                .eventsPerSecondCapacity(theoreticalMaxThroughput)
                .capacityUtilizationPercent(eventsPerSecond / theoreticalMaxThroughput * 100)
                .batchSizeAvg(batchSizeAvg)
                .batchSizeP99(0) // Would need more tracking
                .batchesPerSecond(batchesPerSecond)
                .batchEfficiencyPercent(batchSizeAvg > 0 ? batchSizeAvg / 100 * 100 : 0)
                .parallelismActive(threadCount)
                .parallelismMax(maxParallelism)
                .queueDepth(0) // Set by application
                .queueCapacity(queueCapacity)
                .rejectedCount(rejectedCount.sum())
                .droppedCount(droppedCount.sum())
                .build())
            .latency(LatencyMetrics.builder()
                .totalMsP50(latencyHistogram.getPercentile(50))
                .totalMsP95(latencyHistogram.getPercentile(95))
                .totalMsP99(latencyHistogram.getPercentile(99))
                .totalMsMax(latencyHistogram.getMax())
                .breakdownPercent(Map.of()) // Would need more tracking
                .build())
            .saturation(ResourceSaturation.builder()
                .cpuPercent(0) // Requires OS-level access
                .heapUsedPercent(heapPercent)
                .heapUsedBytes(heapUsed)
                .heapMaxBytes(heapMax)
                .oldGenPercent(oldGenMax > 0 ? (double) oldGenUsed / oldGenMax * 100 : 0)
                .oldGenBytes(oldGenUsed)
                .oldGenMaxBytes(oldGenMax)
                .threadPoolActive(threadCount)
                .threadPoolMax(maxParallelism)
                .threadPoolQueueDepth(0)
                .build())
            .trends(RateOfChange.builder()
                .throughputTrend(throughputTrend)
                .heapGrowthBytesPerSec(heapGrowth)
                .oldGenGrowthBytesPerSec(oldGenGrowth)
                .estimatedHeapExhaustionSec(heapExhaustion)
                .estimatedOldGenExhaustionSec(oldGenExhaustion)
                .build())
            .contention(ContentionAnalysis.builder()
                .blockedThreads(blockedCount)
                .waitingThreads(waitingCount)
                .build())
            .memory(MemoryPressure.builder()
                .heapUsedBytes(heapUsed)
                .heapCommittedBytes(heapUsage.getCommitted())
                .heapMaxBytes(heapMax)
                .nonHeapUsedBytes(nonHeapUsage.getUsed())
                .memoryPools(memoryPools)
                .build())
            .gc(GCAnalysis.builder()
                .youngGcCount(youngGcCount)
                .youngGcTimeMs(youngGcTime)
                .oldGcCount(oldGcCount)
                .oldGcTimeMs(oldGcTime)
                .gcOverheadPercent(gcOverhead)
                .avgYoungGcPauseMs(youngGcCount > 0 ? (double) youngGcTime / youngGcCount : 0)
                .avgOldGcPauseMs(oldGcCount > 0 ? (double) oldGcTime / oldGcCount : 0)
                .gcAlgorithm(gcAlgorithm)
                .build())
            .stages(pipelineStages)
            .dependencies(deps)
            .errors(ErrorAnalysis.builder()
                .totalErrorCount(currentErrorCount)
                .errorRatePercent(currentEventCount > 0 ? (double) currentErrorCount / currentEventCount * 100 : 0)
                .errorsByType(errorsByTypeMap)
                .recentErrors(new ArrayList<>(recentErrors))
                .crashIndicators(CrashIndicators.builder()
                    .oomRisk(oomRisk)
                    .oomRiskPercent(heapPercent)
                    .gcThrashing(gcThrashing)
                    .memoryLeakSuspected(memoryLeak)
                    .threadExhaustionRisk(threadExhaustion)
                    .build())
                .build())
            .capacity(CapacityAnalysis.builder()
                .currentThroughput(eventsPerSecond)
                .theoreticalMaxThroughput(theoreticalMaxThroughput)
                .headroomPercent((theoreticalMaxThroughput - eventsPerSecond) / theoreticalMaxThroughput * 100)
                .build())
            .history(new ArrayList<>(history))
            .build();
    }

    private void rollHistoryBucket(long now) {
        synchronized (history) {
            if (bucketEventCount > 0) {
                history.add(HistoricalBucket.builder()
                    .bucketStartMs(lastBucketTime)
                    .bucketDurationMs(60000)
                    .avgThroughput(bucketEventCount / 60.0)
                    .avgLatencyMs(bucketLatencySum / bucketEventCount)
                    .maxLatencyMs(bucketMaxLatency)
                    .errorCount(bucketErrorCount)
                    .heapUsedAvgPercent(0) // Would need averaging
                    .build());

                while (history.size() > MAX_HISTORY_BUCKETS) {
                    history.remove(0);
                }
            }
        }

        lastBucketTime = now;
        bucketEventCount = 0;
        bucketLatencySum = 0;
        bucketMaxLatency = 0;
        bucketErrorCount = 0;
    }

    // ==================== Configuration ====================

    public void setTheoreticalMaxThroughput(double value) {
        this.theoreticalMaxThroughput = value;
    }

    public void setMaxParallelism(int value) {
        this.maxParallelism = value;
    }

    public void setQueueCapacity(long value) {
        this.queueCapacity = value;
    }

    // ==================== Internal Classes ====================

    private static class StageMetrics {
        private final String name;
        private final LongAdder eventCount = new LongAdder();
        private final LongAdder errorCount = new LongAdder();
        private final LatencyHistogram latency = new LatencyHistogram();
        private volatile long queueDepth = 0;
        private final long startTime = System.currentTimeMillis();

        StageMetrics(String name) {
            this.name = name;
        }

        void recordEvent(double latencyMs) {
            eventCount.increment();
            latency.record(latencyMs);
        }

        void recordError() {
            errorCount.increment();
        }

        void setQueueDepth(long depth) {
            this.queueDepth = depth;
        }

        PipelineStage toStage() {
            long elapsed = System.currentTimeMillis() - startTime;
            double eventsPerSec = elapsed > 0 ? eventCount.sum() / (elapsed / 1000.0) : 0;

            return PipelineStage.builder()
                .name(name)
                .eventsProcessed(eventCount.sum())
                .eventsPerSecond(eventsPerSec)
                .latencyMsP50(latency.getPercentile(50))
                .latencyMsP99(latency.getPercentile(99))
                .queueDepth(queueDepth)
                .errorCount(errorCount.sum())
                .build();
        }
    }

    private static class DependencyMetrics {
        private final String name;
        private final String type;
        private final LongAdder callCount = new LongAdder();
        private final LongAdder errorCount = new LongAdder();
        private final LatencyHistogram latency = new LatencyHistogram();
        private volatile String circuitState = "CLOSED";
        private volatile String lastError;
        private volatile long lastErrorTime;
        private final long startTime = System.currentTimeMillis();

        DependencyMetrics(String name, String type) {
            this.name = name;
            this.type = type;
        }

        void recordCall(double latencyMs, boolean success) {
            callCount.increment();
            latency.record(latencyMs);
            if (!success) {
                errorCount.increment();
            }
        }

        void setCircuitState(String state) {
            this.circuitState = state;
        }

        void setLastError(String error) {
            this.lastError = error;
            this.lastErrorTime = System.currentTimeMillis();
        }

        DependencyAnalysis toDependencyAnalysis() {
            long elapsed = System.currentTimeMillis() - startTime;
            double callsPerSec = elapsed > 0 ? callCount.sum() / (elapsed / 1000.0) : 0;
            double errorRate = callCount.sum() > 0 ? (double) errorCount.sum() / callCount.sum() * 100 : 0;

            return DependencyAnalysis.builder()
                .name(name)
                .type(type)
                .latencyMsP50(latency.getPercentile(50))
                .latencyMsP99(latency.getPercentile(99))
                .callsPerSecond(callsPerSec)
                .errorRatePercent(errorRate)
                .circuitBreakerState(circuitState)
                .lastError(lastError)
                .lastErrorTimeMs(lastErrorTime)
                .build();
        }
    }

    private static class LatencyHistogram {
        private static final int SAMPLE_SIZE = 1000;
        private final double[] samples = new double[SAMPLE_SIZE];
        private final AtomicLong count = new AtomicLong();
        private volatile double max = 0;

        void record(double value) {
            long c = count.getAndIncrement();
            int idx = (int) (c % SAMPLE_SIZE);
            samples[idx] = value;

            double currentMax = max;
            while (value > currentMax) {
                if (currentMax == max) {
                    max = value;
                    break;
                }
                currentMax = max;
            }
        }

        double getPercentile(int percentile) {
            long c = count.get();
            if (c == 0) return 0;

            int sampleCount = (int) Math.min(c, SAMPLE_SIZE);
            double[] sorted = new double[sampleCount];
            System.arraycopy(samples, 0, sorted, 0, sampleCount);
            Arrays.sort(sorted);

            int index = (int) (sampleCount * percentile / 100.0);
            return sorted[Math.max(0, Math.min(index, sampleCount - 1))];
        }

        double getMax() {
            return max;
        }
    }
}
