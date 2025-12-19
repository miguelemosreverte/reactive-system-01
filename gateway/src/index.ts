import './tracing'; // Must be first import for OTEL initialization
import express, { Request, Response, NextFunction } from 'express';
import { createServer } from 'http';
import { WebSocketServer } from './websocket';
import { KafkaClient } from './kafka';
import { logger } from './logger';
import { AutoBenchmark, shouldRunBenchmark, getBenchmarkConfig } from './benchmark';
import { LayeredBenchmark, LayeredBenchmarkResult, BenchmarkLayer } from './benchmark-layers';
import { transactionTracker } from './transaction-tracker';
import {
  BenchmarkRegistry,
  BenchmarkComponentId,
  BENCHMARK_COMPONENTS,
  BENCHMARK_METADATA,
  BenchmarkResult,
  BenchmarkProgress,
  getObservabilityFetcher,
} from './benchmark/index';
import { trace, SpanKind, SpanStatusCode } from '@opentelemetry/api';

const PORT = parseInt(process.env.PORT || '8080', 10);
const KAFKA_BROKERS = (process.env.KAFKA_BROKERS || 'kafka:29092').split(',');
const ADMIN_API_KEY = process.env.ADMIN_API_KEY || 'reactive-admin-key';
const OTEL_ENABLED = process.env.OTEL_ENABLED !== 'false';
const JAEGER_QUERY_URL = process.env.JAEGER_QUERY_URL || 'http://jaeger:16686';
const SERVICE_VERSION = process.env.SERVICE_VERSION || '1.0.0';
const BUILD_TIME = process.env.BUILD_TIME || new Date().toISOString();

const tracer = trace.getTracer('gateway-service');

// Admin authentication middleware
const adminAuth = (req: Request, res: Response, next: NextFunction): void => {
    const apiKey = req.headers['x-api-key'] || req.query.apiKey;
    if (apiKey !== ADMIN_API_KEY) {
        res.status(401).json({ error: 'Unauthorized - Invalid API key' });
        return;
    }
    next();
};

// State to track latest counter value per session with traceId
const counterState: Map<string, { value: number; alert: string; message: string; traceId?: string }> = new Map();

// Store recent trace IDs for lookup (ring buffer of last 1000)
const recentTraces: { traceId: string; sessionId: string; timestamp: number; action: string }[] = [];
const MAX_TRACES = 1000;

// Benchmark state
let benchmark: AutoBenchmark | null = null;
let benchmarkResult: any = null;
const benchmarkResultCallbacks: ((traceId: string) => void)[] = [];

// Layered benchmark state
let layeredBenchmark: LayeredBenchmark | null = null;
let layeredBenchmarkResult: LayeredBenchmarkResult | null = null;

// New component benchmark registry
let benchmarkRegistry: BenchmarkRegistry | null = null;
const componentBenchmarkResults: Map<BenchmarkComponentId, BenchmarkResult> = new Map();

async function main() {
    const app = express();
    app.use(express.json());

    // Declare kafkaClient early so endpoint handlers can reference it
    let kafkaClient: KafkaClient;

    // Health endpoint with version info
    app.get('/health', (req, res) => {
        res.json({
            status: 'UP',
            service: 'gateway',
            version: SERVICE_VERSION,
            buildTime: BUILD_TIME,
            timestamp: Date.now(),
            observability: {
                otelEnabled: OTEL_ENABLED,
                jaegerUrl: JAEGER_QUERY_URL
            }
        });
    });

    // Observability status endpoint
    app.get('/api/observability/status', (req, res) => {
        res.json({
            otelEnabled: OTEL_ENABLED,
            jaegerQueryUrl: JAEGER_QUERY_URL,
            recentTracesCount: recentTraces.length,
            activeSessionsCount: counterState.size
        });
    });

    // Get recent traces (admin only)
    app.get('/api/admin/traces', adminAuth, (req, res) => {
        const limit = Math.min(parseInt(req.query.limit as string) || 50, 100);
        const sessionId = req.query.sessionId as string;

        let traces = [...recentTraces].reverse();
        if (sessionId) {
            traces = traces.filter(t => t.sessionId === sessionId);
        }

        res.json({
            traces: traces.slice(0, limit),
            total: traces.length
        });
    });

    // Query specific trace from Jaeger (admin only)
    app.get('/api/admin/traces/:traceId', adminAuth, async (req, res) => {
        const { traceId } = req.params;

        try {
            const response = await fetch(`${JAEGER_QUERY_URL}/api/traces/${traceId}`);
            if (!response.ok) {
                res.status(response.status).json({ error: 'Trace not found or Jaeger unavailable' });
                return;
            }
            const data = await response.json();
            res.json(data);
        } catch (error) {
            logger.error('Failed to query Jaeger', error);
            res.status(500).json({ error: 'Failed to query trace', details: String(error) });
        }
    });

    // Search traces by service (admin only)
    app.get('/api/admin/traces/search/:service', adminAuth, async (req, res) => {
        const { service } = req.params;
        const limit = req.query.limit || '20';
        const lookback = req.query.lookback || '1h';

        try {
            const response = await fetch(
                `${JAEGER_QUERY_URL}/api/traces?service=${service}&limit=${limit}&lookback=${lookback}`
            );
            if (!response.ok) {
                res.status(response.status).json({ error: 'Search failed or Jaeger unavailable' });
                return;
            }
            const data = await response.json();
            res.json(data);
        } catch (error) {
            logger.error('Failed to search Jaeger', error);
            res.status(500).json({ error: 'Failed to search traces', details: String(error) });
        }
    });

    // Get all services from Jaeger (admin only)
    app.get('/api/admin/services', adminAuth, async (req, res) => {
        try {
            const response = await fetch(`${JAEGER_QUERY_URL}/api/services`);
            if (!response.ok) {
                res.status(response.status).json({ error: 'Jaeger unavailable' });
                return;
            }
            const data = await response.json();
            res.json(data);
        } catch (error) {
            logger.error('Failed to get services from Jaeger', error);
            res.status(500).json({ error: 'Failed to get services', details: String(error) });
        }
    });

    // High-performance counter endpoint for benchmarks
    // Skips tracing, uses fire-and-forget Kafka, minimal overhead
    app.post('/api/counter/fast', (req, res) => {
        const { action, value, sessionId = 'default' } = req.body;

        try {
            const traceId = kafkaClient.publishEventFireAndForget({
                sessionId,
                action: action || 'set',
                value: value || 0,
                timestamp: Date.now(),
            });

            res.json({ success: true, traceId, status: 'accepted' });
        } catch (error) {
            res.status(500).json({ success: false, error: 'Failed to publish' });
        }
    });

    // REST API for counter operations (with full tracing)
    app.post('/api/counter', async (req, res) => {
        const { action, value, sessionId = 'default' } = req.body;
        const gatewayReceivedAt = Date.now();

        // Fast path: skip tracing for benchmark traffic (detected by header or session prefix)
        const isBenchmark = req.headers['x-benchmark'] === 'true' ||
                           sessionId.startsWith('bench-') ||
                           sessionId.startsWith('gateway-bench-') ||
                           sessionId.startsWith('full-bench-');

        if (isBenchmark) {
            try {
                // Create eventId for tracking (needed for Full E2E benchmark)
                const eventId = transactionTracker.create(sessionId);

                const traceId = kafkaClient.publishEventFireAndForget({
                    sessionId,
                    action: action || 'set',
                    value: value || 0,
                    timestamp: Date.now(),
                    eventId,
                    timing: { gatewayReceivedAt }
                });

                // Mark as published for transaction tracking
                transactionTracker.markPublished(eventId);

                res.json({ success: true, traceId, status: 'accepted', id: eventId });
            } catch (error) {
                res.status(500).json({ success: false, error: 'Failed to publish' });
            }
            return;
        }

        // Normal path with full tracing
        tracer.startActiveSpan('http.counter.action', { kind: SpanKind.SERVER }, async (span) => {
            try {
                // Create transaction and get event ID
                const eventId = transactionTracker.create(sessionId);

                span.setAttributes({
                    'http.method': 'POST',
                    'http.route': '/api/counter',
                    'session.id': sessionId,
                    'counter.action': action || 'set',
                    'counter.value': value || 0,
                    'event.id': eventId
                });

                const traceId = await kafkaClient.publishEvent({
                    sessionId,
                    action: action || 'set',
                    value: value || 0,
                    timestamp: Date.now(),
                    eventId,
                    timing: {
                        gatewayReceivedAt
                    }
                });

                // Mark as published with timing
                transactionTracker.markPublished(eventId);

                // Store trace for lookup
                recentTraces.push({
                    traceId,
                    sessionId,
                    timestamp: Date.now(),
                    action: action || 'set'
                });
                if (recentTraces.length > MAX_TRACES) {
                    recentTraces.shift();
                }

                span.setStatus({ code: SpanStatusCode.OK });
                res.json({
                    success: true,
                    id: eventId,
                    status: 'accepted',
                    traceId
                });
            } catch (error) {
                span.setStatus({ code: SpanStatusCode.ERROR, message: String(error) });
                span.recordException(error as Error);
                logger.error('Failed to publish event', error, { sessionId, action });
                res.status(500).json({ success: false, error: 'Failed to publish event' });
            } finally {
                span.end();
            }
        });
    });

    // Get current counter status
    app.get('/api/counter/status', (req, res) => {
        const sessionId = (req.query.sessionId as string) || 'default';
        const state = counterState.get(sessionId) || { value: 0, alert: 'NONE', message: 'No events processed yet' };
        res.json(state);
    });

    // Track transaction by event ID
    app.get('/api/track/:id', (req, res) => {
        const { id } = req.params;
        const tx = transactionTracker.get(id);

        if (!tx) {
            res.status(404).json({
                error: 'Transaction not found',
                id,
                message: 'Transaction may have expired or never existed'
            });
            return;
        }

        res.json({
            id: tx.id,
            sessionId: tx.sessionId,
            status: tx.status,
            stages: {
                gateway: tx.timing.gatewayReceivedAt ? {
                    receivedAt: tx.timing.gatewayReceivedAt,
                    publishedAt: tx.timing.gatewayPublishedAt
                } : undefined,
                flink: tx.timing.flinkReceivedAt ? {
                    receivedAt: tx.timing.flinkReceivedAt,
                    processedAt: tx.timing.flinkProcessedAt
                } : undefined,
                drools: tx.timing.droolsStartAt ? {
                    startAt: tx.timing.droolsStartAt,
                    endAt: tx.timing.droolsEndAt
                } : undefined
            },
            breakdown: tx.breakdown,
            result: tx.result,
            createdAt: tx.createdAt,
            completedAt: tx.completedAt
        });
    });

    // Get transaction tracker stats (admin)
    app.get('/api/admin/transactions/stats', adminAuth, (req, res) => {
        res.json(transactionTracker.getStats());
    });

    // Get pipeline lag statistics (for monitoring backpressure)
    app.get('/api/lag', (req, res) => {
        const stats = kafkaClient.getLagStats();
        res.json({
            ...stats,
            backpressureActive: stats.lag > 100000,
            recommendation: stats.lag > 100000 ? 'Slow down requests' : 'OK'
        });
    });

    // Bulk API endpoint for high-throughput load testing
    // POST /api/counter/bulk - sends multiple events in a single batch
    // IMPORTANT: Distributes events across multiple sessions to utilize all Kafka partitions
    app.post('/api/counter/bulk', async (req, res) => {
        const { count = 100, sessions = 8, fireAndForget = true } = req.body;
        const batchSize = Math.min(Math.max(1, count), 10000); // Cap at 10K per request
        const numSessions = Math.min(Math.max(1, sessions), 100); // 1-100 sessions

        // Check backpressure - reject if system is overloaded
        const lag = kafkaClient.getConsumerLag();
        if (lag > 100000) { // More than 100K messages behind
            res.status(429).json({
                success: false,
                error: 'System overloaded - backpressure active',
                consumerLag: lag,
                retryAfterMs: 1000
            });
            return;
        }

        try {
            // Distribute events across multiple sessions for parallelism
            const events = Array.from({ length: batchSize }, (_, i) => ({
                sessionId: `bulk-${i % numSessions}`, // Round-robin across sessions
                action: 'increment',
                value: 1,
                timestamp: Date.now()
            }));

            const startTime = Date.now();
            const traceIds = await kafkaClient.publishEventBatch(events, fireAndForget);
            const duration = Date.now() - startTime;

            res.json({
                success: true,
                count: batchSize,
                sessions: numSessions,
                durationMs: duration,
                throughput: Math.round(batchSize / (duration / 1000)),
                fireAndForget,
                consumerLag: lag,
                firstTraceId: traceIds[0]
            });
        } catch (error) {
            logger.error('Bulk publish failed', error);
            res.status(500).json({ success: false, error: 'Bulk publish failed' });
        }
    });

    // Benchmark endpoints (admin only)
    app.get('/api/admin/benchmark/status', adminAuth, (req, res) => {
        res.json({
            running: benchmark?.isRunning() || layeredBenchmark?.isRunning() || false,
            lastResult: layeredBenchmarkResult || benchmarkResult,
            autoStart: shouldRunBenchmark(),
            layered: {
                running: layeredBenchmark?.isRunning() || false,
                config: layeredBenchmark?.getConfig() || null,
                lastResult: layeredBenchmarkResult
            }
        });
    });

    app.post('/api/admin/benchmark/start', adminAuth, async (req, res) => {
        if (benchmark?.isRunning()) {
            res.status(400).json({ error: 'Benchmark already running' });
            return;
        }

        // kafkaClient is initialized later in main(), but will be available when this handler runs
        if (!kafkaClient) {
            res.status(503).json({ error: 'Service not ready' });
            return;
        }

        // Initialize benchmark with current kafkaClient (with batch methods for high throughput)
        benchmark = new AutoBenchmark(
            async (action, value) => {
                return kafkaClient.publishEvent({
                    sessionId: 'benchmark',
                    action,
                    value,
                    timestamp: Date.now()
                });
            },
            (callback) => {
                benchmarkResultCallbacks.push(callback);
            },
            { ...getBenchmarkConfig(), ...req.body },
            // Batch publishing for high throughput
            async (events) => {
                return kafkaClient.publishEventBatch(
                    events.map(e => ({
                        sessionId: 'benchmark',
                        action: e.action,
                        value: e.value,
                        timestamp: Date.now()
                    })),
                    true // fire-and-forget for max speed
                );
            },
            // Fire-and-forget individual publishing
            (action, value) => {
                return kafkaClient.publishEventFireAndForget({
                    sessionId: 'benchmark',
                    action,
                    value,
                    timestamp: Date.now()
                });
            }
        );

        // Start async
        benchmark.start().then(result => {
            benchmarkResult = result;
            logger.info('Benchmark completed', { reason: result.reason });
        }).catch(err => {
            logger.error('Benchmark failed', err);
        });

        res.json({ message: 'Benchmark started', config: getBenchmarkConfig() });
    });

    app.post('/api/admin/benchmark/stop', adminAuth, (req, res) => {
        if (benchmark?.isRunning()) {
            benchmark.stop();
            res.json({ message: 'Benchmark stopping' });
        } else if (layeredBenchmark?.isRunning()) {
            layeredBenchmark.stop();
            res.json({ message: 'Layered benchmark stopping' });
        } else {
            res.status(400).json({ error: 'No benchmark running' });
        }
    });

    // Layered benchmark endpoint
    app.post('/api/admin/benchmark/layered', adminAuth, async (req, res) => {
        if (benchmark?.isRunning() || layeredBenchmark?.isRunning()) {
            res.status(400).json({ error: 'Another benchmark is already running' });
            return;
        }

        if (!kafkaClient) {
            res.status(503).json({ error: 'Service not ready' });
            return;
        }

        const { layer = 'full', durationMs = 30000, targetEventCount = 0 } = req.body;

        // Validate layer
        const validLayers: BenchmarkLayer[] = ['kafka', 'kafka-flink', 'kafka-flink-drools', 'full'];
        if (!validLayers.includes(layer)) {
            res.status(400).json({
                error: 'Invalid layer',
                validLayers
            });
            return;
        }

        // Initialize layered benchmark
        layeredBenchmark = new LayeredBenchmark(kafkaClient);

        // Configure benchmark
        layeredBenchmark.configure({
            layer,
            durationMs,
            targetEventCount,
            batchSize: 100,
            sessions: 8,
            warmupMs: 3000,
            cooldownMs: 2000
        });

        // Set up progress logging
        layeredBenchmark.onProgress((progress) => {
            logger.debug('Benchmark progress', { ...progress });
        });

        // Start async
        layeredBenchmark.run().then(result => {
            layeredBenchmarkResult = result;
            benchmarkResult = result; // Also store in legacy result for compatibility
            logger.info('Layered benchmark completed', {
                layer: result.layer,
                peakThroughput: result.peakThroughput,
                status: result.status
            });
        }).catch(err => {
            logger.error('Layered benchmark failed', err);
        });

        res.json({
            message: 'Layered benchmark started',
            config: {
                layer,
                durationMs,
                targetEventCount
            }
        });
    });

    // =========================================================================
    // NEW: Component Benchmark Endpoints
    // =========================================================================

    // Get available benchmark components
    app.get('/api/admin/benchmark/components', adminAuth, (req, res) => {
        res.json({
            components: BENCHMARK_COMPONENTS.map((id: BenchmarkComponentId) => ({
                id,
                ...BENCHMARK_METADATA[id],
            })),
        });
    });

    // Run all benchmarks in sequence
    app.post('/api/admin/benchmark/all', adminAuth, async (req, res) => {
        if (benchmarkRegistry?.isAnyRunning() || benchmark?.isRunning() || layeredBenchmark?.isRunning()) {
            res.status(400).json({ error: 'Another benchmark is already running' });
            return;
        }

        if (!kafkaClient) {
            res.status(503).json({ error: 'Service not ready' });
            return;
        }

        if (!benchmarkRegistry) {
            benchmarkRegistry = new BenchmarkRegistry(kafkaClient);
        }

        const { durationMs = 30000 } = req.body;

        // Run benchmarks in sequence (background)
        (async () => {
            const fetcher = getObservabilityFetcher();

            for (const component of BENCHMARK_COMPONENTS) {
                try {
                    logger.info(`Starting ${component} benchmark`);
                    const benchmarkInstance = benchmarkRegistry!.get(component);
                    const result = await benchmarkInstance.run({
                        durationMs,
                        targetEventCount: 0,
                        concurrency: 8,
                        batchSize: 100,
                        warmupMs: 3000,
                        cooldownMs: 2000,
                    });

                    // Enrich with trace/log data
                    result.sampleEvents = await fetcher.enrichSampleEvents(
                        result.sampleEvents,
                        { start: result.startTime, end: result.endTime }
                    );

                    componentBenchmarkResults.set(component, result);
                    logger.info(`${component} benchmark completed`, {
                        peakThroughput: result.peakThroughput,
                        status: result.status,
                    });

                    // Small delay between benchmarks
                    await new Promise(resolve => setTimeout(resolve, 5000));
                } catch (err) {
                    logger.error(`${component} benchmark failed`, { error: err });
                }
            }

            logger.info('All benchmarks completed');
        })();

        res.json({
            message: 'Running all benchmarks in sequence',
            components: BENCHMARK_COMPONENTS,
            config: { durationMs },
        });
    });

    // Run specific component benchmark
    app.post('/api/admin/benchmark/:component', adminAuth, async (req, res) => {
        const component = req.params.component as BenchmarkComponentId;

        // Validate component
        if (!BENCHMARK_COMPONENTS.includes(component)) {
            res.status(400).json({
                error: 'Invalid component',
                validComponents: BENCHMARK_COMPONENTS,
            });
            return;
        }

        // Check if another benchmark is running
        if (benchmarkRegistry?.isAnyRunning() || benchmark?.isRunning() || layeredBenchmark?.isRunning()) {
            res.status(400).json({ error: 'Another benchmark is already running' });
            return;
        }

        if (!kafkaClient) {
            res.status(503).json({ error: 'Service not ready' });
            return;
        }

        // Initialize registry if needed
        if (!benchmarkRegistry) {
            benchmarkRegistry = new BenchmarkRegistry(kafkaClient);
        }

        const { durationMs = 30000, targetEventCount = 0, concurrency = 8, batchSize = 100 } = req.body;

        const benchmarkInstance = benchmarkRegistry.get(component);

        // Set up progress logging
        benchmarkInstance.onProgress((progress: BenchmarkProgress) => {
            logger.debug('Component benchmark progress', { component, ...progress });
        });

        // Start async
        benchmarkInstance.run({
            durationMs,
            targetEventCount,
            concurrency,
            batchSize,
            warmupMs: 3000,
            cooldownMs: 2000,
        }).then(async (result: BenchmarkResult) => {
            // Enrich sample events with trace/log data
            const fetcher = getObservabilityFetcher();
            const enrichedEvents = await fetcher.enrichSampleEvents(
                result.sampleEvents,
                { start: result.startTime, end: result.endTime }
            );
            result.sampleEvents = enrichedEvents;

            componentBenchmarkResults.set(component, result);
            logger.info('Component benchmark completed', {
                component,
                peakThroughput: result.peakThroughput,
                status: result.status,
            });
        }).catch((err: Error) => {
            logger.error('Component benchmark failed', { component, error: err });
        });

        res.json({
            message: `${BENCHMARK_METADATA[component].name} benchmark started`,
            component,
            config: { durationMs, targetEventCount, concurrency, batchSize },
        });
    });

    // Stop component benchmark
    app.post('/api/admin/benchmark/:component/stop', adminAuth, (req, res) => {
        const component = req.params.component as BenchmarkComponentId;

        if (!BENCHMARK_COMPONENTS.includes(component)) {
            res.status(400).json({ error: 'Invalid component' });
            return;
        }

        if (!benchmarkRegistry) {
            res.status(400).json({ error: 'No benchmark registry initialized' });
            return;
        }

        const benchmarkInstance = benchmarkRegistry.get(component);
        if (benchmarkInstance.isRunning()) {
            benchmarkInstance.stop();
            res.json({ message: `${BENCHMARK_METADATA[component].name} benchmark stopping` });
        } else {
            res.status(400).json({ error: 'Benchmark not running' });
        }
    });

    // Get component benchmark result
    app.get('/api/admin/benchmark/:component/result', adminAuth, (req, res) => {
        const component = req.params.component as BenchmarkComponentId;

        if (!BENCHMARK_COMPONENTS.includes(component)) {
            res.status(400).json({ error: 'Invalid component' });
            return;
        }

        const result = componentBenchmarkResults.get(component);
        if (!result) {
            res.status(404).json({ error: 'No result available for this component' });
            return;
        }

        res.json(result);
    });

    // Get all benchmark results
    app.get('/api/admin/benchmark/results', adminAuth, (req, res) => {
        const results: Record<string, BenchmarkResult> = {};
        for (const [component, result] of componentBenchmarkResults) {
            results[component] = result;
        }
        res.json({
            results,
            components: BENCHMARK_COMPONENTS,
        });
    });

    // Create HTTP server
    const server = createServer(app);

    // Initialize Kafka client
    kafkaClient = new KafkaClient(KAFKA_BROKERS);

    // Initialize WebSocket server
    const wsServer = new WebSocketServer(server);

    // Connect to Kafka
    await kafkaClient.connect();

    // Subscribe to counter results and alerts (CQRS architecture)
    // - counter-results: Immediate results with alert="PENDING"
    // - counter-alerts: Actual alerts from Drools evaluation
    await kafkaClient.subscribeToResults(
        // Handle immediate results (write side)
        (result) => {
            logger.debug('Received counter result', {
                sessionId: result.sessionId,
                currentValue: result.currentValue,
                alert: result.alert,
                traceId: result.traceId,
                eventId: result.eventId
            });

            // Update state value immediately (alert will be updated by alerts callback)
            const currentState = counterState.get(result.sessionId);
            counterState.set(result.sessionId, {
                value: result.currentValue,
                alert: currentState?.alert || result.alert,  // Keep existing alert until updated
                message: currentState?.message || result.message,
                traceId: result.traceId
            });

            // Update transaction tracker with Flink timing (if eventId present)
            if (result.eventId && result.timing) {
                transactionTracker.updateFlinkTiming(
                    result.eventId,
                    result.timing.flinkReceivedAt || Date.now(),
                    result.timing.flinkProcessedAt || Date.now()
                );
            }

            // Broadcast immediate counter update to WebSocket clients
            wsServer.broadcast(result.sessionId, {
                type: 'counter-update',
                data: {
                    ...result,
                    traceId: result.traceId,
                    eventId: result.eventId
                }
            });

            // Notify benchmark callbacks (for latency tracking)
            if (result.traceId) {
                benchmarkResultCallbacks.forEach(cb => cb(result.traceId!));
            }

            // Record result in layered benchmark (for latency tracking)
            if (layeredBenchmark?.isRunning()) {
                layeredBenchmark.recordResult(result);
            }

            // Forward to component benchmark registry (for kafka/flink benchmarks)
            if (benchmarkRegistry) {
                benchmarkRegistry.recordResult(result);
            }
        },
        // Handle alerts (read side - from bounded snapshot evaluation)
        (alert) => {
            logger.info('Received alert', {
                sessionId: alert.sessionId,
                currentValue: alert.currentValue,
                alert: alert.alert,
                message: alert.message,
                traceId: alert.traceId,
                eventId: alert.eventId
            });

            // Update state with alert
            counterState.set(alert.sessionId, {
                value: alert.currentValue,
                alert: alert.alert,
                message: alert.message,
                traceId: alert.traceId
            });

            // Complete transaction with Drools timing (if eventId present)
            if (alert.eventId) {
                const droolsTiming = alert.timing ? {
                    startAt: alert.timing.droolsStartAt || Date.now(),
                    endAt: alert.timing.droolsEndAt || Date.now()
                } : undefined;

                const flinkTiming = alert.timing ? {
                    receivedAt: alert.timing.flinkReceivedAt || Date.now(),
                    processedAt: alert.timing.flinkProcessedAt || Date.now()
                } : undefined;

                transactionTracker.complete(
                    alert.eventId,
                    { value: alert.currentValue, alert: alert.alert, message: alert.message },
                    flinkTiming,
                    droolsTiming
                );
            }

            // Broadcast alert to WebSocket clients (different message type)
            wsServer.broadcast(alert.sessionId, {
                type: 'counter-alert',
                data: {
                    sessionId: alert.sessionId,
                    currentValue: alert.currentValue,
                    alert: alert.alert,
                    message: alert.message,
                    traceId: alert.traceId,
                    eventId: alert.eventId
                }
            });

            // Forward to component benchmark registry (for full benchmark with Drools timing)
            if (benchmarkRegistry) {
                benchmarkRegistry.recordResult(alert);
            }
        }
    );

    // Handle WebSocket messages
    wsServer.onMessage(async (sessionId, message) => {
        logger.debug('WebSocket message received', { sessionId, messageType: message.type });

        if (message.type === 'counter-action') {
            tracer.startActiveSpan('websocket.counter.action', { kind: SpanKind.SERVER }, async (span) => {
                try {
                    span.setAttributes({
                        'websocket.message_type': message.type,
                        'session.id': sessionId,
                        'counter.action': message.action || 'increment',
                        'counter.value': message.value || 1
                    });

                    const traceId = await kafkaClient.publishEvent({
                        sessionId,
                        action: message.action || 'increment',
                        value: message.value || 1,
                        timestamp: Date.now()
                    });

                    // Store trace for lookup
                    recentTraces.push({
                        traceId,
                        sessionId,
                        timestamp: Date.now(),
                        action: message.action || 'increment'
                    });
                    if (recentTraces.length > MAX_TRACES) {
                        recentTraces.shift();
                    }

                    // Send ack with traceId to client
                    wsServer.sendTo(sessionId, {
                        type: 'action-ack',
                        traceId,
                        timestamp: Date.now()
                    });

                    span.setStatus({ code: SpanStatusCode.OK });
                } catch (error) {
                    span.setStatus({ code: SpanStatusCode.ERROR, message: String(error) });
                    span.recordException(error as Error);
                } finally {
                    span.end();
                }
            });
        } else if (message.type === 'ping') {
            wsServer.sendTo(sessionId, { type: 'pong', timestamp: Date.now() });
        }
    });

    // Start server
    server.listen(PORT, () => {
        logger.info('Gateway server started', {
            port: PORT,
            healthCheck: `http://localhost:${PORT}/health`,
            websocket: `ws://localhost:${PORT}/ws`
        });

        // Auto-start benchmark in development mode
        if (shouldRunBenchmark()) {
            logger.info('Development mode: Auto-benchmark will start in 10 seconds');
            setTimeout(() => {
                benchmark = new AutoBenchmark(
                    async (action, value) => {
                        return kafkaClient.publishEvent({
                            sessionId: 'benchmark',
                            action,
                            value,
                            timestamp: Date.now()
                        });
                    },
                    (callback) => {
                        benchmarkResultCallbacks.push(callback);
                    },
                    getBenchmarkConfig(),
                    // Batch publishing for high throughput
                    async (events) => {
                        return kafkaClient.publishEventBatch(
                            events.map(e => ({
                                sessionId: 'benchmark',
                                action: e.action,
                                value: e.value,
                                timestamp: Date.now()
                            })),
                            true // fire-and-forget for max speed
                        );
                    },
                    // Fire-and-forget individual publishing
                    (action, value) => {
                        return kafkaClient.publishEventFireAndForget({
                            sessionId: 'benchmark',
                            action,
                            value,
                            timestamp: Date.now()
                        });
                    }
                );

                benchmark.start().then(result => {
                    benchmarkResult = result;
                    logger.info('Auto-benchmark completed', {
                        peakThroughput: result.peakThroughput + ' events/sec',
                        latencyP95: result.latencyP95Ms + 'ms',
                        reason: result.reason
                    });
                }).catch(err => {
                    logger.error('Auto-benchmark failed', err);
                });
            }, 10000);
        }
    });

    // Graceful shutdown
    process.on('SIGTERM', async () => {
        logger.info('Shutting down gateway');
        await kafkaClient.disconnect();
        server.close();
        process.exit(0);
    });
}

main().catch((error) => {
    logger.error('Failed to start gateway', error);
    process.exit(1);
});
