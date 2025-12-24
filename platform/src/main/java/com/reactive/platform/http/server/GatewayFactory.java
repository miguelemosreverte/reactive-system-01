package com.reactive.platform.http.server;

import com.reactive.platform.http.server.interceptors.LoggingInterceptor;
import com.reactive.platform.http.server.interceptors.TracingInterceptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Factory for creating HTTP gateways with pluggable server implementations.
 *
 * Supports:
 * - Server selection by name
 * - Environment variable configuration
 * - Default fallback to highest-performance server
 * - Automatic interceptor configuration
 *
 * Usage:
 * <pre>
 * // Use default (RocketHttpServer)
 * HttpServerSpec server = GatewayFactory.createServer();
 *
 * // Use specific implementation
 * HttpServerSpec server = GatewayFactory.createServer("SpringBootHttpServer");
 *
 * // Use from environment variable HTTP_SERVER_IMPL
 * HttpServerSpec server = GatewayFactory.createServerFromEnv();
 *
 * // Full builder pattern
 * HttpServerSpec server = GatewayFactory.builder()
 *     .server("RocketHttpServer")
 *     .withTracing(TracingInterceptor.conditional())
 *     .withLogging(LoggingInterceptor.errorsOnly())
 *     .build();
 * </pre>
 */
public final class GatewayFactory {

    private static final String DEFAULT_SERVER = "RocketHttpServer";
    private static final String ENV_SERVER_IMPL = "HTTP_SERVER_IMPL";

    private GatewayFactory() {}

    // ========================================================================
    // Quick factory methods
    // ========================================================================

    /**
     * Create server with default implementation (RocketHttpServer).
     * Note: Only works for servers with factories, not standalone-only servers.
     */
    public static HttpServerSpec createServer() {
        return createServer(DEFAULT_SERVER);
    }

    /**
     * Create server with specified implementation.
     */
    public static HttpServerSpec createServer(String serverName) {
        ServerRegistry.ServerInfo info = ServerRegistry.get(serverName)
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown server: " + serverName + ". Available: " + ServerRegistry.names()));

        if (!info.hasFactory()) {
            throw new UnsupportedOperationException(
                serverName + " is standalone-only. Use mainClass() for command line execution, " +
                "or register a factory with ServerRegistry.");
        }

        return info.create();
    }

    /**
     * Create server from HTTP_SERVER_IMPL environment variable.
     * Falls back to default if not set.
     */
    public static HttpServerSpec createServerFromEnv() {
        String impl = System.getenv(ENV_SERVER_IMPL);
        return impl != null ? createServer(impl) : createServer();
    }

    /**
     * Get server name from environment or default.
     */
    public static String getServerName() {
        String impl = System.getenv(ENV_SERVER_IMPL);
        return impl != null ? impl : DEFAULT_SERVER;
    }

    /**
     * Get all available server names.
     */
    public static List<String> availableServers() {
        return ServerRegistry.all().stream()
            .map(ServerRegistry.ServerInfo::name)
            .toList();
    }

    /**
     * Get all servers that can be instantiated (have factories).
     */
    public static List<String> instantiableServers() {
        return ServerRegistry.all().stream()
            .filter(ServerRegistry.ServerInfo::hasFactory)
            .map(ServerRegistry.ServerInfo::name)
            .toList();
    }

    /**
     * Check if a server name is valid.
     */
    public static boolean isValidServer(String name) {
        return ServerRegistry.get(name).isPresent();
    }

    // ========================================================================
    // Builder for advanced configuration
    // ========================================================================

    /**
     * Create a builder for advanced gateway configuration.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String serverName = DEFAULT_SERVER;
        private TracingInterceptor tracingInterceptor;
        private LoggingInterceptor loggingInterceptor;
        private final List<HttpServerSpec.Interceptor> customInterceptors = new ArrayList<>();

        private Builder() {}

        /**
         * Set server implementation by name.
         */
        public Builder server(String name) {
            if (!isValidServer(name)) {
                throw new IllegalArgumentException(
                    "Unknown server: " + name + ". Available: " + availableServers());
            }
            this.serverName = name;
            return this;
        }

        /**
         * Set server from environment variable.
         */
        public Builder serverFromEnv() {
            String impl = System.getenv(ENV_SERVER_IMPL);
            if (impl != null) {
                this.serverName = impl;
            }
            return this;
        }

        /**
         * Add tracing interceptor.
         */
        public Builder withTracing(TracingInterceptor interceptor) {
            this.tracingInterceptor = interceptor;
            return this;
        }

        /**
         * Add conditional tracing (only when headers request it).
         */
        public Builder withConditionalTracing() {
            return withTracing(TracingInterceptor.conditional());
        }

        /**
         * Add always-on tracing.
         */
        public Builder withAlwaysTracing() {
            return withTracing(TracingInterceptor.always());
        }

        /**
         * Add logging interceptor.
         */
        public Builder withLogging(LoggingInterceptor interceptor) {
            this.loggingInterceptor = interceptor;
            return this;
        }

        /**
         * Add errors-only logging.
         */
        public Builder withErrorLogging() {
            return withLogging(LoggingInterceptor.errorsOnly());
        }

        /**
         * Add full logging.
         */
        public Builder withFullLogging() {
            return withLogging(LoggingInterceptor.all());
        }

        /**
         * Add custom interceptor.
         */
        public Builder withInterceptor(HttpServerSpec.Interceptor interceptor) {
            this.customInterceptors.add(interceptor);
            return this;
        }

        /**
         * Build the configured server.
         */
        public HttpServerSpec build() {
            HttpServerSpec server = createServer(serverName);

            // Add interceptors in order: tracing -> logging -> custom
            if (tracingInterceptor != null) {
                server = server.intercept(tracingInterceptor);
            }
            if (loggingInterceptor != null) {
                server = server.intercept(loggingInterceptor);
            }
            for (HttpServerSpec.Interceptor interceptor : customInterceptors) {
                server = server.intercept(interceptor);
            }

            return server;
        }
    }

    // ========================================================================
    // Server info display
    // ========================================================================

    /**
     * Print available servers to stdout.
     */
    public static void printAvailableServers() {
        System.out.println("Available HTTP Server Implementations:");
        System.out.println("═".repeat(80));
        System.out.printf("%-25s %-20s %s%n", "Name", "Technology", "Notes");
        System.out.println("─".repeat(80));

        for (ServerRegistry.ServerInfo info : ServerRegistry.all()) {
            String factory = info.hasFactory() ? "" : " [standalone]";
            System.out.printf("%-25s %-20s %s%s%n",
                info.name(), info.technology(), info.notes(), factory);
        }

        System.out.println();
        System.out.println("Default: " + DEFAULT_SERVER);
        System.out.println("Override: Set HTTP_SERVER_IMPL environment variable");
    }

    /**
     * Main for listing servers.
     */
    public static void main(String[] args) {
        printAvailableServers();
    }
}
