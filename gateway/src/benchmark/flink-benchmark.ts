/**
 * Flink Benchmark
 *
 * Tests Kafka + Flink processing performance.
 * Events flow through Kafka and Flink, but skip Drools evaluation.
 * This isolates Flink stream processing latency.
 */

import { BaseBenchmark } from './base-benchmark';
import { BenchmarkConfig, BenchmarkComponentId, SampleEvent } from './types';
import { KafkaClient, CounterEvent, CounterResult } from '../kafka';
import { transactionTracker } from '../transaction-tracker';
import { logger } from '../logger';

export class FlinkBenchmark extends BaseBenchmark {
  readonly id: BenchmarkComponentId = 'flink';
  readonly name = 'Flink Benchmark';
  readonly description = 'Tests Kafka + Flink processing (bypass Drools)';

  private kafkaClient: KafkaClient;
  private pendingEvents: Map<string, { sentAt: number; sessionId: string }> = new Map();
  private eventTimings: Map<string, number> = new Map();
  private eventsReceived = 0;

  constructor(kafkaClient: KafkaClient) {
    super();
    this.kafkaClient = kafkaClient;
  }

  protected reset(): void {
    super.reset();
    this.pendingEvents.clear();
    this.eventTimings.clear();
    this.eventsReceived = 0;
  }

  protected async runBenchmarkLoop(config: BenchmarkConfig): Promise<void> {
    // Generate batch of events
    const batchEvents = this.generateEventBatch(config.batchSize, config.concurrency);
    const sentAt = Date.now();

    try {
      // Publish to Kafka with fire-and-forget for max throughput
      // Events will flow through: Kafka -> Flink -> counter-results (PENDING)
      const traceIds = await this.kafkaClient.publishEventBatch(batchEvents, true);

      // Track events for latency measurement
      traceIds.forEach(id => this.eventTimings.set(id, sentAt));

      // Track pending events
      for (const event of batchEvents) {
        if (event.eventId) {
          this.pendingEvents.set(event.eventId, { sentAt, sessionId: event.sessionId });
          transactionTracker.markPublished(event.eventId);
        }
      }

      // We'll count success when results arrive via recordResult()

    } catch (error) {
      // Record batch failure
      for (let i = 0; i < config.batchSize; i++) {
        this.recordFailure();
      }

      this.addSampleEvent({
        id: `flink_${Date.now()}`,
        traceId: '',
        timestamp: sentAt,
        latencyMs: Date.now() - sentAt,
        status: 'error',
        error: String(error),
      });

      logger.warn('Flink batch publish failed', { error: String(error) });
    }

    // Small delay to prevent CPU spin
    await this.sleep(1);
  }

  private generateEventBatch(batchSize: number, sessions: number): CounterEvent[] {
    const now = Date.now();
    return Array.from({ length: batchSize }, (_, i) => {
      const sessionId = `flink-bench-${i % sessions}`;
      const eventId = transactionTracker.create(sessionId);

      return {
        sessionId,
        action: 'increment',
        value: 1,
        timestamp: now,
        eventId,
        // Add timing for component breakdown
        timing: {
          gatewayReceivedAt: now,
        },
      };
    });
  }

  /**
   * Record a result received from Kafka consumer (counter-results topic)
   * Results have alert="PENDING" since Drools hasn't run yet
   */
  recordResult(result: CounterResult): void {
    const receivedAt = Date.now();
    this.eventsReceived++;

    // Track latency by traceId
    let latency: number | undefined;
    if (result.traceId && this.eventTimings.has(result.traceId)) {
      const sentAt = this.eventTimings.get(result.traceId)!;
      latency = receivedAt - sentAt;
      this.recordLatency(latency);
      this.eventTimings.delete(result.traceId);
    }

    // Track success by eventId
    if (result.eventId && this.pendingEvents.has(result.eventId)) {
      const pending = this.pendingEvents.get(result.eventId)!;
      this.recordSuccess();

      // Calculate component timing from result
      const componentTiming = result.timing ? {
        gatewayMs: (result.timing.gatewayPublishedAt && result.timing.gatewayReceivedAt)
          ? result.timing.gatewayPublishedAt - result.timing.gatewayReceivedAt : 0,
        kafkaMs: (result.timing.flinkReceivedAt && result.timing.gatewayPublishedAt)
          ? result.timing.flinkReceivedAt - result.timing.gatewayPublishedAt : 0,
        flinkMs: (result.timing.flinkProcessedAt && result.timing.flinkReceivedAt)
          ? result.timing.flinkProcessedAt - result.timing.flinkReceivedAt : 0,
        droolsMs: 0, // Not used in this benchmark
      } : undefined;

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

  /**
   * Get pending event count for drain tracking
   */
  getPendingCount(): number {
    return this.pendingEvents.size;
  }
}
