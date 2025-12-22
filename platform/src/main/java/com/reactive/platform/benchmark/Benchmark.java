package com.reactive.platform.benchmark;

import com.reactive.platform.benchmark.BenchmarkTypes.*;

/**
 * Benchmark interface - all component benchmarks implement this.
 */
public interface Benchmark {

    /** Component ID (http, kafka, flink, drools, gateway, full). */
    ComponentId id();

    /** Display name. */
    String name();

    /** Description. */
    String description();

    /** Run the benchmark with the given configuration. */
    BenchmarkResult run(Config config);

    /** Stop the running benchmark. */
    void stop();

    /** Check if benchmark is currently running. */
    boolean isRunning();
}
