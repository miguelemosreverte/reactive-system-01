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
     * Boundaries are the ONLY hardcoded values - everything else is learned.
     *
     * The system learns optimal config for each level through experimentation.
     * No hardcoded batch sizes or intervals - purely adaptive.
     */
    public enum PressureLevel {
        IDLE(0, 100),
        LOW(100, 1_000),
        MEDIUM(1_000, 10_000),
        HIGH(10_000, 100_000),
        EXTREME(100_000, 1_000_000),
        OVERLOAD(1_000_000, 10_000_000),
        MEGA(10_000_000, 100_000_000),
        HTTP_30S(100_000_000, 1_000_000_000),
        HTTP_60S(1_000_000_000, Long.MAX_VALUE);

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

        /** Ordinal-based bootstrap config - will be replaced by learned values. */
        public Config bootstrapConfig() {
            // Start with minimal assumptions - let the system learn
            // Only use ordinal to scale initial exploration range
            int ord = ordinal();
            int batchHint = 1 << (4 + ord);  // 16, 32, 64, 128, 256, 512, 1024, 2048, 4096
            int intervalHint = 500 << ord;    // 500µs, 1ms, 2ms, 4ms, 8ms, 16ms, 32ms, 64ms, 128ms
            return Config.bootstrap(
                Math.min(batchHint, 4096),
                Math.min(intervalHint, 1_000_000)
            );
        }
    }

    /**
     * Immutable calibration config with learning metrics.
     *
     * @param batchSize Target batch size
     * @param flushIntervalMicros Max time to wait before flushing
     * @param score Composite score (throughput adjusted for latency)
     * @param throughputPerSec Observed throughput with this config
     * @param avgLatencyMicros Observed average latency
     * @param p99LatencyMicros Observed p99 latency
     * @param expectedThroughput Predicted throughput (for learning)
     * @param calibratedAt When this config was last validated
     */
    public record Config(
        int batchSize,
        int flushIntervalMicros,
        double score,
        long throughputPerSec,
        double avgLatencyMicros,
        double p99LatencyMicros,
        long expectedThroughput,
        Instant calibratedAt
    ) {
        /** Bootstrap config for exploration. */
        public static Config bootstrap(int batchSize, int intervalMicros) {
            return new Config(batchSize, intervalMicros, 0, 0, 0, 0, 0, Instant.now());
        }

        /** Check if this config has been validated with real observations. */
        public boolean isCalibrated() {
            return throughputPerSec > 0;
        }

        /** Score = throughput / (1 + latencyPenalty) */
        public static double calculateScore(long throughput, double avgLatency, double targetLatency) {
            double ratio = avgLatency / targetLatency;
            double penalty = ratio > 1.0 ? Math.pow(ratio, 2) - 1 : 0.0;
            return throughput / (1.0 + penalty);
        }

        /** Update expected throughput based on exponential moving average. */
        public Config withUpdatedExpectation(long observed, double alpha) {
            long newExpected = expectedThroughput == 0
                ? observed
                : (long) (alpha * observed + (1 - alpha) * expectedThroughput);
            return new Config(batchSize, flushIntervalMicros, score, throughputPerSec,
                avgLatencyMicros, p99LatencyMicros, newExpected, calibratedAt);
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
            // Observations table - raw data from each calibration run
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

            // Best config PER PRESSURE LEVEL with expected throughput
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS best_config (
                    pressure_level TEXT PRIMARY KEY,
                    batch_size INTEGER NOT NULL,
                    flush_interval_micros INTEGER NOT NULL,
                    score REAL NOT NULL,
                    throughput_per_sec INTEGER NOT NULL,
                    avg_latency_micros REAL NOT NULL,
                    p99_latency_micros REAL NOT NULL,
                    expected_throughput INTEGER NOT NULL DEFAULT 0,
                    calibrated_at TEXT NOT NULL
                )
                """);

            // Add expected_throughput column if not exists (for migration)
            try {
                stmt.execute("ALTER TABLE best_config ADD COLUMN expected_throughput INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException e) {
                // Column already exists, ignore
            }

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
     * Returns learned config if available, otherwise bootstrap config.
     */
    public Config getBestConfigForPressure(PressureLevel pressure) {
        try (PreparedStatement stmt = conn.prepareStatement(
            "SELECT batch_size, flush_interval_micros, score, throughput_per_sec, " +
            "avg_latency_micros, p99_latency_micros, expected_throughput, calibrated_at " +
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
                    rs.getLong("expected_throughput"),
                    Instant.parse(rs.getString("calibrated_at"))
                );
            }
        } catch (SQLException e) {
            // Fall through to bootstrap
        }
        return pressure.bootstrapConfig();
    }

    /**
     * Record observation for CURRENT pressure level.
     */
    public void recordObservation(Observation obs) {
        recordObservation(obs, currentPressure);
    }

    /**
     * Record observation for specific pressure level.
     * Uses exponential moving average to update expected throughput.
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
            // Calculate new expected throughput (EMA with alpha=0.3)
            long expectedThroughput = current.expectedThroughput() == 0
                ? obs.throughputPerSec()
                : (long) (0.3 * obs.throughputPerSec() + 0.7 * current.expectedThroughput());

            if (score > current.score()) {
                try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT OR REPLACE INTO best_config " +
                    "(pressure_level, batch_size, flush_interval_micros, score, throughput_per_sec, " +
                    "avg_latency_micros, p99_latency_micros, expected_throughput, calibrated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                )) {
                    stmt.setString(1, pressure.name());
                    stmt.setInt(2, obs.batchSize());
                    stmt.setInt(3, obs.flushIntervalMicros());
                    stmt.setDouble(4, score);
                    stmt.setLong(5, obs.throughputPerSec());
                    stmt.setDouble(6, obs.avgLatencyMicros());
                    stmt.setDouble(7, obs.p99LatencyMicros());
                    stmt.setLong(8, expectedThroughput);
                    stmt.setString(9, Instant.now().toString());
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
     * Uses UCB1-inspired exploration: explore more when uncertain.
     */
    public Config suggestNext(java.util.random.RandomGenerator rng) {
        Config best = getBestConfig();

        // If not yet calibrated, explore more aggressively
        if (!best.isCalibrated()) {
            // Wide random exploration for uncalibrated levels
            return Config.bootstrap(
                rng.nextInt(16, 8192),
                rng.nextInt(100, 500_000)
            );
        }

        // 15% random exploration to escape local optima
        if (rng.nextDouble() < 0.15) {
            return Config.bootstrap(
                rng.nextInt(16, 8192),
                rng.nextInt(100, 500_000)
            );
        }

        // 85% local exploration around best known config
        // Scale delta by current values for proportional exploration
        double batchScale = Math.max(0.1, rng.nextGaussian() * 0.2); // ±20%
        double intervalScale = Math.max(0.1, rng.nextGaussian() * 0.2);

        int newBatch = (int) Math.max(1, Math.min(16384, best.batchSize() * (1 + batchScale)));
        int newInterval = (int) Math.max(100, Math.min(1_000_000, best.flushIntervalMicros() * (1 + intervalScale)));

        return Config.bootstrap(newBatch, newInterval);
    }

    /** Get stats for all pressure levels */
    public String getStatsReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("Calibration Status by Pressure Level:\n");
        sb.append("─".repeat(90)).append("\n");
        sb.append(String.format("%-12s %10s %10s %15s %15s %12s%n",
            "Level", "BatchSize", "Interval", "Throughput", "Expected", "Status"));
        sb.append("─".repeat(90)).append("\n");

        for (PressureLevel level : PressureLevel.values()) {
            Config cfg = getBestConfigForPressure(level);
            String status = cfg.isCalibrated() ? "calibrated" : "bootstrap";
            sb.append(String.format("%-12s %10d %8dµs %,13d/s %,13d/s %12s%n",
                level.name(), cfg.batchSize(), cfg.flushIntervalMicros(),
                cfg.throughputPerSec(), cfg.expectedThroughput(), status));
        }
        sb.append("─".repeat(90)).append("\n");
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
