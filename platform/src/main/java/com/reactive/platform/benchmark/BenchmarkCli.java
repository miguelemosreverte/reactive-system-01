package com.reactive.platform.benchmark;

import com.reactive.platform.benchmark.BenchmarkTypes.*;

import java.nio.file.Path;
import java.util.*;

/**
 * CLI for running benchmarks.
 *
 * Usage:
 *   java -cp ... com.reactive.platform.benchmark.BenchmarkCli <component> [options]
 *
 * Components: http, kafka, flink, drools, gateway, full, all
 *
 * Options:
 *   --duration <seconds>    Benchmark duration (default: 30)
 *   --concurrency <n>       Number of concurrent workers (default: 8)
 *   --output <dir>          Output directory for reports (default: ./reports)
 *   --gateway-url <url>     Gateway URL (default: http://gateway:3000)
 *   --drools-url <url>      Drools URL (default: http://drools:8080)
 *   --kafka-brokers <url>   Kafka brokers (default: kafka:29092)
 */
public class BenchmarkCli {

    private static final Map<String, BenchmarkFactory> BENCHMARKS = new LinkedHashMap<>();

    @FunctionalInterface
    public interface BenchmarkFactory {
        Benchmark create();
    }

    /**
     * Register a benchmark factory. Call this from component-specific code.
     */
    public static void register(ComponentId id, BenchmarkFactory factory) {
        BENCHMARKS.put(id.id(), factory);
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        String component = args[0];
        Config.Builder configBuilder = Config.builder();
        Path outputDir = Path.of("./reports");

        // Parse options
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--duration" -> configBuilder.durationMs(Long.parseLong(args[++i]) * 1000);
                case "--concurrency" -> configBuilder.concurrency(Integer.parseInt(args[++i]));
                case "--output" -> outputDir = Path.of(args[++i]);
                case "--gateway-url" -> configBuilder.gatewayUrl(args[++i]);
                case "--drools-url" -> configBuilder.droolsUrl(args[++i]);
                case "--kafka-brokers" -> configBuilder.kafkaBrokers(args[++i]);
                case "--warmup" -> configBuilder.warmupMs(Long.parseLong(args[++i]) * 1000);
                case "--cooldown" -> configBuilder.cooldownMs(Long.parseLong(args[++i]) * 1000);
                case "--quick", "-q" -> configBuilder.quick();
                case "--skip-enrichment" -> configBuilder.skipEnrichment(true);
                case "--help", "-h" -> {
                    printUsage();
                    System.exit(0);
                }
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    printUsage();
                    System.exit(1);
                }
            }
        }

        Config config = configBuilder.build();
        BenchmarkReportGenerator reporter = BenchmarkReportGenerator.create(outputDir);

        try {
            if ("all".equalsIgnoreCase(component)) {
                runAll(config, reporter);
            } else {
                runSingle(component, config, reporter);
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void runSingle(String component, Config config, BenchmarkReportGenerator reporter) throws Exception {
        BenchmarkFactory factory = BENCHMARKS.get(component.toLowerCase());
        if (factory == null) {
            System.err.println("Unknown component: " + component);
            System.err.println("Available: " + String.join(", ", BENCHMARKS.keySet()));
            System.exit(1);
        }

        Benchmark benchmark = factory.create();
        System.out.printf("%n=== Running %s ===%n", benchmark.name());
        System.out.printf("Duration: %d seconds%s%n", config.durationMs() / 1000,
                config.quickMode() ? " (quick mode)" : "");
        System.out.printf("Concurrency: %d%n", config.concurrency());
        if (config.skipEnrichment()) {
            System.out.println("Enrichment: skipped");
        }
        System.out.println();

        long benchmarkStartTime = System.currentTimeMillis();
        BenchmarkResult result = benchmark.run(config);
        long benchmarkDuration = System.currentTimeMillis() - benchmarkStartTime;

        printResult(result);
        System.out.printf("%nMeta: Benchmark turnaround time: %.1fs (overhead: %.1fx)%n",
                benchmarkDuration / 1000.0,
                (double) benchmarkDuration / result.durationMs());

        Path reportPath = reporter.generate(result);
        System.out.printf("Report generated: %s%n", reportPath);
    }

    private static void runAll(Config config, BenchmarkReportGenerator reporter) throws Exception {
        List<BenchmarkResult> results = new ArrayList<>();

        for (var entry : BENCHMARKS.entrySet()) {
            Benchmark benchmark = entry.getValue().create();
            System.out.printf("%n=== Running %s ===%n", benchmark.name());

            BenchmarkResult result = benchmark.run(config);
            results.add(result);
            printResult(result);
        }

        reporter.generateAll(results);
        System.out.printf("%nAll reports generated%n");
    }

    private static void printResult(BenchmarkResult result) {
        System.out.println();
        System.out.println("Results");
        System.out.println("-".repeat(50));
        System.out.printf("Status:       %s%n", result.status());
        System.out.printf("Duration:     %d ms%n", result.durationMs());
        System.out.printf("Operations:   %d total, %d success, %d failed%n",
                result.totalOperations(), result.successfulOperations(), result.failedOperations());
        System.out.printf("Throughput:   %d avg, %d peak (ops/sec)%n",
                result.avgThroughput(), result.peakThroughput());
        System.out.printf("Stability:    %.2f (CV - lower is more stable)%n",
                result.throughputStability());
        System.out.println();
        System.out.println("Latency (ms)");
        System.out.println("-".repeat(30));
        System.out.printf("  P50: %d%n", result.latency().p50());
        System.out.printf("  P95: %d%n", result.latency().p95());
        System.out.printf("  P99: %d%n", result.latency().p99());
        System.out.printf("  Max: %d%n", result.latency().max());

        if (!result.sampleEvents().isEmpty()) {
            System.out.println();
            System.out.println("Sample Events: " + result.sampleEvents().size());
            for (SampleEvent event : result.sampleEvents()) {
                String status = event.status() == EventStatus.SUCCESS ? "✓" : "✗";
                System.out.printf("  %s %s: %dms%n", status, event.id(), event.latencyMs());
            }
        }
    }

    private static void printUsage() {
        System.out.println("""
            Usage: benchmark <component> [options]

            Components:
              http      HTTP endpoint latency
              kafka     Kafka produce/consume round-trip
              flink     Kafka + Flink processing
              drools    Direct Drools rule evaluation
              gateway   HTTP + Kafka publish
              full      Complete pipeline (HTTP → Kafka → Flink → Drools)
              all       Run all benchmarks

            Options:
              --duration <seconds>    Benchmark duration (default: 30)
              --concurrency <n>       Concurrent workers (default: 8)
              --output <dir>          Output directory (default: ./reports)
              --gateway-url <url>     Gateway URL (default: http://gateway:3000)
              --drools-url <url>      Drools URL (default: http://drools:8080)
              --kafka-brokers <url>   Kafka brokers (default: kafka:29092)
              --warmup <seconds>      Warmup duration (default: 3)
              --cooldown <seconds>    Cooldown duration (default: 2)
              --quick, -q             Quick mode: 5s duration, 1s warmup, no cooldown, skip enrichment
              --skip-enrichment       Skip trace/log enrichment (faster)
              --help, -h              Show this help

            Examples:
              benchmark full --duration 30
              benchmark full --quick              # Fast feedback (5s)
              benchmark drools --duration 60 --concurrency 16
              benchmark all --output ./my-reports
            """);
    }
}
