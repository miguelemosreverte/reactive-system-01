/**
 * Transaction Tracker
 * Tracks events from receipt through completion with per-component timing
 */

export interface ComponentTiming {
  gatewayReceivedAt?: number;
  gatewayPublishedAt?: number;
  flinkReceivedAt?: number;
  flinkProcessedAt?: number;
  droolsStartAt?: number;
  droolsEndAt?: number;
}

export interface TimingBreakdown {
  gatewayMs: number;
  kafkaMs: number;
  flinkMs: number;
  droolsMs: number;
  totalMs: number;
}

export interface TransactionResult {
  value: number;
  alert: string;
  message?: string;
}

export type TransactionStatus = 'accepted' | 'published' | 'processing' | 'completed' | 'failed' | 'timeout';

export interface TransactionState {
  id: string;
  sessionId: string;
  status: TransactionStatus;
  timing: ComponentTiming;
  breakdown?: TimingBreakdown;
  result?: TransactionResult;
  createdAt: number;
  completedAt?: number;
  error?: string;
}

export interface TrackerStats {
  total: number;
  completed: number;
  pending: number;
  failed: number;
  avgLatencyMs: number;
  componentAvg: {
    gatewayMs: number;
    kafkaMs: number;
    flinkMs: number;
    droolsMs: number;
  };
}

export class TransactionTracker {
  private transactions: Map<string, TransactionState> = new Map();
  private completedTimings: TimingBreakdown[] = [];
  private readonly TTL_MS: number;
  private readonly MAX_TRANSACTIONS: number;
  private cleanupInterval: NodeJS.Timeout | null = null;

  constructor(options: { ttlMs?: number; maxTransactions?: number } = {}) {
    this.TTL_MS = options.ttlMs ?? 60000; // 1 minute default
    this.MAX_TRANSACTIONS = options.maxTransactions ?? 100000;
    this.startCleanup();
  }

  /**
   * Generate a unique event ID
   */
  private generateId(sessionId: string): string {
    const timestamp = Date.now();
    const random = Math.random().toString(36).substring(2, 6);
    // Sanitize sessionId to avoid special characters
    const safeSession = sessionId.replace(/[^a-zA-Z0-9]/g, '').substring(0, 12);
    return `evt_${timestamp}_${safeSession}_${random}`;
  }

  /**
   * Create a new transaction and return its ID
   */
  create(sessionId: string): string {
    const id = this.generateId(sessionId);
    const now = Date.now();

    const state: TransactionState = {
      id,
      sessionId,
      status: 'accepted',
      timing: {
        gatewayReceivedAt: now,
      },
      createdAt: now,
    };

    this.transactions.set(id, state);

    // Enforce max size
    if (this.transactions.size > this.MAX_TRANSACTIONS) {
      this.evictOldest();
    }

    return id;
  }

  /**
   * Mark transaction as published to Kafka
   */
  markPublished(id: string): void {
    const tx = this.transactions.get(id);
    if (tx) {
      tx.status = 'published';
      tx.timing.gatewayPublishedAt = Date.now();
    }
  }

  /**
   * Update Flink timing
   */
  updateFlinkTiming(id: string, receivedAt: number, processedAt: number): void {
    const tx = this.transactions.get(id);
    if (tx) {
      tx.status = 'processing';
      tx.timing.flinkReceivedAt = receivedAt;
      tx.timing.flinkProcessedAt = processedAt;
    }
  }

  /**
   * Update Drools timing
   */
  updateDroolsTiming(id: string, startAt: number, endAt: number): void {
    const tx = this.transactions.get(id);
    if (tx) {
      tx.timing.droolsStartAt = startAt;
      tx.timing.droolsEndAt = endAt;
    }
  }

  /**
   * Complete a transaction with result and calculate breakdown
   */
  complete(id: string, result: TransactionResult, flinkTiming?: { receivedAt: number; processedAt: number }, droolsTiming?: { startAt: number; endAt: number }): void {
    const tx = this.transactions.get(id);
    if (!tx) return;

    const now = Date.now();
    tx.status = 'completed';
    tx.result = result;
    tx.completedAt = now;

    // Apply timing from result if provided
    if (flinkTiming) {
      tx.timing.flinkReceivedAt = flinkTiming.receivedAt;
      tx.timing.flinkProcessedAt = flinkTiming.processedAt;
    }
    if (droolsTiming) {
      tx.timing.droolsStartAt = droolsTiming.startAt;
      tx.timing.droolsEndAt = droolsTiming.endAt;
    }

    // Calculate breakdown
    tx.breakdown = this.calculateBreakdown(tx.timing, now);
    this.completedTimings.push(tx.breakdown);

    // Keep completed timings bounded
    if (this.completedTimings.length > 10000) {
      this.completedTimings = this.completedTimings.slice(-5000);
    }
  }

  /**
   * Mark transaction as failed
   */
  fail(id: string, error: string): void {
    const tx = this.transactions.get(id);
    if (tx) {
      tx.status = 'failed';
      tx.error = error;
      tx.completedAt = Date.now();
    }
  }

  /**
   * Get transaction by ID
   */
  get(id: string): TransactionState | null {
    return this.transactions.get(id) ?? null;
  }

  /**
   * Check if transaction exists
   */
  has(id: string): boolean {
    return this.transactions.has(id);
  }

  /**
   * Delete transaction
   */
  delete(id: string): boolean {
    return this.transactions.delete(id);
  }

  /**
   * Get all pending transactions (for timeout detection)
   */
  getPending(): TransactionState[] {
    const result: TransactionState[] = [];
    for (const tx of this.transactions.values()) {
      if (tx.status !== 'completed' && tx.status !== 'failed' && tx.status !== 'timeout') {
        result.push(tx);
      }
    }
    return result;
  }

  /**
   * Mark old pending transactions as timed out
   */
  markTimeouts(maxAgeMs: number): number {
    const cutoff = Date.now() - maxAgeMs;
    let count = 0;

    for (const tx of this.transactions.values()) {
      if (tx.status !== 'completed' && tx.status !== 'failed' && tx.status !== 'timeout') {
        if (tx.createdAt < cutoff) {
          tx.status = 'timeout';
          tx.completedAt = Date.now();
          count++;
        }
      }
    }

    return count;
  }

  /**
   * Get tracker statistics
   */
  getStats(): TrackerStats {
    let completed = 0;
    let pending = 0;
    let failed = 0;

    for (const tx of this.transactions.values()) {
      if (tx.status === 'completed') completed++;
      else if (tx.status === 'failed' || tx.status === 'timeout') failed++;
      else pending++;
    }

    // Calculate averages from recent completed timings
    const recentTimings = this.completedTimings.slice(-1000);
    const avgLatencyMs = recentTimings.length > 0
      ? Math.round(recentTimings.reduce((sum, t) => sum + t.totalMs, 0) / recentTimings.length)
      : 0;

    const componentAvg = {
      gatewayMs: 0,
      kafkaMs: 0,
      flinkMs: 0,
      droolsMs: 0,
    };

    if (recentTimings.length > 0) {
      componentAvg.gatewayMs = Math.round(recentTimings.reduce((sum, t) => sum + t.gatewayMs, 0) / recentTimings.length);
      componentAvg.kafkaMs = Math.round(recentTimings.reduce((sum, t) => sum + t.kafkaMs, 0) / recentTimings.length);
      componentAvg.flinkMs = Math.round(recentTimings.reduce((sum, t) => sum + t.flinkMs, 0) / recentTimings.length);
      componentAvg.droolsMs = Math.round(recentTimings.reduce((sum, t) => sum + t.droolsMs, 0) / recentTimings.length);
    }

    return {
      total: this.transactions.size,
      completed,
      pending,
      failed,
      avgLatencyMs,
      componentAvg,
    };
  }

  /**
   * Get recent completed timings for reports
   */
  getRecentTimings(limit: number = 1000): TimingBreakdown[] {
    return this.completedTimings.slice(-limit);
  }

  /**
   * Clear all transactions and timings
   */
  clear(): void {
    this.transactions.clear();
    this.completedTimings = [];
  }

  /**
   * Stop cleanup interval
   */
  stop(): void {
    if (this.cleanupInterval) {
      clearInterval(this.cleanupInterval);
      this.cleanupInterval = null;
    }
  }

  /**
   * Calculate timing breakdown from component timestamps
   */
  private calculateBreakdown(timing: ComponentTiming, completedAt: number): TimingBreakdown {
    const gatewayMs = timing.gatewayPublishedAt && timing.gatewayReceivedAt
      ? timing.gatewayPublishedAt - timing.gatewayReceivedAt
      : 0;

    const kafkaMs = timing.flinkReceivedAt && timing.gatewayPublishedAt
      ? timing.flinkReceivedAt - timing.gatewayPublishedAt
      : 0;

    const flinkMs = timing.flinkProcessedAt && timing.flinkReceivedAt
      ? timing.flinkProcessedAt - timing.flinkReceivedAt
      : 0;

    const droolsMs = timing.droolsEndAt && timing.droolsStartAt
      ? timing.droolsEndAt - timing.droolsStartAt
      : 0;

    const totalMs = timing.gatewayReceivedAt
      ? completedAt - timing.gatewayReceivedAt
      : 0;

    return { gatewayMs, kafkaMs, flinkMs, droolsMs, totalMs };
  }

  /**
   * Evict oldest transactions when limit exceeded
   */
  private evictOldest(): void {
    const entries = Array.from(this.transactions.entries());
    entries.sort((a, b) => a[1].createdAt - b[1].createdAt);

    // Remove oldest 10%
    const toRemove = Math.floor(this.MAX_TRANSACTIONS * 0.1);
    for (let i = 0; i < toRemove && i < entries.length; i++) {
      this.transactions.delete(entries[i][0]);
    }
  }

  /**
   * Start periodic cleanup of expired transactions
   */
  private startCleanup(): void {
    this.cleanupInterval = setInterval(() => {
      const cutoff = Date.now() - this.TTL_MS;
      for (const [id, tx] of this.transactions.entries()) {
        // Only remove completed/failed transactions after TTL
        if ((tx.status === 'completed' || tx.status === 'failed' || tx.status === 'timeout') && tx.createdAt < cutoff) {
          this.transactions.delete(id);
        }
      }
    }, 10000); // Cleanup every 10 seconds
  }
}

// Singleton instance for the gateway
export const transactionTracker = new TransactionTracker();
