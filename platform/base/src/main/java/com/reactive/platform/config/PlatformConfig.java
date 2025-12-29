package com.reactive.platform.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.reactive.platform.config.ConfigAccessor.*;

/**
 * Type-safe access to platform configuration.
 *
 * Uses record classes for immutable, concise config objects.
 * All nested configs are records with static from(Config) factories.
 *
 * Example:
 * <pre>
 *   var config = PlatformConfig.load();
 *   long heapMb = config.service(Service.APPLICATION).heapMb();
 *   int batchSize = config.kafkaBenchmark().batchSizeStandard();
 * </pre>
 */
public final class PlatformConfig {

    private static final String ROOT = "platform";
    private static volatile PlatformConfig instance;

    private final Config config;
    private final Map<Service, ServiceConfig> serviceCache = new ConcurrentHashMap<>();

    // Lazy-cached records
    private volatile BenchmarkConfig benchmarkConfig;
    private volatile KafkaConfig kafkaConfig;
    private volatile KafkaBenchmarkConfig kafkaBenchmarkConfig;
    private volatile NetworkConfig networkConfig;
    private volatile PublisherConfig publisherConfig;
    private volatile MicrobatchConfig microbatchConfig;

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

        private final String key;

        Service(String key) { this.key = key; }

        public String key() { return key; }

        /** @deprecated Use key() instead */
        @Deprecated
        public String configKey() { return key; }
    }

    private PlatformConfig(Config config) {
        this.config = config.getConfig(ROOT);
    }

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

    public static PlatformConfig loadProfile(String profile) {
        Config base = ConfigFactory.load();
        Config profileConfig = base.getConfig(ROOT + ".profiles." + profile);
        return new PlatformConfig(profileConfig.withFallback(base));
    }

    public static void reload() {
        synchronized (PlatformConfig.class) {
            ConfigFactory.invalidateCaches();
            instance = null;
        }
    }

    // =========================================================================
    // Service Configuration
    // =========================================================================

    public ServiceConfig service(Service service) {
        return serviceCache.computeIfAbsent(service,
            s -> ServiceConfig.from(config.getConfig("services." + s.key())));
    }

    public ServiceConfig application()       { return service(Service.APPLICATION); }
    public ServiceConfig gateway()           { return service(Service.GATEWAY); }
    public ServiceConfig flinkJobManager()   { return service(Service.FLINK_JOB_MANAGER); }
    public ServiceConfig flinkTaskManager()  { return service(Service.FLINK_TASK_MANAGER); }
    public ServiceConfig drools()            { return service(Service.DROOLS); }
    public ServiceConfig kafkaService()      { return service(Service.KAFKA); }
    public ServiceConfig otelCollector()     { return service(Service.OTEL_COLLECTOR); }
    public ServiceConfig jaeger()            { return service(Service.JAEGER); }
    public ServiceConfig prometheus()        { return service(Service.PROMETHEUS); }
    public ServiceConfig loki()              { return service(Service.LOKI); }
    public ServiceConfig promtail()          { return service(Service.PROMTAIL); }
    public ServiceConfig grafana()           { return service(Service.GRAFANA); }
    public ServiceConfig cadvisor()          { return service(Service.CADVISOR); }
    public ServiceConfig ui()                { return service(Service.UI); }

    public long totalMemoryMb() {
        return memoryMb(config, "total-memory", 0L);
    }

    public long allocatedMemoryMb() {
        return Arrays.stream(Service.values())
            .mapToLong(svc -> {
                try { return service(svc).containerMb(); }
                catch (Exception e) { return 0L; }
            })
            .sum();
    }

    // =========================================================================
    // Cached Config Accessors
    // =========================================================================

    public BenchmarkConfig benchmark() {
        if (benchmarkConfig == null) {
            benchmarkConfig = BenchmarkConfig.from(config.getConfig("benchmark"));
        }
        return benchmarkConfig;
    }

    public KafkaConfig kafka() {
        if (kafkaConfig == null) {
            kafkaConfig = KafkaConfig.from(config.getConfig("services.kafka"));
        }
        return kafkaConfig;
    }

    public KafkaBenchmarkConfig kafkaBenchmark() {
        if (kafkaBenchmarkConfig == null) {
            kafkaBenchmarkConfig = KafkaBenchmarkConfig.from(config.getConfig("kafka-benchmark"));
        }
        return kafkaBenchmarkConfig;
    }

    public NetworkConfig network() {
        if (networkConfig == null) {
            networkConfig = NetworkConfig.from(config.getConfig("network"));
        }
        return networkConfig;
    }

    public PublisherConfig publisher() {
        if (publisherConfig == null) {
            publisherConfig = PublisherConfig.from(config.getConfig("publisher"));
        }
        return publisherConfig;
    }

    public MicrobatchConfig microbatch() {
        if (microbatchConfig == null) {
            microbatchConfig = MicrobatchConfig.from(config.getConfig("microbatch"));
        }
        return microbatchConfig;
    }

    public Config raw() { return config; }

    // =========================================================================
    // Record: ServiceConfig
    // =========================================================================

    public record ServiceConfig(
        String description,
        long containerMb,
        long heapMb,
        long processSizeMb,
        List<String> jvmOptions,
        int parallelism,
        int asyncCapacity
    ) {
        public static ServiceConfig from(Config c) {
            return new ServiceConfig(
                string(c, "description", ""),
                memoryMb(c, "memory.container", 0L),
                memoryMb(c, "memory.heap")
                    .or(() -> memoryMb(c, "memory.go-memlimit"))
                    .orElse(0L),
                memoryMb(c, "memory.process-size", 0L),
                stringList(c, "jvm.options", List.of()),
                intVal(c, "parallelism", 1),
                intVal(c, "async-capacity", 100)
            );
        }
    }

    // =========================================================================
    // Record: BenchmarkConfig
    // =========================================================================

    public record BenchmarkConfig(
        int minThroughput,
        int maxP99LatencyMs,
        Duration warmupDuration,
        Duration measurementDuration,
        int concurrentClients
    ) {
        public static BenchmarkConfig from(Config c) {
            return new BenchmarkConfig(
                c.getInt("min-throughput"),
                c.getInt("max-p99-latency"),
                c.getDuration("warmup-duration"),
                c.getDuration("measurement-duration"),
                c.getInt("concurrent-clients")
            );
        }
    }

    // =========================================================================
    // Record: NetworkConfig
    // =========================================================================

    public record NetworkConfig(
        int gatewayPort,
        int uiPort,
        int grafanaPort,
        int prometheusPort,
        int jaegerPort,
        String dockerNetwork
    ) {
        public static NetworkConfig from(Config c) {
            return new NetworkConfig(
                c.getInt("ports.gateway"),
                c.getInt("ports.ui"),
                c.getInt("ports.grafana"),
                c.getInt("ports.prometheus"),
                c.getInt("ports.jaeger"),
                c.getString("docker-network")
            );
        }
    }

    // =========================================================================
    // Record: KafkaConfig
    // =========================================================================

    public record KafkaConfig(
        TopicsConfig topics,
        ProducerSettings producer
    ) {
        public static KafkaConfig from(Config c) {
            return new KafkaConfig(
                TopicsConfig.from(c.getConfig("topics")),
                ProducerSettings.from(c.getConfig("producer"))
            );
        }

        public record TopicsConfig(String events, String results, String alerts) {
            public static TopicsConfig from(Config c) {
                return new TopicsConfig(
                    c.getString("events"),
                    c.getString("results"),
                    c.getString("alerts")
                );
            }
        }

        public record ProducerSettings(
            int batchSize, int lingerMs, long bufferMemory, String compression, int acks
        ) {
            public static ProducerSettings from(Config c) {
                return new ProducerSettings(
                    c.getInt("batch-size"),
                    c.getInt("linger-ms"),
                    c.getLong("buffer-memory"),
                    c.getString("compression"),
                    c.getInt("acks")
                );
            }
        }
    }

    // =========================================================================
    // Record: KafkaBenchmarkConfig
    // =========================================================================

    public record KafkaBenchmarkConfig(
        int messageSize,
        Durations durations,
        Warmup warmup,
        BatchSizes batchSize,
        BufferMemory bufferMemory,
        ProducerDefaults producer,
        BatcherDefaults batcher
    ) {
        public static KafkaBenchmarkConfig from(Config c) {
            return new KafkaBenchmarkConfig(
                c.getInt("message-size"),
                Durations.from(c.getConfig("durations")),
                Warmup.from(c.getConfig("warmup")),
                BatchSizes.from(c.getConfig("batch-size")),
                BufferMemory.from(c.getConfig("buffer-memory")),
                ProducerDefaults.from(c.getConfig("producer")),
                BatcherDefaults.from(c.getConfig("batcher"))
            );
        }

        public record Durations(
            int smoke, int quick, int thorough, int brochureTotal, int brochureRamp, int brochureSustain
        ) {
            public static Durations from(Config c) {
                return new Durations(
                    c.getInt("smoke"),
                    c.getInt("quick"),
                    c.getInt("thorough"),
                    c.getInt("brochure-total"),
                    c.getInt("brochure-ramp"),
                    c.getInt("brochure-sustain")
                );
            }
        }

        public record Warmup(int messagesNaive, int batchesBulk) {
            public static Warmup from(Config c) {
                return new Warmup(c.getInt("messages-naive"), c.getInt("batches-bulk"));
            }
        }

        public record BatchSizes(int small, int standard, int large, int max) {
            public static BatchSizes from(Config c) {
                return new BatchSizes(
                    c.getInt("small"), c.getInt("standard"), c.getInt("large"), c.getInt("max")
                );
            }
        }

        public record BufferMemory(long standard, long large) {
            public static BufferMemory from(Config c) {
                return new BufferMemory(c.getLong("standard"), c.getLong("large"));
            }
        }

        public record ProducerDefaults(
            int fetchMaxBytes, int maxRequestSize, String compression,
            int lingerMsHighThroughput, int lingerMsLowLatency, int lingerMsFireAndForget
        ) {
            public static ProducerDefaults from(Config c) {
                return new ProducerDefaults(
                    c.getInt("fetch-max-bytes"),
                    c.getInt("max-request-size"),
                    c.getString("compression"),
                    c.getInt("linger-ms-high-throughput"),
                    c.getInt("linger-ms-low-latency"),
                    c.getInt("linger-ms-fire-and-forget")
                );
            }
        }

        public record BatcherDefaults(int thresholdStandard, int intervalMs) {
            public static BatcherDefaults from(Config c) {
                return new BatcherDefaults(c.getInt("threshold-standard"), c.getInt("interval-ms"));
            }
        }

        // Convenience accessors for backward compatibility
        public int smokeDurationSec()           { return durations.smoke(); }
        public int quickDurationSec()           { return durations.quick(); }
        public int thoroughDurationSec()        { return durations.thorough(); }
        public int brochureTotalDurationSec()   { return durations.brochureTotal(); }
        public int brochureRampDurationSec()    { return durations.brochureRamp(); }
        public int brochureSustainDurationSec() { return durations.brochureSustain(); }
        public int warmupMessagesNaive()        { return warmup.messagesNaive(); }
        public int warmupBatchesBulk()          { return warmup.batchesBulk(); }
        public int batchSizeSmall()             { return batchSize.small(); }
        public int batchSizeStandard()          { return batchSize.standard(); }
        public int batchSizeLarge()             { return batchSize.large(); }
        public int batchSizeMax()               { return batchSize.max(); }
        public long bufferMemoryStandard()      { return bufferMemory.standard(); }
        public long bufferMemoryLarge()         { return bufferMemory.large(); }
        public int fetchMaxBytes()              { return producer.fetchMaxBytes(); }
        public int maxRequestSize()             { return producer.maxRequestSize(); }
        public String compression()             { return producer.compression(); }
        public int lingerMsHighThroughput()     { return producer.lingerMsHighThroughput(); }
        public int lingerMsLowLatency()         { return producer.lingerMsLowLatency(); }
        public int lingerMsFireAndForget()      { return producer.lingerMsFireAndForget(); }
        public int batcherThresholdStandard()   { return batcher.thresholdStandard(); }
        public int batcherIntervalMs()          { return batcher.intervalMs(); }
    }

    // =========================================================================
    // Record: PublisherConfig
    // =========================================================================

    public record PublisherConfig(
        int maxInFlightRequests,
        int lingerMs,
        int batchSize,
        long bufferMemory,
        String compression,
        String acks,
        int estimatedMessageSize,
        FireAndForgetMode fireAndForget,
        HighThroughputMode highThroughput
    ) {
        public static PublisherConfig from(Config c) {
            return new PublisherConfig(
                c.getInt("max-in-flight-requests"),
                c.getInt("linger-ms"),
                c.getInt("batch-size"),
                c.getLong("buffer-memory"),
                c.getString("compression"),
                c.getString("acks"),
                c.getInt("estimated-message-size"),
                FireAndForgetMode.from(c.getConfig("fire-and-forget")),
                HighThroughputMode.from(c.getConfig("high-throughput"))
            );
        }

        public record FireAndForgetMode(
            int maxInFlightRequests, int lingerMs, int batchSize, String compression, String acks
        ) {
            public static FireAndForgetMode from(Config c) {
                return new FireAndForgetMode(
                    c.getInt("max-in-flight-requests"),
                    c.getInt("linger-ms"),
                    c.getInt("batch-size"),
                    c.getString("compression"),
                    c.getString("acks")
                );
            }
        }

        public record HighThroughputMode(
            int maxInFlightRequests, int lingerMs, int batchSize, long bufferMemory, String compression, String acks
        ) {
            public static HighThroughputMode from(Config c) {
                return new HighThroughputMode(
                    c.getInt("max-in-flight-requests"),
                    c.getInt("linger-ms"),
                    c.getInt("batch-size"),
                    c.getLong("buffer-memory"),
                    c.getString("compression"),
                    c.getString("acks")
                );
            }
        }
    }

    // =========================================================================
    // Record: MicrobatchConfig
    // =========================================================================

    public record MicrobatchConfig(
        long pressureWindowNanos,
        int ringBufferCapacity,
        int flushThreads,
        int maxBatchSize
    ) {
        public static MicrobatchConfig from(Config c) {
            return new MicrobatchConfig(
                c.getLong("pressure-window-nanos"),
                c.getInt("ring-buffer-capacity"),
                c.getInt("flush-threads"),
                c.getInt("max-batch-size")
            );
        }
    }
}
