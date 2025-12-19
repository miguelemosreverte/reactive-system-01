import './tracing'; // Must be first import for OTEL initialization
import express, { Request, Response, NextFunction } from 'express';
import { createServer } from 'http';
import { WebSocketServer } from './websocket';
import { KafkaClient } from './kafka';
import { logger } from './logger';
import { AutoBenchmark, shouldRunBenchmark, getBenchmarkConfig } from './benchmark';
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

    // REST API for counter operations
    app.post('/api/counter', async (req, res) => {
        const { action, value, sessionId = 'default' } = req.body;

        tracer.startActiveSpan('http.counter.action', { kind: SpanKind.SERVER }, async (span) => {
            try {
                span.setAttributes({
                    'http.method': 'POST',
                    'http.route': '/api/counter',
                    'session.id': sessionId,
                    'counter.action': action || 'set',
                    'counter.value': value || 0
                });

                const traceId = await kafkaClient.publishEvent({
                    sessionId,
                    action: action || 'set',
                    value: value || 0,
                    timestamp: Date.now()
                });

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
                res.json({ success: true, message: 'Event published', traceId });
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

    // Benchmark endpoints (admin only)
    app.get('/api/admin/benchmark/status', adminAuth, (req, res) => {
        res.json({
            running: benchmark?.isRunning() || false,
            lastResult: benchmarkResult,
            autoStart: shouldRunBenchmark()
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

        // Initialize benchmark with current kafkaClient
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
            { ...getBenchmarkConfig(), ...req.body }
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
        } else {
            res.status(400).json({ error: 'No benchmark running' });
        }
    });

    // Create HTTP server
    const server = createServer(app);

    // Initialize Kafka client
    kafkaClient = new KafkaClient(KAFKA_BROKERS);

    // Initialize WebSocket server
    const wsServer = new WebSocketServer(server);

    // Connect to Kafka
    await kafkaClient.connect();

    // Subscribe to counter results
    await kafkaClient.subscribeToResults((result) => {
        logger.info('Received counter result', {
            sessionId: result.sessionId,
            currentValue: result.currentValue,
            alert: result.alert,
            traceId: result.traceId
        });

        // Update state with traceId
        counterState.set(result.sessionId, {
            value: result.currentValue,
            alert: result.alert,
            message: result.message,
            traceId: result.traceId
        });

        // Broadcast to all WebSocket clients for this session (include traceId)
        wsServer.broadcast(result.sessionId, {
            type: 'counter-update',
            data: {
                ...result,
                traceId: result.traceId
            }
        });

        // Notify benchmark callbacks (for latency tracking)
        if (result.traceId) {
            benchmarkResultCallbacks.forEach(cb => cb(result.traceId!));
        }
    });

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
                    getBenchmarkConfig()
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
