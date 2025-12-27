package com.reactive.platform.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigMemorySize;

import java.time.Duration;
import java.util.List;
import java.util.Map;
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
            try {
                return config.getMemorySize("memory.heap").toBytes() / BYTES_PER_MB;
            } catch (Exception e) {
                // Go services use go-memlimit instead
                return config.getMemorySize("memory.go-memlimit").toBytes() / BYTES_PER_MB;
            }
        }

        public long processSizeMb() {
            return config.getMemorySize("memory.process-size").toBytes() / BYTES_PER_MB;
        }

        public List<String> jvmOptions() {
            try {
                return config.getStringList("jvm.options");
            } catch (Exception e) {
                return List.of();
            }
        }

        public int parallelism() {
            try {
                return config.getInt("parallelism");
            } catch (Exception e) {
                return 1;
            }
        }

        public int asyncCapacity() {
            try {
                return config.getInt("async-capacity");
            } catch (Exception e) {
                return 100;
            }
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
     * Get the raw underlying config (for advanced usage).
     */
    public Config raw() {
        return config;
    }
}
