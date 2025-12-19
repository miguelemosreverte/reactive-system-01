/**
 * Kafka Benchmark
 *
 * Tests Kafka produce/consume round-trip performance.
 * Bypasses HTTP, Flink, and Drools to isolate Kafka latency.
 */

import { BaseBenchmark } from './base-benchmark';
import { BenchmarkConfig, BenchmarkComponentId, SampleEvent } from './types';
import { KafkaClient, CounterEvent, CounterResult } from '../kafka';
import { transactionTracker } from '../transaction-tracker';
import { logger } from '../logger';

export class KafkaBenchmark extends BaseBenchmark {
  readonly id: BenchmarkComponentId = 'kafka';
  readonly name = 'Kafka Benchmark';
  readonly description = 'Tests Kafka produce/consume round-trip (bypass HTTP, Flink, Drools)';

  private kafkaClient: KafkaClient;
  private pendingEvents: Map<string, { sentAt: number; sessionId: string }> = new Map();
  private eventTimings: Map<string, number> = new Map();

  constructor(kafkaClient: KafkaClient) {
    super();
    this.kafkaClient = kafkaClient;
  }

  /**
   * Set up result listener before running
   */
  async run(configOverride?: Partial<BenchmarkConfig>) {
    // Reset pending events
    this.pendingEvents.clear();
    this.eventTimings.clear();

    return super.run(configOverride);
  }

  protected async runBenchmarkLoop(config: BenchmarkConfig): Promise<void> {
    // Generate batch of events
    const batchEvents = this.generateEventBatch(config.batchSize, config.concurrency);
    const sentAt = Date.now();

    try {
      // Publish directly to Kafka (bypass HTTP)
      // fireAndForget=false means we wait for Kafka ack
      const traceIds = await this.kafkaClient.publishEventBatch(batchEvents, false);

      // Track events for latency measurement
      traceIds.forEach(id => this.eventTimings.set(id, sentAt));

      // Track pending events for success measurement
      for (const event of batchEvents) {
        if (event.eventId) {
          this.pendingEvents.set(event.eventId, { sentAt, sessionId: event.sessionId });
          transactionTracker.markPublished(event.eventId);
        }
      }

      // Record batch as successful (we got Kafka ack)
      for (let i = 0; i < config.batchSize; i++) {
        this.recordSuccess();
        // Kafka ack latency is the publish latency
        this.recordLatency(Date.now() - sentAt);
      }

      // Sample first 2 successful events
      if (batchEvents.length > 0 && this.sampleEvents.filter(e => e.status === 'success').length < 2) {
        const event = batchEvents[0];
        this.addSampleEvent({
          id: event.eventId || `kafka_${Date.now()}`,
          traceId: traceIds[0] || '',
          timestamp: sentAt,
          latencyMs: Date.now() - sentAt,
          status: 'success',
          componentTiming: {
            gatewayMs: 0,
            kafkaMs: Date.now() - sentAt,
            flinkMs: 0,
            droolsMs: 0,
          },
        });
      }
    } catch (error) {
      // Record batch failure
      for (let i = 0; i < config.batchSize; i++) {
        this.recordFailure();
      }

      // Sample error event
      this.addSampleEvent({
        id: `kafka_${Date.now()}`,
        traceId: '',
        timestamp: sentAt,
        latencyMs: Date.now() - sentAt,
        status: 'error',
        error: String(error),
      });

      logger.warn('Kafka batch publish failed', { error: String(error) });
    }

    // Small delay to prevent CPU spin
    await this.sleep(1);
  }

  private generateEventBatch(batchSize: number, sessions: number): CounterEvent[] {
    const now = Date.now();
    return Array.from({ length: batchSize }, (_, i) => {
      const sessionId = `kafka-bench-${i % sessions}`;
      const eventId = transactionTracker.create(sessionId);

      return {
        sessionId,
        action: 'increment',
        value: 1,
        timestamp: now,
        eventId,
      };
    });
  }

  /**
   * Record a result received from Kafka consumer
   * This is called by the benchmark runner when results arrive
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
      this.pendingEvents.delete(result.eventId);
    }
  }
}
