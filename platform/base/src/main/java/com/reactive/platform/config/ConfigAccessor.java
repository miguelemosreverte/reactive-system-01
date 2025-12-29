package com.reactive.platform.config;

import com.typesafe.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Simple utility for accessing HOCON config values with Optional support.
 *
 * Eliminates boilerplate like:
 * <pre>
 *   // Before:
 *   return config.hasPath(path) ? Optional.of(config.getInt(path)) : Optional.empty();
 *
 *   // After:
 *   return ConfigAccessor.intVal(config, path);
 * </pre>
 *
 * All methods follow the same pattern:
 * - Optional variant: returns Optional.empty() if path missing
 * - Default variant: returns default value if path missing
 */
public final class ConfigAccessor {

    private static final long BYTES_PER_MB = 1024L * 1024L;

    private ConfigAccessor() {} // Utility class

    // =========================================================================
    // String
    // =========================================================================

    public static Optional<String> string(Config c, String path) {
        return c.hasPath(path) ? Optional.of(c.getString(path)) : Optional.empty();
    }

    public static String string(Config c, String path, String defaultValue) {
        return c.hasPath(path) ? c.getString(path) : defaultValue;
    }

    // =========================================================================
    // Integer
    // =========================================================================

    public static Optional<Integer> intVal(Config c, String path) {
        return c.hasPath(path) ? Optional.of(c.getInt(path)) : Optional.empty();
    }

    public static int intVal(Config c, String path, int defaultValue) {
        return c.hasPath(path) ? c.getInt(path) : defaultValue;
    }

    // =========================================================================
    // Long
    // =========================================================================

    public static Optional<Long> longVal(Config c, String path) {
        return c.hasPath(path) ? Optional.of(c.getLong(path)) : Optional.empty();
    }

    public static long longVal(Config c, String path, long defaultValue) {
        return c.hasPath(path) ? c.getLong(path) : defaultValue;
    }

    // =========================================================================
    // Boolean
    // =========================================================================

    public static Optional<Boolean> bool(Config c, String path) {
        return c.hasPath(path) ? Optional.of(c.getBoolean(path)) : Optional.empty();
    }

    public static boolean bool(Config c, String path, boolean defaultValue) {
        return c.hasPath(path) ? c.getBoolean(path) : defaultValue;
    }

    // =========================================================================
    // Duration
    // =========================================================================

    public static Optional<Duration> duration(Config c, String path) {
        return c.hasPath(path) ? Optional.of(c.getDuration(path)) : Optional.empty();
    }

    public static Duration duration(Config c, String path, Duration defaultValue) {
        return c.hasPath(path) ? c.getDuration(path) : defaultValue;
    }

    // =========================================================================
    // Memory (returns MB)
    // =========================================================================

    public static Optional<Long> memoryMb(Config c, String path) {
        return c.hasPath(path)
            ? Optional.of(c.getMemorySize(path).toBytes() / BYTES_PER_MB)
            : Optional.empty();
    }

    public static long memoryMb(Config c, String path, long defaultMb) {
        return c.hasPath(path)
            ? c.getMemorySize(path).toBytes() / BYTES_PER_MB
            : defaultMb;
    }

    // =========================================================================
    // String List
    // =========================================================================

    public static Optional<List<String>> stringList(Config c, String path) {
        return c.hasPath(path) ? Optional.of(c.getStringList(path)) : Optional.empty();
    }

    public static List<String> stringList(Config c, String path, List<String> defaultValue) {
        return c.hasPath(path) ? c.getStringList(path) : defaultValue;
    }

    // =========================================================================
    // Nested Config
    // =========================================================================

    public static Optional<Config> nested(Config c, String path) {
        return c.hasPath(path) ? Optional.of(c.getConfig(path)) : Optional.empty();
    }
}
