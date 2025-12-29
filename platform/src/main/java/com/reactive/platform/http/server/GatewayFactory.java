package com.reactive.platform.http.server;

import com.reactive.platform.http.server.interceptors.LoggingInterceptor;
import com.reactive.platform.http.server.interceptors.TracingInterceptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Factory for creating HTTP servers with pluggable implementations.
 *
 * Provides type-safe server selection using the Server enum.
 *
 * Usage:
 * <pre>
 * // Direct creation
 * HttpServerSpec server = GatewayFactory.create(Server.ROCKET);
 *
 * // From environment variable
 * HttpServerSpec server = GatewayFactory.fromEnv();
 *
 * // Builder with interceptors
 * HttpServerSpec server = GatewayFactory.builder()
 *     .server(Server.ROCKET)
 *     .withConditionalTracing()
 *     .withErrorLogging()
 *     .build();
 * </pre>
 */
public final class GatewayFactory {

    private static final String ENV_SERVER = "HTTP_SERVER_IMPL";

    private GatewayFactory() {}

    // ========================================================================
    // Direct Creation
    // ========================================================================

    /**
     * Create server with specified implementation.
     */
    public static HttpServerSpec create(Server server) {
        return server.create();
    }

    /**
     * Create server with default implementation (ROCKET).
     */
    public static HttpServerSpec create() {
        return create(Server.defaultServer());
    }

    /**
     * Create server from HTTP_SERVER_IMPL environment variable.
     * Falls back to default if not set or invalid.
     */
    public static HttpServerSpec fromEnv() {
        return create(serverFromEnv());
    }

    /**
     * Get server enum from environment variable.
     */
    public static Server serverFromEnv() {
        return Optional.ofNullable(System.getenv(ENV_SERVER))
            .flatMap(impl -> Server.fromName(impl)
                .or(() -> {
                    System.err.printf("Warning: Unknown server '%s', using default %s%n",
                        impl, Server.defaultServer().displayName());
                    return Optional.empty();
                }))
            .orElseGet(Server::defaultServer);
    }

    // ========================================================================
    // Builder
    // ========================================================================

    /**
     * Create a builder for advanced configuration.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Server server = Server.defaultServer();
        private TracingInterceptor tracing;
        private LoggingInterceptor logging;
        private final List<HttpServerSpec.Interceptor> interceptors = new ArrayList<>();

        private Builder() {}

        /**
         * Set server implementation.
         */
        public Builder server(Server server) {
            this.server = server;
            return this;
        }

        /**
         * Set server from environment variable.
         */
        public Builder serverFromEnv() {
            this.server = GatewayFactory.serverFromEnv();
            return this;
        }

        /**
         * Add conditional tracing (only when headers request it).
         */
        public Builder withConditionalTracing() {
            this.tracing = TracingInterceptor.conditional();
            return this;
        }

        /**
         * Add always-on tracing.
         */
        public Builder withTracing() {
            this.tracing = TracingInterceptor.always();
            return this;
        }

        /**
         * Add custom tracing.
         */
        public Builder withTracing(TracingInterceptor tracing) {
            this.tracing = tracing;
            return this;
        }

        /**
         * Add error-only logging.
         */
        public Builder withErrorLogging() {
            this.logging = LoggingInterceptor.errorsOnly();
            return this;
        }

        /**
         * Add full logging.
         */
        public Builder withLogging() {
            this.logging = LoggingInterceptor.all();
            return this;
        }

        /**
         * Add custom logging.
         */
        public Builder withLogging(LoggingInterceptor logging) {
            this.logging = logging;
            return this;
        }

        /**
         * Add custom interceptor.
         */
        public Builder with(HttpServerSpec.Interceptor interceptor) {
            this.interceptors.add(interceptor);
            return this;
        }

        /**
         * Build the configured server.
         */
        public HttpServerSpec build() {
            HttpServerSpec result = server.create();

            // Apply interceptors in order: tracing -> logging -> custom
            if (tracing != null) {
                result = result.intercept(tracing);
            }
            if (logging != null) {
                result = result.intercept(logging);
            }
            for (HttpServerSpec.Interceptor i : interceptors) {
                result = result.intercept(i);
            }

            return result;
        }
    }

    // ========================================================================
    // Utilities
    // ========================================================================

    /**
     * List all available servers.
     */
    public static void printServers() {
        Server.printAll();
    }

    /**
     * Main for listing servers.
     */
    public static void main(String[] args) {
        printServers();
    }
}
