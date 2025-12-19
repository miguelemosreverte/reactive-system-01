/**
 * Observability Fetcher
 *
 * Fetches trace data from Jaeger and logs from Loki
 * for embedding in static benchmark reports.
 */

import { logger } from '../logger';
import {
  JaegerTrace,
  JaegerSpan,
  JaegerProcess,
  LokiLogEntry,
  LokiQueryResult,
  LokiStream,
  SampleEvent,
} from './types';

const JAEGER_URL = process.env.JAEGER_QUERY_URL || 'http://jaeger:16686';
const LOKI_URL = process.env.LOKI_URL || 'http://loki:3100';

export class ObservabilityFetcher {
  private jaegerUrl: string;
  private lokiUrl: string;

  constructor(jaegerUrl?: string, lokiUrl?: string) {
    this.jaegerUrl = jaegerUrl || JAEGER_URL;
    this.lokiUrl = lokiUrl || LOKI_URL;
  }

  // ============================================================================
  // Jaeger Trace Fetching
  // ============================================================================

  /**
   * Fetch a single trace from Jaeger by trace ID
   */
  async fetchTrace(traceId: string): Promise<JaegerTrace | null> {
    if (!traceId) return null;

    try {
      const response = await fetch(`${this.jaegerUrl}/api/traces/${traceId}`);

      if (!response.ok) {
        logger.debug('Trace not found in Jaeger', { traceId, status: response.status });
        return null;
      }

      const data = await response.json() as { data?: JaegerTrace[] };

      // Jaeger returns { data: [trace1, trace2, ...] }
      if (data.data && data.data.length > 0) {
        return data.data[0];
      }

      return null;
    } catch (error) {
      logger.warn('Failed to fetch trace from Jaeger', { traceId, error: String(error) });
      return null;
    }
  }

  /**
   * Fetch multiple traces by their IDs
   */
  async fetchTraces(traceIds: string[]): Promise<Record<string, JaegerTrace>> {
    const traces: Record<string, JaegerTrace> = {};

    // Fetch in parallel, but limit concurrency
    const batchSize = 10;
    for (let i = 0; i < traceIds.length; i += batchSize) {
      const batch = traceIds.slice(i, i + batchSize);
      const results = await Promise.all(
        batch.map(async (traceId) => {
          const trace = await this.fetchTrace(traceId);
          return { traceId, trace };
        })
      );

      for (const { traceId, trace } of results) {
        if (trace) {
          traces[traceId] = trace;
        }
      }
    }

    return traces;
  }

  // ============================================================================
  // Loki Log Fetching
  // ============================================================================

  /**
   * Fetch logs from Loki by trace ID
   * First tries label filter, then falls back to line filter
   */
  async fetchLogs(
    traceId: string,
    timeRange: { start: Date; end: Date }
  ): Promise<LokiLogEntry[]> {
    if (!traceId) return [];

    // Convert to nanoseconds for Loki
    const startNs = timeRange.start.getTime() * 1_000_000;
    const endNs = timeRange.end.getTime() * 1_000_000;

    // Primary: label filter (if traceId is indexed as label)
    let query = `{traceId="${traceId}"}`;
    let logs = await this.queryLoki(query, startNs, endNs);

    // Fallback: line filter (search in log content)
    if (logs.length === 0) {
      query = `{service=~".+"} |= "traceId" |= "${traceId}"`;
      logs = await this.queryLoki(query, startNs, endNs);
    }

    return logs;
  }

  /**
   * Fetch logs for multiple trace IDs
   */
  async fetchLogsForTraces(
    traceIds: string[],
    timeRange: { start: Date; end: Date }
  ): Promise<Record<string, LokiLogEntry[]>> {
    const logs: Record<string, LokiLogEntry[]> = {};

    // Fetch in parallel, but limit concurrency
    const batchSize = 5;
    for (let i = 0; i < traceIds.length; i += batchSize) {
      const batch = traceIds.slice(i, i + batchSize);
      const results = await Promise.all(
        batch.map(async (traceId) => {
          const traceLogs = await this.fetchLogs(traceId, timeRange);
          return { traceId, logs: traceLogs };
        })
      );

      for (const { traceId, logs: traceLogs } of results) {
        logs[traceId] = traceLogs;
      }
    }

    return logs;
  }

  /**
   * Query Loki with LogQL
   */
  private async queryLoki(
    query: string,
    startNs: number,
    endNs: number
  ): Promise<LokiLogEntry[]> {
    try {
      const url = new URL(`${this.lokiUrl}/loki/api/v1/query_range`);
      url.searchParams.set('query', query);
      url.searchParams.set('start', startNs.toString());
      url.searchParams.set('end', endNs.toString());
      url.searchParams.set('limit', '100');

      const response = await fetch(url.toString());

      if (!response.ok) {
        logger.debug('Loki query failed', { query, status: response.status });
        return [];
      }

      const data = await response.json() as LokiQueryResult;

      if (data.status !== 'success' || !data.data?.result) {
        return [];
      }

      return this.parseLokiResult(data.data.result);
    } catch (error) {
      logger.warn('Failed to query Loki', { query, error: String(error) });
      return [];
    }
  }

  /**
   * Parse Loki response into log entries
   */
  private parseLokiResult(streams: LokiStream[]): LokiLogEntry[] {
    const entries: LokiLogEntry[] = [];

    for (const stream of streams) {
      const labels = stream.stream;

      for (const [timestamp, line] of stream.values) {
        // Try to parse JSON logs
        let fields: Record<string, unknown> | undefined;
        try {
          fields = JSON.parse(line);
        } catch {
          // Not JSON, leave fields undefined
        }

        entries.push({
          timestamp,
          line,
          fields,
          labels,
        });
      }
    }

    // Sort by timestamp (oldest first)
    entries.sort((a, b) => {
      const ta = BigInt(a.timestamp);
      const tb = BigInt(b.timestamp);
      return ta < tb ? -1 : ta > tb ? 1 : 0;
    });

    return entries;
  }

  // ============================================================================
  // Combined Fetching for Sample Events
  // ============================================================================

  /**
   * Enrich sample events with trace and log data
   */
  async enrichSampleEvents(
    events: SampleEvent[],
    timeRange: { start: Date; end: Date }
  ): Promise<SampleEvent[]> {
    // Get unique trace IDs
    const traceIds = [...new Set(events.map(e => e.traceId).filter(Boolean))];

    if (traceIds.length === 0) {
      return events;
    }

    // Fetch traces and logs in parallel
    const [traces, logs] = await Promise.all([
      this.fetchTraces(traceIds),
      this.fetchLogsForTraces(traceIds, timeRange),
    ]);

    // Enrich events
    return events.map(event => {
      if (!event.traceId) return event;

      return {
        ...event,
        jaegerTrace: traces[event.traceId],
        lokiLogs: logs[event.traceId],
      };
    });
  }
}

// Singleton instance
let instance: ObservabilityFetcher | null = null;

export function getObservabilityFetcher(): ObservabilityFetcher {
  if (!instance) {
    instance = new ObservabilityFetcher();
  }
  return instance;
}
