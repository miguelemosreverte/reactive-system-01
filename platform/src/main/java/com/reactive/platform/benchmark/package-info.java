/**
 * Benchmark infrastructure for the Reactive System.
 *
 * <h2>Components</h2>
 * <ul>
 *   <li>{@link com.reactive.platform.benchmark.Benchmark} - Interface for all benchmarks</li>
 *   <li>{@link com.reactive.platform.benchmark.BaseBenchmark} - Base class with common logic</li>
 *   <li>{@link com.reactive.platform.benchmark.BenchmarkResult} - Immutable result record</li>
 *   <li>{@link com.reactive.platform.benchmark.BenchmarkTypes} - Types (Config, LatencyStats, etc.)</li>
 *   <li>{@link com.reactive.platform.benchmark.ObservabilityFetcher} - Jaeger/Loki integration</li>
 *   <li>{@link com.reactive.platform.benchmark.BenchmarkReportGenerator} - JSON report generator</li>
 *   <li>{@link com.reactive.platform.benchmark.BenchmarkCli} - CLI entry point</li>
 * </ul>
 *
 * <h2>Available Benchmarks</h2>
 * Each component has its own benchmark in its test folder:
 * <ul>
 *   <li>gateway: HttpBenchmark - HTTP endpoint latency</li>
 *   <li>drools: DroolsBenchmark - Direct Drools rule evaluation</li>
 *   <li>application: FullBenchmark - Complete E2E pipeline</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Register a benchmark
 * BenchmarkCli.register(ComponentId.FULL, FullBenchmark::create);
 *
 * // Run via CLI
 * java -cp ... com.reactive.platform.benchmark.BenchmarkCli full --duration 30
 * }</pre>
 *
 * @see com.reactive.platform.benchmark.BenchmarkCli
 */
package com.reactive.platform.benchmark;
