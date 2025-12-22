import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;

/**
 * High-performance async benchmark client.
 * Usage: java Benchmark.java [target] [duration_seconds] [concurrency]
 *
 * Targets: gateway, drools, full
 */
public class Benchmark {

    private static final AtomicLong successCount = new AtomicLong(0);
    private static final AtomicLong errorCount = new AtomicLong(0);
    private static final AtomicLong totalLatency = new AtomicLong(0);

    public static void main(String[] args) throws Exception {
        String target = args.length > 0 ? args[0] : "gateway";
        int durationSecs = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 100;

        String url;
        String body;

        // Use env vars for Docker network compatibility
        String droolsHost = System.getenv().getOrDefault("DROOLS_HOST", "localhost:8180");
        String gatewayHost = System.getenv().getOrDefault("GATEWAY_HOST", "localhost:8080");

        switch (target) {
            case "drools":
                url = "http://" + droolsHost + "/api/evaluate";
                body = "{\"sessionId\":\"bench\",\"value\":100}";
                break;
            case "full":
            case "gateway":
            default:
                url = "http://" + gatewayHost + "/api/counter";
                body = "{\"sessionId\":\"bench-%d\",\"action\":\"increment\",\"value\":1}";
                break;
        }

        System.out.println("=== Async Benchmark ===");
        System.out.println("Target: " + target + " (" + url + ")");
        System.out.println("Duration: " + durationSecs + "s");
        System.out.println("Concurrency: " + concurrency);
        System.out.println();

        HttpClient client = HttpClient.newBuilder()
            .executor(Executors.newFixedThreadPool(concurrency))
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

        long startTime = System.currentTimeMillis();
        long endTime = startTime + (durationSecs * 1000L);
        long lastReport = startTime;
        long lastCount = 0;

        // Warmup
        System.out.println("Warming up...");
        for (int i = 0; i < 100; i++) {
            sendRequest(client, url, body.replace("%d", String.valueOf(i)));
        }
        Thread.sleep(1000);
        successCount.set(0);
        errorCount.set(0);
        totalLatency.set(0);

        System.out.println("Running benchmark...");
        System.out.println();

        startTime = System.currentTimeMillis();
        endTime = startTime + (durationSecs * 1000L);
        lastReport = startTime;

        // Main benchmark loop
        long requestId = 0;
        while (System.currentTimeMillis() < endTime) {
            // Send batch of concurrent requests
            CompletableFuture<?>[] futures = new CompletableFuture[concurrency];
            for (int i = 0; i < concurrency; i++) {
                final String reqBody = body.replace("%d", String.valueOf(requestId++));
                futures[i] = sendRequestAsync(client, url, reqBody);
            }

            // Wait for batch to complete
            CompletableFuture.allOf(futures).join();

            // Report progress every 5 seconds
            long now = System.currentTimeMillis();
            if (now - lastReport >= 5000) {
                long currentCount = successCount.get();
                long intervalCount = currentCount - lastCount;
                double intervalThroughput = intervalCount / ((now - lastReport) / 1000.0);
                System.out.printf("  [%ds] %,.0f req/s (total: %,d success, %,d errors)%n",
                    (now - startTime) / 1000, intervalThroughput, currentCount, errorCount.get());
                lastReport = now;
                lastCount = currentCount;
            }
        }

        long actualDuration = System.currentTimeMillis() - startTime;
        long total = successCount.get();
        long errors = errorCount.get();
        double throughput = total / (actualDuration / 1000.0);
        double avgLatency = total > 0 ? totalLatency.get() / (double) total : 0;

        System.out.println();
        System.out.println("=== Results ===");
        System.out.printf("Duration:    %,d ms%n", actualDuration);
        System.out.printf("Requests:    %,d success, %,d errors%n", total, errors);
        System.out.printf("Throughput:  %,.0f req/s%n", throughput);
        System.out.printf("Avg Latency: %.2f ms%n", avgLatency);
        System.out.println();

        // Output for scripting
        System.out.println("BENCHMARK_THROUGHPUT=" + (int) throughput);
        System.out.println("BENCHMARK_STATUS=" + (errors == 0 ? "success" : "partial"));
    }

    private static void sendRequest(HttpClient client, String url, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(10))
                .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            // Ignore warmup errors
        }
    }

    private static CompletableFuture<Void> sendRequestAsync(HttpClient client, String url, String body) {
        long start = System.currentTimeMillis();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(10))
            .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
            .thenAccept(response -> {
                long latency = System.currentTimeMillis() - start;
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    successCount.incrementAndGet();
                    totalLatency.addAndGet(latency);
                } else {
                    errorCount.incrementAndGet();
                }
            })
            .exceptionally(e -> {
                errorCount.incrementAndGet();
                return null;
            });
    }
}
