package com.reactive.counter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple HTTP benchmark using Java HttpClient.
 * Works with any HTTP server implementation.
 */
public class SimpleBenchmark {

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "http://localhost:3000/api/counter";
        long durationMs = args.length > 1 ? Long.parseLong(args[1]) : 10000;
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 100;

        System.out.printf("Benchmarking: %s%n", url);
        System.out.printf("Duration: %d ms, Concurrency: %d%n", durationMs, concurrency);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        String body = "{\"sessionId\":\"bench\",\"action\":\"INCREMENT\",\"value\":1}";

        AtomicLong requestCount = new AtomicLong(0);
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong errorCount = new AtomicLong(0);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);

        System.out.println("Warming up...");
        // Warmup
        for (int i = 0; i < 1000; i++) {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json")
                    .build();
            try {
                client.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) { /* ignore warmup errors */ }
        }

        System.out.println("Starting benchmark...");
        Instant startTime = Instant.now();
        Instant endTime = startTime.plus(Duration.ofMillis(durationMs));

        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (Instant.now().isBefore(endTime)) {
                        long start = System.nanoTime();
                        try {
                            HttpRequest req = HttpRequest.newBuilder()
                                    .uri(URI.create(url))
                                    .POST(HttpRequest.BodyPublishers.ofString(body))
                                    .header("Content-Type", "application/json")
                                    .timeout(Duration.ofSeconds(10))
                                    .build();

                            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());

                            long latencyMicros = (System.nanoTime() - start) / 1000;
                            requestCount.incrementAndGet();
                            latencies.offer(latencyMicros);

                            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                                successCount.incrementAndGet();
                            } else {
                                errorCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            requestCount.incrementAndGet();
                            errorCount.incrementAndGet();
                        }
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

        long actualDurationMs = Duration.between(startTime, Instant.now()).toMillis();

        long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        double avg = sorted.length > 0 ? java.util.Arrays.stream(sorted).average().orElse(0) : 0;
        long p50 = sorted.length > 0 ? sorted[sorted.length / 2] : 0;
        long p99 = sorted.length > 0 ? sorted[(int) (sorted.length * 0.99)] : 0;

        double throughput = (requestCount.get() * 1000.0) / actualDurationMs;

        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("BENCHMARK RESULTS");
        System.out.println("=".repeat(60));
        System.out.printf("Total requests:  %,d%n", requestCount.get());
        System.out.printf("Duration:        %,d ms%n", actualDurationMs);
        System.out.printf("Throughput:      %,.0f req/s%n", throughput);
        System.out.printf("Success:         %,d%n", successCount.get());
        System.out.printf("Errors:          %,d%n", errorCount.get());
        System.out.printf("Avg latency:     %.0f µs%n", avg);
        System.out.printf("P50 latency:     %d µs%n", p50);
        System.out.printf("P99 latency:     %d µs%n", p99);
        System.out.println("=".repeat(60));

        executor.shutdown();
    }
}
