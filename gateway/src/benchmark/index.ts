/**
 * Benchmark System Exports
 *
 * Provides a unified interface for all component benchmarks.
 */

// Types
export * from './types';

// Base class
export { BaseBenchmark } from './base-benchmark';

// Component benchmarks
export { HttpBenchmark } from './http-benchmark';
export { KafkaBenchmark } from './kafka-benchmark';
export { FlinkBenchmark } from './flink-benchmark';
export { DroolsBenchmark } from './drools-benchmark';
export { GatewayBenchmark } from './gateway-benchmark';
export { FullBenchmark } from './full-benchmark';

// Observability
export { ObservabilityFetcher, getObservabilityFetcher } from './observability-fetcher';

// Registry
import { KafkaClient } from '../kafka';
import { BenchmarkInterface, BenchmarkComponentId, BENCHMARK_COMPONENTS } from './types';
import { HttpBenchmark } from './http-benchmark';
import { KafkaBenchmark } from './kafka-benchmark';
import { FlinkBenchmark } from './flink-benchmark';
import { DroolsBenchmark } from './drools-benchmark';
import { GatewayBenchmark } from './gateway-benchmark';
import { FullBenchmark } from './full-benchmark';

/**
 * Benchmark Registry
 *
 * Creates and manages benchmark instances.
 * Each benchmark is lazily instantiated when requested.
 */
export class BenchmarkRegistry {
  private benchmarks: Map<BenchmarkComponentId, BenchmarkInterface> = new Map();
  private kafkaClient: KafkaClient;

  constructor(kafkaClient: KafkaClient) {
    this.kafkaClient = kafkaClient;
  }

  /**
   * Get a benchmark by component ID
   */
  get(id: BenchmarkComponentId): BenchmarkInterface {
    if (!this.benchmarks.has(id)) {
      this.benchmarks.set(id, this.createBenchmark(id));
    }
    return this.benchmarks.get(id)!;
  }

  /**
   * Get all available benchmark IDs
   */
  getAvailableIds(): BenchmarkComponentId[] {
    return [...BENCHMARK_COMPONENTS];
  }

  /**
   * Get all benchmarks
   */
  getAll(): Map<BenchmarkComponentId, BenchmarkInterface> {
    // Ensure all are instantiated
    for (const id of BENCHMARK_COMPONENTS) {
      this.get(id);
    }
    return new Map(this.benchmarks);
  }

  /**
   * Check if any benchmark is currently running
   */
  isAnyRunning(): boolean {
    for (const benchmark of this.benchmarks.values()) {
      if (benchmark.isRunning()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Stop all running benchmarks
   */
  stopAll(): void {
    for (const benchmark of this.benchmarks.values()) {
      if (benchmark.isRunning()) {
        benchmark.stop();
      }
    }
  }

  /**
   * Record a result for benchmarks that need Kafka consumer results
   */
  recordResult(result: any): void {
    // Forward to benchmarks that need Kafka results
    const kafkaBench = this.benchmarks.get('kafka');
    if (kafkaBench?.isRunning() && (kafkaBench as any).recordResult) {
      (kafkaBench as any).recordResult(result);
    }

    const flinkBench = this.benchmarks.get('flink');
    if (flinkBench?.isRunning() && (flinkBench as any).recordResult) {
      (flinkBench as any).recordResult(result);
    }

    const fullBench = this.benchmarks.get('full');
    if (fullBench?.isRunning() && (fullBench as any).recordResult) {
      (fullBench as any).recordResult(result);
    }
  }

  private createBenchmark(id: BenchmarkComponentId): BenchmarkInterface {
    switch (id) {
      case 'http':
        return new HttpBenchmark();
      case 'kafka':
        return new KafkaBenchmark(this.kafkaClient);
      case 'flink':
        return new FlinkBenchmark(this.kafkaClient);
      case 'drools':
        return new DroolsBenchmark();
      case 'gateway':
        return new GatewayBenchmark();
      case 'full':
        return new FullBenchmark(this.kafkaClient);
      default:
        throw new Error(`Unknown benchmark component: ${id}`);
    }
  }
}

/**
 * Component metadata for UI/reports
 */
export const BENCHMARK_METADATA: Record<BenchmarkComponentId, {
  name: string;
  description: string;
  icon: string;
  color: string;
}> = {
  http: {
    name: 'HTTP',
    description: 'Gateway HTTP endpoint latency',
    icon: '🌐',
    color: '#3b82f6',
  },
  kafka: {
    name: 'Kafka',
    description: 'Kafka produce/consume round-trip',
    icon: '📨',
    color: '#8b5cf6',
  },
  flink: {
    name: 'Flink',
    description: 'Kafka + Flink processing',
    icon: '⚡',
    color: '#06b6d4',
  },
  drools: {
    name: 'Drools',
    description: 'Rule evaluation via direct HTTP',
    icon: '📜',
    color: '#10b981',
  },
  gateway: {
    name: 'Gateway',
    description: 'HTTP + Kafka publish (no wait)',
    icon: '🚪',
    color: '#f59e0b',
  },
  full: {
    name: 'Full End-to-End',
    description: 'HTTP → Kafka → Flink → Drools',
    icon: '📊',
    color: '#22c55e',
  },
};
