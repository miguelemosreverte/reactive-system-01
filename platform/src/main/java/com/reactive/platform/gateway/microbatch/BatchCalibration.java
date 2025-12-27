package com.reactive.platform.gateway.microbatch;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Pressure-aware calibration store for microbatch parameters.
 *
 * KEY INSIGHT: Different load levels need different optimal configurations.
 * HTTP timeout gives us massive latency headroom (30-60s) that we can exploit
 * for throughput at extreme load levels.
 *
 * BULK BENCHMARK BASELINE: 131.8M msg/s with batch=1000, flush=linger.ms
 * Our goal: Make adaptive system reach this theoretical max.
 *
 * Pressure Buckets (requests per 10 seconds):
 *   IDLE:       < 100 req/10s      → Small batches, sub-ms latency
 *   LOW:        100 - 1K           → Moderate batches, 1ms latency
 *   MEDIUM:     1K - 10K           → Balanced, 2ms latency
 *   HIGH:       10K - 100K         → Larger batches, 5ms latency
 *   EXTREME:    100K - 1M          → Big batches, 10ms latency
 *   OVERLOAD:   1M - 10M           → Mega batches, 50ms latency
 *   MEGA:       10M - 100M         → Maximum batches, 100ms latency
 *   HTTP_30S:   100M - 1B          → HTTP timeout tolerance, 1s latency OK
 *   HTTP_60S:   > 1B               → Extended timeout, 5s latency OK (benchmark only)
 *
 * The system learns optimal config for EACH pressure level separately.
 * When load changes, it switches to the appropriate bucket's config.
 */
public final class BatchCalibration implements AutoCloseable {

    /**
     * Pressure levels based on requests per 10 seconds.
     * Higher pressure = bigger batches = higher latency but better throughput.
     *
     * BULK benchmark achieved 131.8M msg/s with batch=1000.
     * HTTP_30S and HTTP_60S are designed to reach that throughput.
     */
    public enum PressureLevel {
        IDLE(0, 100),                              // Low traffic, optimize for latency
        LOW(100, 1_000),                           // Light load
        MEDIUM(1_000, 10_000),                     // Normal load
        HIGH(10_000, 100_000),                     // Heavy load
        EXTREME(100_000, 1_000_000),               // Very heavy load
        OVERLOAD(1_000_000, 10_000_000),           // Overload - mega batches
        MEGA(10_000_000, 100_000_000),             // 10M-100M req/10s
        HTTP_30S(100_000_000, 1_000_000_000),      // 100M-1B req/10s - HTTP timeout OK
        HTTP_60S(1_000_000_000, Long.MAX_VALUE);   // >1B req/10s - Extended timeout (benchmark)

        public final long minReqPer10s;
        public final long maxReqPer10s;

        PressureLevel(long min, long max) {
            this.minReqPer10s = min;
            this.maxReqPer10s = max;
        }

        public static PressureLevel fromRequestRate(long requestsPer10Seconds) {
            for (PressureLevel level : values()) {
                if (requestsPer10Seconds >= level.minReqPer10s && requestsPer10Seconds < level.maxReqPer10s) {
                    return level;
                }
            }
            return HTTP_60S;
        }

        /**
         * Default config for this pressure level.
         *
         * Key insight from BULK benchmark:
         * - 1000 messages per Kafka send = 131.8M msg/s
         * - This is our theoretical max to chase
         *
         * HTTP_30S/HTTP_60S configs match BULK benchmark settings:
         * - batch=1000 (matching BULK benchmark)
         * - flush interval allows accumulation time
         */
        public Config defaultConfig() {
            return switch (this) {
                case IDLE -> new Config(16, 500, 0, 0, 0, 0, Instant.now());              // 0.5ms
                case LOW -> new Config(64, 1_000, 0, 0, 0, 0, Instant.now());             // 1ms
                case MEDIUM -> new Config(256, 2_000, 0, 0, 0, 0, Instant.now());          // 2ms
                case HIGH -> new Config(512, 5_000, 0, 0, 0, 0, Instant.now());           // 5ms
                case EXTREME -> new Config(1_000, 10_000, 0, 0, 0, 0, Instant.now());     // 10ms
                case OVERLOAD -> new Config(2_000, 50_000, 0, 0, 0, 0, Instant.now());    // 50ms
                case MEGA -> new Config(4_000, 100_000, 0, 0, 0, 0, Instant.now());       // 100ms
                case HTTP_30S -> new Config(4_000, 500_000, 0, 0, 0, 0, Instant.now());   // 500ms - larger batches
                case HTTP_60S -> new Config(4_000, 1_000_000, 0, 0, 0, 0, Instant.now()); // 1s - max batch
            };
        }
    }

    /** Immutable calibration config */
    public record Config(
        int batchSize,
        int flushIntervalMicros,
        double score,
        long throughputPerSec,
        double avgLatencyMicros,
        double p99LatencyMicros,
        Instant calibratedAt
    ) {
        public static Config initial() {
            return new Config(64, 1000, 0, 0, 0, 0, Instant.now());
        }

        /** Score = throughput / (1 + latencyPenalty) */
        public static double calculateScore(long throughput, double avgLatency, double targetLatency) {
            double ratio = avgLatency / targetLatency;
            double penalty = ratio > 1.0 ? Math.pow(ratio, 2) - 1 : 0.0;
            return throughput / (1.0 + penalty);
        }
    }

    /** Observation from a calibration run */
    public record Observation(
        int batchSize,
        int flushIntervalMicros,
        long throughputPerSec,
        double avgLatencyMicros,
        double p99LatencyMicros,
        long sampleCount,
        Instant observedAt
    ) {}

    private final Path dbPath;
    private final Connection conn;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final double targetLatencyMicros;

    private volatile PressureLevel currentPressure = PressureLevel.MEDIUM;
    private volatile long lastPressureUpdateNanos = System.nanoTime();
    private volatile long requestCountInWindow = 0;

    private BatchCalibration(Path dbPath, double targetLatencyMicros) throws SQLException {
        this.dbPath = dbPath;
        this.targetLatencyMicros = targetLatencyMicros;

        String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        this.conn = DriverManager.getConnection(url);
        initializeSchema();
    }

    public static BatchCalibration create(Path dbPath, double targetLatencyMicros) {
        try {
            Files.createDirectories(dbPath.getParent());
            return new BatchCalibration(dbPath, targetLatencyMicros);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create calibration store", e);
        }
    }

    public static BatchCalibration createDefault() {
        Path home = Path.of(System.getProperty("user.home"));
        return create(home.resolve(".reactive/calibration.db"), 5000.0);
    }

    private void initializeSchema() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Observations table with pressure level
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS observations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    pressure_level TEXT NOT NULL,
                    batch_size INTEGER NOT NULL,
                    flush_interval_micros INTEGER NOT NULL,
                    throughput_per_sec INTEGER NOT NULL,
                    avg_latency_micros REAL NOT NULL,
                    p99_latency_micros REAL NOT NULL,
                    sample_count INTEGER NOT NULL,
                    score REAL NOT NULL,
                    observed_at TEXT NOT NULL
                )
                """);

            // Best config PER PRESSURE LEVEL
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS best_config (
                    pressure_level TEXT PRIMARY KEY,
                    batch_size INTEGER NOT NULL,
                    flush_interval_micros INTEGER NOT NULL,
                    score REAL NOT NULL,
                    throughput_per_sec INTEGER NOT NULL,
                    avg_latency_micros REAL NOT NULL,
                    p99_latency_micros REAL NOT NULL,
                    calibrated_at TEXT NOT NULL
                )
                """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_obs_pressure ON observations(pressure_level)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_obs_score ON observations(score DESC)");
        }
    }

    /**
     * Update pressure level based on observed request rate.
     * Call this with number of requests in the last 10 seconds.
     */
    public void updatePressure(long requestsPer10Seconds) {
        this.currentPressure = PressureLevel.fromRequestRate(requestsPer10Seconds);
        this.requestCountInWindow = requestsPer10Seconds;
        this.lastPressureUpdateNanos = System.nanoTime();
    }

    /** Get current pressure level */
    public PressureLevel getCurrentPressure() {
        return currentPressure;
    }

    /**
     * Get best config for CURRENT pressure level.
     */
    public Config getBestConfig() {
        return getBestConfigForPressure(currentPressure);
    }

    /**
     * Get best config for specific pressure level.
     */
    public Config getBestConfigForPressure(PressureLevel pressure) {
        try (PreparedStatement stmt = conn.prepareStatement(
            "SELECT batch_size, flush_interval_micros, score, throughput_per_sec, " +
            "avg_latency_micros, p99_latency_micros, calibrated_at " +
            "FROM best_config WHERE pressure_level = ?"
        )) {
            stmt.setString(1, pressure.name());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return new Config(
                    rs.getInt("batch_size"),
                    rs.getInt("flush_interval_micros"),
                    rs.getDouble("score"),
                    rs.getLong("throughput_per_sec"),
                    rs.getDouble("avg_latency_micros"),
                    rs.getDouble("p99_latency_micros"),
                    Instant.parse(rs.getString("calibrated_at"))
                );
            }
        } catch (SQLException e) {
            // Fall through to default
        }
        return pressure.defaultConfig();
    }

    /**
     * Record observation for CURRENT pressure level.
     */
    public void recordObservation(Observation obs) {
        recordObservation(obs, currentPressure);
    }

    /**
     * Record observation for specific pressure level.
     */
    public void recordObservation(Observation obs, PressureLevel pressure) {
        double score = Config.calculateScore(obs.throughputPerSec(), obs.avgLatencyMicros(), targetLatencyMicros);

        writeLock.lock();
        try {
            // Insert observation
            try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO observations (pressure_level, batch_size, flush_interval_micros, " +
                "throughput_per_sec, avg_latency_micros, p99_latency_micros, sample_count, score, observed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
            )) {
                stmt.setString(1, pressure.name());
                stmt.setInt(2, obs.batchSize());
                stmt.setInt(3, obs.flushIntervalMicros());
                stmt.setLong(4, obs.throughputPerSec());
                stmt.setDouble(5, obs.avgLatencyMicros());
                stmt.setDouble(6, obs.p99LatencyMicros());
                stmt.setLong(7, obs.sampleCount());
                stmt.setDouble(8, score);
                stmt.setString(9, obs.observedAt().toString());
                stmt.executeUpdate();
            }

            // Update best config if improved FOR THIS PRESSURE LEVEL
            Config current = getBestConfigForPressure(pressure);
            if (score > current.score()) {
                try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT OR REPLACE INTO best_config " +
                    "(pressure_level, batch_size, flush_interval_micros, score, throughput_per_sec, " +
                    "avg_latency_micros, p99_latency_micros, calibrated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                )) {
                    stmt.setString(1, pressure.name());
                    stmt.setInt(2, obs.batchSize());
                    stmt.setInt(3, obs.flushIntervalMicros());
                    stmt.setDouble(4, score);
                    stmt.setLong(5, obs.throughputPerSec());
                    stmt.setDouble(6, obs.avgLatencyMicros());
                    stmt.setDouble(7, obs.p99LatencyMicros());
                    stmt.setString(8, Instant.now().toString());
                    stmt.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record observation", e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Suggest next config to try for current pressure level.
     */
    public Config suggestNext(java.util.random.RandomGenerator rng) {
        Config best = getBestConfig();

        // 10% random exploration
        if (rng.nextDouble() < 0.1) {
            return new Config(
                rng.nextInt(16, 512),
                rng.nextInt(100, 10000),
                0, 0, 0, 0, Instant.now()
            );
        }

        // Local exploration around best
        int batchDelta = rng.nextInt(-32, 33);
        int intervalDelta = rng.nextInt(-500, 501);

        int newBatch = Math.max(1, Math.min(1024, best.batchSize() + batchDelta));
        int newInterval = Math.max(100, Math.min(50000, best.flushIntervalMicros() + intervalDelta));

        return new Config(newBatch, newInterval, 0, 0, 0, 0, Instant.now());
    }

    /** Get stats for all pressure levels */
    public String getStatsReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("Calibration Status by Pressure Level:\n");
        sb.append("─".repeat(70)).append("\n");
        sb.append(String.format("%-12s %10s %10s %15s %12s%n",
            "Level", "BatchSize", "Interval", "Throughput", "Score"));
        sb.append("─".repeat(70)).append("\n");

        for (PressureLevel level : PressureLevel.values()) {
            Config cfg = getBestConfigForPressure(level);
            sb.append(String.format("%-12s %10d %8dµs %,13d/s %12.0f%n",
                level.name(), cfg.batchSize(), cfg.flushIntervalMicros(),
                cfg.throughputPerSec(), cfg.score()));
        }
        sb.append("─".repeat(70)).append("\n");
        sb.append("Current pressure: ").append(currentPressure.name()).append("\n");
        return sb.toString();
    }

    public record CalibrationStats(int totalObservations, double maxScore, double avgThroughput) {}

    /** Get calibration statistics. */
    public CalibrationStats getStats() {
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                "SELECT COUNT(*) as total, MAX(score) as max_score, " +
                "AVG(throughput_per_sec) as avg_throughput FROM observations"
            );
            if (rs.next()) {
                return new CalibrationStats(
                    rs.getInt("total"),
                    rs.getDouble("max_score"),
                    rs.getDouble("avg_throughput")
                );
            }
        } catch (SQLException e) {
            // Fall through
        }
        return new CalibrationStats(0, 0.0, 0.0);
    }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}
