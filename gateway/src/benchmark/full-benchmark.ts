/**
 * Full End-to-End Benchmark
 *
 * Tests the complete pipeline: HTTP → Gateway → Kafka → Flink → Drools
 * Uses HTTP POST to /api/counter and waits for results via Kafka consumer.
 * This measures true end-to-end latency including all components.
 */

import { BaseBenchmark } from './base-benchmark';
import { BenchmarkConfig, BenchmarkComponentId, SampleEvent, ComponentTimingStats } from './types';
import { KafkaClient, CounterResult } from '../kafka';
import { transactionTracker, TimingBreakdown } from '../transaction-tracker';
import { logger } from '../logger';

const GATEWAY_URL = process.env.GATEWAY_URL || 'http://localhost:8080';

export class FullBenchmark extends BaseBenchmark {
  readonly id: BenchmarkComponentId = 'full';
  readonly name = 'Full End-to-End Benchmark';
  readonly description = 'Tests complete pipeline: HTTP → Kafka → Flink → Drools';

  private kafkaClient: KafkaClient;
  private pendingEvents: Map<string, { sentAt: number; sessionId: string }> = new Map();
  private eventTimings: Map<string, number> = new Map();
  private componentTimings: TimingBreakdown[] = [];

  constructor(kafkaClient: KafkaClient) {
    super();
    this.kafkaClient = kafkaClient;
  }

  protected reset(): void {
    super.reset();
    this.pendingEvents.clear();
    this.eventTimings.clear();
    this.componentTimings = [];
  }

  protected async runBenchmarkLoop(config: BenchmarkConfig): Promise<void> {
    // Send batch of events via HTTP
    const promises: Promise<void>[] = [];
    const sentAt = Date.now();

    for (let i = 0; i < config.batchSize; i++) {
      const sessionId = `full-bench-${i % config.concurrency}`;
      promises.push(this.sendHttpEvent(sessionId, sentAt));
    }

    await Promise.all(promises);

    // Small delay to prevent CPU spin
    await this.sleep(1);
  }

  private async sendHttpEvent(sessionId: string, sentAt: number): Promise<void> {
    try {
      const response = await fetch(`${GATEWAY_URL}/api/counter`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          action: 'increment',
          value: 1,
          sessionId,
        }),
      });

      if (response.ok) {
        const data = await response.json() as { id: string; traceId: string };

        // Track the server-assigned eventId
        if (data.id) {
          this.pendingEvents.set(data.id, { sentAt, sessionId });
        }
        if (data.traceId) {
          this.eventTimings.set(data.traceId, sentAt);
        }
      } else {
        this.recordFailure();

        // Sample error event
        this.addSampleEvent({
          id: `full_${Date.now()}`,
          traceId: '',
          timestamp: sentAt,
          latencyMs: Date.now() - sentAt,
          status: 'error',
          error: `HTTP ${response.status}: ${response.statusText}`,
        });
      }
    } catch (error) {
      this.recordFailure();

      this.addSampleEvent({
        id: `full_${Date.now()}`,
        traceId: '',
        timestamp: sentAt,
        latencyMs: Date.now() - sentAt,
        status: 'error',
        error: String(error),
      });
    }
  }

  /**
   * Record a result received from Kafka consumer
   * In CQRS architecture, we receive PENDING results from counter-results
   * and final results from counter-alerts.
   */
  recordResult(result: CounterResult): void {
    const receivedAt = Date.now();

    // Track latency by traceId
    if (result.traceId && this.eventTimings.has(result.traceId)) {
      const sentAt = this.eventTimings.get(result.traceId)!;
      const latency = receivedAt - sentAt;
      this.recordLatency(latency);
      this.eventTimings.delete(result.traceId);
    }

    // Track success by eventId
    if (result.eventId && this.pendingEvents.has(result.eventId)) {
      const pending = this.pendingEvents.get(result.eventId)!;
      this.recordSuccess();

      // Calculate component timing breakdown
      const componentTiming = this.calculateComponentTiming(result, pending.sentAt, receivedAt);

      if (componentTiming) {
        this.componentTimings.push({
          gatewayMs: componentTiming.gatewayMs,
          kafkaMs: componentTiming.kafkaMs,
          flinkMs: componentTiming.flinkMs,
          droolsMs: componentTiming.droolsMs,
          totalMs: receivedAt - pending.sentAt,
        });
      }

      // Sample first 2 successful events
      if (this.sampleEvents.filter(e => e.status === 'success').length < 2) {
        this.addSampleEvent({
          id: result.eventId,
          traceId: result.traceId || '',
          timestamp: pending.sentAt,
          latencyMs: receivedAt - pending.sentAt,
          status: 'success',
          componentTiming,
        });
      }

      this.pendingEvents.delete(result.eventId);
    }
  }

  private calculateComponentTiming(
    result: CounterResult,
    sentAt: number,
    receivedAt: number
  ): ComponentTimingStats | undefined {
    if (!result.timing) return undefined;

    return {
      gatewayMs: (result.timing.gatewayPublishedAt && result.timing.gatewayReceivedAt)
        ? result.timing.gatewayPublishedAt - result.timing.gatewayReceivedAt : 0,
      kafkaMs: (result.timing.flinkReceivedAt && result.timing.gatewayPublishedAt)
        ? result.timing.flinkReceivedAt - result.timing.gatewayPublishedAt : 0,
      flinkMs: (result.timing.flinkProcessedAt && result.timing.flinkReceivedAt)
        ? result.timing.flinkProcessedAt - result.timing.flinkReceivedAt : 0,
      droolsMs: (result.timing.droolsEndAt && result.timing.droolsStartAt)
        ? result.timing.droolsEndAt - result.timing.droolsStartAt : 0,
    };
  }

  /**
   * Get pending event count for drain tracking
   */
  getPendingCount(): number {
    return this.pendingEvents.size;
  }

  /**
   * Get average component timing from collected samples
   */
  getComponentTimingAvg(): ComponentTimingStats | undefined {
    if (this.componentTimings.length === 0) return undefined;

    const len = this.componentTimings.length;
    return {
      gatewayMs: Math.round(this.componentTimings.reduce((a, b) => a + b.gatewayMs, 0) / len),
      kafkaMs: Math.round(this.componentTimings.reduce((a, b) => a + b.kafkaMs, 0) / len),
      flinkMs: Math.round(this.componentTimings.reduce((a, b) => a + b.flinkMs, 0) / len),
      droolsMs: Math.round(this.componentTimings.reduce((a, b) => a + b.droolsMs, 0) / len),
    };
  }

  /**
   * Override buildResult to include component timing
   */
  async run(configOverride?: Partial<BenchmarkConfig>) {
    const result = await super.run(configOverride);

    // Add component timing average to result
    result.componentTiming = this.getComponentTimingAvg();

    return result;
  }
}
