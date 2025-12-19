/**
 * Base Benchmark Class
 *
 * Abstract base class implementing common benchmark logic.
 * Component benchmarks extend this and implement runBenchmarkLoop().
 *
 * Follows Template Method pattern:
 * - run() is the template method (common workflow)
 * - runBenchmarkLoop() is the hook method (component-specific)
 */

import * as os from 'os';
import { logger } from '../logger';
import {
  BenchmarkInterface,
  BenchmarkConfig,
  BenchmarkResult,
  BenchmarkProgress,
  BenchmarkComponentId,
  ProgressCallback,
  SampleEvent,
  LatencyStats,
  ComponentTimingStats,
  DEFAULT_BENCHMARK_CONFIG,
} from './types';

export abstract class BaseBenchmark implements BenchmarkInterface {
  // ============================================================================
  // Abstract properties - each component defines these
  // ============================================================================

  abstract readonly id: BenchmarkComponentId;
  abstract readonly name: string;
  abstract readonly description: string;

  // ============================================================================
  // State
  // ============================================================================

  protected running = false;
  protected config: BenchmarkConfig;
  protected progressCallback?: ProgressCallback;

  // ============================================================================
  // Tracking Arrays
  // ============================================================================

  protected latencies: number[] = [];
  protected throughputSamples: number[] = [];
  protected cpuSamples: number[] = [];
  protected memorySamples: number[] = [];
  protected successCount = 0;
  protected failCount = 0;
  protected sampleEvents: SampleEvent[] = [];

  // For per-second throughput calculation
  protected lastOperationCount = 0;

  // ============================================================================
  // Constructor
  // ============================================================================

  constructor(defaultConfig?: Partial<BenchmarkConfig>) {
    this.config = { ...DEFAULT_BENCHMARK_CONFIG, ...defaultConfig };
  }

  // ============================================================================
  // BenchmarkInterface Implementation
  // ============================================================================

  getConfig(): BenchmarkConfig {
    return { ...this.config };
  }

  isRunning(): boolean {
    return this.running;
  }

  stop(): void {
    if (this.running) {
      logger.info(`Stopping ${this.name}`);
      this.running = false;
    }
  }

  onProgress(callback: ProgressCallback): void {
    this.progressCallback = callback;
  }

  /**
   * Template method - orchestrates the benchmark workflow.
   * Subclasses implement runBenchmarkLoop() for component-specific logic.
   */
  async run(configOverride?: Partial<BenchmarkConfig>): Promise<BenchmarkResult> {
    if (this.running) {
      throw new Error(`${this.name} is already running`);
    }

    const config = { ...this.config, ...configOverride };
    this.running = true;
    this.reset();

    logger.info(`Starting ${this.name}`, {
      component: this.id,
      durationMs: config.durationMs,
      targetEvents: config.targetEventCount || 'unlimited',
      concurrency: config.concurrency,
    });

    const startTime = new Date();
    let status: BenchmarkResult['status'] = 'completed';
    let errorMessage: string | undefined;

    try {
      // Cooldown before starting
      await this.sleep(config.cooldownMs);

      const benchmarkStart = Date.now();
      const warmupEnd = benchmarkStart + config.warmupMs;
      let lastProgressTime = benchmarkStart;
      let isWarmup = true;

      // Main benchmark loop
      while (this.running) {
        const now = Date.now();
        const elapsed = now - benchmarkStart;

        // Check termination conditions
        if (config.targetEventCount > 0 && this.getOperationCount() >= config.targetEventCount) {
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
          // Reset counters after warmup
          this.lastOperationCount = this.getOperationCount();
        }

        // Sample resources
        const resources = this.sampleResources();

        if (!isWarmup) {
          this.cpuSamples.push(resources.cpu);
          this.memorySamples.push(resources.memory);
        }

        // Run one iteration of the benchmark loop
        await this.runBenchmarkLoop(config);

        // Calculate throughput every second
        if (now - lastProgressTime >= 1000) {
          const currentOps = this.getOperationCount();
          const throughput = currentOps - this.lastOperationCount;

          if (!isWarmup) {
            this.throughputSamples.push(throughput);
          }

          // Report progress
          if (this.progressCallback) {
            this.progressCallback({
              operationsCompleted: currentOps,
              currentThroughput: throughput,
              elapsedMs: elapsed,
              remainingMs: Math.max(0, config.durationMs - elapsed),
              percentComplete: config.targetEventCount > 0
                ? Math.round((currentOps / config.targetEventCount) * 100)
                : Math.round((elapsed / config.durationMs) * 100),
              currentCpu: resources.cpu,
              currentMemory: resources.memory,
            });
          }

          lastProgressTime = now;
          this.lastOperationCount = currentOps;
        }
      }
    } catch (error) {
      status = 'error';
      errorMessage = String(error);
      logger.error(`${this.name} error`, { error });
    }

    // Build and return result
    this.running = false;
    const result = this.buildResult(startTime, status, errorMessage);
    this.logResults(result);

    return result;
  }

  // ============================================================================
  // Abstract Method - Component-specific logic
  // ============================================================================

  /**
   * Run one iteration of the benchmark.
   * Each component implements this differently.
   *
   * Examples:
   * - HTTP: Make one HTTP request
   * - Kafka: Publish a batch of messages
   * - Drools: Evaluate a rule
   */
  protected abstract runBenchmarkLoop(config: BenchmarkConfig): Promise<void>;

  // ============================================================================
  // Protected Helpers - Available to subclasses
  // ============================================================================

  /**
   * Reset all tracking state before a new benchmark run
   */
  protected reset(): void {
    this.latencies = [];
    this.throughputSamples = [];
    this.cpuSamples = [];
    this.memorySamples = [];
    this.successCount = 0;
    this.failCount = 0;
    this.sampleEvents = [];
    this.lastOperationCount = 0;
  }

  /**
   * Record a latency measurement
   */
  protected recordLatency(latencyMs: number): void {
    this.latencies.push(latencyMs);
  }

  /**
   * Record a successful operation
   */
  protected recordSuccess(): void {
    this.successCount++;
  }

  /**
   * Record a failed operation
   */
  protected recordFailure(): void {
    this.failCount++;
  }

  /**
   * Add a sample event for the report
   */
  protected addSampleEvent(event: SampleEvent): void {
    // Keep max 2 successful + 10 error events
    if (event.status === 'success') {
      const successCount = this.sampleEvents.filter(e => e.status === 'success').length;
      if (successCount < 2) {
        this.sampleEvents.push(event);
      }
    } else {
      const errorCount = this.sampleEvents.filter(e => e.status !== 'success').length;
      if (errorCount < 10) {
        this.sampleEvents.push(event);
      }
    }
  }

  /**
   * Get total operation count (success + failure)
   */
  protected getOperationCount(): number {
    return this.successCount + this.failCount;
  }

  /**
   * Sample current CPU and memory usage
   */
  protected sampleResources(): { cpu: number; memory: number } {
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

    return { cpu: cpuPercent, memory: memoryPercent };
  }

  /**
   * Calculate a percentile from a sorted array
   */
  protected percentile(arr: number[], p: number): number {
    if (arr.length === 0) return 0;
    const sorted = [...arr].sort((a, b) => a - b);
    const index = Math.ceil((p / 100) * sorted.length) - 1;
    return sorted[Math.max(0, index)];
  }

  /**
   * Sleep for a given number of milliseconds
   */
  protected sleep(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  // ============================================================================
  // Private Helpers
  // ============================================================================

  /**
   * Build the final benchmark result from collected data
   */
  private buildResult(
    startTime: Date,
    status: BenchmarkResult['status'],
    errorMessage?: string
  ): BenchmarkResult {
    const endTime = new Date();
    const durationMs = endTime.getTime() - startTime.getTime();

    // Calculate latency stats
    const latency = this.calculateLatencyStats();

    // Calculate throughput
    const peakThroughput = this.throughputSamples.length > 0
      ? Math.max(...this.throughputSamples)
      : 0;
    const avgThroughput = this.throughputSamples.length > 0
      ? Math.round(this.throughputSamples.reduce((a, b) => a + b, 0) / this.throughputSamples.length)
      : Math.round((this.successCount + this.failCount) / (durationMs / 1000));

    // Calculate resource stats
    const peakCpu = this.cpuSamples.length > 0 ? Math.max(...this.cpuSamples) : 0;
    const peakMemory = this.memorySamples.length > 0 ? Math.max(...this.memorySamples) : 0;
    const avgCpu = this.cpuSamples.length > 0
      ? this.cpuSamples.reduce((a, b) => a + b, 0) / this.cpuSamples.length
      : 0;
    const avgMemory = this.memorySamples.length > 0
      ? this.memorySamples.reduce((a, b) => a + b, 0) / this.memorySamples.length
      : 0;

    return {
      component: this.id,
      name: this.name,
      description: this.description,
      startTime,
      endTime,
      durationMs,
      totalOperations: this.successCount + this.failCount,
      successfulOperations: this.successCount,
      failedOperations: this.failCount,
      peakThroughput,
      avgThroughput,
      throughputTimeline: [...this.throughputSamples],
      latency,
      cpuTimeline: [...this.cpuSamples],
      memoryTimeline: [...this.memorySamples],
      peakCpu: Math.round(peakCpu),
      peakMemory: Math.round(peakMemory),
      avgCpu: Math.round(avgCpu),
      avgMemory: Math.round(avgMemory),
      sampleEvents: [...this.sampleEvents],
      status,
      errorMessage,
    };
  }

  /**
   * Calculate latency statistics from collected samples
   */
  private calculateLatencyStats(): LatencyStats {
    if (this.latencies.length === 0) {
      return { min: 0, max: 0, avg: 0, p50: 0, p95: 0, p99: 0 };
    }

    const sorted = [...this.latencies].sort((a, b) => a - b);

    return {
      min: sorted[0],
      max: sorted[sorted.length - 1],
      avg: Math.round(sorted.reduce((a, b) => a + b, 0) / sorted.length),
      p50: this.percentile(sorted, 50),
      p95: this.percentile(sorted, 95),
      p99: this.percentile(sorted, 99),
    };
  }

  /**
   * Log benchmark results summary
   */
  private logResults(result: BenchmarkResult): void {
    logger.info(`=== ${this.name.toUpperCase()} RESULTS ===`);
    logger.info(`Component: ${result.component}`);
    logger.info(`Duration: ${Math.round(result.durationMs / 1000)}s`);
    logger.info('');
    logger.info('THROUGHPUT (ops/sec):');
    logger.info(`  Peak: ${result.peakThroughput}  |  Avg: ${result.avgThroughput}`);
    logger.info('');
    logger.info('OPERATIONS:');
    logger.info(`  Total: ${result.totalOperations}  |  Success: ${result.successfulOperations}  |  Failed: ${result.failedOperations}`);
    logger.info('');
    logger.info('LATENCY (ms):');
    logger.info(`  P50: ${result.latency.p50}  |  P95: ${result.latency.p95}  |  P99: ${result.latency.p99}  |  Max: ${result.latency.max}`);
    logger.info('');
    logger.info('RESOURCES:');
    logger.info(`  CPU: ${result.peakCpu}% peak, ${result.avgCpu}% avg`);
    logger.info(`  Memory: ${result.peakMemory}% peak, ${result.avgMemory}% avg`);
    logger.info('');
    logger.info(`Status: ${result.status}`);
    logger.info('================================');
  }
}
