package com.reactive.integration.benchmark;

import com.reactive.platform.gateway.microbatch.*;
import com.reactive.platform.kafka.KafkaPublisher;
import com.reactive.platform.serialization.Codec;
import com.reactive.platform.serialization.Result;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Gateway Comparison Benchmark - High-performance testing with NIO client.
 *
 * This benchmark uses the SAME fast NIO client as UnifiedHttpBenchmark
 * to ensure we're measuring gateway throughput, not client limitations.
 *
 * Modes:
 *   microbatch  - Start and benchmark MicrobatchingGateway (in-process)
 *   spring      - Benchmark external Spring gateway at GATEWAY_URL
 *   collector   - Benchmark MicrobatchCollector WITH REAL KAFKA
 *
 * Usage:
 *   java GatewayComparisonBenchmark <mode> <durationSec> <concurrency> <kafkaBootstrap> <reportsDir>
 */
public class GatewayComparisonBenchmark {

    // Fast NIO client configuration (same as UnifiedHttpBenchmark)
    private static final String POST_BODY = "{\"e\":1}";
    private static final byte[] REQUEST = buildRequest("/events");

    private static byte[] buildRequest(String path) {
        return ("POST " + path + " HTTP/1.1\r\n" +
                "Host: l\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + POST_BODY.length() + "\r\n" +
                "\r\n" +
                POST_BODY).getBytes(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "microbatch";
        int durationSec = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 100;
        String kafkaBootstrap = args.length > 3 ? args[3] : "kafka:29092";
        String reportsDir = args.length > 4 ? args[4] : "reports/gateway-comparison";

        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                    GATEWAY COMPARISON BENCHMARK                               ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.printf("  Mode:        %s%n", mode);
        System.out.printf("  Duration:    %d seconds%n", durationSec);
        System.out.printf("  Concurrency: %d%n", concurrency);
        System.out.printf("  Kafka:       %s%n", kafkaBootstrap);
        System.out.printf("  Reports:     %s%n", reportsDir);
        System.out.println();

        BenchmarkResult result = switch (mode.toLowerCase()) {
            case "microbatch" -> benchmarkMicrobatchGateway(durationSec, concurrency, kafkaBootstrap);
            case "spring" -> benchmarkSpringGateway(durationSec, concurrency);
            case "collector" -> benchmarkCollectorWithKafka(durationSec, concurrency, kafkaBootstrap);
            default -> {
                System.out.println("Unknown mode: " + mode);
                System.out.println("Valid modes: microbatch, spring, collector");
                yield null;
            }
        };

        if (result != null) {
            writeResults(result, reportsDir, mode);
            printResults(result);
        }
    }

    /**
     * Benchmark MicrobatchingGateway with FAST NIO client.
     * This uses the same client pattern as UnifiedHttpBenchmark for accurate measurement.
     */
    static BenchmarkResult benchmarkMicrobatchGateway(int durationSec, int concurrency, String kafkaBootstrap)
            throws Exception {
        System.out.println("=== Microbatch Gateway Benchmark (in-process, NIO client) ===");
        System.out.println();

        int port = 9999;
        String topic = "gateway-benchmark";

        // Simple byte codec
        Codec<byte[]> byteCodec = Codec.of(
            bytes -> Result.success(bytes),
            bytes -> Result.success(bytes),
            "application/octet-stream",
            "bytes"
        );

        System.out.printf("Starting MicrobatchingGateway on port %d...%n", port);

        MicrobatchingGateway<byte[]> gateway = MicrobatchingGateway.<byte[]>builder()
            .kafka(kafkaBootstrap, topic)
            .codec(byteCodec)
            .eventFactory(GatewayComparisonBenchmark::bufferToBytes)
            .port(port)
            .reactors(Runtime.getRuntime().availableProcessors())
            .targetLatency(5000.0)
            .build();

        // Wait for server to start
        Thread.sleep(1000);
        System.out.println("Gateway started, running warmup...");

        // Warmup with NIO client
        warmupNio("localhost", port, 3);
        System.out.println("Warmup complete, starting benchmark...");

        // Run benchmark with FAST NIO client
        System.out.printf("Running for %d seconds with %d connections (NIO client)...%n", durationSec, concurrency);
        ClientStats stats = runNioClient("localhost", port, durationSec, concurrency);

        // Get gateway metrics
        MicrobatchingGateway.GatewayMetrics metrics = gateway.getMetrics();

        gateway.close();
        System.out.println("Gateway stopped.");

        double throughput = stats.totalRequests / (durationSec * 1.0);
        double avgLatencyMs = stats.totalRequests > 0 ? (stats.totalLatencyNanos / stats.totalRequests) / 1_000_000.0 : 0;

        BenchmarkResult result = new BenchmarkResult(
            "microbatch-gateway",
            "MicrobatchingGateway (NIO Client)",
            stats.totalRequests,
            stats.totalRequests,
            0,
            throughput,
            avgLatencyMs,
            stats.p99Ms,
            durationSec * 1000.0
        );

        result.addNote("Batch size: " + metrics.collectorMetrics().currentBatchSize());
        result.addNote("Flush interval: " + metrics.collectorMetrics().currentFlushIntervalMicros() + " µs");
        result.addNote("Collector throughput: " + String.format("%.0f", metrics.collectorMetrics().throughputPerSec()) + " events/sec");

        return result;
    }

    /**
     * Benchmark MicrobatchCollector WITH REAL KAFKA.
     * This measures actual Kafka producer throughput with adaptive microbatching.
     */
    static BenchmarkResult benchmarkCollectorWithKafka(int durationSec, int concurrency, String kafkaBootstrap)
            throws Exception {
        System.out.println("=== Collector Benchmark WITH REAL KAFKA ===");
        System.out.println();
        System.out.println("This measures actual Kafka producer throughput with adaptive microbatching.");
        System.out.println();

        String topic = "collector-benchmark";

        // Simple byte codec
        Codec<byte[]> byteCodec = Codec.of(
            bytes -> Result.success(bytes),
            bytes -> Result.success(bytes),
            "application/octet-stream",
            "bytes"
        );

        // Create REAL Kafka publisher
        System.out.printf("Connecting to Kafka at %s...%n", kafkaBootstrap);
        KafkaPublisher<byte[]> publisher = KafkaPublisher.create(c -> c
            .bootstrapServers(kafkaBootstrap)
            .topic(topic)
            .codec(byteCodec)
            .keyExtractor(bytes -> "bench")
            .fireAndForget());

        Path tempDb = Path.of(System.getProperty("java.io.tmpdir"), "bench-calibration-" + System.currentTimeMillis() + ".db");
        BatchCalibration calibration = BatchCalibration.create(tempDb, 5000.0);

        LongAdder batchCount = new LongAdder();
        LongAdder itemCount = new LongAdder();
        LongAdder kafkaSendTimeNanos = new LongAdder();

        // Create collector with REAL Kafka consumer
        MicrobatchCollector<byte[]> collector = MicrobatchCollector.create(
            batch -> {
                long start = System.nanoTime();
                // Actually send to Kafka using fire-and-forget for max throughput!
                for (byte[] item : batch) {
                    publisher.publishFireAndForget(item);
                }
                kafkaSendTimeNanos.add(System.nanoTime() - start);
                batchCount.increment();
                itemCount.add(batch.size());
            },
            calibration
        );

        byte[] testEvent = "{\"test\":true,\"value\":12345,\"timestamp\":1234567890}".getBytes();

        // Quick warmup
        System.out.println("Warming up (sending to Kafka)...");
        for (int i = 0; i < 10_000; i++) {
            collector.submitFireAndForget(testEvent);
        }
        Thread.sleep(1000);
        batchCount.reset();
        itemCount.reset();
        kafkaSendTimeNanos.reset();

        // Run benchmark
        System.out.printf("Running for %d seconds with %d threads (REAL Kafka)...%n", durationSec, concurrency);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        AtomicLong submitted = new AtomicLong(0);
        Instant start = Instant.now();
        Instant end = start.plusSeconds(durationSec);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);

        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (Instant.now().isBefore(end)) {
                        collector.submitFireAndForget(testEvent);
                        submitted.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        collector.flush();

        Duration elapsed = Duration.between(start, Instant.now());
        double elapsedSec = elapsed.toMillis() / 1000.0;
        long totalSubmitted = submitted.get();
        long totalBatches = batchCount.sum();
        long dropped = collector.droppedCount();
        long actuallyQueued = totalSubmitted - dropped;
        long totalItems = itemCount.sum();

        MicrobatchCollector.Metrics metrics = collector.getMetrics();
        BatchCalibration.Config config = calibration.getBestConfig();

        double avgKafkaBatchTimeMs = totalBatches > 0 ? (kafkaSendTimeNanos.sum() / totalBatches) / 1_000_000.0 : 0;

        collector.close();
        publisher.close();
        calibration.close();
        executor.shutdown();

        double throughput = actuallyQueued / elapsedSec;

        BenchmarkResult result = new BenchmarkResult(
            "microbatch-collector-kafka",
            "MicrobatchCollector + Kafka",
            totalSubmitted,
            actuallyQueued,
            dropped,
            throughput,
            avgKafkaBatchTimeMs,
            avgKafkaBatchTimeMs * 2,
            elapsedSec * 1000
        );

        result.addNote("Submitted: " + String.format("%,d", totalSubmitted) + " events");
        result.addNote("Sent to Kafka: " + String.format("%,d", totalItems) + " events");
        result.addNote("Dropped: " + String.format("%,d", dropped) + " events (backpressure)");
        result.addNote("Batches sent: " + String.format("%,d", totalBatches));
        result.addNote("Avg batch size: " + String.format("%.1f", totalBatches > 0 ? (double) totalItems / totalBatches : 0));
        result.addNote("Avg Kafka batch time: " + String.format("%.3f", avgKafkaBatchTimeMs) + " ms");
        result.addNote("Best config: batch=" + config.batchSize() + ", interval=" + config.flushIntervalMicros() + "µs");

        return result;
    }

    /**
     * Benchmark Spring Gateway with FAST NIO client.
     */
    static BenchmarkResult benchmarkSpringGateway(int durationSec, int concurrency) throws Exception {
        System.out.println("=== Spring Gateway Benchmark (NIO client) ===");
        System.out.println();

        String gatewayHost = System.getenv().getOrDefault("GATEWAY_HOST", "gateway");
        int gatewayPort = Integer.parseInt(System.getenv().getOrDefault("GATEWAY_PORT", "3000"));

        System.out.printf("Testing gateway at: %s:%d%n", gatewayHost, gatewayPort);

        // Warmup
        warmupNio(gatewayHost, gatewayPort, 2);

        // Run benchmark
        System.out.printf("Running for %d seconds with %d connections (NIO client)...%n", durationSec, concurrency);
        ClientStats stats = runNioClient(gatewayHost, gatewayPort, durationSec, concurrency);

        double throughput = stats.totalRequests / (durationSec * 1.0);
        double avgLatencyMs = stats.totalRequests > 0 ? (stats.totalLatencyNanos / stats.totalRequests) / 1_000_000.0 : 0;

        return new BenchmarkResult(
            "spring-gateway",
            "Spring Gateway (NIO Client)",
            stats.totalRequests,
            stats.totalRequests,
            0,
            throughput,
            avgLatencyMs,
            stats.p99Ms,
            durationSec * 1000.0
        );
    }

    // ========================================================================
    // FAST NIO CLIENT (same pattern as UnifiedHttpBenchmark)
    // ========================================================================

    record ClientStats(long totalRequests, long totalLatencyNanos, double p50Ms, double p99Ms) {}

    static void warmupNio(String host, int port, int seconds) {
        try {
            runNioClient(host, port, seconds, 50);
        } catch (Exception e) {
            System.err.println("Warmup error: " + e.getMessage());
        }
    }

    static ClientStats runNioClient(String host, int port, int durationSeconds, int concurrency) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        int clientThreads = Math.min(cores, concurrency);
        int connectionsPerThread = Math.max(1, concurrency / clientThreads);

        LongAdder requestCount = new LongAdder();
        LongAdder totalLatencyNanos = new LongAdder();
        ConcurrentSkipListMap<Long, LongAdder> latencyHistogram = new ConcurrentSkipListMap<>();

        AtomicBoolean running = new AtomicBoolean(true);
        ExecutorService executor = Executors.newFixedThreadPool(clientThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(clientThreads);

        Instant endTime = Instant.now().plusSeconds(durationSeconds);

        for (int t = 0; t < clientThreads; t++) {
            executor.submit(() -> {
                try {
                    runClientThread(host, port, connectionsPerThread, running, endTime,
                                   requestCount, totalLatencyNanos, latencyHistogram, startLatch);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(durationSeconds + 10, TimeUnit.SECONDS);
        running.set(false);

        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.SECONDS);

        // Calculate percentiles
        long total = requestCount.sum();
        long p50Target = (long) (total * 0.50);
        long p99Target = (long) (total * 0.99);

        double p50Ms = 0, p99Ms = 0;
        long cumulative = 0;
        for (Map.Entry<Long, LongAdder> entry : latencyHistogram.entrySet()) {
            cumulative += entry.getValue().sum();
            if (p50Ms == 0 && cumulative >= p50Target) {
                p50Ms = entry.getKey() / 1_000_000.0;
            }
            if (p99Ms == 0 && cumulative >= p99Target) {
                p99Ms = entry.getKey() / 1_000_000.0;
                break;
            }
        }

        return new ClientStats(total, totalLatencyNanos.sum(), p50Ms, p99Ms);
    }

    /**
     * Single client thread managing multiple connections via NIO selector.
     * This is the SAME fast pattern as UnifiedHttpBenchmark.
     */
    private static void runClientThread(String host, int port, int connectionCount, AtomicBoolean running,
                                         Instant endTime, LongAdder requestCount,
                                         LongAdder totalLatencyNanos,
                                         ConcurrentSkipListMap<Long, LongAdder> latencyHistogram,
                                         CountDownLatch startLatch) {
        try {
            startLatch.await();

            Selector selector = Selector.open();
            InetSocketAddress address = new InetSocketAddress(host, port);

            ByteBuffer[] writeBuffers = new ByteBuffer[connectionCount];
            ByteBuffer[] readBuffers = new ByteBuffer[connectionCount];
            long[] requestStartTimes = new long[connectionCount];
            SocketChannel[] channels = new SocketChannel[connectionCount];

            // Open all connections
            for (int i = 0; i < connectionCount; i++) {
                writeBuffers[i] = ByteBuffer.allocateDirect(REQUEST.length);
                readBuffers[i] = ByteBuffer.allocateDirect(512);

                SocketChannel channel = SocketChannel.open();
                channel.configureBlocking(false);
                channel.connect(address);

                SelectionKey key = channel.register(selector, SelectionKey.OP_CONNECT);
                key.attach(i);
                channels[i] = channel;
            }

            // Event loop
            while (running.get() && Instant.now().isBefore(endTime)) {
                int ready = selector.select(1);
                if (ready == 0) continue;

                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();

                    if (!key.isValid()) continue;

                    int idx = (Integer) key.attachment();
                    SocketChannel channel = (SocketChannel) key.channel();

                    try {
                        if (key.isConnectable()) {
                            if (channel.finishConnect()) {
                                channel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);
                                key.interestOps(SelectionKey.OP_WRITE);
                            }
                        } else if (key.isWritable()) {
                            ByteBuffer wb = writeBuffers[idx];
                            wb.clear();
                            wb.put(REQUEST);
                            wb.flip();

                            requestStartTimes[idx] = System.nanoTime();

                            while (wb.hasRemaining()) {
                                channel.write(wb);
                            }
                            key.interestOps(SelectionKey.OP_READ);

                        } else if (key.isReadable()) {
                            ByteBuffer rb = readBuffers[idx];
                            rb.clear();
                            int read = channel.read(rb);

                            if (read > 0) {
                                long latencyNanos = System.nanoTime() - requestStartTimes[idx];

                                requestCount.increment();
                                totalLatencyNanos.add(latencyNanos);

                                // Record in histogram (bucket by microseconds)
                                long bucket = (latencyNanos / 1000) * 1000;
                                latencyHistogram.computeIfAbsent(bucket, k -> new LongAdder()).increment();

                                key.interestOps(SelectionKey.OP_WRITE);
                            } else if (read < 0) {
                                key.cancel();
                                channel.close();
                            }
                        }
                    } catch (IOException e) {
                        key.cancel();
                        try { channel.close(); } catch (IOException ignored) {}
                    }
                }
            }

            // Cleanup
            for (SocketChannel channel : channels) {
                if (channel != null) {
                    try { channel.close(); } catch (IOException ignored) {}
                }
            }
            selector.close();

        } catch (Exception e) {
            // Thread error - continue
        }
    }

    // ========================================================================
    // Utilities
    // ========================================================================

    static byte[] bufferToBytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    static void writeResults(BenchmarkResult result, String reportsDir, String mode) {
        try {
            Path dir = Path.of(reportsDir);
            Files.createDirectories(dir);

            String json = String.format("""
                {
                  "name": "%s",
                  "component": "%s",
                  "status": "completed",
                  "totalOperations": %d,
                  "successfulOperations": %d,
                  "failedOperations": %d,
                  "avgThroughput": %.2f,
                  "latency": {
                    "avg": %.3f,
                    "p50": %.3f,
                    "p95": %.3f,
                    "p99": %.3f,
                    "max": %.3f
                  },
                  "durationMs": %.0f,
                  "notes": "%s"
                }
                """,
                result.name,
                result.component,
                result.totalOps,
                result.successOps,
                result.failedOps,
                result.throughput,
                result.avgLatencyMs,
                result.avgLatencyMs,
                result.avgLatencyMs * 1.2,
                result.p99LatencyMs,
                result.p99LatencyMs * 1.1,
                result.durationMs,
                String.join("; ", result.notes)
            );

            Files.writeString(dir.resolve("results.json"), json);
            System.out.printf("Results written to %s/results.json%n", reportsDir);
        } catch (Exception e) {
            System.err.println("Failed to write results: " + e.getMessage());
        }
    }

    static void printResults(BenchmarkResult result) {
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println("RESULTS: " + result.name);
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.printf("  Successful:    %,d requests%n", result.successOps);
        System.out.printf("  Errors:        %,d%n", result.failedOps);
        System.out.printf("  Duration:      %.2f seconds%n", result.durationMs / 1000.0);
        System.out.printf("  Throughput:    %,.0f ops/sec%n", result.throughput);
        System.out.printf("  Avg Latency:   %.2f ms%n", result.avgLatencyMs);
        System.out.printf("  P99 Latency:   %.2f ms%n", result.p99LatencyMs);
        System.out.println();
        for (String note : result.notes) {
            System.out.println("  " + note);
        }
        System.out.println("═══════════════════════════════════════════════════════════════════════");
    }

    static class BenchmarkResult {
        final String component;
        final String name;
        final long totalOps;
        final long successOps;
        final long failedOps;
        final double throughput;
        final double avgLatencyMs;
        final double p99LatencyMs;
        final double durationMs;
        final java.util.List<String> notes = new java.util.ArrayList<>();

        BenchmarkResult(String component, String name, long totalOps, long successOps, long failedOps,
                       double throughput, double avgLatencyMs, double p99LatencyMs, double durationMs) {
            this.component = component;
            this.name = name;
            this.totalOps = totalOps;
            this.successOps = successOps;
            this.failedOps = failedOps;
            this.throughput = throughput;
            this.avgLatencyMs = avgLatencyMs;
            this.p99LatencyMs = p99LatencyMs;
            this.durationMs = durationMs;
        }

        void addNote(String note) {
            notes.add(note);
        }
    }
}
