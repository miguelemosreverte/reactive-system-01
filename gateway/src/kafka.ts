import { Kafka, Producer, Consumer, EachMessagePayload } from 'kafkajs';
import { trace, context, SpanKind, SpanStatusCode, propagation } from '@opentelemetry/api';
import { logger } from './logger';

const tracer = trace.getTracer('gateway-kafka');

export interface CounterEvent {
    sessionId: string;
    action: string;
    value: number;
    timestamp: number;
    traceId?: string;
    spanId?: string;
}

export interface CounterResult {
    sessionId: string;
    currentValue: number;
    alert: string;
    message: string;
    timestamp: number;
    traceId?: string;
}

export class KafkaClient {
    private kafka: Kafka;
    private producer: Producer;
    private consumer: Consumer;
    private isConnected: boolean = false;

    constructor(brokers: string[]) {
        this.kafka = new Kafka({
            clientId: 'reactive-gateway',
            brokers,
            retry: {
                initialRetryTime: 1000,
                retries: 10
            }
        });

        // Low-latency producer configuration
        this.producer = this.kafka.producer({
            allowAutoTopicCreation: true,
            // Acks: 1 = leader acknowledgment only (faster than 'all')
            // For lowest latency in development; use 'all' in production for durability
        });

        // Low-latency consumer configuration
        this.consumer = this.kafka.consumer({
            groupId: 'gateway-group',
            maxWaitTimeInMs: 10,  // Don't wait long for batches (default: 5000)
            minBytes: 1,          // Return as soon as any data is available
        });
    }

    async connect(): Promise<void> {
        logger.info('Connecting to Kafka');

        // Retry connection with backoff
        let retries = 0;
        const maxRetries = 30;

        while (retries < maxRetries) {
            try {
                await this.producer.connect();
                await this.consumer.connect();
                this.isConnected = true;
                logger.info('Connected to Kafka');
                return;
            } catch (error) {
                retries++;
                logger.warn('Kafka connection attempt failed, retrying', {
                    attempt: retries,
                    maxRetries,
                    retryDelayMs: 2000
                });
                await new Promise(resolve => setTimeout(resolve, 2000));
            }
        }

        throw new Error('Failed to connect to Kafka after maximum retries');
    }

    async disconnect(): Promise<void> {
        await this.producer.disconnect();
        await this.consumer.disconnect();
        this.isConnected = false;
    }

    async publishEvent(event: CounterEvent): Promise<string> {
        if (!this.isConnected) {
            throw new Error('Kafka client not connected');
        }

        return tracer.startActiveSpan('kafka.publish', { kind: SpanKind.PRODUCER }, async (span) => {
            try {
                // Add trace context to event
                const activeSpan = trace.getActiveSpan();
                const traceId = activeSpan?.spanContext().traceId || span.spanContext().traceId;
                const spanId = span.spanContext().spanId;

                const eventWithTrace: CounterEvent = {
                    ...event,
                    traceId,
                    spanId
                };

                // Inject trace context into Kafka headers
                const headers: Record<string, string> = {};
                propagation.inject(context.active(), headers);

                span.setAttributes({
                    'messaging.system': 'kafka',
                    'messaging.destination': 'counter-events',
                    'messaging.operation': 'publish',
                    'session.id': event.sessionId,
                    'counter.action': event.action,
                    'counter.value': event.value,
                    'trace.id': traceId
                });

                await this.producer.send({
                    topic: 'counter-events',
                    messages: [
                        {
                            key: event.sessionId,
                            value: JSON.stringify(eventWithTrace),
                            headers
                        }
                    ]
                });

                span.setStatus({ code: SpanStatusCode.OK });
                logger.debug('Published event to Kafka', {
                    topic: 'counter-events',
                    sessionId: event.sessionId,
                    action: event.action,
                    traceId
                });
                return traceId;
            } catch (error) {
                span.setStatus({ code: SpanStatusCode.ERROR, message: String(error) });
                span.recordException(error as Error);
                throw error;
            } finally {
                span.end();
            }
        });
    }

    async subscribeToResults(callback: (result: CounterResult) => void): Promise<void> {
        await this.consumer.subscribe({ topic: 'counter-results', fromBeginning: false });

        await this.consumer.run({
            eachMessage: async ({ message }: EachMessagePayload) => {
                if (message.value) {
                    // Extract trace context from headers if available
                    const headers: Record<string, string> = {};
                    if (message.headers) {
                        for (const [key, value] of Object.entries(message.headers)) {
                            if (value) {
                                headers[key] = value.toString();
                            }
                        }
                    }

                    const parentContext = propagation.extract(context.active(), headers);

                    context.with(parentContext, () => {
                        tracer.startActiveSpan('kafka.consume', { kind: SpanKind.CONSUMER }, (span) => {
                            try {
                                const result: CounterResult = JSON.parse(message.value!.toString());

                                span.setAttributes({
                                    'messaging.system': 'kafka',
                                    'messaging.destination': 'counter-results',
                                    'messaging.operation': 'consume',
                                    'session.id': result.sessionId,
                                    'counter.value': result.currentValue,
                                    'counter.alert': result.alert,
                                    'trace.id': result.traceId || 'unknown'
                                });

                                callback(result);
                                span.setStatus({ code: SpanStatusCode.OK });
                            } catch (error) {
                                span.setStatus({ code: SpanStatusCode.ERROR, message: String(error) });
                                span.recordException(error as Error);
                                logger.error('Failed to parse Kafka result', error);
                            } finally {
                                span.end();
                            }
                        });
                    });
                }
            }
        });

        logger.info('Subscribed to Kafka topic', { topic: 'counter-results' });
    }
}
