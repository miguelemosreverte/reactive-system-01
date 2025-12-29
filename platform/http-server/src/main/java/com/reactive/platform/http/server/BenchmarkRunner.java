package com.reactive.platform.http.server;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * Benchmark Runner - Benchmarks HTTP server implementations using wrk.
 *
 * Uses the Server enum for type-safe server discovery.
 *
 * Usage:
 * <pre>
 * java com.reactive.platform.http.server.BenchmarkRunner [options]
 *
 * Options:
 *   --workers N       Number of server workers (default: 16)
 *   --duration N      Test duration in seconds (default: 6)
 *   --threads N       wrk threads (default: 12)
 *   --connections N   wrk connections (default: 1000)
 *   --output DIR      Output directory for reports (default: ./benchmark-results)
 *   --servers A,B,C   Only benchmark specific servers (comma-separated, e.g., ROCKET,BOSS_WORKER)
 *   --exclude A,B     Exclude specific servers
 *   --tier TIER       Only benchmark servers in a specific tier (MAXIMUM_THROUGHPUT, HIGH_THROUGHPUT, etc.)
 * </pre>
 */
public class BenchmarkRunner {

    private final Config config;
    private final List<BenchmarkResult> results = new ArrayList<>();

    public BenchmarkRunner(Config config) {
        this.config = config;
    }

    public record Config(
        int workers,
        int durationSeconds,
        int wrkThreads,
        int wrkConnections,
        Path outputDir,
        Set<Server> includeServers,
        Set<Server> excludeServers,
        Server.Tier tier
    ) {
        public static Config defaults() {
            return new Config(
                16, 6, 12, 1000,
                Path.of("benchmark-results"),
                Set.of(), Set.of(), null
            );
        }
    }

    public record BenchmarkResult(
        int rank,
        Server server,
        double requestsPerSecond,
        String avgLatency,
        String maxLatency,
        boolean success,
        String errorMessage
    ) implements Comparable<BenchmarkResult> {
        @Override
        public int compareTo(BenchmarkResult other) {
            return Double.compare(other.requestsPerSecond, this.requestsPerSecond);
        }

        public String name() { return server.displayName(); }
        public String technology() { return server.technology(); }
        public String notes() { return server.notes(); }
    }

    /**
     * Run benchmarks for all selected servers.
     */
    public List<BenchmarkResult> run() throws Exception {
        System.out.println("""
            ╔══════════════════════════════════════════════════════════════════════════════╗
            ║                    HTTP SERVER BENCHMARK RUNNER                              ║
            ╚══════════════════════════════════════════════════════════════════════════════╝
            """);

        System.out.printf("Config: %d workers, %ds duration, %d wrk threads, %d connections%n%n",
            config.workers, config.durationSeconds, config.wrkThreads, config.wrkConnections);

        // Get servers to benchmark
        List<Server> servers = Arrays.stream(Server.values())
            .filter(s -> config.includeServers.isEmpty() || config.includeServers.contains(s))
            .filter(s -> !config.excludeServers.contains(s))
            .filter(s -> config.tier == null || s.tier() == config.tier)
            .toList();

        System.out.printf("Benchmarking %d servers...%n%n", servers.size());

        // Run each benchmark
        for (Server server : servers) {
            BenchmarkResult result = benchmarkServer(server);
            results.add(result);
        }

        // Sort by throughput
        Collections.sort(results);

        // Assign ranks
        List<BenchmarkResult> rankedResults = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            BenchmarkResult r = results.get(i);
            rankedResults.add(new BenchmarkResult(
                i + 1, r.server, r.requestsPerSecond,
                r.avgLatency, r.maxLatency, r.success, r.errorMessage
            ));
        }

        // Print and save results
        printResults(rankedResults);
        saveReports(rankedResults);

        return rankedResults;
    }

    private BenchmarkResult benchmarkServer(Server server) {
        System.out.printf("Testing: %s (%s)%n", server.displayName(), server.technology());

        try {
            // Kill any existing Java processes on benchmark ports
            killExistingServers();
            Thread.sleep(1000);

            int port = 9990 + new Random().nextInt(100);

            // Build command to start server
            String[] startCmd = buildServerCommand(server, port);

            // Start server process
            ProcessBuilder pb = new ProcessBuilder(startCmd);
            pb.redirectErrorStream(true);
            Process serverProcess = pb.start();

            // Wait for server to start
            Thread.sleep(3000);

            if (!serverProcess.isAlive()) {
                String output = new String(serverProcess.getInputStream().readAllBytes());
                return new BenchmarkResult(0, server, 0, "N/A", "N/A", false,
                    "Server failed to start: " + output.substring(0, Math.min(200, output.length())));
            }

            // Run wrk benchmark
            String[] wrkCmd = {
                "wrk",
                "-t" + config.wrkThreads,
                "-c" + config.wrkConnections,
                "-d" + config.durationSeconds + "s",
                "-s", "/tmp/post.lua",
                "http://localhost:" + port + "/events"
            };

            // Create post.lua if it doesn't exist
            Files.writeString(Path.of("/tmp/post.lua"), """
                wrk.method = "POST"
                wrk.body   = "{\\"e\\":1}"
                wrk.headers["Content-Type"] = "application/json"
                """);

            ProcessBuilder wrkPb = new ProcessBuilder(wrkCmd);
            wrkPb.redirectErrorStream(true);
            Process wrkProcess = wrkPb.start();
            String wrkOutput = new String(wrkProcess.getInputStream().readAllBytes());
            wrkProcess.waitFor();

            // Stop server
            serverProcess.destroyForcibly();
            serverProcess.waitFor(5, TimeUnit.SECONDS);

            // Parse results
            double rps = parseRequestsPerSecond(wrkOutput);
            String avgLatency = parseAvgLatency(wrkOutput);
            String maxLatency = parseMaxLatency(wrkOutput);

            System.out.printf("  Result: %.2f req/s (avg latency: %s)%n%n", rps, avgLatency);

            return new BenchmarkResult(0, server, rps, avgLatency, maxLatency, true, null);

        } catch (Exception e) {
            System.out.printf("  ERROR: %s%n%n", e.getMessage());
            return new BenchmarkResult(0, server, 0, "N/A", "N/A", false, e.getMessage());
        }
    }

    private String[] buildServerCommand(Server server, int port) {
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("--enable-native-access=ALL-UNNAMED");
        cmd.add("-Xms256m");
        cmd.add("-Xmx256m");
        cmd.add("-XX:+UseZGC");
        cmd.add("-cp");
        cmd.add("target/classes:target/dependency/*");
        cmd.add(server.mainClass());
        cmd.add(String.valueOf(port));

        // Add workers arg for most servers (except Spring Boot which configures internally)
        if (server != Server.SPRING_BOOT) {
            cmd.add(String.valueOf(config.workers));
        }

        return cmd.toArray(new String[0]);
    }

    private void killExistingServers() throws Exception {
        new ProcessBuilder("pkill", "-9", "-f", "com.reactive.platform.http")
            .start().waitFor(2, TimeUnit.SECONDS);
    }

    private double parseRequestsPerSecond(String output) {
        for (String line : output.split("\n")) {
            if (line.contains("Requests/sec")) {
                String[] parts = line.trim().split("\\s+");
                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].equals("Requests/sec:") && i + 1 < parts.length) {
                        return Double.parseDouble(parts[i + 1]);
                    }
                }
            }
        }
        return 0;
    }

    private String parseAvgLatency(String output) {
        for (String line : output.split("\n")) {
            if (line.trim().startsWith("Latency")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) return parts[1];
            }
        }
        return "N/A";
    }

    private String parseMaxLatency(String output) {
        for (String line : output.split("\n")) {
            if (line.trim().startsWith("Latency")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 4) return parts[3];
            }
        }
        return "N/A";
    }

    private void printResults(List<BenchmarkResult> results) {
        System.out.println("""

            ╔══════════════════════════════════════════════════════════════════════════════════════════════╗
            ║                                    FINAL RANKINGS                                            ║
            ╚══════════════════════════════════════════════════════════════════════════════════════════════╝
            """);

        System.out.printf("%-4s %-22s %13s  %-8s  %-20s  %s%n",
            "Rank", "Server", "Throughput", "Latency", "Technology", "Notes");
        System.out.println("═".repeat(110));

        for (BenchmarkResult r : results) {
            if (r.success) {
                System.out.printf("%-4s %-22s %10.0f req/s  %-8s  %-20s  %s%n",
                    r.rank + ".", r.name(), r.requestsPerSecond, r.avgLatency, r.technology(), r.notes());
            } else {
                System.out.printf("%-4s %-22s %13s  %-8s  %-20s  %s%n",
                    r.rank + ".", r.name(), "FAILED", "N/A", r.technology(), r.errorMessage);
            }
        }
    }

    private void saveReports(List<BenchmarkResult> results) throws Exception {
        Files.createDirectories(config.outputDir);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        Path jsonPath = config.outputDir.resolve("benchmark_" + timestamp + ".json");
        Path mdPath = config.outputDir.resolve("benchmark_" + timestamp + ".md");

        saveJsonReport(results, jsonPath);
        saveMarkdownReport(results, mdPath);

        // Save latest (overwrite)
        saveJsonReport(results, config.outputDir.resolve("latest.json"));
        saveMarkdownReport(results, config.outputDir.resolve("RANKING.md"));

        System.out.printf("%nReports saved to:%n  %s%n  %s%n", jsonPath, mdPath);
    }

    private void saveJsonReport(List<BenchmarkResult> results, Path path) throws Exception {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append(String.format("  \"timestamp\": \"%s\",%n", LocalDateTime.now()));
        json.append("  \"config\": {\n");
        json.append(String.format("    \"workers\": %d,%n", config.workers));
        json.append(String.format("    \"duration_seconds\": %d,%n", config.durationSeconds));
        json.append(String.format("    \"wrk_threads\": %d,%n", config.wrkThreads));
        json.append(String.format("    \"wrk_connections\": %d%n", config.wrkConnections));
        json.append("  },\n");
        json.append("  \"results\": [\n");

        for (int i = 0; i < results.size(); i++) {
            BenchmarkResult r = results.get(i);
            json.append("    {\n");
            json.append(String.format("      \"rank\": %d,%n", r.rank));
            json.append(String.format("      \"server\": \"%s\",%n", r.server.name()));
            json.append(String.format("      \"name\": \"%s\",%n", r.name()));
            json.append(String.format("      \"technology\": \"%s\",%n", r.technology()));
            json.append(String.format("      \"tier\": \"%s\",%n", r.server.tier().name()));
            json.append(String.format("      \"notes\": \"%s\",%n", r.notes()));
            json.append(String.format("      \"requests_per_second\": %.2f,%n", r.requestsPerSecond));
            json.append(String.format("      \"avg_latency\": \"%s\",%n", r.avgLatency));
            json.append(String.format("      \"max_latency\": \"%s\",%n", r.maxLatency));
            json.append(String.format("      \"success\": %s%n", r.success));
            json.append("    }");
            if (i < results.size() - 1) json.append(",");
            json.append("\n");
        }

        json.append("  ]\n");
        json.append("}\n");

        Files.writeString(path, json.toString());
    }

    private void saveMarkdownReport(List<BenchmarkResult> results, Path path) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# HTTP Server Benchmark Rankings\n\n");
        md.append(String.format("**Generated:** %s%n", LocalDateTime.now()));
        md.append(String.format("**Config:** %d workers, %ds duration, %d wrk threads, %d connections%n%n",
            config.workers, config.durationSeconds, config.wrkThreads, config.wrkConnections));

        md.append("## Rankings\n\n");
        md.append("| Rank | Server | Throughput | Latency | Tier | Technology |\n");
        md.append("|------|--------|------------|---------|------|------------|\n");

        for (BenchmarkResult r : results) {
            if (r.success) {
                md.append(String.format("| %d | %s | %.0f req/s | %s | %s | %s |\n",
                    r.rank, r.name(), r.requestsPerSecond, r.avgLatency,
                    r.server.tier().description(), r.technology()));
            } else {
                md.append(String.format("| %d | %s | FAILED | N/A | %s | %s |\n",
                    r.rank, r.name(), r.server.tier().description(), r.errorMessage));
            }
        }

        md.append("\n## Available Servers\n\n");
        for (Server.Tier tier : Server.Tier.values()) {
            md.append(String.format("### %s (%s)\n\n", tier.name().replace("_", " "), tier.description()));
            md.append("| Server | Technology | Notes |\n");
            md.append("|--------|------------|-------|\n");
            for (Server s : Server.byTier(tier)) {
                md.append(String.format("| %s | %s | %s |\n", s.displayName(), s.technology(), s.notes()));
            }
            md.append("\n");
        }

        Files.writeString(path, md.toString());
    }

    // ========================================================================
    // Main
    // ========================================================================

    public static void main(String[] args) throws Exception {
        Config config = parseArgs(args);
        BenchmarkRunner runner = new BenchmarkRunner(config);
        runner.run();
    }

    private static Config parseArgs(String[] args) {
        int workers = 16;
        int duration = 6;
        int threads = 12;
        int connections = 1000;
        Path outputDir = Path.of("benchmark-results");
        Set<Server> include = new HashSet<>();
        Set<Server> exclude = new HashSet<>();
        Server.Tier tier = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--workers" -> workers = Integer.parseInt(args[++i]);
                case "--duration" -> duration = Integer.parseInt(args[++i]);
                case "--threads" -> threads = Integer.parseInt(args[++i]);
                case "--connections" -> connections = Integer.parseInt(args[++i]);
                case "--output" -> outputDir = Path.of(args[++i]);
                case "--servers" -> {
                    for (String name : args[++i].split(",")) {
                        Server.fromName(name.trim()).ifPresent(include::add);
                    }
                }
                case "--exclude" -> {
                    for (String name : args[++i].split(",")) {
                        Server.fromName(name.trim()).ifPresent(exclude::add);
                    }
                }
                case "--tier" -> {
                    try {
                        tier = Server.Tier.valueOf(args[++i].toUpperCase());
                    } catch (IllegalArgumentException e) {
                        System.err.println("Invalid tier. Valid: MAXIMUM_THROUGHPUT, FRAMEWORK");
                    }
                }
            }
        }

        return new Config(workers, duration, threads, connections, outputDir, include, exclude, tier);
    }
}
