package com.reactive.platform.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigMemorySize;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Type-safe access to platform configuration.
 *
 * Loads configuration from:
 * 1. reference.conf (defaults in JAR)
 * 2. application.conf (overrides, if present)
 * 3. Environment variables (highest priority)
 *
 * Example usage:
 * <pre>
 *   PlatformConfig config = PlatformConfig.load();
 *
 *   // Type-safe service access (no string keys!)
 *   long heapMb = config.application().heapMb();
 *   long flinkMemory = config.flinkTaskManager().containerMb();
 *
 *   // Or use the enum
 *   long kafkaMemory = config.service(Service.KAFKA).containerMb();
 *
 *   // Get benchmark thresholds
 *   int minThroughput = config.benchmark().minThroughput();
 *
 *   // Get Kafka settings
 *   String topic = config.kafka().topics().events();
 * </pre>
 */
public final class PlatformConfig {

    private static final String ROOT = "platform";
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static volatile PlatformConfig instance;

    private final Config config;
    private final Map<Service, ServiceConfig> serviceCache = new ConcurrentHashMap<>();

    /**
     * All platform services - provides compile-time safety for service access.
     */
    public enum Service {
        APPLICATION("application"),
        GATEWAY("gateway"),
        FLINK_JOB_MANAGER("flink-jobmanager"),
        FLINK_TASK_MANAGER("flink-taskmanager"),
        DROOLS("drools"),
        KAFKA("kafka"),
        OTEL_COLLECTOR("otel-collector"),
        JAEGER("jaeger"),
        PROMETHEUS("prometheus"),
        LOKI("loki"),
        PROMTAIL("promtail"),
        GRAFANA("grafana"),
        CADVISOR("cadvisor"),
        UI("ui");

        private final String configKey;

        Service(String configKey) {
            this.configKey = configKey;
        }

        public String configKey() {
            return configKey;
        }
    }

    private PlatformConfig(Config config) {
        this.config = config.getConfig(ROOT);
    }

    /**
     * Load configuration with standard resolution order.
     */
    public static PlatformConfig load() {
        if (instance == null) {
            synchronized (PlatformConfig.class) {
                if (instance == null) {
                    instance = new PlatformConfig(ConfigFactory.load());
                }
            }
        }
        return instance;
    }

    /**
     * Load with a specific profile (e.g., "ci", "production").
     */
    public static PlatformConfig loadProfile(String profile) {
        Config base = ConfigFactory.load();
        Config profileConfig = base.getConfig(ROOT + ".profiles." + profile);
        Config merged = profileConfig.withFallback(base);
        return new PlatformConfig(merged);
    }

    /**
     * Reload configuration (useful for testing).
     */
    public static void reload() {
        synchronized (PlatformConfig.class) {
            ConfigFactory.invalidateCaches();
            instance = null;
        }
    }

    // ==========================================================================
    // Service Configuration - Type-safe accessors
    // ==========================================================================

    /**
     * Get configuration for a specific service using the enum.
     */
    public ServiceConfig service(Service service) {
        return serviceCache.computeIfAbsent(service, s ->
            new ServiceConfig(config.getConfig("services." + s.configKey())));
    }

    // Direct accessors for each service - fully type-safe, no strings!

    public ServiceConfig application() {
        return service(Service.APPLICATION);
    }

    public ServiceConfig gateway() {
        return service(Service.GATEWAY);
    }

    public ServiceConfig flinkJobManager() {
        return service(Service.FLINK_JOB_MANAGER);
    }

    public ServiceConfig flinkTaskManager() {
        return service(Service.FLINK_TASK_MANAGER);
    }

    public ServiceConfig drools() {
        return service(Service.DROOLS);
    }

    public ServiceConfig kafkaService() {
        return service(Service.KAFKA);
    }

    public ServiceConfig otelCollector() {
        return service(Service.OTEL_COLLECTOR);
    }

    public ServiceConfig jaeger() {
        return service(Service.JAEGER);
    }

    public ServiceConfig prometheus() {
        return service(Service.PROMETHEUS);
    }

    public ServiceConfig loki() {
        return service(Service.LOKI);
    }

    public ServiceConfig promtail() {
        return service(Service.PROMTAIL);
    }

    public ServiceConfig grafana() {
        return service(Service.GRAFANA);
    }

    public ServiceConfig cadvisor() {
        return service(Service.CADVISOR);
    }

    public ServiceConfig ui() {
        return service(Service.UI);
    }

    /**
     * Total memory budget for the platform.
     */
    public long totalMemoryMb() {
        return config.getMemorySize("total-memory").toBytes() / BYTES_PER_MB;
    }

    /**
     * Sum of all service container memory limits.
     */
    public long allocatedMemoryMb() {
        long total = 0;
        for (Service svc : Service.values()) {
            try {
                total += service(svc).containerMb();
            } catch (Exception ignored) {
                // Service might not have memory config
            }
        }
        return total;
    }

    // ==========================================================================
    // Benchmark Configuration
    // ==========================================================================

    public BenchmarkConfig benchmark() {
        return new BenchmarkConfig(config.getConfig("benchmark"));
    }

    // ==========================================================================
    // Kafka Configuration
    // ==========================================================================

    public KafkaConfig kafka() {
        return new KafkaConfig(config.getConfig("services.kafka"));
    }

    // ==========================================================================
    // Kafka Benchmark Configuration
    // ==========================================================================

    public KafkaBenchmarkConfig kafkaBenchmark() {
        return new KafkaBenchmarkConfig(config.getConfig("kafka-benchmark"));
    }

    // ==========================================================================
    // Network Configuration
    // ==========================================================================

    public NetworkConfig network() {
        return new NetworkConfig(config.getConfig("network"));
    }

    // ==========================================================================
    // Nested Configuration Classes
    // ==========================================================================

    public static final class ServiceConfig {
        private final Config config;

        ServiceConfig(Config config) {
            this.config = config;
        }

        public String description() {
            return config.getString("description");
        }

        public long containerMb() {
            return config.getMemorySize("memory.container").toBytes() / BYTES_PER_MB;
        }

        public long heapMb() {
            return getOptionalMemory("memory.heap")
                .or(() -> getOptionalMemory("memory.go-memlimit"))
                .orElse(0L);
        }

        private Optional<Long> getOptionalMemory(String path) {
            return config.hasPath(path)
                ? Optional.of(config.getMemorySize(path).toBytes() / BYTES_PER_MB)
                : Optional.empty();
        }

        public long processSizeMb() {
            return config.getMemorySize("memory.process-size").toBytes() / BYTES_PER_MB;
        }

        public List<String> jvmOptions() {
            return getOptionalStringList("jvm.options").orElse(List.of());
        }

        public int parallelism() {
            return getOptionalInt("parallelism").orElse(1);
        }

        public int asyncCapacity() {
            return getOptionalInt("async-capacity").orElse(100);
        }

        private Optional<List<String>> getOptionalStringList(String path) {
            return config.hasPath(path)
                ? Optional.of(config.getStringList(path))
                : Optional.empty();
        }

        private Optional<Integer> getOptionalInt(String path) {
            return config.hasPath(path)
                ? Optional.of(config.getInt(path))
                : Optional.empty();
        }

        public Config raw() {
            return config;
        }
    }

    public static final class BenchmarkConfig {
        private final Config config;

        BenchmarkConfig(Config config) {
            this.config = config;
        }

        public int minThroughput() {
            return config.getInt("min-throughput");
        }

        public int maxP99LatencyMs() {
            return config.getInt("max-p99-latency");
        }

        public Duration warmupDuration() {
            return config.getDuration("warmup-duration");
        }

        public Duration measurementDuration() {
            return config.getDuration("measurement-duration");
        }

        public int concurrentClients() {
            return config.getInt("concurrent-clients");
        }
    }

    public static final class KafkaConfig {
        private final Config config;

        KafkaConfig(Config config) {
            this.config = config;
        }

        public TopicsConfig topics() {
            return new TopicsConfig(config.getConfig("topics"));
        }

        public ProducerConfig producer() {
            return new ProducerConfig(config.getConfig("producer"));
        }

        public static final class TopicsConfig {
            private final Config config;

            TopicsConfig(Config config) {
                this.config = config;
            }

            public String events() {
                return config.getString("events");
            }

            public String results() {
                return config.getString("results");
            }

            public String alerts() {
                return config.getString("alerts");
            }
        }

        public static final class ProducerConfig {
            private final Config config;

            ProducerConfig(Config config) {
                this.config = config;
            }

            public int batchSize() {
                return config.getInt("batch-size");
            }

            public int lingerMs() {
                return config.getInt("linger-ms");
            }

            public long bufferMemory() {
                return config.getLong("buffer-memory");
            }

            public String compression() {
                return config.getString("compression");
            }

            public int acks() {
                return config.getInt("acks");
            }
        }
    }

    public static final class NetworkConfig {
        private final Config config;

        NetworkConfig(Config config) {
            this.config = config;
        }

        public int gatewayPort() {
            return config.getInt("ports.gateway");
        }

        public int uiPort() {
            return config.getInt("ports.ui");
        }

        public int grafanaPort() {
            return config.getInt("ports.grafana");
        }

        public int prometheusPort() {
            return config.getInt("ports.prometheus");
        }

        public int jaegerPort() {
            return config.getInt("ports.jaeger");
        }

        public String dockerNetwork() {
            return config.getString("docker-network");
        }
    }

    /**
     * Kafka benchmark configuration - all settings for high-throughput benchmarks.
     */
    public static final class KafkaBenchmarkConfig {
        private final Config config;

        KafkaBenchmarkConfig(Config config) {
            this.config = config;
        }

        // Message configuration
        public int messageSize() {
            return config.getInt("message-size");
        }

        // Durations
        public int smokeDurationSec() {
            return config.getInt("durations.smoke");
        }

        public int quickDurationSec() {
            return config.getInt("durations.quick");
        }

        public int thoroughDurationSec() {
            return config.getInt("durations.thorough");
        }

        public int brochureTotalDurationSec() {
            return config.getInt("durations.brochure-total");
        }

        public int brochureRampDurationSec() {
            return config.getInt("durations.brochure-ramp");
        }

        public int brochureSustainDurationSec() {
            return config.getInt("durations.brochure-sustain");
        }

        // Warmup
        public int warmupMessagesNaive() {
            return config.getInt("warmup.messages-naive");
        }

        public int warmupBatchesBulk() {
            return config.getInt("warmup.batches-bulk");
        }

        // Batch sizes
        public int batchSizeSmall() {
            return config.getInt("batch-size.small");
        }

        public int batchSizeStandard() {
            return config.getInt("batch-size.standard");
        }

        public int batchSizeLarge() {
            return config.getInt("batch-size.large");
        }

        public int batchSizeMax() {
            return config.getInt("batch-size.max");
        }

        // Buffer memory
        public long bufferMemoryStandard() {
            return config.getLong("buffer-memory.standard");
        }

        public long bufferMemoryLarge() {
            return config.getLong("buffer-memory.large");
        }

        // Producer settings
        public int fetchMaxBytes() {
            return config.getInt("producer.fetch-max-bytes");
        }

        public int maxRequestSize() {
            return config.getInt("producer.max-request-size");
        }

        public String compression() {
            return config.getString("producer.compression");
        }

        public int lingerMsHighThroughput() {
            return config.getInt("producer.linger-ms-high-throughput");
        }

        public int lingerMsLowLatency() {
            return config.getInt("producer.linger-ms-low-latency");
        }

        public int lingerMsFireAndForget() {
            return config.getInt("producer.linger-ms-fire-and-forget");
        }

        // Batcher settings
        public int batcherThresholdStandard() {
            return config.getInt("batcher.threshold-standard");
        }

        public int batcherIntervalMs() {
            return config.getInt("batcher.interval-ms");
        }
    }

    // ==========================================================================
    // Publisher Configuration
    // ==========================================================================

    public PublisherConfig publisher() {
        return new PublisherConfig(config.getConfig("publisher"));
    }

    // ==========================================================================
    // Microbatch Configuration
    // ==========================================================================

    public MicrobatchConfig microbatch() {
        return new MicrobatchConfig(config.getConfig("microbatch"));
    }

    /**
     * Microbatch configuration - settings for MicrobatchCollector and ring buffers.
     */
    public static final class MicrobatchConfig {
        private final Config config;

        MicrobatchConfig(Config config) {
            this.config = config;
        }

        public long pressureWindowNanos() {
            return config.getLong("pressure-window-nanos");
        }

        public int ringBufferCapacity() {
            return config.getInt("ring-buffer-capacity");
        }

        public int flushThreads() {
            return config.getInt("flush-threads");
        }

        public int maxBatchSize() {
            return config.getInt("max-batch-size");
        }
    }

    /**
     * Publisher configuration - defaults for KafkaPublisher builder.
     */
    public static final class PublisherConfig {
        private final Config config;

        PublisherConfig(Config config) {
            this.config = config;
        }

        // Default settings
        public int maxInFlightRequests() {
            return config.getInt("max-in-flight-requests");
        }

        public int lingerMs() {
            return config.getInt("linger-ms");
        }

        public int batchSize() {
            return config.getInt("batch-size");
        }

        public long bufferMemory() {
            return config.getLong("buffer-memory");
        }

        public String compression() {
            return config.getString("compression");
        }

        public String acks() {
            return config.getString("acks");
        }

        public int estimatedMessageSize() {
            return config.getInt("estimated-message-size");
        }

        // Fire-and-forget mode
        public FireAndForgetConfig fireAndForget() {
            return new FireAndForgetConfig(config.getConfig("fire-and-forget"));
        }

        // High-throughput mode
        public HighThroughputConfig highThroughput() {
            return new HighThroughputConfig(config.getConfig("high-throughput"));
        }

        public static final class FireAndForgetConfig {
            private final Config config;

            FireAndForgetConfig(Config config) {
                this.config = config;
            }

            public int maxInFlightRequests() {
                return config.getInt("max-in-flight-requests");
            }

            public int lingerMs() {
                return config.getInt("linger-ms");
            }

            public int batchSize() {
                return config.getInt("batch-size");
            }

            public String compression() {
                return config.getString("compression");
            }

            public String acks() {
                return config.getString("acks");
            }
        }

        public static final class HighThroughputConfig {
            private final Config config;

            HighThroughputConfig(Config config) {
                this.config = config;
            }

            public int maxInFlightRequests() {
                return config.getInt("max-in-flight-requests");
            }

            public int lingerMs() {
                return config.getInt("linger-ms");
            }

            public int batchSize() {
                return config.getInt("batch-size");
            }

            public long bufferMemory() {
                return config.getLong("buffer-memory");
            }

            public String compression() {
                return config.getString("compression");
            }

            public String acks() {
                return config.getString("acks");
            }
        }
    }

    /**
     * Get the raw underlying config (for advanced usage).
     */
    public Config raw() {
        return config;
    }
}
