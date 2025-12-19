import { logger } from './logger';
import * as os from 'os';

/**
 * Development Mode Auto-Benchmark
 *
 * Automatically runs a load test to discover system limits.
 * Like a car dyno test - finds maximum performance without breaking things.
 *
 * Features:
 * - Gradual ramp-up (doesn't shock the system)
 * - Resource monitoring (CPU/memory limits)
 * - Automatic backoff when approaching limits
 * - Comprehensive results reporting
 */

interface BenchmarkConfig {
    maxDurationMs: number;       // Maximum benchmark duration (default: 5 minutes)
    maxCpuPercent: number;       // Back off above this CPU usage (default: 90%)
    maxMemoryPercent: number;    // Back off above this memory usage (default: 85%)
    initialRatePerSec: number;   // Starting events per second
    maxRatePerSec: number;       // Maximum events per second to try
    rampUpIntervalMs: number;    // How often to increase rate
    rampUpMultiplier: number;    // Multiply rate by this each interval
    cooldownMs: number;          // Wait before starting (let system stabilize)
}

interface BenchmarkResult {
    startTime: Date;
    endTime: Date;
    durationMs: number;
    totalEventsSent: number;
    totalResultsReceived: number;
    peakThroughput: number;      // events/sec achieved
    sustainedThroughput: number; // events/sec without resource pressure
    latencyP50Ms: number;
    latencyP95Ms: number;
    latencyP99Ms: number;
    peakCpuPercent: number;
    peakMemoryPercent: number;
    adaptiveGearChanges: number;
    reason: 'completed' | 'cpu_limit' | 'memory_limit' | 'error' | 'stopped';
}

interface EventTiming {
    sentAt: number;
    receivedAt?: number;
}

export class AutoBenchmark {
    private config: BenchmarkConfig;
    private running = false;
    private eventTimings: Map<string, EventTiming> = new Map();
    private latencies: number[] = [];
    private result: Partial<BenchmarkResult> = {};
    private currentRate = 0;
    private intervalHandle?: NodeJS.Timeout;
    private sendEvent: (action: string, value: number) => Promise<string>;
    private sendEventBatch?: (events: { action: string; value: number }[]) => Promise<string[]>;
    private sendEventFireAndForget?: (action: string, value: number) => string;
    private onResult: (callback: (traceId: string) => void) => void;

    constructor(
        sendEvent: (action: string, value: number) => Promise<string>,
        onResult: (callback: (traceId: string) => void) => void,
        config?: Partial<BenchmarkConfig>,
        sendEventBatch?: (events: { action: string; value: number }[]) => Promise<string[]>,
        sendEventFireAndForget?: (action: string, value: number) => string
    ) {
        this.sendEvent = sendEvent;
        this.sendEventBatch = sendEventBatch;
        this.sendEventFireAndForget = sendEventFireAndForget;
        this.onResult = onResult;
        this.config = {
            maxDurationMs: 5 * 60 * 1000,  // 5 minutes
            maxCpuPercent: 90,
            maxMemoryPercent: 85,
            initialRatePerSec: 10,
            maxRatePerSec: Infinity,       // No artificial limit - find true system limit
            rampUpIntervalMs: 5000,        // Increase rate every 5 seconds
            rampUpMultiplier: 2.0,         // Double rate each step (faster discovery)
            cooldownMs: 3000,              // 3 second cooldown
            ...config
        };

        // Subscribe to results for latency tracking
        this.onResult((traceId: string) => {
            const timing = this.eventTimings.get(traceId);
            if (timing) {
                timing.receivedAt = Date.now();
                const latency = timing.receivedAt - timing.sentAt;
                this.latencies.push(latency);
                this.eventTimings.delete(traceId);
            }
        });
    }

    async start(): Promise<BenchmarkResult> {
        if (this.running) {
            throw new Error('Benchmark already running');
        }

        this.running = true;
        this.latencies = [];
        this.eventTimings.clear();
        this.result = {
            startTime: new Date(),
            totalEventsSent: 0,
            totalResultsReceived: 0,
            peakThroughput: 0,
            sustainedThroughput: 0,
            peakCpuPercent: 0,
            peakMemoryPercent: 0,
            adaptiveGearChanges: 0,
            reason: 'completed'
        };

        logger.info('Auto-benchmark starting', {
            maxDuration: this.config.maxDurationMs / 1000 + 's',
            maxCpu: this.config.maxCpuPercent + '%',
            maxMemory: this.config.maxMemoryPercent + '%',
            maxRate: this.config.maxRatePerSec === Infinity ? 'unlimited' : this.config.maxRatePerSec + ' events/sec'
        });

        // Cooldown period
        await this.sleep(this.config.cooldownMs);

        this.currentRate = this.config.initialRatePerSec;
        const startTime = Date.now();
        let lastRampUp = startTime;
        let lastRateCheck = startTime;
        let eventsSinceLastCheck = 0;

        try {
            while (this.running) {
                const now = Date.now();
                const elapsed = now - startTime;

                // Check duration limit
                if (elapsed >= this.config.maxDurationMs) {
                    logger.info('Benchmark duration limit reached');
                    break;
                }

                // Check resource limits
                const resources = this.checkResources();
                this.result.peakCpuPercent = Math.max(this.result.peakCpuPercent || 0, resources.cpuPercent);
                this.result.peakMemoryPercent = Math.max(this.result.peakMemoryPercent || 0, resources.memoryPercent);

                if (resources.cpuPercent > this.config.maxCpuPercent) {
                    logger.warn('CPU limit reached, backing off', { cpu: resources.cpuPercent });
                    this.currentRate = Math.max(1, this.currentRate * 0.7);
                    this.result.reason = 'cpu_limit';
                }

                if (resources.memoryPercent > this.config.maxMemoryPercent) {
                    logger.warn('Memory limit reached, backing off', { memory: resources.memoryPercent });
                    this.currentRate = Math.max(1, this.currentRate * 0.7);
                    this.result.reason = 'memory_limit';
                }

                // Ramp up rate periodically
                if (now - lastRampUp >= this.config.rampUpIntervalMs &&
                    resources.cpuPercent < this.config.maxCpuPercent * 0.8 &&
                    resources.memoryPercent < this.config.maxMemoryPercent * 0.8) {

                    const newRate = Math.min(
                        this.currentRate * this.config.rampUpMultiplier,
                        this.config.maxRatePerSec
                    );

                    if (newRate > this.currentRate) {
                        logger.info('Ramping up rate', {
                            from: Math.round(this.currentRate),
                            to: Math.round(newRate)
                        });
                        this.currentRate = newRate;
                        this.result.adaptiveGearChanges = (this.result.adaptiveGearChanges || 0) + 1;
                    }
                    lastRampUp = now;
                }

                // Calculate actual throughput
                if (now - lastRateCheck >= 1000) {
                    const actualRate = eventsSinceLastCheck;
                    this.result.peakThroughput = Math.max(this.result.peakThroughput || 0, actualRate);

                    // Update sustained throughput (when not resource-limited)
                    if (resources.cpuPercent < this.config.maxCpuPercent * 0.7) {
                        this.result.sustainedThroughput = Math.max(
                            this.result.sustainedThroughput || 0,
                            actualRate
                        );
                    }

                    eventsSinceLastCheck = 0;
                    lastRateCheck = now;
                }

                // Send events at current rate using batches for high throughput
                const eventsToSend = Math.ceil(this.currentRate / 10); // Larger batches
                const batchSize = Math.min(eventsToSend, 1000); // Cap batch size

                try {
                    // Use batch sending for high throughput
                    if (this.sendEventBatch && batchSize > 10) {
                        // Create batch of events
                        const events = Array.from({ length: batchSize }, () => ({
                            action: 'increment' as const,
                            value: 1
                        }));

                        const traceIds = await this.sendEventBatch(events);
                        const sentAt = Date.now();
                        traceIds.forEach(traceId => {
                            this.eventTimings.set(traceId, { sentAt });
                        });
                        this.result.totalEventsSent = (this.result.totalEventsSent || 0) + batchSize;
                        eventsSinceLastCheck += batchSize;
                    } else {
                        // Fall back to fire-and-forget individual sends (still fast)
                        for (let i = 0; i < batchSize && this.running; i++) {
                            try {
                                const traceId = this.sendEventFireAndForget
                                    ? this.sendEventFireAndForget('increment', 1)
                                    : await this.sendEvent('increment', 1);
                                this.eventTimings.set(traceId, { sentAt: Date.now() });
                                this.result.totalEventsSent = (this.result.totalEventsSent || 0) + 1;
                                eventsSinceLastCheck++;
                            } catch (error) {
                                // Ignore individual failures in fire-and-forget mode
                            }
                        }
                    }
                } catch (error) {
                    logger.error('Failed to send benchmark batch', error);
                }

                // Pace the loop based on target rate (faster pacing)
                const targetDelay = Math.max(1, 1000 / this.currentRate * batchSize);
                await this.sleep(Math.min(targetDelay, 50)); // Shorter max delay
            }
        } catch (error) {
            logger.error('Benchmark error', error);
            this.result.reason = 'error';
        }

        this.running = false;
        this.result.endTime = new Date();
        this.result.durationMs = Date.now() - startTime;
        this.result.totalResultsReceived = this.latencies.length;

        // Calculate latency percentiles
        if (this.latencies.length > 0) {
            this.latencies.sort((a, b) => a - b);
            this.result.latencyP50Ms = this.percentile(this.latencies, 50);
            this.result.latencyP95Ms = this.percentile(this.latencies, 95);
            this.result.latencyP99Ms = this.percentile(this.latencies, 99);
        }

        const finalResult = this.result as BenchmarkResult;
        this.logResults(finalResult);

        return finalResult;
    }

    stop(): void {
        if (this.running) {
            logger.info('Stopping benchmark');
            this.running = false;
            this.result.reason = 'stopped';
        }
    }

    isRunning(): boolean {
        return this.running;
    }

    private checkResources(): { cpuPercent: number; memoryPercent: number } {
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

    private logResults(result: BenchmarkResult): void {
        logger.info('=== BENCHMARK RESULTS ===', {});
        logger.info('Duration', { durationSec: Math.round(result.durationMs / 1000) });
        logger.info('Events', {
            sent: result.totalEventsSent,
            received: result.totalResultsReceived,
            lossRate: ((result.totalEventsSent - result.totalResultsReceived) / result.totalEventsSent * 100).toFixed(2) + '%'
        });
        logger.info('Throughput', {
            peak: Math.round(result.peakThroughput) + ' events/sec',
            sustained: Math.round(result.sustainedThroughput) + ' events/sec'
        });
        logger.info('Latency', {
            p50: result.latencyP50Ms + 'ms',
            p95: result.latencyP95Ms + 'ms',
            p99: result.latencyP99Ms + 'ms'
        });
        logger.info('Resources', {
            peakCpu: result.peakCpuPercent.toFixed(1) + '%',
            peakMemory: result.peakMemoryPercent.toFixed(1) + '%'
        });
        logger.info('Adaptive', {
            gearChanges: result.adaptiveGearChanges,
            stopReason: result.reason
        });
        logger.info('=========================', {});
    }
}

/**
 * Check if we should run benchmark (development mode only)
 */
export function shouldRunBenchmark(): boolean {
    return process.env.NODE_ENV !== 'production';
}

/**
 * Get benchmark configuration - sensible defaults, no env vars needed
 */
export function getBenchmarkConfig(): Partial<BenchmarkConfig> {
    return {
        maxDurationMs: 300000,    // 5 minutes
        maxCpuPercent: 90,        // Back off at 90% CPU
        maxMemoryPercent: 85      // Back off at 85% memory
        // No maxRatePerSec - let the system find its true limit
    };
}
