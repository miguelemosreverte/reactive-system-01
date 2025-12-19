import { NodeSDK } from '@opentelemetry/sdk-node';
import { getNodeAutoInstrumentations } from '@opentelemetry/auto-instrumentations-node';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { OTLPMetricExporter } from '@opentelemetry/exporter-metrics-otlp-http';
import { Resource } from '@opentelemetry/resources';
import { SemanticResourceAttributes } from '@opentelemetry/semantic-conventions';
import { PeriodicExportingMetricReader } from '@opentelemetry/sdk-metrics';
import { diag, DiagConsoleLogger, DiagLogLevel, Context, SpanKind, Attributes, Link } from '@opentelemetry/api';
import { Sampler, SamplingDecision, SamplingResult } from '@opentelemetry/sdk-trace-base';

// Enable debug logging if needed
if (process.env.OTEL_DEBUG === 'true') {
  diag.setLogger(new DiagConsoleLogger(), DiagLogLevel.DEBUG);
}

/**
 * Adaptive Sampler - adjusts sampling rate based on throughput
 *
 * Like automatic transmission for traces:
 * - Low throughput: 100% sampling (full detail)
 * - Medium throughput: reduce sampling (protect observability stack)
 * - High throughput: minimal sampling (survive the load)
 *
 * Self-tuning every 10 seconds based on actual event rate.
 */
class AdaptiveSampler implements Sampler {
  private eventCount = 0;
  private lastResetTime = Date.now();
  private currentSamplingRate = 1.0; // Start with 100%
  private readonly tuningIntervalMs = 10000; // Re-tune every 10 seconds

  // Throughput thresholds and corresponding sampling rates
  private readonly thresholds = [
    { maxEventsPerSec: 10, rate: 1.0 },     // Low: 100%
    { maxEventsPerSec: 100, rate: 0.5 },    // Medium: 50%
    { maxEventsPerSec: 500, rate: 0.1 },    // High: 10%
    { maxEventsPerSec: 2000, rate: 0.01 },  // Very high: 1%
    { maxEventsPerSec: Infinity, rate: 0.001 } // Extreme: 0.1%
  ];

  shouldSample(
    context: Context,
    traceId: string,
    spanName: string,
    spanKind: SpanKind,
    attributes: Attributes,
    links: Link[]
  ): SamplingResult {
    // FAST PATH: Skip common non-interesting spans immediately (no counting)
    // This reduces overhead for high-throughput benchmark scenarios
    if (spanName.includes('health') ||
        spanName.includes('Health') ||
        spanName.includes('benchmark') ||
        spanName.includes('fetch') ||
        spanName === 'tcp.connect' ||
        spanName === 'dns.lookup' ||
        spanName === 'tls.connect') {
      return { decision: SamplingDecision.NOT_RECORD };
    }

    this.eventCount++;
    this.maybeTune();

    // Use trace ID for deterministic sampling (same trace = same decision)
    const hash = this.hashTraceId(traceId);
    const shouldSample = hash < this.currentSamplingRate;

    return {
      decision: shouldSample ? SamplingDecision.RECORD_AND_SAMPLED : SamplingDecision.NOT_RECORD,
      attributes: shouldSample ? { 'sampling.rate': this.currentSamplingRate } : undefined
    };
  }

  private maybeTune(): void {
    const now = Date.now();
    const elapsed = now - this.lastResetTime;

    if (elapsed >= this.tuningIntervalMs) {
      const eventsPerSec = (this.eventCount * 1000) / elapsed;
      const previousRate = this.currentSamplingRate;

      // Find appropriate sampling rate for current throughput
      for (const threshold of this.thresholds) {
        if (eventsPerSec <= threshold.maxEventsPerSec) {
          this.currentSamplingRate = threshold.rate;
          break;
        }
      }

      // Log if rate changed significantly
      if (Math.abs(this.currentSamplingRate - previousRate) > 0.01) {
        console.log(`[AdaptiveSampler] Throughput: ${eventsPerSec.toFixed(1)} events/sec, ` +
                    `sampling: ${(previousRate * 100).toFixed(1)}% → ${(this.currentSamplingRate * 100).toFixed(1)}%`);
      }

      // Reset counters
      this.eventCount = 0;
      this.lastResetTime = now;
    }
  }

  private hashTraceId(traceId: string): number {
    // Convert trace ID to a number between 0 and 1
    let hash = 0;
    for (let i = 0; i < traceId.length; i++) {
      hash = ((hash << 5) - hash) + traceId.charCodeAt(i);
      hash = hash & hash; // Convert to 32-bit integer
    }
    return Math.abs(hash) / 2147483647; // Normalize to 0-1
  }

  toString(): string {
    return `AdaptiveSampler{rate=${this.currentSamplingRate}}`;
  }

  // Expose current rate for metrics
  getCurrentRate(): number {
    return this.currentSamplingRate;
  }
}

// Export sampler instance for metrics access
export const adaptiveSampler = new AdaptiveSampler();

const otelEnabled = process.env.OTEL_ENABLED !== 'false';

let sdk: NodeSDK | null = null;

if (otelEnabled) {
  const serviceName = process.env.OTEL_SERVICE_NAME || 'gateway';
  const otlpEndpoint = process.env.OTEL_EXPORTER_OTLP_ENDPOINT || 'http://otel-collector:4318';

  const resource = new Resource({
    [SemanticResourceAttributes.SERVICE_NAME]: serviceName,
    [SemanticResourceAttributes.SERVICE_VERSION]: '1.0.0',
    [SemanticResourceAttributes.DEPLOYMENT_ENVIRONMENT]: process.env.NODE_ENV || 'development',
  });

  const traceExporter = new OTLPTraceExporter({
    url: `${otlpEndpoint}/v1/traces`,
  });

  const metricExporter = new OTLPMetricExporter({
    url: `${otlpEndpoint}/v1/metrics`,
  });

  sdk = new NodeSDK({
    resource,
    traceExporter,
    sampler: adaptiveSampler, // Use adaptive sampler
    metricReader: new PeriodicExportingMetricReader({
      exporter: metricExporter,
      exportIntervalMillis: 10000,
    }),
    instrumentations: [
      getNodeAutoInstrumentations({
        '@opentelemetry/instrumentation-fs': { enabled: false },
        '@opentelemetry/instrumentation-http': { enabled: true },
        '@opentelemetry/instrumentation-express': { enabled: true },
      }),
    ],
  });

  sdk.start();
  console.log('OpenTelemetry tracing initialized with ADAPTIVE SAMPLING for', serviceName);

  // Graceful shutdown
  process.on('SIGTERM', () => {
    sdk?.shutdown()
      .then(() => console.log('Tracing terminated'))
      .catch((error) => console.error('Error terminating tracing', error))
      .finally(() => process.exit(0));
  });
} else {
  console.log('OpenTelemetry tracing disabled');
}

export { sdk };
