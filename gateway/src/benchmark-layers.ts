import { KafkaClient, CounterEvent, CounterResult } from './kafka';
import { transactionTracker, TimingBreakdown, TransactionState, ComponentTiming } from './transaction-tracker';
import { logger } from './logger';
import * as os from 'os';

// HTTP client for full end-to-end benchmark
const GATEWAY_URL = process.env.GATEWAY_URL || 'http://localhost:8080';

/**
 * Layered Benchmarking System
 *
 * Tests each system layer in isolation to pinpoint bottlenecks:
 * - Layer 1 (kafka): Kafka produce/consume only (bypass HTTP and Flink)
 * - Layer 2 (kafka-flink): Kafka + Flink processing (skip Drools)
 * - Layer 3 (kafka-flink-drools): Full stream processing
 * - Layer 4 (full): End-to-end HTTP → Kafka → Flink → Drools
 *
 * Uses production code paths (KafkaClient) for accurate measurements.
 */

export type BenchmarkLayer = 'kafka' | 'kafka-flink' | 'kafka-flink-drools' | 'full';

export interface LayeredBenchmarkConfig {
    layer: BenchmarkLayer;
    durationMs: number;           // Run for this duration
    targetEventCount: number;     // Or stop after N events (0 = use duration)
    batchSize: number;            // Events per batch
    sessions: number;             // Number of parallel sessions for distribution
    warmupMs: number;             // Warmup period before measuring
    cooldownMs: number;           // Cooldown before starting
    reportDir?: string;           // Directory for report output
}

export interface ComponentTimingAvg {
    gatewayMs: number;
    kafkaMs: number;
    flinkMs: number;
    droolsMs: number;
}

export interface LayeredBenchmarkResult {
    layer: BenchmarkLayer;
    startTime: Date;
    endTime: Date;
    durationMs: number;

    // Throughput metrics - CLEARLY DISTINGUISHED
    totalEventsSent: number;
    totalEventsReceived: number;

    // INPUT throughput (events entering system per second)
    inputPeakThroughput: number;
    inputAvgThroughput: number;

    // OUTPUT throughput (events completing per second) - THE REAL METRIC
    outputPeakThroughput: number;
    outputAvgThroughput: number;
    outputThroughputTimeline: number[];  // Per-second for charts

    // Legacy fields for compatibility (mapped to output metrics)
    peakThroughput: number;
    sustainedThroughput: number;
    avgThroughput: number;
    throughputTimeline?: number[];

    // Latency metrics (ms)
    latencyMin: number;
    latencyMax: number;
    latencyAvg: number;
    latencyP50: number;
    latencyP95: number;
    latencyP99: number;

    // Component timing breakdown (ms)
    componentTimingAvg?: ComponentTimingAvg;

    // Resource metrics
    peakCpuPercent: number;
    peakMemoryPercent: number;
    avgCpuPercent: number;
    avgMemoryPercent: number;

    // Kafka metrics
    kafkaLagAtEnd: number;
    kafkaLagPeak: number;

    // Event tracking - PROPERLY DEFINED
    // Success = event sent AND received back with valid result within timeout
    successfulEvents: number;
    timedOutEvents: number;      // Sent but no response within drain period
    successRate: number;         // (successfulEvents / totalEventsSent) * 100

    // Sample events for verification
    sampleEvents?: SampleEvent[];

    // Status
    status: 'completed' | 'stopped' | 'error' | 'timeout';
    errorMessage?: string;
}

export interface SampleEvent {
    eventId: string;
    sessionId: string;
    sentAt: number;
    receivedAt?: number;
    totalLatencyMs?: number;
    timing?: TimingBreakdown;
    result?: { value: number; alert: string };
    status: 'completed' | 'timed_out';
    // For timed-out events: detailed tracking
    lastKnownStage?: string;  // Human readable: "gateway", "kafka", "flink", "drools"
    stageTimestamps?: {       // Actual timestamps for each stage reached
        gatewayReceivedAt?: number;
        gatewayPublishedAt?: number;
        flinkReceivedAt?: number;
        flinkProcessedAt?: number;
        droolsStartAt?: number;
        droolsEndAt?: number;
    };
    trackerStatus?: string;   // Status from transaction tracker
}

export interface BenchmarkProgress {
    eventsSent: number;
    eventsReceived: number;
    currentThroughput: number;
    elapsedMs: number;
    remainingMs: number;
    percentComplete: number;
}

type ProgressCallback = (progress: BenchmarkProgress) => void;

export class LayeredBenchmark {
    private kafkaClient: KafkaClient;
    private running = false;
    private config: LayeredBenchmarkConfig;

    // Tracking
    private latencies: number[] = [];
    private inputThroughputSamples: number[] = [];   // Events SENT per second
    private outputThroughputSamples: number[] = [];  // Events RECEIVED per second
    private outputThroughputTimeline: number[] = []; // Per-second output for charts
    private cpuSamples: number[] = [];
    private memorySamples: number[] = [];
    private eventTimings: Map<string, number> = new Map();  // traceId -> sentAt
    private pendingEvents: Map<string, { sentAt: number; sessionId: string }> = new Map();  // eventId -> info
    private componentTimings: TimingBreakdown[] = [];
    private sampleEvents: SampleEvent[] = [];  // First N events for verification
    private eventsReceived = 0;
    private successfulEvents = 0;
    private kafkaLagPeak = 0;
    private lastReceivedCount = 0;  // For output throughput calculation

    // Callbacks
    private progressCallback?: ProgressCallback;

    constructor(kafkaClient: KafkaClient) {
        this.kafkaClient = kafkaClient;
        this.config = this.getDefaultConfig();
    }

    private getDefaultConfig(): LayeredBenchmarkConfig {
        return {
            layer: 'full',
            durationMs: 30000,
            targetEventCount: 0,
            batchSize: 100,
            sessions: 8,
            warmupMs: 3000,
            cooldownMs: 2000
        };
    }

    /**
     * Configure the benchmark
     */
    configure(config: Partial<LayeredBenchmarkConfig>): void {
        this.config = { ...this.config, ...config };
    }

    /**
     * Set progress callback for live updates
     */
    onProgress(callback: ProgressCallback): void {
        this.progressCallback = callback;
    }

    /**
     * Run a layered benchmark
     */
    async run(configOverride?: Partial<LayeredBenchmarkConfig>): Promise<LayeredBenchmarkResult> {
        if (this.running) {
            throw new Error('Benchmark already running');
        }

        const config = { ...this.config, ...configOverride };
        this.running = true;
        this.reset();

        logger.info('Starting layered benchmark', {
            layer: config.layer,
            durationMs: config.durationMs,
            targetEvents: config.targetEventCount || 'unlimited',
            batchSize: config.batchSize,
            sessions: config.sessions
        });

        const result: Partial<LayeredBenchmarkResult> = {
            layer: config.layer,
            startTime: new Date(),
            totalEventsSent: 0,
            totalEventsReceived: 0,
            inputPeakThroughput: 0,
            inputAvgThroughput: 0,
            outputPeakThroughput: 0,
            outputAvgThroughput: 0,
            outputThroughputTimeline: [],
            peakThroughput: 0,
            sustainedThroughput: 0,
            avgThroughput: 0,
            peakCpuPercent: 0,
            peakMemoryPercent: 0,
            kafkaLagAtEnd: 0,
            kafkaLagPeak: 0,
            successfulEvents: 0,
            timedOutEvents: 0,
            successRate: 0,
            status: 'completed'
        };

        try {
            // Cooldown before starting
            await this.sleep(config.cooldownMs);

            const startTime = Date.now();
            const warmupEnd = startTime + config.warmupMs;
            let lastProgressTime = startTime;
            let lastEventCount = 0;
            let eventsSent = 0;
            let isWarmup = true;

            // Main benchmark loop
            while (this.running) {
                const now = Date.now();
                const elapsed = now - startTime;

                // Check termination conditions
                if (config.targetEventCount > 0 && eventsSent >= config.targetEventCount) {
                    logger.info('Target event count reached');
                    break;
                }
                if (elapsed >= config.durationMs) {
                    logger.info('Duration limit reached');
                    break;
                }

                // Check if warmup is complete
                if (isWarmup && now >= warmupEnd) {
                    isWarmup = false;
                    logger.info('Warmup complete, starting measurements');
                }

                // Sample resources
                const resources = this.sampleResources();
                result.peakCpuPercent = Math.max(result.peakCpuPercent || 0, resources.cpuPercent);
                result.peakMemoryPercent = Math.max(result.peakMemoryPercent || 0, resources.memoryPercent);

                if (!isWarmup) {
                    this.cpuSamples.push(resources.cpuPercent);
                    this.memorySamples.push(resources.memoryPercent);
                }

                // Track Kafka lag
                const lagStats = this.kafkaClient.getLagStats();
                this.kafkaLagPeak = Math.max(this.kafkaLagPeak, lagStats.lag);

                // Send batch of events
                const batchEvents = this.generateEventBatch(config.batchSize, config.sessions);
                const sentAt = Date.now();

                try {
                    if (config.layer === 'full') {
                        // Layer 4 (full): Use HTTP endpoint for true end-to-end measurement
                        // This tests the complete path: HTTP → Gateway → Kafka → Flink → Drools
                        const httpPromises = batchEvents.map(async (event) => {
                            try {
                                const response = await fetch(`${GATEWAY_URL}/api/counter`, {
                                    method: 'POST',
                                    headers: { 'Content-Type': 'application/json' },
                                    body: JSON.stringify({
                                        action: event.action,
                                        value: event.value,
                                        sessionId: event.sessionId
                                    })
                                });
                                if (response.ok) {
                                    const data = await response.json() as { id: string; traceId: string };
                                    // HTTP endpoint creates its own eventId and tracks in transactionTracker
                                    // Update our pendingEvents map with the server-assigned eventId
                                    if (data.id && event.eventId) {
                                        // Remove our local eventId tracking
                                        this.pendingEvents.delete(event.eventId);
                                        // Track the server-assigned eventId
                                        this.pendingEvents.set(data.id, { sentAt, sessionId: event.sessionId });
                                    }
                                    if (data.traceId) {
                                        this.eventTimings.set(data.traceId, sentAt);
                                    }
                                    return true;
                                }
                                return false;
                            } catch {
                                return false;
                            }
                        });
                        await Promise.all(httpPromises);
                    } else if (config.layer === 'kafka') {
                        // Layer 1: Direct Kafka, track round-trip
                        const traceIds = await this.kafkaClient.publishEventBatch(batchEvents, false);
                        traceIds.forEach(id => this.eventTimings.set(id, sentAt));
                        // Mark events as published in transaction tracker
                        for (const event of batchEvents) {
                            if (event.eventId) {
                                transactionTracker.markPublished(event.eventId);
                            }
                        }
                    } else {
                        // Layers 2-3: Fire and forget to Kafka, measure end-to-end
                        const traceIds = await this.kafkaClient.publishEventBatch(batchEvents, true);
                        traceIds.forEach(id => this.eventTimings.set(id, sentAt));
                        // Mark events as published in transaction tracker
                        for (const event of batchEvents) {
                            if (event.eventId) {
                                transactionTracker.markPublished(event.eventId);
                            }
                        }
                    }
                    eventsSent += config.batchSize;
                } catch (error) {
                    logger.warn('Batch send failed', { error: String(error) });
                }

                // Calculate throughput every second
                if (now - lastProgressTime >= 1000) {
                    const inputThroughput = eventsSent - lastEventCount;
                    const outputThroughput = this.eventsReceived - this.lastReceivedCount;

                    // Track peak for both input and output
                    result.inputPeakThroughput = Math.max(result.inputPeakThroughput || 0, inputThroughput);
                    result.outputPeakThroughput = Math.max(result.outputPeakThroughput || 0, outputThroughput);

                    // Legacy field - now uses OUTPUT (the real metric)
                    result.peakThroughput = result.outputPeakThroughput;

                    if (!isWarmup) {
                        this.inputThroughputSamples.push(inputThroughput);
                        this.outputThroughputSamples.push(outputThroughput);
                        this.outputThroughputTimeline.push(outputThroughput);  // For chart
                    }

                    // Report progress
                    if (this.progressCallback) {
                        this.progressCallback({
                            eventsSent,
                            eventsReceived: this.eventsReceived,
                            currentThroughput: outputThroughput,  // Report OUTPUT throughput
                            elapsedMs: elapsed,
                            remainingMs: Math.max(0, config.durationMs - elapsed),
                            percentComplete: config.targetEventCount > 0
                                ? Math.round((eventsSent / config.targetEventCount) * 100)
                                : Math.round((elapsed / config.durationMs) * 100)
                        });
                    }

                    lastProgressTime = now;
                    lastEventCount = eventsSent;
                    this.lastReceivedCount = this.eventsReceived;
                }

                // Small delay to prevent CPU spin
                await this.sleep(1);
            }

            result.totalEventsSent = eventsSent;
            result.totalEventsReceived = this.eventsReceived;

        } catch (error) {
            result.status = 'error';
            result.errorMessage = String(error);
            logger.error('Benchmark error', { error });
        }

        // Wait for in-flight events to complete (up to 15 seconds - generous for slow events)
        const drainStart = Date.now();
        const drainTimeoutMs = 15000;
        logger.info(`Waiting up to ${drainTimeoutMs / 1000}s for ${this.pendingEvents.size} in-flight events...`);
        while (this.pendingEvents.size > 0 && Date.now() - drainStart < drainTimeoutMs) {
            await this.sleep(100);
        }
        if (this.pendingEvents.size > 0) {
            logger.warn(`${this.pendingEvents.size} events still pending after ${drainTimeoutMs / 1000}s drain period`);
        }

        // Finalize results
        result.endTime = new Date();
        result.durationMs = Date.now() - (result.startTime?.getTime() || Date.now());
        result.totalEventsReceived = this.eventsReceived;
        result.kafkaLagAtEnd = this.kafkaClient.getLagStats().lag;
        result.kafkaLagPeak = this.kafkaLagPeak;

        // Calculate throughput metrics - BOTH input and output
        if (this.inputThroughputSamples.length > 0) {
            result.inputAvgThroughput = Math.round(
                this.inputThroughputSamples.reduce((a, b) => a + b, 0) / this.inputThroughputSamples.length
            );
        } else {
            result.inputAvgThroughput = Math.round(
                (result.totalEventsSent || 0) / ((result.durationMs || 1) / 1000)
            );
        }

        if (this.outputThroughputSamples.length > 0) {
            result.outputAvgThroughput = Math.round(
                this.outputThroughputSamples.reduce((a, b) => a + b, 0) / this.outputThroughputSamples.length
            );
        } else {
            result.outputAvgThroughput = Math.round(
                (result.totalEventsReceived || 0) / ((result.durationMs || 1) / 1000)
            );
        }

        // Legacy fields - map to OUTPUT (the real metric)
        result.sustainedThroughput = result.outputAvgThroughput;
        result.avgThroughput = result.outputAvgThroughput;
        result.outputThroughputTimeline = [...this.outputThroughputTimeline];

        // Calculate latency metrics
        if (this.latencies.length > 0) {
            this.latencies.sort((a, b) => a - b);
            result.latencyMin = this.latencies[0];
            result.latencyMax = this.latencies[this.latencies.length - 1];
            result.latencyAvg = Math.round(
                this.latencies.reduce((a, b) => a + b, 0) / this.latencies.length
            );
            result.latencyP50 = this.percentile(this.latencies, 50);
            result.latencyP95 = this.percentile(this.latencies, 95);
            result.latencyP99 = this.percentile(this.latencies, 99);
        } else {
            result.latencyMin = 0;
            result.latencyMax = 0;
            result.latencyAvg = 0;
            result.latencyP50 = 0;
            result.latencyP95 = 0;
            result.latencyP99 = 0;
        }

        // Calculate resource averages
        if (this.cpuSamples.length > 0) {
            result.avgCpuPercent = Math.round(
                this.cpuSamples.reduce((a, b) => a + b, 0) / this.cpuSamples.length
            );
        }
        if (this.memorySamples.length > 0) {
            result.avgMemoryPercent = Math.round(
                this.memorySamples.reduce((a, b) => a + b, 0) / this.memorySamples.length
            );
        }

        // Calculate component timing averages
        if (this.componentTimings.length > 0) {
            const len = this.componentTimings.length;
            result.componentTimingAvg = {
                gatewayMs: Math.round(this.componentTimings.reduce((a, b) => a + b.gatewayMs, 0) / len),
                kafkaMs: Math.round(this.componentTimings.reduce((a, b) => a + b.kafkaMs, 0) / len),
                flinkMs: Math.round(this.componentTimings.reduce((a, b) => a + b.flinkMs, 0) / len),
                droolsMs: Math.round(this.componentTimings.reduce((a, b) => a + b.droolsMs, 0) / len)
            };
        }

        // Add throughput timeline for charts (use output timeline)
        result.throughputTimeline = [...this.outputThroughputTimeline];

        // Calculate success rate - CLEARLY DEFINED:
        // Success = event was sent AND we received a valid result back
        result.successfulEvents = this.successfulEvents;
        result.timedOutEvents = this.pendingEvents.size;
        const totalSent = result.totalEventsSent || 0;
        result.successRate = totalSent > 0
            ? Math.round((this.successfulEvents / totalSent) * 10000) / 100
            : 100;

        // Collect timed-out events as samples (up to 10) with detailed tracking info
        const timedOutSamples: SampleEvent[] = [];
        let timedOutCount = 0;
        for (const [eventId, info] of this.pendingEvents) {
            if (timedOutCount >= 10) break;

            // Query the transaction tracker for detailed state
            const trackerState = transactionTracker.get(eventId);
            const lastStage = this.determineLastStage(trackerState?.timing);

            timedOutSamples.push({
                eventId,
                sessionId: info.sessionId,
                sentAt: info.sentAt,
                status: 'timed_out',
                lastKnownStage: lastStage,
                stageTimestamps: trackerState?.timing ? { ...trackerState.timing } : undefined,
                trackerStatus: trackerState?.status
            });
            timedOutCount++;
        }

        // Combine successful samples (first 10) + timed out samples (first 10)
        const successfulSamples = this.sampleEvents.slice(0, 10);
        result.sampleEvents = [...successfulSamples, ...timedOutSamples];

        this.running = false;
        const finalResult = result as LayeredBenchmarkResult;
        this.logResults(finalResult);

        return finalResult;
    }

    /**
     * Record a result received from Kafka (called by consumer callback)
     *
     * CQRS Architecture:
     * - counter-results topic: Immediate results with alert="PENDING" (Gateway→Kafka→Flink timing)
     * - counter-alerts topic: Drools evaluation results (via timer, not per-event)
     *
     * For throughput measurement, we count PENDING results as "processed through Flink".
     * Drools is evaluated on a timer (aggregated) so it doesn't map 1:1 to individual events.
     */
    recordResult(result: CounterResult): void {
        const receivedAt = Date.now();
        this.eventsReceived++;

        // Track latency by traceId
        let latency: number | undefined;
        if (result.traceId && this.eventTimings.has(result.traceId)) {
            const sentAt = this.eventTimings.get(result.traceId)!;
            latency = receivedAt - sentAt;
            this.latencies.push(latency);
            this.eventTimings.delete(result.traceId);
        }

        // Track success by eventId and collect sample events
        if (result.eventId && this.pendingEvents.has(result.eventId)) {
            const pending = this.pendingEvents.get(result.eventId)!;
            this.successfulEvents++;

            // Collect first 10 events as samples for the report
            if (this.sampleEvents.length < 10) {
                const breakdown: TimingBreakdown | undefined = result.timing ? {
                    gatewayMs: (result.timing.gatewayPublishedAt && result.timing.gatewayReceivedAt)
                        ? result.timing.gatewayPublishedAt - result.timing.gatewayReceivedAt : 0,
                    kafkaMs: (result.timing.flinkReceivedAt && result.timing.gatewayPublishedAt)
                        ? result.timing.flinkReceivedAt - result.timing.gatewayPublishedAt : 0,
                    flinkMs: (result.timing.flinkProcessedAt && result.timing.flinkReceivedAt)
                        ? result.timing.flinkProcessedAt - result.timing.flinkReceivedAt : 0,
                    droolsMs: (result.timing.droolsEndAt && result.timing.droolsStartAt)
                        ? result.timing.droolsEndAt - result.timing.droolsStartAt : 0,
                    totalMs: receivedAt - pending.sentAt
                } : undefined;

                this.sampleEvents.push({
                    eventId: result.eventId,
                    sessionId: pending.sessionId,
                    sentAt: pending.sentAt,
                    receivedAt,
                    totalLatencyMs: receivedAt - pending.sentAt,
                    timing: breakdown,
                    result: { value: result.currentValue, alert: result.alert },
                    status: 'completed'
                });
            }

            this.pendingEvents.delete(result.eventId);
        }

        // Collect component timing from final results (has Drools timing)
        if (result.timing) {
            const breakdown: TimingBreakdown = {
                gatewayMs: (result.timing.gatewayPublishedAt && result.timing.gatewayReceivedAt)
                    ? result.timing.gatewayPublishedAt - result.timing.gatewayReceivedAt : 0,
                kafkaMs: (result.timing.flinkReceivedAt && result.timing.gatewayPublishedAt)
                    ? result.timing.flinkReceivedAt - result.timing.gatewayPublishedAt : 0,
                flinkMs: (result.timing.flinkProcessedAt && result.timing.flinkReceivedAt)
                    ? result.timing.flinkProcessedAt - result.timing.flinkReceivedAt : 0,
                droolsMs: (result.timing.droolsEndAt && result.timing.droolsStartAt)
                    ? result.timing.droolsEndAt - result.timing.droolsStartAt : 0,
                totalMs: (result.timing.gatewayReceivedAt)
                    ? receivedAt - result.timing.gatewayReceivedAt : 0
            };
            this.componentTimings.push(breakdown);
        }
    }

    /**
     * Stop a running benchmark
     */
    stop(): void {
        if (this.running) {
            logger.info('Stopping benchmark');
            this.running = false;
        }
    }

    /**
     * Check if benchmark is running
     */
    isRunning(): boolean {
        return this.running;
    }

    /**
     * Get current configuration
     */
    getConfig(): LayeredBenchmarkConfig {
        return { ...this.config };
    }

    private reset(): void {
        this.latencies = [];
        this.inputThroughputSamples = [];
        this.outputThroughputSamples = [];
        this.outputThroughputTimeline = [];
        this.cpuSamples = [];
        this.memorySamples = [];
        this.eventTimings.clear();
        this.pendingEvents.clear();
        this.componentTimings = [];
        this.sampleEvents = [];
        this.eventsReceived = 0;
        this.successfulEvents = 0;
        this.kafkaLagPeak = 0;
        this.lastReceivedCount = 0;
    }

    private generateEventBatch(batchSize: number, sessions: number): CounterEvent[] {
        const now = Date.now();
        return Array.from({ length: batchSize }, (_, i) => {
            const sessionId = `bench-${i % sessions}`;
            // Use transactionTracker to create a proper event ID with tracking
            const eventId = transactionTracker.create(sessionId);
            // Also track in pendingEvents for success measurement
            this.pendingEvents.set(eventId, { sentAt: now, sessionId });
            return {
                sessionId,
                action: 'increment',
                value: 1,
                timestamp: now,
                eventId
            };
        });
    }

    private sampleResources(): { cpuPercent: number; memoryPercent: number } {
        const cpus = os.cpus();
        const totalCpu = cpus.reduce((acc, cpu) => {
            const total = Object.values(cpu.times).reduce((a, b) => a + b, 0);
            const idle = cpu.times.idle;
            return acc + (1 - idle / total);
        }, 0);
        const cpuPercent = (totalCpu / cpus.length) * 100;

        const totalMem = os.totalmem();
        const freeMem = os.freemem();
        const memoryPercent = ((totalMem - freeMem) / totalMem) * 100;

        return { cpuPercent, memoryPercent };
    }

    private percentile(arr: number[], p: number): number {
        const index = Math.ceil((p / 100) * arr.length) - 1;
        return arr[Math.max(0, index)];
    }

    private sleep(ms: number): Promise<void> {
        return new Promise(resolve => setTimeout(resolve, ms));
    }

    /**
     * Determine the last stage an event reached based on timestamps
     */
    private determineLastStage(timing?: ComponentTiming): string {
        if (!timing) return 'unknown (not in tracker)';

        // Check stages in reverse order to find the last one reached
        if (timing.droolsEndAt) return 'drools_completed (result lost on return)';
        if (timing.droolsStartAt) return 'drools_processing (stuck in Drools)';
        if (timing.flinkProcessedAt) return 'flink_completed (never reached Drools)';
        if (timing.flinkReceivedAt) return 'flink_processing (stuck in Flink)';
        if (timing.gatewayPublishedAt) return 'kafka (published but never reached Flink)';
        if (timing.gatewayReceivedAt) return 'gateway (received but never published)';

        return 'unknown';
    }

    private logResults(result: LayeredBenchmarkResult): void {
        logger.info('=== LAYERED BENCHMARK RESULTS ===');
        logger.info(`Layer: ${result.layer}`);
        logger.info(`Duration: ${Math.round(result.durationMs / 1000)}s`);
        logger.info('');
        logger.info('THROUGHPUT (events/sec):');
        logger.info(`  INPUT (sent to Kafka):     Peak ${result.inputPeakThroughput}, Avg ${result.inputAvgThroughput}`);
        logger.info(`  OUTPUT (end-to-end):       Peak ${result.outputPeakThroughput}, Avg ${result.outputAvgThroughput}`);
        logger.info('');
        logger.info('EVENTS:');
        logger.info(`  Sent:       ${result.totalEventsSent}`);
        logger.info(`  Received:   ${result.totalEventsReceived}`);
        logger.info(`  Successful: ${result.successfulEvents}`);
        logger.info(`  Timed out:  ${result.timedOutEvents}`);
        logger.info(`  Success:    ${result.successRate}%`);
        logger.info('');
        logger.info('LATENCY (ms):');
        logger.info(`  P50:  ${result.latencyP50}  |  P95:  ${result.latencyP95}  |  P99:  ${result.latencyP99}  |  Max:  ${result.latencyMax}`);
        logger.info('');
        if (result.componentTimingAvg) {
            logger.info('COMPONENT TIMING (avg ms):');
            logger.info(`  Gateway: ${result.componentTimingAvg.gatewayMs}  |  Kafka: ${result.componentTimingAvg.kafkaMs}  |  Flink: ${result.componentTimingAvg.flinkMs}  |  Drools: ${result.componentTimingAvg.droolsMs}`);
            logger.info('');
        }
        logger.info('RESOURCES:');
        logger.info(`  CPU: ${result.peakCpuPercent?.toFixed(0)}% peak, ${result.avgCpuPercent?.toFixed(0)}% avg`);
        logger.info(`  Memory: ${result.peakMemoryPercent?.toFixed(0)}% peak, ${result.avgMemoryPercent?.toFixed(0)}% avg`);
        logger.info(`  Kafka Lag: ${result.kafkaLagPeak} peak, ${result.kafkaLagAtEnd} at end`);
        logger.info('');
        logger.info(`Status: ${result.status}`);
        logger.info('=================================');
    }
}

/**
 * Generate a benchmark report summary as JSON
 */
export function generateReportData(result: LayeredBenchmarkResult): object {
    const now = new Date();
    return {
        metadata: {
            generatedAt: now.toISOString(),
            layer: result.layer,
            duration: result.durationMs,
            status: result.status
        },
        throughput: {
            peak: result.peakThroughput,
            sustained: result.sustainedThroughput,
            average: result.avgThroughput,
            eventsSent: result.totalEventsSent,
            eventsReceived: result.totalEventsReceived,
            lossRate: result.totalEventsSent > 0
                ? ((result.totalEventsSent - result.totalEventsReceived) / result.totalEventsSent * 100).toFixed(2) + '%'
                : '0%'
        },
        latency: {
            min: result.latencyMin,
            p50: result.latencyP50,
            p95: result.latencyP95,
            p99: result.latencyP99,
            max: result.latencyMax,
            avg: result.latencyAvg
        },
        resources: {
            cpu: {
                peak: result.peakCpuPercent,
                average: result.avgCpuPercent
            },
            memory: {
                peak: result.peakMemoryPercent,
                average: result.avgMemoryPercent
            }
        },
        kafka: {
            lagAtEnd: result.kafkaLagAtEnd,
            lagPeak: result.kafkaLagPeak
        }
    };
}
