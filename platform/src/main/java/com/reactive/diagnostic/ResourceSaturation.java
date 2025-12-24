package com.reactive.diagnostic;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Resource saturation - how full are our resources.
 * High saturation explains degraded throughput and latency.
 */
public record ResourceSaturation(
    @JsonProperty("cpu_percent") double cpuPercent,
    @JsonProperty("heap_used_percent") double heapUsedPercent,
    @JsonProperty("heap_used_bytes") long heapUsedBytes,
    @JsonProperty("heap_max_bytes") long heapMaxBytes,
    @JsonProperty("old_gen_percent") double oldGenPercent,
    @JsonProperty("old_gen_bytes") long oldGenBytes,
    @JsonProperty("old_gen_max_bytes") long oldGenMaxBytes,
    @JsonProperty("thread_pool_active") int threadPoolActive,
    @JsonProperty("thread_pool_max") int threadPoolMax,
    @JsonProperty("thread_pool_queue_depth") long threadPoolQueueDepth,
    @JsonProperty("connection_pool_active") int connectionPoolActive,
    @JsonProperty("connection_pool_max") int connectionPoolMax,
    @JsonProperty("buffer_pool_used_percent") double bufferPoolUsedPercent,
    @JsonProperty("file_descriptors_used") int fileDescriptorsUsed,
    @JsonProperty("file_descriptors_max") int fileDescriptorsMax
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private double cpuPercent;
        private double heapUsedPercent;
        private long heapUsedBytes;
        private long heapMaxBytes;
        private double oldGenPercent;
        private long oldGenBytes;
        private long oldGenMaxBytes;
        private int threadPoolActive;
        private int threadPoolMax;
        private long threadPoolQueueDepth;
        private int connectionPoolActive;
        private int connectionPoolMax;
        private double bufferPoolUsedPercent;
        private int fileDescriptorsUsed;
        private int fileDescriptorsMax;

        public Builder cpuPercent(double v) { this.cpuPercent = v; return this; }
        public Builder heapUsedPercent(double v) { this.heapUsedPercent = v; return this; }
        public Builder heapUsedBytes(long v) { this.heapUsedBytes = v; return this; }
        public Builder heapMaxBytes(long v) { this.heapMaxBytes = v; return this; }
        public Builder oldGenPercent(double v) { this.oldGenPercent = v; return this; }
        public Builder oldGenBytes(long v) { this.oldGenBytes = v; return this; }
        public Builder oldGenMaxBytes(long v) { this.oldGenMaxBytes = v; return this; }
        public Builder threadPoolActive(int v) { this.threadPoolActive = v; return this; }
        public Builder threadPoolMax(int v) { this.threadPoolMax = v; return this; }
        public Builder threadPoolQueueDepth(long v) { this.threadPoolQueueDepth = v; return this; }
        public Builder connectionPoolActive(int v) { this.connectionPoolActive = v; return this; }
        public Builder connectionPoolMax(int v) { this.connectionPoolMax = v; return this; }
        public Builder bufferPoolUsedPercent(double v) { this.bufferPoolUsedPercent = v; return this; }
        public Builder fileDescriptorsUsed(int v) { this.fileDescriptorsUsed = v; return this; }
        public Builder fileDescriptorsMax(int v) { this.fileDescriptorsMax = v; return this; }

        public ResourceSaturation build() {
            return new ResourceSaturation(
                cpuPercent, heapUsedPercent, heapUsedBytes, heapMaxBytes,
                oldGenPercent, oldGenBytes, oldGenMaxBytes, threadPoolActive,
                threadPoolMax, threadPoolQueueDepth, connectionPoolActive,
                connectionPoolMax, bufferPoolUsedPercent, fileDescriptorsUsed, fileDescriptorsMax
            );
        }
    }
}
