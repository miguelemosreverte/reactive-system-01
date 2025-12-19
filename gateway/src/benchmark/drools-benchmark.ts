/**
 * Drools Benchmark
 *
 * Tests Drools rule evaluation performance via direct HTTP.
 * Bypasses Gateway, Kafka, and Flink to isolate rule evaluation latency.
 */

import { BaseBenchmark } from './base-benchmark';
import { BenchmarkConfig, BenchmarkComponentId, SampleEvent } from './types';
import { logger } from '../logger';

// In Docker, DROOLS_URL should be set to http://drools:8080
// The fallback 'http://drools:8080' works in Docker; 'localhost:8180' works on host
const DROOLS_URL = process.env.DROOLS_URL || 'http://drools:8080';

export class DroolsBenchmark extends BaseBenchmark {
  readonly id: BenchmarkComponentId = 'drools';
  readonly name = 'Drools Benchmark';
  readonly description = 'Tests Drools rule evaluation via direct HTTP. NOTE: In CQRS architecture, Drools processes global snapshots periodically (decoupled from main event flow). This benchmark measures raw capacity, not a bottleneck.';

  protected async runBenchmarkLoop(config: BenchmarkConfig): Promise<void> {
    // Run concurrent Drools requests
    const promises: Promise<void>[] = [];

    for (let i = 0; i < config.concurrency; i++) {
      promises.push(this.evaluateRule(i));
    }

    await Promise.all(promises);
  }

  private async evaluateRule(index: number): Promise<void> {
    const start = Date.now();
    const requestId = `drools_${Date.now()}_${index}`;
    const sessionId = `drools-bench-${index}`;

    try {
      // Call Drools evaluate endpoint directly
      const response = await fetch(`${DROOLS_URL}/api/evaluate`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          sessionId,
          currentValue: 100 + index, // Vary values to exercise different rules
        }),
      });

      const latency = Date.now() - start;
      this.recordLatency(latency);

      if (response.ok) {
        const result = await response.json() as { traceId?: string; alert?: string };
        this.recordSuccess();

        // Sample first 2 successful events
        if (this.sampleEvents.filter(e => e.status === 'success').length < 2) {
          this.addSampleEvent({
            id: requestId,
            traceId: result.traceId || '',
            timestamp: start,
            latencyMs: latency,
            status: 'success',
            componentTiming: {
              gatewayMs: 0,
              kafkaMs: 0,
              flinkMs: 0,
              droolsMs: latency, // All time spent in Drools
            },
          });
        }
      } else {
        this.recordFailure();

        this.addSampleEvent({
          id: requestId,
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
        id: requestId,
        traceId: '',
        timestamp: start,
        latencyMs: latency,
        status: 'error',
        error: String(error),
      });
    }
  }
}
