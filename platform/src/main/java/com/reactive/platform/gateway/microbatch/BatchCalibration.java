package com.reactive.platform.gateway.microbatch;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.Arrays;
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
     * Pressure levels with human-readable thresholds.
     *
     * Each level defines:
     * - Request rate range (when this bucket activates)
     * - Target latency (what users should expect)
     * - Use case description
     *
     * The system learns optimal batch/interval config for each level.
     */
    public enum PressureLevel {
        // Level          min/10s       max/10s        targetLatencyMs  description
        IDLE(             0,            100,           1,    "< 10 req/s",       "Development/testing"),
        LOW(              100,          1_000,         2,    "10-100 req/s",     "Light traffic"),
        MEDIUM(           1_000,        10_000,        5,    "100-1K req/s",     "Normal load"),
        HIGH(             10_000,       100_000,       10,   "1K-10K req/s",     "High traffic"),
        EXTREME(          100_000,      1_000_000,     20,   "10K-100K req/s",   "Peak load"),
        OVERLOAD(         1_000_000,    10_000_000,    50,   "100K-1M req/s",    "Stress test"),
        MEGA(             10_000_000,   100_000_000,   100,  "1M-10M req/s",     "Benchmark"),
        HTTP_30S(         100_000_000,  1_000_000_000, 1000, "10M-100M req/s",   "Max throughput"),
        HTTP_60S(         1_000_000_000,Long.MAX_VALUE,5000, "> 100M req/s",     "Theoretical max");

        public final long minReqPer10s;
        public final long maxReqPer10s;
        public final int targetLatencyMs;
        public final String rateRange;
        public final String description;

        PressureLevel(long min, long max, int targetLatencyMs, String rateRange, String description) {
            this.minReqPer10s = min;
            this.maxReqPer10s = max;
            this.targetLatencyMs = targetLatencyMs;
            this.rateRange = rateRange;
            this.description = description;
        }

        /** Get min requests per SECOND (more intuitive than per 10s). */
        public long minReqPerSec() { return minReqPer10s / 10; }

        /** Get max requests per SECOND. */
        public long maxReqPerSec() { return maxReqPer10s / 10; }

        /** Format target latency for display. */
        public String latencyDisplay() {
            if (targetLatencyMs < 1) return "<1ms";
            if (targetLatencyMs >= 1000) return (targetLatencyMs / 1000) + "s";
            return targetLatencyMs + "ms";
        }

        public static PressureLevel fromRequestRate(long requestsPer10Seconds) {
            return Arrays.stream(values())
                .filter(level -> requestsPer10Seconds >= level.minReqPer10s && requestsPer10Seconds < level.maxReqPer10s)
                .findFirst()
                .orElse(HTTP_60S);
        }

        /** Create from requests per second (convenience method). */
        public static PressureLevel fromReqPerSec(long reqPerSec) {
            return fromRequestRate(reqPerSec * 10);
        }

        /**
         * Bootstrap config based on latency budget.
         *
         * Key insight: We can batch up to targetLatency worth of events.
         * Bigger batches = better Kafka throughput.
         *
         * No artificial limits - the only constraint is the latency budget.
         */
        public Config bootstrapConfig() {
            // Scale batch size based on latency budget
            // At 1ms latency budget: small batches (64)
            // At 30s latency budget: huge batches (1M+)
            //
            // Formula: batch_hint = latency_ms * 1000 (at 1K events/ms production rate)
            // This is just a starting point - system will learn the real optimum
            int batchHint = targetLatencyMs * 1000;

            // Interval is proportional to latency budget
            // At low latency: flush often (1ms)
            // At high latency: batch aggressively (up to latency budget)
            int intervalHint = targetLatencyMs * 1000; // microseconds

            // No artificial caps - let the system explore freely
            // The only real limits are memory and the latency budget
            return Config.bootstrap(
                Math.max(16, batchHint),           // At least 16
                Math.max(500, intervalHint)        // At least 500µs
            );
        }

        /**
         * Maximum batch size allowed for this pressure level.
         * Based on: 100 bytes/event, targeting ~10MB max batch.
         */
        public int maxBatchSize() {
            // More latency budget = bigger allowed batches
            // At 30s budget: could theoretically batch millions
            // Practical limit: ~100K events (10MB at 100 bytes/event)
            return Math.min(targetLatencyMs * 10_000, 1_000_000);
        }

        /**
         * Maximum flush interval (microseconds) for this level.
         * Equals the latency budget converted to microseconds.
         */
        public int maxFlushIntervalMicros() {
            return targetLatencyMs * 1000;
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
     * Uses UCB1-inspired exploration with latency-budget-based limits.
     *
     * No artificial limits - exploration bounded only by latency budget.
     */
    public Config suggestNext(java.util.random.RandomGenerator rng) {
        Config best = getBestConfig();
        PressureLevel level = currentPressure;

        // Dynamic limits based on pressure level's latency budget
        int maxBatch = level.maxBatchSize();
        int maxInterval = level.maxFlushIntervalMicros();

        // If not yet calibrated, explore the full range aggressively
        if (!best.isCalibrated()) {
            // Wide random exploration - use latency budget
            return Config.bootstrap(
                rng.nextInt(16, maxBatch),
                rng.nextInt(500, maxInterval)
            );
        }

        // 20% random exploration to escape local optima
        // Use wider exploration for high-latency-budget levels
        if (rng.nextDouble() < 0.20) {
            return Config.bootstrap(
                rng.nextInt(16, maxBatch),
                rng.nextInt(500, maxInterval)
            );
        }

        // 80% local exploration around best known config
        // Scale delta by current values for proportional exploration
        double batchScale = rng.nextGaussian() * 0.3; // ±30%
        double intervalScale = rng.nextGaussian() * 0.3;

        int newBatch = (int) Math.max(16, Math.min(maxBatch, best.batchSize() * (1 + batchScale)));
        int newInterval = (int) Math.max(500, Math.min(maxInterval, best.flushIntervalMicros() * (1 + intervalScale)));

        return Config.bootstrap(newBatch, newInterval);
    }

    /** Get stats for all pressure levels - human-readable CLI output */
    public String getStatsReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("╔══════════════════════════════════════════════════════════════════════════════════════════════════════╗\n");
        sb.append("║                              ADAPTIVE MICROBATCH CALIBRATION                                         ║\n");
        sb.append("╚══════════════════════════════════════════════════════════════════════════════════════════════════════╝\n\n");

        // Header
        sb.append(String.format("%-10s │ %-15s │ %-8s │ %-10s │ %-8s │ %-12s │ %-10s │ %s%n",
            "Bucket", "Activates At", "Latency", "Batch", "Interval", "Throughput", "Status", "Trend"));
        sb.append("─".repeat(105)).append("\n");

        long prevThroughput = 0;
        for (PressureLevel level : PressureLevel.values()) {
            Config cfg = getBestConfigForPressure(level);
            boolean calibrated = cfg.isCalibrated();

            // Status with icon
            String status = calibrated ? "✓ learned" : "○ default";

            // Trend indicator (regression detection)
            String trend = "";
            if (calibrated && cfg.expectedThroughput() > 0) {
                double ratio = (double) cfg.throughputPerSec() / cfg.expectedThroughput();
                if (ratio < 0.9) {
                    trend = "⚠ -" + Math.round((1 - ratio) * 100) + "%";
                } else if (ratio > 1.1) {
                    trend = "↑ +" + Math.round((ratio - 1) * 100) + "%";
                } else {
                    trend = "→ stable";
                }
            }

            // Format throughput
            String throughput = calibrated
                ? formatThroughput(cfg.throughputPerSec())
                : "—";

            // Format interval
            String interval = formatInterval(cfg.flushIntervalMicros());

            sb.append(String.format("%-10s │ %-15s │ %-8s │ %-10d │ %-8s │ %-12s │ %-10s │ %s%n",
                level.name(),
                level.rateRange,
                level.latencyDisplay(),
                cfg.batchSize(),
                interval,
                throughput,
                status,
                trend));

            prevThroughput = cfg.throughputPerSec();
        }

        sb.append("─".repeat(105)).append("\n");
        sb.append("\n");

        // Legend
        sb.append("Legend:\n");
        sb.append("  • Activates At: Request rate that triggers this bucket\n");
        sb.append("  • Latency: Target end-to-end latency for this load level\n");
        sb.append("  • Batch: Number of events batched together\n");
        sb.append("  • Interval: Max time before flushing a partial batch\n");
        sb.append("  • Trend: ⚠ regression | → stable | ↑ improvement\n");
        sb.append("\n");

        // Current state
        sb.append("Current pressure: ").append(currentPressure.name());
        sb.append(" (").append(currentPressure.rateRange).append(")\n");

        return sb.toString();
    }

    /** Format throughput for display (e.g., "9.8M/s") */
    private static String formatThroughput(long throughput) {
        if (throughput >= 1_000_000_000) return String.format("%.1fB/s", throughput / 1_000_000_000.0);
        if (throughput >= 1_000_000) return String.format("%.1fM/s", throughput / 1_000_000.0);
        if (throughput >= 1_000) return String.format("%.1fK/s", throughput / 1_000.0);
        return throughput + "/s";
    }

    /** Format interval for display */
    private static String formatInterval(int micros) {
        if (micros >= 1_000_000) return String.format("%.1fs", micros / 1_000_000.0);
        if (micros >= 1_000) return String.format("%.1fms", micros / 1_000.0);
        return micros + "µs";
    }

    /** Get Markdown report for documentation */
    public String getMarkdownReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Adaptive Microbatch Calibration\n\n");
        sb.append("The system learns optimal batching configurations for each load level.\n\n");

        sb.append("## Bucket Configurations\n\n");
        sb.append("| Bucket | Activates At | Target Latency | Batch Size | Interval | Throughput | Status |\n");
        sb.append("|--------|--------------|----------------|------------|----------|------------|--------|\n");

        for (PressureLevel level : PressureLevel.values()) {
            Config cfg = getBestConfigForPressure(level);
            boolean calibrated = cfg.isCalibrated();
            String status = calibrated ? "Learned" : "Default";
            String throughput = calibrated ? formatThroughput(cfg.throughputPerSec()) : "—";

            sb.append(String.format("| %s | %s | %s | %d | %s | %s | %s |\n",
                level.name(),
                level.rateRange,
                level.latencyDisplay(),
                cfg.batchSize(),
                formatInterval(cfg.flushIntervalMicros()),
                throughput,
                status));
        }

        sb.append("\n## How It Works\n\n");
        sb.append("1. **Pressure Detection**: System monitors request rate over 10-second windows\n");
        sb.append("2. **Bucket Selection**: Selects appropriate bucket based on observed load\n");
        sb.append("3. **Config Application**: Applies learned batch size and flush interval\n");
        sb.append("4. **Continuous Learning**: Records performance and updates optimal configs\n\n");

        sb.append("## Bucket Descriptions\n\n");
        for (PressureLevel level : PressureLevel.values()) {
            sb.append(String.format("- **%s** (%s): %s - target latency %s\n",
                level.name(), level.rateRange, level.description, level.latencyDisplay()));
        }

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
