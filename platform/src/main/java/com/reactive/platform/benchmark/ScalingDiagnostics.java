package com.reactive.platform.benchmark;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Scaling Diagnostics - Find where linear scaling breaks down.
 *
 * Questions to answer:
 * 1. Does throughput scale linearly with reactors?
 * 2. Are reactors evenly loaded?
 * 3. Where is time spent on the server side?
 * 4. What's the maximum single-reactor throughput?
 * 5. What happens at higher connection counts?
 */
public final class ScalingDiagnostics {

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
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  Scaling Diagnostics - Finding the Bottleneck");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println();

        // Test 1: Single reactor baseline
        System.out.println("TEST 1: SINGLE REACTOR MAXIMUM THROUGHPUT");
        System.out.println("─────────────────────────────────────────────────────────────────────────────────");
        double singleReactorMax = testReactorScaling(1, 5);
        System.out.printf("  Single reactor max: %,.0f req/s%n", singleReactorMax);
        System.out.printf("  If linear: 10 reactors = %,.0f req/s%n", singleReactorMax * 10);
        System.out.println();

        // Test 2: Reactor scaling
        System.out.println("TEST 2: REACTOR SCALING (does it scale linearly?)");
        System.out.println("─────────────────────────────────────────────────────────────────────────────────");
        testReactorScalingCurve(singleReactorMax);
        System.out.println();

        // Test 3: Per-reactor load distribution
        System.out.println("TEST 3: PER-REACTOR LOAD DISTRIBUTION");
        System.out.println("─────────────────────────────────────────────────────────────────────────────────");
        testReactorDistribution();
        System.out.println();

        // Test 4: Connection count scaling
        System.out.println("TEST 4: CONNECTION SCALING (fixed reactors, vary connections)");
        System.out.println("─────────────────────────────────────────────────────────────────────────────────");
        testConnectionScaling();
        System.out.println();

        // Test 5: Server-side timing breakdown
        System.out.println("TEST 5: SERVER-SIDE TIMING BREAKDOWN");
        System.out.println("─────────────────────────────────────────────────────────────────────────────────");
        testServerTiming();
        System.out.println();

        // Test 6: Client vs Server bottleneck
        System.out.println("TEST 6: CLIENT VS SERVER BOTTLENECK");
        System.out.println("─────────────────────────────────────────────────────────────────────────────────");
        testClientVsServer();
        System.out.println();

        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  ANALYSIS COMPLETE");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
    }

    /**
     * Test scaling with different reactor counts.
     */
    private static double testReactorScaling(int reactors, int durationSeconds) throws Exception {
        DiagnosticServer server = new DiagnosticServer(9999, reactors);
        server.start();
        Thread.sleep(200);

        try {
            // Warmup
            runLoadGenerator(9999, 4, 50, 2);

            // Measure with optimal client config
            double throughput = runLoadGenerator(9999, 8, 100, durationSeconds);
            return throughput;
        } finally {
            server.stop();
        }
    }

    private static void testReactorScalingCurve(double singleMax) throws Exception {
        int[] reactorCounts = {1, 2, 4, 6, 8, 10, 12};

        System.out.println("  Reactors     Throughput      Linear Target    Efficiency    Δ from prev");
        System.out.println("  ─────────────────────────────────────────────────────────────────────────");

        double prevThroughput = 0;
        for (int r : reactorCounts) {
            double throughput = testReactorScaling(r, 5);
            double linearTarget = singleMax * r;
            double efficiency = (throughput / linearTarget) * 100;
            double delta = prevThroughput > 0 ? throughput - prevThroughput : throughput;
            String deltaStr = prevThroughput > 0 ? String.format("+%,.0f", delta) : "-";

            System.out.printf("  %8d %,14.0f %,17.0f %11.1f%% %14s%n",
                r, throughput, linearTarget, efficiency, deltaStr);

            prevThroughput = throughput;
            Thread.sleep(500); // Let things settle
        }
    }

    /**
     * Test if reactors are evenly loaded.
     */
    private static void testReactorDistribution() throws Exception {
        DiagnosticServer server = new DiagnosticServer(9999, 4);
        server.start();
        Thread.sleep(200);

        try {
            // Run load test
            runLoadGenerator(9999, 8, 100, 5);

            // Get per-reactor stats
            long[] perReactor = server.getPerReactorCounts();
            long total = Arrays.stream(perReactor).sum();

            System.out.println("  Reactor    Requests      Share      Deviation");
            System.out.println("  ─────────────────────────────────────────────────");

            double idealShare = 100.0 / perReactor.length;
            for (int i = 0; i < perReactor.length; i++) {
                double share = (perReactor[i] * 100.0) / total;
                double deviation = share - idealShare;
                String marker = Math.abs(deviation) > 10 ? " ⚠️" : "";
                System.out.printf("  %7d %,12d %9.1f%% %+10.1f%%%s%n",
                    i, perReactor[i], share, deviation, marker);
            }

            System.out.printf("%n  Total: %,d requests%n", total);
        } finally {
            server.stop();
        }
    }

    /**
     * Test how throughput scales with connection count.
     */
    private static void testConnectionScaling() throws Exception {
        DiagnosticServer server = new DiagnosticServer(9999, 4);
        server.start();
        Thread.sleep(200);

        try {
            int[] connectionCounts = {50, 100, 200, 400, 800, 1600};

            System.out.println("  Connections    Throughput     Per-Conn    Client Threads");
            System.out.println("  ──────────────────────────────────────────────────────────");

            for (int conns : connectionCounts) {
                // Scale client threads with connections
                int threads = Math.min(conns / 25, 16);
                threads = Math.max(threads, 4);
                int connsPerThread = conns / threads;

                double throughput = runLoadGenerator(9999, threads, connsPerThread, 5);
                double perConn = throughput / conns;

                System.out.printf("  %11d %,14.0f %10.0f %15d%n",
                    conns, throughput, perConn, threads);
            }
        } finally {
            server.stop();
        }
    }

    /**
     * Test server-side timing with instrumented server.
     */
    private static void testServerTiming() throws Exception {
        TimingServer server = new TimingServer(9999, 4);
        server.start();
        Thread.sleep(200);

        try {
            // Run load
            runLoadGenerator(9999, 4, 50, 5);

            // Print timing breakdown
            server.printTimingStats();
        } finally {
            server.stop();
        }
    }

    /**
     * Test if client or server is the bottleneck.
     */
    private static void testClientVsServer() throws Exception {
        // Test 1: One client process
        System.out.println("  Configuration                        Throughput");
        System.out.println("  ─────────────────────────────────────────────────");

        DiagnosticServer server = new DiagnosticServer(9999, 8);
        server.start();
        Thread.sleep(200);

        try {
            // Single client thread, many connections
            double t1 = runLoadGenerator(9999, 1, 200, 5);
            System.out.printf("  1 thread × 200 connections:       %,12.0f%n", t1);

            // Multiple threads, same total connections
            double t2 = runLoadGenerator(9999, 4, 50, 5);
            System.out.printf("  4 threads × 50 connections:       %,12.0f%n", t2);

            double t3 = runLoadGenerator(9999, 8, 25, 5);
            System.out.printf("  8 threads × 25 connections:       %,12.0f%n", t3);

            double t4 = runLoadGenerator(9999, 16, 12, 5);
            System.out.printf("  16 threads × 12 connections:      %,12.0f%n", t4);

            // More connections total
            double t5 = runLoadGenerator(9999, 8, 100, 5);
            System.out.printf("  8 threads × 100 connections:      %,12.0f%n", t5);

            double t6 = runLoadGenerator(9999, 16, 100, 5);
            System.out.printf("  16 threads × 100 connections:     %,12.0f%n", t6);

            System.out.println();
            System.out.printf("  Best config: %,.0f req/s%n",
                Math.max(Math.max(Math.max(t1, t2), Math.max(t3, t4)), Math.max(t5, t6)));
        } finally {
            server.stop();
        }
    }

    /**
     * Run load generator and return throughput.
     */
    private static double runLoadGenerator(int port, int threads, int connsPerThread, int durationSeconds)
            throws Exception {
        LongAdder requestCount = new LongAdder();
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        long endTime = System.currentTimeMillis() + durationSeconds * 1000L;

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    runNioClient(port, connsPerThread, endTime, requestCount);
                } catch (Exception e) {
                    // ignore
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(durationSeconds + 5, TimeUnit.SECONDS);
        running.set(false);

        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.SECONDS);

        return requestCount.sum() * 1.0 / durationSeconds;
    }

    private static void runNioClient(int port, int connCount, long endTimeMs, LongAdder requestCount)
            throws Exception {
        Selector selector = Selector.open();
        InetSocketAddress address = new InetSocketAddress("localhost", port);

        ByteBuffer[] writeBuffers = new ByteBuffer[connCount];
        ByteBuffer[] readBuffers = new ByteBuffer[connCount];

        for (int i = 0; i < connCount; i++) {
            writeBuffers[i] = ByteBuffer.allocateDirect(REQUEST.length);
            readBuffers[i] = ByteBuffer.allocateDirect(256);

            SocketChannel channel = SocketChannel.open();
            channel.configureBlocking(false);
            channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            channel.connect(address);
            SelectionKey key = channel.register(selector, SelectionKey.OP_CONNECT);
            key.attach(i);
        }

        while (System.currentTimeMillis() < endTimeMs) {
            if (selector.select(1) == 0) continue;

            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();
                if (!key.isValid()) continue;

                int idx = (Integer) key.attachment();
                SocketChannel channel = (SocketChannel) key.channel();

                try {
                    if (key.isConnectable() && channel.finishConnect()) {
                        key.interestOps(SelectionKey.OP_WRITE);
                    } else if (key.isWritable()) {
                        ByteBuffer wb = writeBuffers[idx];
                        wb.clear();
                        wb.put(REQUEST);
                        wb.flip();
                        while (wb.hasRemaining()) channel.write(wb);
                        key.interestOps(SelectionKey.OP_READ);
                    } else if (key.isReadable()) {
                        ByteBuffer rb = readBuffers[idx];
                        rb.clear();
                        if (channel.read(rb) > 0) {
                            requestCount.increment();
                            key.interestOps(SelectionKey.OP_WRITE);
                        }
                    }
                } catch (IOException e) {
                    key.cancel();
                }
            }
        }

        selector.close();
    }

    // ========================================================================
    // Diagnostic Server - Tracks per-reactor stats
    // ========================================================================

    private static class DiagnosticServer {
        private final int port;
        private final int reactorCount;
        private final DiagnosticReactor[] reactors;
        private final AtomicBoolean running = new AtomicBoolean(true);

        DiagnosticServer(int port, int reactorCount) {
            this.port = port;
            this.reactorCount = reactorCount;
            this.reactors = new DiagnosticReactor[reactorCount];
        }

        void start() throws IOException {
            for (int i = 0; i < reactorCount; i++) {
                reactors[i] = new DiagnosticReactor(i, port, running);
                reactors[i].start();
            }
        }

        void stop() {
            running.set(false);
            for (DiagnosticReactor r : reactors) {
                if (r != null) {
                    r.selector.wakeup();
                    try { r.join(1000); } catch (InterruptedException ignored) {}
                    r.cleanup();
                }
            }
        }

        long[] getPerReactorCounts() {
            long[] counts = new long[reactorCount];
            for (int i = 0; i < reactorCount; i++) {
                counts[i] = reactors[i].requests.sum();
            }
            return counts;
        }
    }

    private static class DiagnosticReactor extends Thread {
        private static final byte[] RESPONSE = (
            "HTTP/1.1 202 Accepted\r\n" +
            "Content-Length: 2\r\n" +
            "\r\n" +
            "ok"
        ).getBytes();

        final Selector selector;
        private final ServerSocketChannel serverChannel;
        private final AtomicBoolean running;
        final LongAdder requests = new LongAdder();
        private final ByteBuffer readBuffer = ByteBuffer.allocateDirect(4096);
        private final ByteBuffer writeBuffer = ByteBuffer.allocateDirect(RESPONSE.length);

        DiagnosticReactor(int id, int port, AtomicBoolean running) throws IOException {
            super("diag-reactor-" + id);
            this.running = running;

            selector = Selector.open();
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            try {
                serverChannel.setOption(StandardSocketOptions.SO_REUSEPORT, true);
            } catch (UnsupportedOperationException e) {
                if (id > 0) throw new IOException("SO_REUSEPORT required for multiple reactors");
            }
            serverChannel.bind(new InetSocketAddress(port), 4096);
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        }

        void cleanup() {
            try { serverChannel.close(); } catch (IOException ignored) {}
            try { selector.close(); } catch (IOException ignored) {}
        }

        @Override
        public void run() {
            try {
                while (running.get()) {
                    if (selector.select(1) == 0) continue;

                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();
                        if (!key.isValid()) continue;

                        if (key.isAcceptable()) {
                            SocketChannel client = serverChannel.accept();
                            if (client != null) {
                                client.configureBlocking(false);
                                client.setOption(StandardSocketOptions.TCP_NODELAY, true);
                                client.register(selector, SelectionKey.OP_READ);
                            }
                        } else if (key.isReadable()) {
                            SocketChannel channel = (SocketChannel) key.channel();
                            try {
                                readBuffer.clear();
                                int n = channel.read(readBuffer);
                                if (n <= 0) {
                                    key.cancel();
                                    channel.close();
                                } else {
                                    writeBuffer.clear();
                                    writeBuffer.put(RESPONSE);
                                    writeBuffer.flip();
                                    while (writeBuffer.hasRemaining()) {
                                        channel.write(writeBuffer);
                                    }
                                    requests.increment();
                                }
                            } catch (IOException e) {
                                key.cancel();
                                try { channel.close(); } catch (IOException ignored) {}
                            }
                        }
                    }
                }
            } catch (IOException e) {
                if (running.get()) e.printStackTrace();
            }
        }
    }

    // ========================================================================
    // Timing Server - Measures time in each phase
    // ========================================================================

    private static class TimingServer {
        private final int port;
        private final int reactorCount;
        private final TimingReactor[] reactors;
        private final AtomicBoolean running = new AtomicBoolean(true);

        TimingServer(int port, int reactorCount) {
            this.port = port;
            this.reactorCount = reactorCount;
            this.reactors = new TimingReactor[reactorCount];
        }

        void start() throws IOException {
            for (int i = 0; i < reactorCount; i++) {
                reactors[i] = new TimingReactor(i, port, running);
                reactors[i].start();
            }
        }

        void stop() {
            running.set(false);
            for (TimingReactor r : reactors) {
                if (r != null) {
                    r.selector.wakeup();
                    try { r.join(1000); } catch (InterruptedException ignored) {}
                    r.cleanup();
                }
            }
        }

        void printTimingStats() {
            long totalRequests = 0;
            long totalSelectTime = 0;
            long totalReadTime = 0;
            long totalWriteTime = 0;
            long totalSelectCalls = 0;

            for (TimingReactor r : reactors) {
                totalRequests += r.requests.sum();
                totalSelectTime += r.selectTime.sum();
                totalReadTime += r.readTime.sum();
                totalWriteTime += r.writeTime.sum();
                totalSelectCalls += r.selectCalls.sum();
            }

            double avgSelectUs = totalSelectCalls > 0 ? totalSelectTime / 1000.0 / totalSelectCalls : 0;
            double avgReadUs = totalRequests > 0 ? totalReadTime / 1000.0 / totalRequests : 0;
            double avgWriteUs = totalRequests > 0 ? totalWriteTime / 1000.0 / totalRequests : 0;
            double totalTimeUs = avgSelectUs + avgReadUs + avgWriteUs;

            System.out.println("  Operation          Avg Time (µs)    % of Total");
            System.out.println("  ──────────────────────────────────────────────────");
            System.out.printf("  selector.select()    %10.2f     %8.1f%%%n", avgSelectUs, avgSelectUs/totalTimeUs*100);
            System.out.printf("  channel.read()       %10.2f     %8.1f%%%n", avgReadUs, avgReadUs/totalTimeUs*100);
            System.out.printf("  channel.write()      %10.2f     %8.1f%%%n", avgWriteUs, avgWriteUs/totalTimeUs*100);
            System.out.println("  ──────────────────────────────────────────────────");
            System.out.printf("  Total per request    %10.2f µs%n", avgReadUs + avgWriteUs);
            System.out.printf("  Theoretical max      %,10.0f req/s%n", 1_000_000 / (avgReadUs + avgWriteUs));
            System.out.println();
            System.out.printf("  Actual requests:     %,d%n", totalRequests);
            System.out.printf("  Select calls:        %,d%n", totalSelectCalls);
            System.out.printf("  Requests per select: %.2f%n", totalRequests * 1.0 / totalSelectCalls);
        }
    }

    private static class TimingReactor extends Thread {
        private static final byte[] RESPONSE = (
            "HTTP/1.1 202 Accepted\r\n" +
            "Content-Length: 2\r\n" +
            "\r\n" +
            "ok"
        ).getBytes();

        final Selector selector;
        private final ServerSocketChannel serverChannel;
        private final AtomicBoolean running;

        final LongAdder requests = new LongAdder();
        final LongAdder selectTime = new LongAdder();
        final LongAdder readTime = new LongAdder();
        final LongAdder writeTime = new LongAdder();
        final LongAdder selectCalls = new LongAdder();

        private final ByteBuffer readBuffer = ByteBuffer.allocateDirect(4096);
        private final ByteBuffer writeBuffer = ByteBuffer.allocateDirect(RESPONSE.length);

        TimingReactor(int id, int port, AtomicBoolean running) throws IOException {
            super("timing-reactor-" + id);
            this.running = running;

            selector = Selector.open();
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            try {
                serverChannel.setOption(StandardSocketOptions.SO_REUSEPORT, true);
            } catch (UnsupportedOperationException e) {
                if (id > 0) throw new IOException("SO_REUSEPORT required");
            }
            serverChannel.bind(new InetSocketAddress(port), 4096);
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        }

        void cleanup() {
            try { serverChannel.close(); } catch (IOException ignored) {}
            try { selector.close(); } catch (IOException ignored) {}
        }

        @Override
        public void run() {
            try {
                while (running.get()) {
                    long t0 = System.nanoTime();
                    int ready = selector.select(1);
                    long t1 = System.nanoTime();
                    selectTime.add(t1 - t0);
                    selectCalls.increment();

                    if (ready == 0) continue;

                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();
                        if (!key.isValid()) continue;

                        if (key.isAcceptable()) {
                            SocketChannel client = serverChannel.accept();
                            if (client != null) {
                                client.configureBlocking(false);
                                client.setOption(StandardSocketOptions.TCP_NODELAY, true);
                                client.register(selector, SelectionKey.OP_READ);
                            }
                        } else if (key.isReadable()) {
                            SocketChannel channel = (SocketChannel) key.channel();
                            try {
                                readBuffer.clear();

                                long r0 = System.nanoTime();
                                int n = channel.read(readBuffer);
                                long r1 = System.nanoTime();
                                readTime.add(r1 - r0);

                                if (n <= 0) {
                                    key.cancel();
                                    channel.close();
                                } else {
                                    writeBuffer.clear();
                                    writeBuffer.put(RESPONSE);
                                    writeBuffer.flip();

                                    long w0 = System.nanoTime();
                                    while (writeBuffer.hasRemaining()) {
                                        channel.write(writeBuffer);
                                    }
                                    long w1 = System.nanoTime();
                                    writeTime.add(w1 - w0);

                                    requests.increment();
                                }
                            } catch (IOException e) {
                                key.cancel();
                                try { channel.close(); } catch (IOException ignored) {}
                            }
                        }
                    }
                }
            } catch (IOException e) {
                if (running.get()) e.printStackTrace();
            }
        }
    }
}
