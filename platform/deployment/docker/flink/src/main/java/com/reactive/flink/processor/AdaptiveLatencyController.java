package com.reactive.flink.processor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.Queue;

/**
 * Self-Tuning Adaptive Latency Controller
 *
 * Like an automatic car transmission - continuously discovers the optimal
 * batch wait time at runtime based on ACTUAL measured performance.
 *
 * NO manual tuning required. The system learns by itself.
 *
 * Algorithm:
 * 1. Every tuning interval (default 10 seconds), analyze recent performance
 * 2. Measure: actual latency, throughput, SLA headroom
 * 3. Adjust batch wait time:
 *    - Lots of SLA headroom + high throughput → increase batching (save resources)
 *    - Tight on SLA → decrease batching (protect latency)
 *    - Low throughput → minimize batching (why wait?)
 * 4. Use AIMD-like control (Additive Increase, Multiplicative Decrease)
 *    similar to TCP congestion control - proven stable
 *
 * The only fixed constraint is the SLA (max acceptable latency) -
 * everything else is discovered automatically.
 */
public class AdaptiveLatencyController implements Serializable {
    private static final long serialVersionUID = 2L;
    private static final Logger LOG = LoggerFactory.getLogger(AdaptiveLatencyController.class);

    // The ONLY hard constraint - maximum acceptable latency (SLA)
    private final long slaLatencyMs;

    // Tuning bounds (physical limits, not tuning targets)
    private final long minBatchWaitMs = 0;      // Can't go below zero
    private final long maxBatchWaitMs;          // Don't exceed SLA

    // Tuning interval
    private final long tuningIntervalMs;

    // Current learned optimal value (starts conservative)
    private volatile long currentBatchWaitMs = 0;

    // Performance tracking
    private final Queue<Long> recentLatencies = new LinkedList<>();
    private final Queue<Long> recentEventTimes = new LinkedList<>();
    private static final int MAX_SAMPLES = 1000;

    // Tuning state
    private transient long lastTuningTime = 0;
    private transient long eventsInInterval = 0;
    private transient double lastThroughput = 0;
    private transient long lastP95Latency = 0;

    // Control parameters (AIMD-like)
    private static final double INCREASE_FACTOR = 1.1;    // 10% increase when safe
    private static final double DECREASE_FACTOR = 0.5;    // 50% decrease when tight
    private static final double HEADROOM_SAFE = 0.5;      // 50% headroom = safe to increase
    private static final double HEADROOM_DANGER = 0.2;    // 20% headroom = must decrease
    private static final double LOW_THROUGHPUT_THRESHOLD = 2.0; // events/sec

    /**
     * Create a self-tuning controller.
     *
     * @param slaLatencyMs The maximum acceptable latency (your SLA promise)
     */
    public AdaptiveLatencyController(long slaLatencyMs) {
        this(slaLatencyMs, 10000); // Default: tune every 10 seconds
    }

    /**
     * Create a self-tuning controller with custom tuning interval.
     *
     * @param slaLatencyMs The maximum acceptable latency (your SLA promise)
     * @param tuningIntervalMs How often to re-tune (default 10000ms)
     */
    public AdaptiveLatencyController(long slaLatencyMs, long tuningIntervalMs) {
        this.slaLatencyMs = slaLatencyMs;
        this.maxBatchWaitMs = slaLatencyMs / 2; // Never use more than half SLA for batching
        this.tuningIntervalMs = tuningIntervalMs;
        this.currentBatchWaitMs = 0; // Start with zero wait (safest)

        LOG.info("Self-tuning controller initialized: SLA={}ms, tuning every {}s, " +
                 "will automatically discover optimal batch wait (0-{}ms range)",
                 slaLatencyMs, tuningIntervalMs / 1000, maxBatchWaitMs);
    }

    /**
     * Record that an event was processed with given latency.
     * Call this for every completed event.
     *
     * @param latencyMs End-to-end latency for this event
     */
    public void recordEventLatency(long latencyMs) {
        long now = System.currentTimeMillis();

        synchronized (recentLatencies) {
            recentLatencies.add(latencyMs);
            if (recentLatencies.size() > MAX_SAMPLES) {
                recentLatencies.poll();
            }
        }

        synchronized (recentEventTimes) {
            recentEventTimes.add(now);
            if (recentEventTimes.size() > MAX_SAMPLES) {
                recentEventTimes.poll();
            }
        }

        eventsInInterval++;

        // Check if it's time to tune
        if (now - lastTuningTime >= tuningIntervalMs) {
            tune(now);
        }
    }

    /**
     * Record that an event arrived (for throughput tracking when latency not yet known)
     */
    public void recordEventArrival() {
        long now = System.currentTimeMillis();
        synchronized (recentEventTimes) {
            recentEventTimes.add(now);
            if (recentEventTimes.size() > MAX_SAMPLES) {
                recentEventTimes.poll();
            }
        }
        eventsInInterval++;

        // Check if it's time to tune
        if (now - lastTuningTime >= tuningIntervalMs) {
            tune(now);
        }
    }

    /**
     * Get the current recommended batch wait time.
     * This value is continuously adjusted based on observed performance.
     */
    public long getRecommendedWaitMs() {
        return currentBatchWaitMs;
    }

    /**
     * Get current throughput estimate (events/second)
     */
    public double getCurrentThroughput() {
        return lastThroughput;
    }

    /**
     * Get last measured P95 latency
     */
    public long getLastP95Latency() {
        return lastP95Latency;
    }

    /**
     * Get current operating mode description
     */
    public String getCurrentMode() {
        if (lastThroughput < LOW_THROUGHPUT_THRESHOLD) {
            return "IDLE";
        } else if (currentBatchWaitMs == 0) {
            return "IMMEDIATE";
        } else if (currentBatchWaitMs >= maxBatchWaitMs * 0.8) {
            return "MAX_BATCH";
        } else {
            return "ADAPTIVE";
        }
    }

    /**
     * Check if we should flush the current batch.
     */
    public boolean shouldFlush(int currentBatchSize, long batchStartTime) {
        // In idle/immediate mode, always flush
        if (currentBatchWaitMs == 0) {
            return true;
        }

        // Flush if wait time exceeded
        long elapsed = System.currentTimeMillis() - batchStartTime;
        return elapsed >= currentBatchWaitMs;
    }

    /**
     * The core tuning algorithm - runs every tuning interval.
     * Analyzes recent performance and adjusts batch wait time.
     */
    private void tune(long now) {
        if (lastTuningTime == 0) {
            lastTuningTime = now;
            return;
        }

        long intervalMs = now - lastTuningTime;

        // Calculate throughput
        double throughput = (eventsInInterval * 1000.0) / intervalMs;
        lastThroughput = throughput;

        // Calculate P95 latency
        long p95Latency = calculateP95Latency();
        lastP95Latency = p95Latency;

        // Calculate SLA headroom
        double headroom = (double)(slaLatencyMs - p95Latency) / slaLatencyMs;

        // Previous value for logging
        long previousWait = currentBatchWaitMs;

        // TUNING DECISION
        if (throughput < LOW_THROUGHPUT_THRESHOLD) {
            // Low throughput: minimize batching (why wait for events that aren't coming?)
            currentBatchWaitMs = 0;

        } else if (p95Latency == 0) {
            // No latency data yet, stay conservative
            currentBatchWaitMs = Math.min(currentBatchWaitMs, 5);

        } else if (headroom < HEADROOM_DANGER) {
            // DANGER: Too close to SLA - multiplicative decrease (aggressive)
            currentBatchWaitMs = (long)(currentBatchWaitMs * DECREASE_FACTOR);
            LOG.warn("Latency approaching SLA! P95={}ms, SLA={}ms, headroom={}%, " +
                     "reducing batch wait: {}ms → {}ms",
                     p95Latency, slaLatencyMs, String.format("%.1f", headroom * 100), previousWait, currentBatchWaitMs);

        } else if (headroom > HEADROOM_SAFE && throughput > LOW_THROUGHPUT_THRESHOLD * 2) {
            // SAFE: Lots of headroom and decent throughput - additive increase (conservative)
            long increase = Math.max(1, (long)(currentBatchWaitMs * (INCREASE_FACTOR - 1)));
            currentBatchWaitMs = Math.min(currentBatchWaitMs + increase, maxBatchWaitMs);

        }
        // else: in the middle zone, hold steady

        // Enforce bounds
        currentBatchWaitMs = Math.max(minBatchWaitMs, Math.min(currentBatchWaitMs, maxBatchWaitMs));

        // Log tuning decision if changed significantly
        if (Math.abs(currentBatchWaitMs - previousWait) >= 2 ||
            (previousWait == 0 && currentBatchWaitMs > 0) ||
            (previousWait > 0 && currentBatchWaitMs == 0)) {
            LOG.info("Auto-tuned: throughput={} events/sec, P95={}ms, SLA={}ms, headroom={}%, " +
                     "batch wait: {}ms → {}ms (mode: {})",
                     String.format("%.1f", throughput), p95Latency, slaLatencyMs,
                     String.format("%.1f", headroom * 100), previousWait, currentBatchWaitMs, getCurrentMode());
        }

        // Reset for next interval
        lastTuningTime = now;
        eventsInInterval = 0;
    }

    private long calculateP95Latency() {
        synchronized (recentLatencies) {
            if (recentLatencies.isEmpty()) {
                return 0;
            }

            long[] sorted = recentLatencies.stream()
                    .mapToLong(Long::longValue)
                    .sorted()
                    .toArray();

            int p95Index = (int)(sorted.length * 0.95);
            return sorted[Math.min(p95Index, sorted.length - 1)];
        }
    }

    /**
     * Force an immediate re-tune (useful after configuration changes)
     */
    public void forceTune() {
        tune(System.currentTimeMillis());
    }

    /**
     * Reset to initial state (conservative, zero wait)
     */
    public void reset() {
        currentBatchWaitMs = 0;
        lastTuningTime = 0;
        eventsInInterval = 0;
        lastThroughput = 0;
        lastP95Latency = 0;
        synchronized (recentLatencies) {
            recentLatencies.clear();
        }
        synchronized (recentEventTimes) {
            recentEventTimes.clear();
        }
        LOG.info("Controller reset to initial state (immediate mode)");
    }
}
