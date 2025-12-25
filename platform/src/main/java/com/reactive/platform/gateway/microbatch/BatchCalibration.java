package com.reactive.platform.gateway.microbatch;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SQLite-backed calibration store for microbatch parameters.
 *
 * Persists learned optimal batch configuration across restarts.
 * Uses a scoring function to evaluate throughput vs latency trade-offs.
 *
 * Functional design:
 * - Immutable configuration records
 * - Pure scoring functions
 * - Side effects isolated to persistence layer
 */
public final class BatchCalibration implements AutoCloseable {

    /**
     * Calibration configuration - immutable snapshot of optimal parameters.
     */
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
            return new Config(
                64,      // Start with reasonable batch size
                1000,    // 1ms flush interval
                0.0,
                0L,
                0.0,
                0.0,
                Instant.now()
            );
        }

        /**
         * Calculate score balancing throughput and latency.
         * Higher is better.
         *
         * Score = throughput / (1 + latencyPenalty)
         * where latencyPenalty grows exponentially above target latency.
         */
        public static double calculateScore(
            long throughputPerSec,
            double avgLatencyMicros,
            double targetLatencyMicros
        ) {
            double latencyRatio = avgLatencyMicros / targetLatencyMicros;
            double latencyPenalty = latencyRatio > 1.0
                ? Math.pow(latencyRatio, 2) - 1  // Quadratic penalty above target
                : 0.0;
            return throughputPerSec / (1.0 + latencyPenalty);
        }
    }

    /**
     * Observation from a calibration run.
     */
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

    private BatchCalibration(Path dbPath, double targetLatencyMicros) throws SQLException {
        this.dbPath = dbPath;
        this.targetLatencyMicros = targetLatencyMicros;

        // Initialize SQLite
        String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        this.conn = DriverManager.getConnection(url);
        initializeSchema();
    }

    /**
     * Create calibration store.
     *
     * @param dbPath Path to SQLite database file
     * @param targetLatencyMicros Target latency in microseconds (e.g., 5000 for 5ms)
     */
    public static BatchCalibration create(Path dbPath, double targetLatencyMicros) {
        try {
            Files.createDirectories(dbPath.getParent());
            return new BatchCalibration(dbPath, targetLatencyMicros);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create calibration store", e);
        }
    }

    /**
     * Create with default path and target latency.
     */
    public static BatchCalibration createDefault() {
        Path home = Path.of(System.getProperty("user.home"));
        Path dbPath = home.resolve(".reactive/calibration.db");
        return create(dbPath, 5000.0); // 5ms target latency
    }

    private void initializeSchema() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS observations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
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

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS best_config (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    batch_size INTEGER NOT NULL,
                    flush_interval_micros INTEGER NOT NULL,
                    score REAL NOT NULL,
                    throughput_per_sec INTEGER NOT NULL,
                    avg_latency_micros REAL NOT NULL,
                    p99_latency_micros REAL NOT NULL,
                    calibrated_at TEXT NOT NULL
                )
                """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_observations_score
                ON observations(score DESC)
                """);
        }
    }

    /**
     * Get the best known configuration.
     */
    public Config getBestConfig() {
        try (PreparedStatement stmt = conn.prepareStatement(
            "SELECT batch_size, flush_interval_micros, score, throughput_per_sec, " +
            "avg_latency_micros, p99_latency_micros, calibrated_at FROM best_config WHERE id = 1"
        )) {
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
        return Config.initial();
    }

    /**
     * Record an observation and update best config if improved.
     */
    public void recordObservation(Observation obs) {
        double score = Config.calculateScore(
            obs.throughputPerSec(),
            obs.avgLatencyMicros(),
            targetLatencyMicros
        );

        writeLock.lock();
        try {
            // Insert observation
            try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO observations (batch_size, flush_interval_micros, throughput_per_sec, " +
                "avg_latency_micros, p99_latency_micros, sample_count, score, observed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            )) {
                stmt.setInt(1, obs.batchSize());
                stmt.setInt(2, obs.flushIntervalMicros());
                stmt.setLong(3, obs.throughputPerSec());
                stmt.setDouble(4, obs.avgLatencyMicros());
                stmt.setDouble(5, obs.p99LatencyMicros());
                stmt.setLong(6, obs.sampleCount());
                stmt.setDouble(7, score);
                stmt.setString(8, obs.observedAt().toString());
                stmt.executeUpdate();
            }

            // Update best config if improved
            Config current = getBestConfig();
            if (score > current.score()) {
                try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT OR REPLACE INTO best_config " +
                    "(id, batch_size, flush_interval_micros, score, throughput_per_sec, " +
                    "avg_latency_micros, p99_latency_micros, calibrated_at) " +
                    "VALUES (1, ?, ?, ?, ?, ?, ?, ?)"
                )) {
                    stmt.setInt(1, obs.batchSize());
                    stmt.setInt(2, obs.flushIntervalMicros());
                    stmt.setDouble(3, score);
                    stmt.setLong(4, obs.throughputPerSec());
                    stmt.setDouble(5, obs.avgLatencyMicros());
                    stmt.setDouble(6, obs.p99LatencyMicros());
                    stmt.setString(7, Instant.now().toString());
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
     * Suggest next configuration to try based on exploration strategy.
     *
     * Uses gradient-free optimization:
     * - Start from best known config
     * - Explore nearby batch sizes and intervals
     * - Occasionally explore randomly for global optima
     */
    public Config suggestNext(java.util.random.RandomGenerator rng) {
        Config best = getBestConfig();

        // 10% chance of random exploration
        if (rng.nextDouble() < 0.1) {
            return new Config(
                rng.nextInt(16, 512),
                rng.nextInt(100, 10000),
                0.0, 0L, 0.0, 0.0, Instant.now()
            );
        }

        // Local exploration around best
        int batchDelta = rng.nextInt(-32, 33);
        int intervalDelta = rng.nextInt(-500, 501);

        int newBatch = Math.max(1, Math.min(1024, best.batchSize() + batchDelta));
        int newInterval = Math.max(100, Math.min(50000, best.flushIntervalMicros() + intervalDelta));

        return new Config(newBatch, newInterval, 0.0, 0L, 0.0, 0.0, Instant.now());
    }

    /**
     * Get statistics about calibration history.
     */
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

    public record CalibrationStats(int totalObservations, double maxScore, double avgThroughput) {}

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {}
    }
}
