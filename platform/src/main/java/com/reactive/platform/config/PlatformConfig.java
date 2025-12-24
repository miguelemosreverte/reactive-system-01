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
 *   // Get service memory
 *   long heapMb = config.service("application").heapMb();
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
    private final Map<String, ServiceConfig> serviceCache = new ConcurrentHashMap<>();

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
    // Service Configuration
    // ==========================================================================

    /**
     * Get configuration for a specific service.
     */
    public ServiceConfig service(String name) {
        return serviceCache.computeIfAbsent(name, n ->
            new ServiceConfig(config.getConfig("services." + n)));
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
        Config services = config.getConfig("services");
        for (String service : services.root().keySet()) {
            try {
                total += services.getMemorySize(service + ".memory.container").toBytes() / BYTES_PER_MB;
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
