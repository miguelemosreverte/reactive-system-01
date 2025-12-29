package com.reactive.platform.http.server;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Type-safe enumeration of HTTP server implementations.
 *
 * Each server defines:
 * - Unique identifier (the enum constant itself)
 * - Technology description
 * - Usage notes
 * - Main class for standalone execution
 * - Optional factory for programmatic instantiation
 *
 * Registration is explicit - add to this enum when a server is ready for use.
 *
 * Usage:
 * <pre>
 * // Type-safe selection
 * HttpServerSpec server = Server.ROCKET.create();
 *
 * // List all available
 * Server.all().forEach(s -> System.out.println(s.displayName()));
 *
 * // For CLI/config (parse from string)
 * Server server = Server.fromName("ROCKET").orElseThrow();
 * </pre>
 */
public enum Server {

    // ========================================================================
    // Tier 1: Maximum Throughput (600K+ req/s)
    // ========================================================================

    ROCKET(
        "NIO + SO_REUSEPORT",
        "Kernel-level connection distribution, multi-reactor",
        "com.reactive.platform.http.RocketHttpServer",
        Tier.MAXIMUM_THROUGHPUT
    ),

    BOSS_WORKER(
        "NIO Boss/Worker",
        "Classic Netty-style architecture with accept/worker separation",
        "com.reactive.platform.http.BossWorkerHttpServer",
        Tier.MAXIMUM_THROUGHPUT
    ),

    IO_URING(
        "io_uring + FFM",
        "Linux kernel async I/O, zero syscall overhead (Linux only)",
        "com.reactive.platform.http.IoUringServer",
        Tier.MAXIMUM_THROUGHPUT
    ),

    // ========================================================================
    // Tier 2: High Throughput (500-600K req/s)
    // ========================================================================

    HYPER(
        "NIO + Pipelining",
        "HTTP pipelining support for batched requests",
        "com.reactive.platform.http.HyperHttpServer",
        Tier.HIGH_THROUGHPUT
    ),

    RAW(
        "NIO Multi-EventLoop",
        "Pure Java, multiple independent event loops",
        "com.reactive.platform.http.RawHttpServer",
        Tier.HIGH_THROUGHPUT
    ),

    TURBO(
        "NIO + selectNow",
        "Busy-polling when active, sleep when idle",
        "com.reactive.platform.http.TurboHttpServer",
        Tier.HIGH_THROUGHPUT
    ),

    // ========================================================================
    // Tier 3: Specialized (300-500K req/s)
    // ========================================================================

    ULTRA(
        "NIO Minimal",
        "Minimal 38-byte response, aggressive selectNow()",
        "com.reactive.platform.http.UltraHttpServer",
        Tier.SPECIALIZED
    ),

    ZERO_COPY(
        "NIO + Busy-poll",
        "Direct ByteBuffers, adaptive busy-polling",
        "com.reactive.platform.http.ZeroCopyHttpServer",
        Tier.SPECIALIZED
    ),

    ULTRA_FAST(
        "NIO Single-thread",
        "Redis-style single event loop, CPU-bound",
        "com.reactive.platform.http.UltraFastHttpServer",
        Tier.SPECIALIZED
    ),

    // ========================================================================
    // Tier 4: Framework-based (<300K req/s)
    // ========================================================================

    FAST(
        "NIO + VirtualThreads",
        "Project Loom virtual threads for blocking I/O",
        "com.reactive.platform.http.FastHttpServer",
        Tier.FRAMEWORK
    ),

    NETTY(
        "Netty NIO",
        "Industry standard, requires Netty dependency",
        "com.reactive.platform.http.NettyHttpServer",
        Tier.FRAMEWORK
    ),

    SPRING_BOOT(
        "Spring WebFlux",
        "Enterprise framework, Netty under the hood",
        "com.reactive.platform.http.SpringBootHttpServer",
        Tier.FRAMEWORK
    );

    // ========================================================================
    // Enum Infrastructure
    // ========================================================================

    private final String technology;
    private final String notes;
    private final String mainClass;
    private final Tier tier;
    private Supplier<HttpServerSpec> factory;

    Server(String technology, String notes, String mainClass, Tier tier) {
        this.technology = technology;
        this.notes = notes;
        this.mainClass = mainClass;
        this.tier = tier;
    }

    /**
     * Performance tier classification.
     */
    public enum Tier {
        MAXIMUM_THROUGHPUT("600K+ req/s"),
        HIGH_THROUGHPUT("500-600K req/s"),
        SPECIALIZED("300-500K req/s"),
        FRAMEWORK("<300K req/s");

        private final String description;

        Tier(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    // ========================================================================
    // Accessors
    // ========================================================================

    /**
     * Human-readable display name (e.g., "RocketHttpServer").
     */
    public String displayName() {
        return mainClass.substring(mainClass.lastIndexOf('.') + 1);
    }

    /**
     * Technology used (e.g., "NIO + SO_REUSEPORT").
     */
    public String technology() {
        return technology;
    }

    /**
     * Usage notes and characteristics.
     */
    public String notes() {
        return notes;
    }

    /**
     * Fully qualified main class for standalone execution.
     */
    public String mainClass() {
        return mainClass;
    }

    /**
     * Performance tier.
     */
    public Tier tier() {
        return tier;
    }

    /**
     * Check if this server has a programmatic factory.
     */
    public boolean hasFactory() {
        return factory != null;
    }

    /**
     * Register a factory for programmatic instantiation.
     * Called by server implementations during class loading.
     */
    public void registerFactory(Supplier<HttpServerSpec> factory) {
        this.factory = factory;
    }

    /**
     * Create an instance of this server.
     * Requires factory to be registered.
     */
    public HttpServerSpec create() {
        if (factory == null) {
            throw new UnsupportedOperationException(
                displayName() + " is standalone-only. Use mainClass() for command line execution.");
        }
        return factory.get();
    }

    // ========================================================================
    // Static Utilities
    // ========================================================================

    /**
     * Find server by name (case-insensitive, supports multiple formats).
     * Accepts: "ROCKET", "Rocket", "RocketHttpServer", "rocket"
     */
    public static Optional<Server> fromName(String name) {
        if (name == null) return Optional.empty();

        String normalized = name.toUpperCase()
            .replace("HTTPSERVER", "")
            .replace("SERVER", "")
            .replace("_", "")
            .trim();

        return Arrays.stream(values())
            .filter(s -> s.name().replace("_", "").equals(normalized))
            .findFirst()
            .or(() -> Arrays.stream(values())
                .filter(s -> s.displayName().equalsIgnoreCase(name))
                .findFirst());
    }

    /**
     * Get all servers in a specific tier.
     */
    public static Server[] byTier(Tier tier) {
        return java.util.Arrays.stream(values())
            .filter(s -> s.tier == tier)
            .toArray(Server[]::new);
    }

    /**
     * Get the default server (recommended for production).
     */
    public static Server defaultServer() {
        return ROCKET;
    }

    /**
     * Print a formatted table of all servers.
     */
    public static void printAll() {
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                      HTTP SERVER IMPLEMENTATIONS                             ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        Arrays.stream(Tier.values()).forEach(tier -> {
            System.out.printf("  %s (%s)%n", tier.name().replace("_", " "), tier.description());
            System.out.println("  " + "─".repeat(70));

            Arrays.stream(byTier(tier)).forEach(s -> {
                String factory = s.hasFactory() ? "" : " [standalone]";
                System.out.printf("    %-12s  %-20s  %s%s%n",
                    s.name(), s.technology(), s.notes(), factory);
            });
            System.out.println();
        });

        System.out.println("  Default: " + defaultServer().displayName());
        System.out.println("  Override: Set HTTP_SERVER_IMPL environment variable");
    }

    /**
     * Main for listing servers.
     */
    public static void main(String[] args) {
        printAll();
    }
}
