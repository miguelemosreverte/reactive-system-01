import { trace } from '@opentelemetry/api';

export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

interface LogEntry {
    timestamp: string;
    level: LogLevel;
    service: string;
    message: string;
    traceId?: string;
    spanId?: string;
    [key: string]: unknown;
}

const SERVICE_NAME = process.env.OTEL_SERVICE_NAME || 'gateway';
const LOG_LEVEL = (process.env.LOG_LEVEL || 'info') as LogLevel;

const levels: Record<LogLevel, number> = {
    debug: 0,
    info: 1,
    warn: 2,
    error: 3
};

function shouldLog(level: LogLevel): boolean {
    return levels[level] >= levels[LOG_LEVEL];
}

function getTraceContext(): { traceId?: string; spanId?: string } {
    const activeSpan = trace.getActiveSpan();
    if (activeSpan) {
        const ctx = activeSpan.spanContext();
        return {
            traceId: ctx.traceId,
            spanId: ctx.spanId
        };
    }
    return {};
}

function formatLog(level: LogLevel, message: string, meta?: Record<string, unknown>): string {
    const entry: LogEntry = {
        timestamp: new Date().toISOString(),
        level,
        service: SERVICE_NAME,
        message,
        ...getTraceContext(),
        ...meta
    };
    return JSON.stringify(entry);
}

export const logger = {
    debug(message: string, meta?: Record<string, unknown>): void {
        if (shouldLog('debug')) {
            console.log(formatLog('debug', message, meta));
        }
    },

    info(message: string, meta?: Record<string, unknown>): void {
        if (shouldLog('info')) {
            console.log(formatLog('info', message, meta));
        }
    },

    warn(message: string, meta?: Record<string, unknown>): void {
        if (shouldLog('warn')) {
            console.warn(formatLog('warn', message, meta));
        }
    },

    error(message: string, error?: Error | unknown, meta?: Record<string, unknown>): void {
        if (shouldLog('error')) {
            const errorMeta: Record<string, unknown> = { ...meta };
            if (error instanceof Error) {
                errorMeta.error = {
                    name: error.name,
                    message: error.message,
                    stack: error.stack
                };
            } else if (error) {
                errorMeta.error = String(error);
            }
            console.error(formatLog('error', message, errorMeta));
        }
    }
};
