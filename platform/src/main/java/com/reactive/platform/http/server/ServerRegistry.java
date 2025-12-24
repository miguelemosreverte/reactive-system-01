package com.reactive.platform.http.server;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Registry for HTTP server implementations.
 *
 * Plugin architecture - servers register themselves and can be discovered
 * by the benchmark system or selected at runtime.
 *
 * Usage:
 * <pre>
 * // Register a server
 * ServerRegistry.register(ServerInfo.of(
 *     "RocketHttpServer",
 *     "NIO + SO_REUSEPORT",
 *     "Kernel-level connection distribution",
 *     () -> RocketHttpServer.create()
 * ));
 *
 * // Get all registered servers
 * List<ServerInfo> servers = ServerRegistry.all();
 *
 * // Get a specific server
 * Optional<ServerInfo> server = ServerRegistry.get("RocketHttpServer");
 * </pre>
 */
public final class ServerRegistry {

    private static final Map<String, ServerInfo> SERVERS = new ConcurrentHashMap<>();

    private ServerRegistry() {}

    /**
     * Register a server implementation.
     */
    public static void register(ServerInfo info) {
        SERVERS.put(info.name(), info);
    }

    /**
     * Get a registered server by name.
     */
    public static Optional<ServerInfo> get(String name) {
        return Optional.ofNullable(SERVERS.get(name));
    }

    /**
     * Get all registered servers, sorted by name.
     */
    public static List<ServerInfo> all() {
        List<ServerInfo> list = new ArrayList<>(SERVERS.values());
        list.sort(Comparator.comparing(ServerInfo::name));
        return Collections.unmodifiableList(list);
    }

    /**
     * Get all server names.
     */
    public static Set<String> names() {
        return Collections.unmodifiableSet(SERVERS.keySet());
    }

    /**
     * Clear all registrations (for testing).
     */
    public static void clear() {
        SERVERS.clear();
    }

    /**
     * Information about a registered server.
     */
    public record ServerInfo(
        String name,
        String technology,
        String notes,
        Supplier<HttpServerSpec> factory,
        Class<?> implementationClass,
        String mainClass
    ) {
        /**
         * Create a ServerInfo with a factory.
         */
        public static ServerInfo of(
            String name,
            String technology,
            String notes,
            Supplier<HttpServerSpec> factory
        ) {
            return new ServerInfo(name, technology, notes, factory, null, null);
        }

        /**
         * Create a ServerInfo for a standalone server (with main method).
         */
        public static ServerInfo standalone(
            String name,
            String technology,
            String notes,
            String mainClass
        ) {
            return new ServerInfo(name, technology, notes, null, null, mainClass);
        }

        /**
         * Create an instance of this server.
         */
        public HttpServerSpec create() {
            if (factory == null) {
                throw new UnsupportedOperationException(
                    name + " is standalone-only. Use mainClass() to run via command line.");
            }
            return factory.get();
        }

        /**
         * Check if this server can be instantiated programmatically.
         */
        public boolean hasFactory() {
            return factory != null;
        }

        /**
         * Check if this server has a main method for standalone execution.
         */
        public boolean isStandalone() {
            return mainClass != null;
        }
    }

    // ========================================================================
    // Static initialization - register all known servers
    // ========================================================================

    static {
        registerBuiltinServers();
    }

    private static void registerBuiltinServers() {
        // io_uring based (Linux only, standalone)
        register(ServerInfo.standalone(
            "IoUringServer",
            "io_uring + FFM",
            "Linux kernel async I/O, zero syscall overhead",
            "com.reactive.platform.http.IoUringServer"
        ));

        // NIO-based servers (standalone with main methods)
        register(ServerInfo.standalone(
            "RocketHttpServer",
            "NIO + SO_REUSEPORT",
            "Kernel-level connection distribution, multi-reactor",
            "com.reactive.platform.http.RocketHttpServer"
        ));

        register(ServerInfo.standalone(
            "BossWorkerHttpServer",
            "NIO Boss/Worker",
            "Classic Netty-style architecture with accept/worker separation",
            "com.reactive.platform.http.BossWorkerHttpServer"
        ));

        register(ServerInfo.standalone(
            "HyperHttpServer",
            "NIO + Pipelining",
            "HTTP pipelining support for batched requests",
            "com.reactive.platform.http.HyperHttpServer"
        ));

        register(ServerInfo.standalone(
            "RawHttpServer",
            "NIO Multi-EventLoop",
            "Pure Java, multiple independent event loops",
            "com.reactive.platform.http.RawHttpServer"
        ));

        register(ServerInfo.standalone(
            "TurboHttpServer",
            "NIO + selectNow",
            "Busy-polling when active, sleep when idle",
            "com.reactive.platform.http.TurboHttpServer"
        ));

        register(ServerInfo.standalone(
            "UltraHttpServer",
            "NIO Minimal",
            "Minimal 38-byte response, aggressive selectNow()",
            "com.reactive.platform.http.UltraHttpServer"
        ));

        register(ServerInfo.standalone(
            "ZeroCopyHttpServer",
            "NIO + Busy-poll",
            "Direct ByteBuffers, adaptive busy-polling",
            "com.reactive.platform.http.ZeroCopyHttpServer"
        ));

        register(ServerInfo.standalone(
            "UltraFastHttpServer",
            "NIO Single-thread",
            "Redis-style single event loop, CPU-bound",
            "com.reactive.platform.http.UltraFastHttpServer"
        ));

        register(ServerInfo.standalone(
            "FastHttpServer",
            "NIO + VirtualThreads",
            "Project Loom virtual threads",
            "com.reactive.platform.http.FastHttpServer"
        ));

        register(ServerInfo.standalone(
            "NettyHttpServer",
            "Netty NIO",
            "Industry standard, requires Netty dependency",
            "com.reactive.platform.http.NettyHttpServer"
        ));

        register(ServerInfo.standalone(
            "SpringBootHttpServer",
            "Spring WebFlux",
            "Enterprise framework, Netty under the hood",
            "com.reactive.platform.http.SpringBootHttpServer"
        ));
    }
}
