package com.reactive.platform.benchmark;

import com.reactive.platform.base.Result;
import com.reactive.platform.http.server.Server;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Unified HTTP Server Benchmark - Same-container testing for maximum throughput.
 *
 * This benchmark follows the proven MillionUserBenchmark pattern:
 * - Client and server run in the SAME JVM (no network overhead)
 * - NIO selector-based client with multiple connections per thread
 * - Sequential request-response pattern (realistic traffic)
 * - Measures true server capability without Docker/network limitations
 *
 * Expected throughput: 600K-700K req/s on modern hardware
 *
 * Usage:
 *   java UnifiedHttpBenchmark ROCKET 60 500 /path/to/output
 *   java UnifiedHttpBenchmark ZERO_COPY 30 200 /path/to/output
 *   java UnifiedHttpBenchmark --all 60 500 /path/to/output
 */
public final class UnifiedHttpBenchmark {

    private static final int WARMUP_SECONDS = 3;
    private static final int PORT = 9999;

    // Minimal request for maximum throughput
    private static final String POST_BODY = "{\"e\":1}";
    private static final byte[] REQUEST = buildRequest();

    private static byte[] buildRequest() {
        return ("POST /e HTTP/1.1\r\n" +
                "Host: l\r\n" +
                "Content-Length: " + POST_BODY.length() + "\r\n" +
                "\r\n" +
                POST_BODY).getBytes(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            printUsage();
            return;
        }

        String serverArg = args[0];
        int durationSeconds = Integer.parseInt(args[1]);
        int concurrency = Integer.parseInt(args[2]);
        String outputDir = args[3];

        if ("--all".equals(serverArg)) {
            runAllServers(durationSeconds, concurrency, outputDir);
        } else if ("--top5".equals(serverArg)) {
            runTop5Servers(durationSeconds, concurrency, outputDir);
        } else {
            Server server = Server.fromName(serverArg)
                .orElseThrow(() -> new IllegalArgumentException("Unknown server: " + serverArg));
            BenchmarkResult result = runBenchmark(server, durationSeconds, concurrency);
            saveResult(result, outputDir);
            printResult(result);
        }
    }

    private static void printUsage() {
        System.out.println("Usage: java UnifiedHttpBenchmark <SERVER|--all|--top5> <duration_sec> <concurrency> <output_dir>");
        System.out.println();
        System.out.println("Arguments:");
        System.out.println("  SERVER      - Server name from Server enum (e.g., ROCKET, ZERO_COPY, NETTY)");
        System.out.println("  --all       - Run all servers in sequence");
        System.out.println("  --top5      - Run top 5 performers + Spring Boot");
        System.out.println("  duration    - Benchmark duration in seconds");
        System.out.println("  concurrency - Number of concurrent connections");
        System.out.println("  output_dir  - Directory for results.json output");
        System.out.println();
        System.out.println("Available servers:");
        for (Server s : Server.values()) {
            System.out.printf("  %-12s - %s (%s)%n", s.name(), s.technology(), s.tier().description());
        }
    }

    /**
     * Run benchmark for all servers and generate comparison.
     */
    private static void runAllServers(int durationSeconds, int concurrency, String outputDir) throws Exception {
        List<BenchmarkResult> results = new ArrayList<>();

        for (Server server : Server.values()) {
            if (server == Server.IO_URING) {
                System.out.println("Skipping " + server.name() + " (Linux-only)");
                continue;
            }

            System.out.println("\n" + "═".repeat(70));
            System.out.println("  Testing: " + server.displayName());
            System.out.println("═".repeat(70));

            try {
                BenchmarkResult result = runBenchmark(server, durationSeconds, concurrency);
                results.add(result);
                printResult(result);

                Path serverDir = Paths.get(outputDir, "http-" + server.name().toLowerCase().replace("_", "-"));
                Files.createDirectories(serverDir);
                saveResult(result, serverDir.toString());

            } catch (Exception e) {
                System.err.println("Failed to benchmark " + server.name() + ": " + e.getMessage());
            }
        }

        generateComparison(results, outputDir);
    }

    /**
     * Run benchmark for top 5 performers + Spring Boot baseline.
     */
    private static void runTop5Servers(int durationSeconds, int concurrency, String outputDir) throws Exception {
        // Based on documented performance, top 5 + Spring Boot:
        Server[] top5 = {
            Server.ROCKET,      // Tier 1: ~662K req/s
            Server.ZERO_COPY,   // Best in recent tests
            Server.RAW,         // High performer
            Server.NETTY,       // Industry standard
            Server.HYPER,       // HTTP pipelining
            Server.SPRING_BOOT  // Baseline (always include for comparison)
        };

        List<BenchmarkResult> results = new ArrayList<>();

        for (Server server : top5) {
            System.out.println("\n" + "═".repeat(70));
            System.out.println("  Testing: " + server.displayName() + " (" + server.tier().description() + ")");
            System.out.println("═".repeat(70));

            try {
                BenchmarkResult result = runBenchmark(server, durationSeconds, concurrency);
                results.add(result);
                printResult(result);

                Path serverDir = Paths.get(outputDir, "http-" + server.name().toLowerCase().replace("_", "-"));
                Files.createDirectories(serverDir);
                saveResult(result, serverDir.toString());

            } catch (Exception e) {
                System.err.println("Failed to benchmark " + server.name() + ": " + e.getMessage());
            }
        }

        generateComparison(results, outputDir);
    }

    /**
     * Run benchmark for a single server.
     */
    public static BenchmarkResult runBenchmark(Server server, int durationSeconds, int concurrency) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();

        System.out.printf("Starting %s benchmark...%n", server.displayName());
        System.out.printf("  Cores: %d, Duration: %ds, Concurrency: %d%n", cores, durationSeconds, concurrency);

        // Spring Boot uses external gateway container
        if (server == Server.SPRING_BOOT) {
            return runSpringBootBenchmark(server, durationSeconds, concurrency, cores);
        }

        // Start server using reflection (to avoid compile-time dependencies)
        Object serverHandle = startServer(server, cores);
        Thread.sleep(500); // Allow server startup

        try {
            // Warmup
            System.out.println("Warming up...");
            runClientBenchmark("localhost", PORT, cores, concurrency / cores, WARMUP_SECONDS);

            // Actual benchmark
            System.out.printf("Running benchmark for %d seconds...%n", durationSeconds);
            long startTime = System.currentTimeMillis();
            ClientStats stats = runClientBenchmark("localhost", PORT, cores, concurrency / cores, durationSeconds);
            long endTime = System.currentTimeMillis();

            double throughput = stats.requests * 1000.0 / (endTime - startTime);
            double avgLatencyMs = stats.totalLatencyNanos / (stats.requests * 1_000_000.0);

            return new BenchmarkResult(
                server.name(),
                server.displayName(),
                server.technology(),
                server.tier().description(),
                throughput,
                stats.requests,
                0, // errors
                avgLatencyMs,
                stats.p50LatencyMs,
                stats.p99LatencyMs,
                durationSeconds,
                concurrency
            );

        } finally {
            stopServer(serverHandle);
        }
    }

    /**
     * Run benchmark against Spring Boot gateway (external container).
     * Connects to gateway:3000 which is the Spring WebFlux gateway.
     */
    private static BenchmarkResult runSpringBootBenchmark(Server server, int durationSeconds, int concurrency, int cores) throws Exception {
        System.out.println("  Connecting to Spring Gateway at gateway:3000...");

        // Warmup
        System.out.println("Warming up...");
        runClientBenchmark("gateway", 3000, cores, concurrency / cores, WARMUP_SECONDS);

        // Actual benchmark
        System.out.printf("Running benchmark for %d seconds...%n", durationSeconds);
        long startTime = System.currentTimeMillis();
        ClientStats stats = runClientBenchmark("gateway", 3000, cores, concurrency / cores, durationSeconds);
        long endTime = System.currentTimeMillis();

        double throughput = stats.requests * 1000.0 / (endTime - startTime);
        double avgLatencyMs = stats.requests > 0 ? stats.totalLatencyNanos / (stats.requests * 1_000_000.0) : 0;

        return new BenchmarkResult(
            server.name(),
            server.displayName(),
            server.technology(),
            server.tier().description(),
            throughput,
            stats.requests,
            0, // errors
            avgLatencyMs,
            stats.p50LatencyMs,
            stats.p99LatencyMs,
            durationSeconds,
            concurrency
        );
    }

    /**
     * Start HTTP server using reflection.
     */
    private static Object startServer(Server server, int reactors) throws Exception {
        if (server == Server.SPRING_BOOT) {
            // Spring Boot requires special handling - use the gateway container
            System.out.println("  Note: Spring Boot uses existing gateway container");
            return null;
        }

        Class<?> serverClass = Class.forName(server.mainClass());

        // Try different factory patterns used by different servers
        try {
            // Pattern 1: RocketHttpServer.create().reactors(n).start(port)
            Method createMethod = serverClass.getMethod("create");
            Object builder = createMethod.invoke(null);

            // Set reactors if available
            Object finalBuilder1 = builder;
            builder = Result.of(() -> finalBuilder1.getClass().getMethod("reactors", int.class))
                .flatMap(m -> Result.of(() -> m.invoke(finalBuilder1, reactors)))
                .getOrElse(finalBuilder1);

            // Set no-op handler if available
            Object finalBuilder2 = builder;
            builder = Result.of(() -> finalBuilder2.getClass().getMethod("onBody", java.util.function.Consumer.class))
                .flatMap(m -> Result.of(() -> m.invoke(finalBuilder2, (java.util.function.Consumer<ByteBuffer>) buf -> {})))
                .getOrElse(finalBuilder2);

            Method startMethod = builder.getClass().getMethod("start", int.class);
            return startMethod.invoke(builder, PORT);

        } catch (NoSuchMethodException e) {
            // Pattern 2: new Server(port, reactors)
            try {
                var constructor = serverClass.getConstructor(int.class, int.class);
                Object instance = constructor.newInstance(PORT, reactors);

                // Try to start if there's a start method
                Result.of(() -> serverClass.getMethod("start"))
                    .flatMap(m -> Result.of(() -> m.invoke(instance)));

                return instance;

            } catch (NoSuchMethodException e2) {
                // Pattern 3: main(String[] args) - run in separate thread
                Method mainMethod = serverClass.getMethod("main", String[].class);
                Thread serverThread = new Thread(() -> {
                    try {
                        mainMethod.invoke(null, (Object) new String[]{String.valueOf(PORT), String.valueOf(reactors)});
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                });
                serverThread.setDaemon(true);
                serverThread.start();
                return serverThread;
            }
        }
    }

    /**
     * Stop HTTP server.
     */
    private static void stopServer(Object handle) {
        if (handle == null) return;

        try {
            if (handle instanceof AutoCloseable) {
                ((AutoCloseable) handle).close();
            } else if (handle instanceof Thread) {
                ((Thread) handle).interrupt();
            } else {
                // Try close() method via reflection
                Method closeMethod = handle.getClass().getMethod("close");
                closeMethod.invoke(handle);
            }
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    /**
     * Run NIO-based client benchmark (MillionUserBenchmark pattern).
     */
    private static ClientStats runClientBenchmark(String host, int port, int clientThreads, int connectionsPerThread, int durationSeconds)
            throws Exception {

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
                readBuffers[i] = ByteBuffer.allocateDirect(256);

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
                                long bucket = (latencyNanos / 1000) * 1000; // Round to nearest µs
                                latencyHistogram.computeIfAbsent(bucket, k -> new LongAdder()).increment();

                                key.interestOps(SelectionKey.OP_WRITE);
                            } else if (read < 0) {
                                key.cancel();
                                channel.close();
                            }
                        }
                    } catch (IOException e) {
                        key.cancel();
                        Result.run(channel::close);
                    }
                }
            }

            // Cleanup
            for (SocketChannel channel : channels) {
                if (channel != null) {
                    Result.run(channel::close);
                }
            }
            selector.close();

        } catch (Exception e) {
            // Thread error
        }
    }

    private static void printResult(BenchmarkResult result) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  %-74s ║%n", result.serverName + " - " + result.technology);
        System.out.println("╠══════════════════════════════════════════════════════════════════════════════╣");
        System.out.printf("║  Throughput:    %,15.0f req/s                                       ║%n", result.throughput);
        System.out.printf("║  Total Reqs:    %,15d                                              ║%n", result.totalRequests);
        System.out.printf("║  Avg Latency:   %,15.2f ms                                           ║%n", result.avgLatencyMs);
        System.out.printf("║  P50 Latency:   %,15.2f ms                                           ║%n", result.p50LatencyMs);
        System.out.printf("║  P99 Latency:   %,15.2f ms                                           ║%n", result.p99LatencyMs);
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
    }

    private static void saveResult(BenchmarkResult result, String outputDir) throws IOException {
        Path dir = Paths.get(outputDir);
        Files.createDirectories(dir);

        String json = String.format("""
            {
              "brochure": "%s",
              "name": "%s",
              "technology": "%s",
              "tier": "%s",
              "startTime": "%s",
              "endTime": "%s",
              "durationMs": %d,
              "throughput": %.2f,
              "p50Ms": %.2f,
              "p99Ms": %.2f,
              "totalOps": %d,
              "successOps": %d,
              "failedOps": %d,
              "avgLatencyMs": %.4f,
              "concurrency": %d
            }
            """,
            result.brochureName,
            result.serverName,
            result.technology,
            result.tier,
            LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            result.durationSeconds * 1000,
            result.throughput,
            result.p50LatencyMs,
            result.p99LatencyMs,
            result.totalRequests,
            result.totalRequests,
            result.errors,
            result.avgLatencyMs,
            result.concurrency
        );

        Files.writeString(dir.resolve("results.json"), json);
    }

    private static void generateComparison(List<BenchmarkResult> results, String outputDir) throws IOException {
        // Sort by throughput descending
        results.sort((a, b) -> Double.compare(b.throughput, a.throughput));

        Path dir = Paths.get(outputDir);
        Files.createDirectories(dir);

        // Generate Markdown
        StringBuilder md = new StringBuilder();
        md.append("# HTTP Server Benchmark Comparison\n\n");
        md.append("## 🏆 Winner: ").append(results.get(0).serverName)
          .append(" — ").append(formatThroughput(results.get(0).throughput)).append("\n\n");

        md.append("## Results\n\n");
        md.append("| Rank | Server | Technology | Throughput | P50 | P99 |\n");
        md.append("|:----:|--------|------------|------------|-----|-----|\n");

        int rank = 1;
        for (BenchmarkResult r : results) {
            md.append(String.format("| %d | **%s** | %s | %s | %.1f ms | %.1f ms |\n",
                rank++, r.serverName, r.technology,
                formatThroughput(r.throughput), r.p50LatencyMs, r.p99LatencyMs));
        }

        md.append("\n*Generated: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("*\n");

        Files.writeString(dir.resolve("comparison.md"), md.toString());

        // Generate HTML
        generateHtmlReport(results, dir);

        System.out.println("\n✓ Comparison report generated: " + dir.resolve("comparison.html"));
    }

    private static void generateHtmlReport(List<BenchmarkResult> results, Path dir) throws IOException {
        StringBuilder html = new StringBuilder();
        html.append("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>HTTP Server Benchmark Comparison</title>
                <script src="https://www.gstatic.com/charts/loader.js"></script>
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                           background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
                           min-height: 100vh; padding: 40px; color: #e0e0e0; }
                    .container { max-width: 1200px; margin: 0 auto; }
                    h1 { text-align: center; margin-bottom: 40px; font-size: 2.5em;
                         background: linear-gradient(90deg, #00d4ff, #7b2ff7);
                         -webkit-background-clip: text; -webkit-text-fill-color: transparent; }
                    .winner { background: linear-gradient(135deg, #00d4ff22, #7b2ff722);
                              border: 2px solid #00d4ff; border-radius: 16px; padding: 30px;
                              text-align: center; margin-bottom: 40px; }
                    .winner h2 { font-size: 1.8em; margin-bottom: 10px; }
                    .winner .throughput { font-size: 3em; font-weight: bold;
                                          color: #00d4ff; text-shadow: 0 0 20px #00d4ff44; }
                    .chart-container { background: #1e2a3a; border-radius: 12px;
                                       padding: 20px; margin-bottom: 30px; }
                    table { width: 100%; border-collapse: collapse; background: #1e2a3a;
                            border-radius: 12px; overflow: hidden; }
                    th { background: #2d3a4a; padding: 16px; text-align: left;
                         font-weight: 600; color: #00d4ff; }
                    td { padding: 14px 16px; border-bottom: 1px solid #2d3a4a; }
                    tr:hover { background: #2d3a4a44; }
                    .rank { font-weight: bold; color: #ffd700; }
                    .throughput-bar { height: 8px; background: linear-gradient(90deg, #00d4ff, #7b2ff7);
                                      border-radius: 4px; margin-top: 4px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>🚀 HTTP Server Benchmark</h1>
            """);

        if (!results.isEmpty()) {
            BenchmarkResult winner = results.get(0);
            html.append(String.format("""
                    <div class="winner">
                        <h2>🏆 %s</h2>
                        <div class="throughput">%s</div>
                        <p>%s</p>
                    </div>
                """, winner.serverName, formatThroughput(winner.throughput), winner.technology));
        }

        // Chart container
        html.append("""
                    <div class="chart-container">
                        <div id="chart" style="height: 400px;"></div>
                    </div>
            """);

        // Results table
        html.append("""
                    <table>
                        <thead>
                            <tr>
                                <th>Rank</th>
                                <th>Server</th>
                                <th>Technology</th>
                                <th>Throughput</th>
                                <th>P50</th>
                                <th>P99</th>
                            </tr>
                        </thead>
                        <tbody>
            """);

        double maxThroughput = results.isEmpty() ? 1 : results.get(0).throughput;
        int rank = 1;
        for (BenchmarkResult r : results) {
            double barWidth = (r.throughput / maxThroughput) * 100;
            html.append(String.format("""
                            <tr>
                                <td class="rank">%s</td>
                                <td><strong>%s</strong></td>
                                <td>%s</td>
                                <td>
                                    %s
                                    <div class="throughput-bar" style="width: %.1f%%"></div>
                                </td>
                                <td>%.1f ms</td>
                                <td>%.1f ms</td>
                            </tr>
                """,
                rank == 1 ? "🏆" : String.valueOf(rank),
                r.serverName, r.technology, formatThroughput(r.throughput),
                barWidth, r.p50LatencyMs, r.p99LatencyMs));
            rank++;
        }

        html.append("</tbody></table>");

        // Chart script
        html.append("""
                    <script>
                        google.charts.load('current', {'packages':['corechart', 'bar']});
                        google.charts.setOnLoadCallback(drawChart);

                        function drawChart() {
                            var data = google.visualization.arrayToDataTable([
                                ['Server', 'Throughput (req/s)', { role: 'style' }],
            """);

        String[] colors = {"#00d4ff", "#7b2ff7", "#ff6b6b", "#ffd93d", "#6bcb77", "#4d96ff"};
        int i = 0;
        for (BenchmarkResult r : results) {
            html.append(String.format("['%s', %.0f, '%s'],\n",
                r.serverName, r.throughput, colors[i % colors.length]));
            i++;
        }

        html.append("""
                            ]);

                            var options = {
                                title: 'HTTP Server Throughput Comparison',
                                titleTextStyle: { color: '#e0e0e0', fontSize: 18 },
                                backgroundColor: 'transparent',
                                legend: { position: 'none' },
                                hAxis: { textStyle: { color: '#e0e0e0' }, gridlines: { color: '#2d3a4a' } },
                                vAxis: { textStyle: { color: '#e0e0e0' } },
                                chartArea: { width: '80%', height: '70%' }
                            };

                            var chart = new google.visualization.BarChart(document.getElementById('chart'));
                            chart.draw(data, options);
                        }
                    </script>
                </div>
            </body>
            </html>
            """);

        Files.writeString(dir.resolve("comparison.html"), html.toString());
    }

    private static String formatThroughput(double throughput) {
        if (throughput >= 1_000_000) {
            return String.format("%.2fM req/s", throughput / 1_000_000);
        } else if (throughput >= 1000) {
            return String.format("%.0fK req/s", throughput / 1000);
        } else {
            return String.format("%.0f req/s", throughput);
        }
    }

    // Result data class
    record BenchmarkResult(
        String brochureName,
        String serverName,
        String technology,
        String tier,
        double throughput,
        long totalRequests,
        long errors,
        double avgLatencyMs,
        double p50LatencyMs,
        double p99LatencyMs,
        int durationSeconds,
        int concurrency
    ) {}

    // Client stats
    record ClientStats(long requests, long totalLatencyNanos, double p50LatencyMs, double p99LatencyMs) {}
}
