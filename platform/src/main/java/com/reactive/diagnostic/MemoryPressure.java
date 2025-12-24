package com.reactive.diagnostic;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Detailed memory state - explains GC behavior and OOM risk.
 */
public record MemoryPressure(
    @JsonProperty("heap_used_bytes") long heapUsedBytes,
    @JsonProperty("heap_committed_bytes") long heapCommittedBytes,
    @JsonProperty("heap_max_bytes") long heapMaxBytes,
    @JsonProperty("non_heap_used_bytes") long nonHeapUsedBytes,
    @JsonProperty("direct_buffer_used_bytes") long directBufferUsedBytes,
    @JsonProperty("direct_buffer_max_bytes") long directBufferMaxBytes,
    @JsonProperty("allocation_rate_bytes_per_sec") long allocationRateBytesPerSec,
    @JsonProperty("promotion_rate_bytes_per_sec") long promotionRateBytesPerSec,
    @JsonProperty("survivor_space_used_percent") double survivorSpaceUsedPercent,
    @JsonProperty("tenuring_threshold") int tenuringThreshold,
    @JsonProperty("large_object_allocations_per_sec") long largeObjectAllocationsPerSec,
    @JsonProperty("fragmentation_percent") double fragmentationPercent,
    @JsonProperty("native_memory_used_bytes") long nativeMemoryUsedBytes,
    @JsonProperty("memory_pools") List<MemoryPool> memoryPools
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private long heapUsedBytes;
        private long heapCommittedBytes;
        private long heapMaxBytes;
        private long nonHeapUsedBytes;
        private long directBufferUsedBytes;
        private long directBufferMaxBytes;
        private long allocationRateBytesPerSec;
        private long promotionRateBytesPerSec;
        private double survivorSpaceUsedPercent;
        private int tenuringThreshold;
        private long largeObjectAllocationsPerSec;
        private double fragmentationPercent;
        private long nativeMemoryUsedBytes;
        private List<MemoryPool> memoryPools = List.of();

        public Builder heapUsedBytes(long v) { this.heapUsedBytes = v; return this; }
        public Builder heapCommittedBytes(long v) { this.heapCommittedBytes = v; return this; }
        public Builder heapMaxBytes(long v) { this.heapMaxBytes = v; return this; }
        public Builder nonHeapUsedBytes(long v) { this.nonHeapUsedBytes = v; return this; }
        public Builder directBufferUsedBytes(long v) { this.directBufferUsedBytes = v; return this; }
        public Builder directBufferMaxBytes(long v) { this.directBufferMaxBytes = v; return this; }
        public Builder allocationRateBytesPerSec(long v) { this.allocationRateBytesPerSec = v; return this; }
        public Builder promotionRateBytesPerSec(long v) { this.promotionRateBytesPerSec = v; return this; }
        public Builder survivorSpaceUsedPercent(double v) { this.survivorSpaceUsedPercent = v; return this; }
        public Builder tenuringThreshold(int v) { this.tenuringThreshold = v; return this; }
        public Builder largeObjectAllocationsPerSec(long v) { this.largeObjectAllocationsPerSec = v; return this; }
        public Builder fragmentationPercent(double v) { this.fragmentationPercent = v; return this; }
        public Builder nativeMemoryUsedBytes(long v) { this.nativeMemoryUsedBytes = v; return this; }
        public Builder memoryPools(List<MemoryPool> v) { this.memoryPools = v; return this; }

        public MemoryPressure build() {
            return new MemoryPressure(
                heapUsedBytes, heapCommittedBytes, heapMaxBytes, nonHeapUsedBytes,
                directBufferUsedBytes, directBufferMaxBytes, allocationRateBytesPerSec,
                promotionRateBytesPerSec, survivorSpaceUsedPercent, tenuringThreshold,
                largeObjectAllocationsPerSec, fragmentationPercent, nativeMemoryUsedBytes, memoryPools
            );
        }
    }
}
