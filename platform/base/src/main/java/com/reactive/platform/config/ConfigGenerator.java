package com.reactive.platform.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigRenderOptions;
import com.typesafe.config.ConfigValue;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Generates configuration files from HOCON for non-JVM consumers.
 *
 * Outputs:
 * - .env file for Docker Compose
 * - JSON for Go CLI
 * - Validation report
 *
 * Usage:
 *   java ConfigGenerator [validate|env|json|summary]
 */
public final class ConfigGenerator {

    private final PlatformConfig config;

    public ConfigGenerator() {
        this.config = PlatformConfig.load();
    }

    public ConfigGenerator(String profile) {
        this.config = PlatformConfig.loadProfile(profile);
    }

    // Mapping from Service enum to environment variable prefix
    private static final Map<PlatformConfig.Service, String> ENV_PREFIX = Map.ofEntries(
        Map.entry(PlatformConfig.Service.APPLICATION, "APP"),
        Map.entry(PlatformConfig.Service.GATEWAY, "GATEWAY"),
        Map.entry(PlatformConfig.Service.FLINK_JOB_MANAGER, "FLINK_JM"),
        Map.entry(PlatformConfig.Service.FLINK_TASK_MANAGER, "FLINK_TM"),
        Map.entry(PlatformConfig.Service.DROOLS, "DROOLS"),
        Map.entry(PlatformConfig.Service.KAFKA, "KAFKA"),
        Map.entry(PlatformConfig.Service.OTEL_COLLECTOR, "OTEL"),
        Map.entry(PlatformConfig.Service.JAEGER, "JAEGER"),
        Map.entry(PlatformConfig.Service.PROMETHEUS, "PROMETHEUS"),
        Map.entry(PlatformConfig.Service.LOKI, "LOKI"),
        Map.entry(PlatformConfig.Service.PROMTAIL, "PROMTAIL"),
        Map.entry(PlatformConfig.Service.GRAFANA, "GRAFANA"),
        Map.entry(PlatformConfig.Service.CADVISOR, "CADVISOR"),
        Map.entry(PlatformConfig.Service.UI, "UI")
    );

    /**
     * Generate .env file for Docker Compose.
     */
    public String generateEnvFile() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Generated from reference.conf - DO NOT EDIT MANUALLY\n");
        sb.append("# Regenerate with: ./cli.sh config generate env\n\n");

        // Memory settings - type-safe iteration over all services
        sb.append("# Service Memory Limits (container)\n");
        for (PlatformConfig.Service service : PlatformConfig.Service.values()) {
            appendServiceMemory(sb, service);
        }

        sb.append("\n# Flink Settings\n");
        var flinkTm = config.flinkTaskManager();
        sb.append("FLINK_TM_PROCESS_SIZE=").append(flinkTm.processSizeMb()).append("m\n");
        sb.append("FLINK_PARALLELISM=").append(flinkTm.parallelism()).append("\n");
        sb.append("FLINK_ASYNC_CAPACITY=").append(flinkTm.asyncCapacity()).append("\n");

        sb.append("\n# Kafka Settings\n");
        PlatformConfig.KafkaConfig.ProducerSettings producer = config.kafka().producer();
        sb.append("KAFKA_BATCH_SIZE=").append(producer.batchSize()).append("\n");
        sb.append("KAFKA_LINGER_MS=").append(producer.lingerMs()).append("\n");
        sb.append("KAFKA_BUFFER_MEMORY=").append(producer.bufferMemory()).append("\n");
        sb.append("KAFKA_COMPRESSION=").append(producer.compression()).append("\n");

        sb.append("\n# Benchmark Thresholds\n");
        PlatformConfig.BenchmarkConfig bench = config.benchmark();
        sb.append("BENCHMARK_MIN_THROUGHPUT=").append(bench.minThroughput()).append("\n");
        sb.append("BENCHMARK_MAX_P99_LATENCY=").append(bench.maxP99LatencyMs()).append("\n");

        sb.append("\n# Network\n");
        PlatformConfig.NetworkConfig network = config.network();
        sb.append("GATEWAY_PORT=").append(network.gatewayPort()).append("\n");
        sb.append("UI_PORT=").append(network.uiPort()).append("\n");
        sb.append("GRAFANA_PORT=").append(network.grafanaPort()).append("\n");

        return sb.toString();
    }

    private void appendServiceMemory(StringBuilder sb, PlatformConfig.Service service) {
        String prefix = ENV_PREFIX.get(service);
        try {
            PlatformConfig.ServiceConfig svc = config.service(service);
            sb.append(prefix).append("_MEMORY=").append(svc.containerMb()).append("M\n");
            try {
                sb.append(prefix).append("_HEAP=").append(svc.heapMb()).append("m\n");
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    /**
     * Generate JSON for Go CLI consumption.
     */
    public String generateJson() {
        Config raw = config.raw();
        ConfigRenderOptions options = ConfigRenderOptions.defaults()
            .setOriginComments(false)
            .setComments(false)
            .setFormatted(true)
            .setJson(true);
        return raw.root().render(options);
    }

    /**
     * Validate configuration and return report.
     */
    public ValidationReport validate() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        long totalMemory = config.totalMemoryMb();
        long allocated = config.allocatedMemoryMb();

        // Check memory budget
        if (allocated > totalMemory) {
            errors.add(String.format(
                "Memory over-allocated: %dMB allocated > %dMB budget (%.1f%% over)",
                allocated, totalMemory, ((double)(allocated - totalMemory) / totalMemory) * 100));
        }

        // Check headroom
        long headroom = totalMemory - allocated;
        double headroomPct = (double) headroom / totalMemory * 100;
        if (headroomPct < 10 && headroomPct >= 0) {
            warnings.add(String.format(
                "Low memory headroom: %dMB free (%.1f%%). Recommend at least 10%%.",
                headroom, headroomPct));
        }

        // Check heap vs container ratios for JVM services
        checkHeapRatio(errors, warnings, PlatformConfig.Service.APPLICATION);
        checkHeapRatio(errors, warnings, PlatformConfig.Service.GATEWAY);
        checkHeapRatio(errors, warnings, PlatformConfig.Service.DROOLS);
        checkHeapRatio(errors, warnings, PlatformConfig.Service.KAFKA);

        // Check benchmark thresholds
        if (config.benchmark().minThroughput() < 1000) {
            warnings.add("Benchmark min-throughput < 1000 ops/sec seems too low");
        }

        return new ValidationReport(errors, warnings, totalMemory, allocated);
    }

    private void checkHeapRatio(List<String> errors, List<String> warnings, PlatformConfig.Service service) {
        try {
            PlatformConfig.ServiceConfig svc = config.service(service);
            long container = svc.containerMb();
            long heap = svc.heapMb();
            double ratio = (double) heap / container;

            String name = service.configKey();
            if (ratio > 0.85) {
                warnings.add(String.format(
                    "%s: heap/container ratio %.0f%% too high (heap=%dMB, container=%dMB). Leave room for native memory.",
                    name, ratio * 100, heap, container));
            }
            if (ratio < 0.5) {
                warnings.add(String.format(
                    "%s: heap/container ratio %.0f%% wasteful (heap=%dMB, container=%dMB)",
                    name, ratio * 100, heap, container));
            }
        } catch (Exception ignored) {}
    }

    /**
     * Print memory summary.
     */
    public String generateSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Platform Memory Summary\n");
        sb.append("═══════════════════════════════════════════════════════════\n\n");

        long total = config.totalMemoryMb();
        long allocated = config.allocatedMemoryMb();
        long free = total - allocated;

        sb.append(String.format("Budget:    %,6d MB\n", total));
        sb.append(String.format("Allocated: %,6d MB (%.1f%%)\n", allocated, (double) allocated / total * 100));
        sb.append(String.format("Free:      %,6d MB (%.1f%%)\n\n", free, (double) free / total * 100));

        sb.append("Service Breakdown:\n");
        sb.append("───────────────────────────────────────────────────────────\n");
        sb.append(String.format("%-22s %8s %8s %s\n", "Service", "Container", "Heap", "Description"));
        sb.append("───────────────────────────────────────────────────────────\n");

        // Type-safe iteration over all services
        for (PlatformConfig.Service service : PlatformConfig.Service.values()) {
            try {
                PlatformConfig.ServiceConfig svc = config.service(service);
                String heap;
                try {
                    heap = svc.heapMb() + "MB";
                } catch (Exception e) {
                    heap = "-";
                }
                sb.append(String.format("%-22s %6dMB %8s %s\n",
                    service.configKey(), svc.containerMb(), heap, truncate(svc.description(), 30)));
            } catch (Exception ignored) {}
        }

        sb.append("───────────────────────────────────────────────────────────\n");
        sb.append(String.format("%-22s %6dMB\n", "TOTAL", allocated));

        return sb.toString();
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    public record ValidationReport(
        List<String> errors,
        List<String> warnings,
        long budgetMb,
        long allocatedMb
    ) {
        public boolean isValid() {
            return errors.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Configuration Validation Report\n");
            sb.append("═══════════════════════════════════════════════════════════\n\n");

            sb.append(String.format("Budget: %,d MB | Allocated: %,d MB | Free: %,d MB\n\n",
                budgetMb, allocatedMb, budgetMb - allocatedMb));

            if (errors.isEmpty() && warnings.isEmpty()) {
                sb.append("✓ Configuration is valid\n");
            } else {
                if (!errors.isEmpty()) {
                    sb.append("ERRORS:\n");
                    for (String error : errors) {
                        sb.append("  ✗ ").append(error).append("\n");
                    }
                    sb.append("\n");
                }
                if (!warnings.isEmpty()) {
                    sb.append("WARNINGS:\n");
                    for (String warning : warnings) {
                        sb.append("  ⚠ ").append(warning).append("\n");
                    }
                }
            }

            return sb.toString();
        }
    }

    public static void main(String[] args) {
        String command = args.length > 0 ? args[0] : "summary";
        String profile = args.length > 1 ? args[1] : null;

        ConfigGenerator generator = profile != null
            ? new ConfigGenerator(profile)
            : new ConfigGenerator();

        switch (command) {
            case "validate" -> System.out.println(generator.validate());
            case "env" -> System.out.println(generator.generateEnvFile());
            case "json" -> System.out.println(generator.generateJson());
            case "summary" -> System.out.println(generator.generateSummary());
            default -> {
                System.err.println("Usage: ConfigGenerator [validate|env|json|summary] [profile]");
                System.exit(1);
            }
        }
    }
}
