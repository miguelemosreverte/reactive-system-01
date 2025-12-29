package com.reactive.platform.benchmark;

import com.reactive.platform.http.RocketHttpServer;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Hybrid Benchmark - Blocking clients (yield CPU) + Non-blocking server (spin).
 *
 * Theory: Blocking I/O clients naturally yield CPU when waiting for I/O,
 * allowing server reactors to use more CPU cycles.
 */
public final class HybridBenchmark {

    private static final byte[] REQUEST = "POST /e HTTP/1.1\r\nHost: l\r\nContent-Length: 0\r\n\r\n".getBytes();

    public static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();

        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  Hybrid Benchmark - Blocking Clients + Non-blocking Server");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.printf("  CPU Cores: %d%n", cores);
        System.out.println("  Client: Blocking I/O (yields CPU on syscalls)");
        System.out.println("  Server: Non-blocking with spin-wait");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println();

        // Start server with optimal reactor count
        RocketHttpServer.Handle handle = RocketHttpServer.create()
            .reactors(4)
            .start(9999);
        Thread.sleep(200);

        try {
            // Warmup
            System.out.println("Warming up...");
            runBlockingClients(9999, 100, 2);

            // Test different connection counts
            System.out.println();
            System.out.println("Testing connection scaling:");
            System.out.println("  Connections    Throughput      Per-Conn");
            System.out.println("  ───────────────────────────────────────────");

            int[] connectionCounts = {50, 100, 200, 400, 600, 800, 1000, 1500, 2000};
            double best = 0;
            int bestConns = 0;

            for (int conns : connectionCounts) {
                double throughput = runBlockingClients(9999, conns, 5);
                double perConn = throughput / conns;

                String marker = throughput >= 1_000_000 ? "★" : " ";
                System.out.printf("%s %10d %,14.0f %,13.0f%n",
                    marker, conns, throughput, perConn);

                if (throughput > best) {
                    best = throughput;
                    bestConns = conns;
                }
            }

            System.out.println("  ───────────────────────────────────────────");
            System.out.printf("  Best: %d connections = %,.0f req/s%n", bestConns, best);

            // Extended test
            System.out.println();
            System.out.println("Extended test with best config (15 seconds)...");
            double extended = runBlockingClients(9999, bestConns, 15);
            System.out.printf("  Result: %,.0f req/s%n", extended);

            // Summary
            System.out.println();
            System.out.println("═══════════════════════════════════════════════════════════════════════════════");

            if (extended >= 1_000_000) {
                System.out.println("  ╔═══════════════════════════════════════════════════════════════╗");
                System.out.println("  ║  ★★★ TARGET ACHIEVED: 1 MILLION+ REQUESTS/SECOND! ★★★        ║");
                System.out.println("  ╚═══════════════════════════════════════════════════════════════╝");
            } else {
                System.out.printf("  BEST RESULT: %,.0f req/s (%.1f%% of 1M)%n", extended, extended / 10000);
                System.out.printf("  Gap to 1M: %.2fx%n", 1_000_000.0 / extended);
            }
            System.out.println("═══════════════════════════════════════════════════════════════════════════════");

        } finally {
            handle.close();
        }
    }

    /**
     * Run blocking clients using virtual threads (one per connection).
     */
    private static double runBlockingClients(int port, int connectionCount, int durationSeconds)
            throws Exception {
        LongAdder requestCount = new LongAdder();
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(connectionCount);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        long endTime = System.currentTimeMillis() + durationSeconds * 1000L;

        for (int i = 0; i < connectionCount; i++) {
            executor.submit(() -> {
                byte[] buf = new byte[64];
                try {
                    startLatch.await();

                    Socket socket = new Socket("localhost", port);
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(5000);

                    OutputStream out = socket.getOutputStream();
                    InputStream in = socket.getInputStream();

                    while (running.get() && System.currentTimeMillis() < endTime) {
                        out.write(REQUEST);
                        // No flush needed with TCP_NODELAY for small writes

                        int read = in.read(buf);
                        if (read > 0) {
                            requestCount.increment();
                        } else {
                            break;
                        }
                    }

                    socket.close();
                } catch (Exception e) {
                    // Connection closed or error
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
}
