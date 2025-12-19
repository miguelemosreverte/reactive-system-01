/**
 * HTTP Benchmark
 *
 * Tests Gateway HTTP endpoint latency in isolation.
 * Uses the /health endpoint to measure pure HTTP performance
 * without any downstream processing.
 */

import { BaseBenchmark } from './base-benchmark';
import { BenchmarkConfig, BenchmarkComponentId, SampleEvent } from './types';
import { logger } from '../logger';

const GATEWAY_URL = process.env.GATEWAY_URL || 'http://localhost:8080';

export class HttpBenchmark extends BaseBenchmark {
  readonly id: BenchmarkComponentId = 'http';
  readonly name = 'HTTP Benchmark';
  readonly description = 'Tests Gateway HTTP endpoint latency (health check, no downstream)';

  protected async runBenchmarkLoop(config: BenchmarkConfig): Promise<void> {
    // Run concurrent HTTP requests
    const promises: Promise<void>[] = [];

    for (let i = 0; i < config.concurrency; i++) {
      promises.push(this.makeRequest());
    }

    await Promise.all(promises);
  }

  private async makeRequest(): Promise<void> {
    const start = Date.now();
    const requestId = `http_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`;

    try {
      const response = await fetch(`${GATEWAY_URL}/health`);
      const latency = Date.now() - start;

      this.recordLatency(latency);

      if (response.ok) {
        this.recordSuccess();

        // Sample first 2 successful events
        if (this.sampleEvents.filter(e => e.status === 'success').length < 2) {
          this.addSampleEvent({
            id: requestId,
            traceId: '', // No trace for simple health check
            timestamp: start,
            latencyMs: latency,
            status: 'success',
          });
        }
      } else {
        this.recordFailure();

        // Sample up to 10 error events
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
