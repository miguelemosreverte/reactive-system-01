/**
 * Gateway Benchmark
 *
 * Tests Gateway HTTP + Kafka publish performance.
 * Events are sent via HTTP POST and published to Kafka,
 * but we don't wait for results (fire-and-forget).
 * This isolates Gateway + Kafka publish latency.
 */

import { BaseBenchmark } from './base-benchmark';
import { BenchmarkConfig, BenchmarkComponentId, SampleEvent } from './types';
import { logger } from '../logger';

const GATEWAY_URL = process.env.GATEWAY_URL || 'http://localhost:8080';

export class GatewayBenchmark extends BaseBenchmark {
  readonly id: BenchmarkComponentId = 'gateway';
  readonly name = 'Gateway Benchmark';
  readonly description = 'Tests Gateway HTTP + Kafka publish (no wait for result)';

  protected async runBenchmarkLoop(config: BenchmarkConfig): Promise<void> {
    // Run concurrent Gateway requests
    const promises: Promise<void>[] = [];

    for (let i = 0; i < config.concurrency; i++) {
      promises.push(this.sendEvent(i));
    }

    await Promise.all(promises);
  }

  private async sendEvent(index: number): Promise<void> {
    const start = Date.now();
    const sessionId = `gateway-bench-${index}`;

    try {
      // POST to Gateway fast endpoint (bypasses tracing, uses fire-and-forget Kafka)
      const response = await fetch(`${GATEWAY_URL}/api/counter/fast`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          action: 'increment',
          value: 1,
          sessionId,
        }),
      });

      const latency = Date.now() - start;
      this.recordLatency(latency);

      if (response.ok) {
        const result = await response.json() as { id: string; traceId: string; status: string };
        this.recordSuccess();

        // Sample first 2 successful events
        if (this.sampleEvents.filter(e => e.status === 'success').length < 2) {
          this.addSampleEvent({
            id: result.id || `gateway_${Date.now()}`,
            traceId: result.traceId || '',
            timestamp: start,
            latencyMs: latency,
            status: 'success',
            componentTiming: {
              gatewayMs: latency, // All measured time is Gateway + Kafka publish
              kafkaMs: 0, // Can't separate without consuming
              flinkMs: 0,
              droolsMs: 0,
            },
          });
        }
      } else {
        this.recordFailure();

        this.addSampleEvent({
          id: `gateway_${Date.now()}`,
          traceId: '',
          timestamp: start,
          latencyMs: latency,
          status: 'error',
          error: `HTTP ${response.status}: ${response.statusText}`,
        });
      }
    } catch (error) {
      const latency = Date.now() - start;
      this.recordLatency(latency);
      this.recordFailure();

      this.addSampleEvent({
        id: `gateway_${Date.now()}`,
        traceId: '',
        timestamp: start,
        latencyMs: latency,
        status: 'error',
        error: String(error),
      });
    }
  }
}
