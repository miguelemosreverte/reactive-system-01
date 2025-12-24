# Pluggable Server Architecture

## Vision

The platform abstracts over HTTP server implementations, allowing:

1. **Teams to use familiar tools** (Spring Boot) while complying with the platform interface
2. **Hot paths to use optimal implementations** (RocketHttpServer for /events)
3. **Easy benchmarking** of any component with any server implementation
4. **Conditional cross-cutting concerns** (trace only when header requests it)

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              CLI                                            │
├─────────────────────────────────────────────────────────────────────────────┤
│  ./cli benchmark server                    # Benchmark server impls         │
│  ./cli benchmark gateway --server=Rocket   # Gateway with specific server   │
│  ./cli benchmark e2e --server=Spring       # Full stack with Spring         │
│  ./cli start gateway --server=BossWorker   # Start with specific impl       │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         ServerRegistry                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│  IoUringServer      │ 674K req/s │ io_uring + FFM                           │
│  RocketHttpServer   │ 643K req/s │ NIO + SO_REUSEPORT                       │
│  BossWorkerServer   │ 632K req/s │ NIO Boss/Worker                          │
│  SpringBootServer   │  48K req/s │ Spring WebFlux                           │
│  ...                                                                         │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         HttpServerSpec                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│  .intercept(TracingInterceptor)   // Conditional on X-Trace-Id header       │
│  .intercept(LoggingInterceptor)   // Configurable verbosity                 │
│  .intercept(AuthInterceptor)      // JWT validation                         │
│  .post("/events", handler)        // Declarative routes                     │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Gateway / Application                                │
├─────────────────────────────────────────────────────────────────────────────┤
│  // The gateway doesn't care which server implementation is used            │
│  // It only knows HttpServerSpec                                            │
│                                                                              │
│  public class Gateway {                                                      │
│      private final HttpServerSpec server;                                   │
│                                                                              │
│      public Gateway(HttpServerSpec server) {                                │
│          this.server = server                                               │
│              .intercept(conditionalTracing())                               │
│              .post("/events", this::handleEvent);                           │
│      }                                                                       │
│  }                                                                           │
└─────────────────────────────────────────────────────────────────────────────┘
```

## CLI Commands

### Benchmark Submenu

```bash
# List available server implementations
./cli benchmark servers --list

# Benchmark all server implementations (what we have now)
./cli benchmark servers --all

# Benchmark specific implementations
./cli benchmark servers --only=IoUringServer,RocketHttpServer

# Benchmark the gateway with different server backends
./cli benchmark gateway --server=RocketHttpServer
./cli benchmark gateway --server=SpringBootHttpServer
./cli benchmark gateway --server=all  # Compare all

# Benchmark end-to-end with specific server
./cli benchmark e2e --server=BossWorkerHttpServer
```

### Start with Specific Implementation

```bash
# Start gateway with high-performance server (production)
./cli start gateway --server=RocketHttpServer

# Start gateway with Spring Boot (development/debugging)
./cli start gateway --server=SpringBootHttpServer

# Start with environment variable
HTTP_SERVER_IMPL=RocketHttpServer ./cli start gateway
```

## Conditional Interceptors

### Tracing (Header-Based)

Only trace when `X-Trace-Id` or `X-Request-Trace: true` header is present:

```java
public class ConditionalTracingInterceptor implements Interceptor {
    @Override
    public CompletableFuture<Response> intercept(Request req, Handler next) {
        String traceId = req.header("X-Trace-Id");
        boolean shouldTrace = traceId != null ||
                              "true".equals(req.header("X-Request-Trace"));

        if (!shouldTrace) {
            return next.handle(req);  // Skip tracing
        }

        Span span = tracer.spanBuilder("http.request")
            .setAttribute("trace.id", traceId)
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            return next.handle(req.withAttribute("span", span))
                .whenComplete((res, err) -> span.end());
        }
    }
}
```

### Logging (Configurable)

```java
public class LoggingInterceptor implements Interceptor {
    public enum Level { NONE, ERRORS_ONLY, ALL }

    private final Level level;

    @Override
    public CompletableFuture<Response> intercept(Request req, Handler next) {
        if (level == Level.NONE) {
            return next.handle(req);
        }

        long start = System.nanoTime();
        return next.handle(req)
            .whenComplete((res, err) -> {
                if (level == Level.ALL || (level == Level.ERRORS_ONLY && err != null)) {
                    log(req, res, err, System.nanoTime() - start);
                }
            });
    }
}
```

## Gateway Factory

```java
public class GatewayFactory {

    /**
     * Create gateway with specified server implementation.
     */
    public static Gateway create(String serverName) {
        HttpServerSpec server = ServerRegistry.get(serverName)
            .orElseThrow(() -> new IllegalArgumentException("Unknown server: " + serverName))
            .create();

        return new Gateway(server);
    }

    /**
     * Create gateway with default (fastest) implementation.
     */
    public static Gateway createDefault() {
        // Use RocketHttpServer by default (pure Java, high performance)
        return create("RocketHttpServer");
    }

    /**
     * Create gateway from environment variable.
     */
    public static Gateway createFromEnv() {
        String impl = System.getenv("HTTP_SERVER_IMPL");
        return impl != null ? create(impl) : createDefault();
    }
}
```

## Use Cases

### 1. Development Team Comfortable with Spring Boot

```bash
# Run with Spring Boot for familiar debugging
./cli start gateway --server=SpringBootHttpServer

# When ready for production, switch to high-performance
./cli start gateway --server=RocketHttpServer
```

### 2. Benchmark Before Choosing

```bash
# See how gateway performs with each implementation
./cli benchmark gateway --server=all

# Output:
# RocketHttpServer:    620,000 req/s
# BossWorkerHttpServer: 600,000 req/s
# NettyHttpServer:     180,000 req/s
# SpringBootHttpServer: 45,000 req/s
```

### 3. Hybrid Deployment

```java
// High-throughput events endpoint
HttpServerSpec eventsServer = ServerRegistry.get("RocketHttpServer").create()
    .post("/events", eventHandler);

// Admin/management with Spring Boot features
HttpServerSpec adminServer = ServerRegistry.get("SpringBootHttpServer").create()
    .get("/actuator/health", healthHandler)
    .get("/admin/metrics", metricsHandler);

// Run both
eventsServer.start(8080);
adminServer.start(8081);
```

### 4. A/B Testing Implementations

```bash
# Run canary with new implementation
./cli start gateway --server=IoUringServer --port=8080 --canary=10%
./cli start gateway --server=RocketHttpServer --port=8081 --primary
```

## Implementation Tasks

1. [ ] Add `benchmark servers` submenu to CLI
2. [ ] Add `--server` flag to `benchmark gateway` command
3. [ ] Create `GatewayFactory` with server selection
4. [ ] Implement `ConditionalTracingInterceptor`
5. [ ] Implement `LoggingInterceptor` with levels
6. [ ] Add `--server` flag to `start gateway` command
7. [ ] Support `HTTP_SERVER_IMPL` environment variable
8. [ ] Update gateway to use `HttpServerSpec` interface
