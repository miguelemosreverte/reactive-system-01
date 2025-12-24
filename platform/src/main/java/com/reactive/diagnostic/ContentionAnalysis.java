package com.reactive.diagnostic;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Contention analysis - what's blocking processing.
 * Explains why threads are waiting instead of working.
 */
public record ContentionAnalysis(
    @JsonProperty("lock_contention_percent") double lockContentionPercent,
    @JsonProperty("most_contended_locks") List<LockInfo> mostContendedLocks,
    @JsonProperty("blocked_threads") int blockedThreads,
    @JsonProperty("waiting_threads") int waitingThreads,
    @JsonProperty("deadlock_detected") boolean deadlockDetected,
    @JsonProperty("io_wait_percent") double ioWaitPercent,
    @JsonProperty("queue_blocking_percent") double queueBlockingPercent
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private double lockContentionPercent;
        private List<LockInfo> mostContendedLocks = List.of();
        private int blockedThreads;
        private int waitingThreads;
        private boolean deadlockDetected;
        private double ioWaitPercent;
        private double queueBlockingPercent;

        public Builder lockContentionPercent(double v) { this.lockContentionPercent = v; return this; }
        public Builder mostContendedLocks(List<LockInfo> v) { this.mostContendedLocks = v; return this; }
        public Builder blockedThreads(int v) { this.blockedThreads = v; return this; }
        public Builder waitingThreads(int v) { this.waitingThreads = v; return this; }
        public Builder deadlockDetected(boolean v) { this.deadlockDetected = v; return this; }
        public Builder ioWaitPercent(double v) { this.ioWaitPercent = v; return this; }
        public Builder queueBlockingPercent(double v) { this.queueBlockingPercent = v; return this; }

        public ContentionAnalysis build() {
            return new ContentionAnalysis(
                lockContentionPercent, mostContendedLocks, blockedThreads,
                waitingThreads, deadlockDetected, ioWaitPercent, queueBlockingPercent
            );
        }
    }
}
