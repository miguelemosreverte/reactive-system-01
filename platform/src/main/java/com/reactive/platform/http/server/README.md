# HTTP Server Framework

A collection of high-performance HTTP server implementations for the Reactive Platform.

## Architecture

```
http/
├── server/                    # Core framework
│   ├── HttpServerSpec.java    # Standard interface (implement this)
│   ├── ServerRegistry.java    # Plugin registry (auto-discovery)
│   ├── BenchmarkRunner.java   # Automated benchmarking
│   └── README.md              # This file
│
└── [implementations]          # Server implementations
    ├── IoUringServer.java     # io_uring + FFM API
    ├── RocketHttpServer.java  # NIO + SO_REUSEPORT
    ├── BossWorkerHttpServer.java
    ├── HyperHttpServer.java
    ├── RawHttpServer.java
    ├── TurboHttpServer.java
    ├── UltraHttpServer.java
    ├── ZeroCopyHttpServer.java
    ├── UltraFastHttpServer.java
    ├── FastHttpServer.java
    ├── NettyHttpServer.java
    └── SpringBootHttpServer.java
```

## Interface

All servers should implement `HttpServerSpec`:

```java
HttpServerSpec server = MyServer.create()
    .intercept(new TracingInterceptor())    // Add tracing
    .intercept(new LoggingInterceptor())    // Add logging
    .get("/health", req -> Response.ok("{\"status\":\"UP\"}"))
    .post("/events", req -> {
        process(req.body());
        return Response.accepted();
    });

try (ServerHandle handle = server.start(8080)) {
    handle.awaitTermination();
}
```

### Interceptors

Interceptors provide cross-cutting concerns:

```java
public class TracingInterceptor implements Interceptor {
    @Override
    public CompletableFuture<Response> intercept(Request req, Handler next) {
        Span span = tracer.spanBuilder("http.request").startSpan();
        try (Scope scope = span.makeCurrent()) {
            return next.handle(req.withAttribute("span", span))
                .whenComplete((res, err) -> {
                    span.setStatus(err == null ? StatusCode.OK : StatusCode.ERROR);
                    span.end();
                });
        }
    }
}
```

## Server Implementations

### Tier 1: Maximum Throughput (600K+ req/s)

| Server | Technology | Best For |
|--------|------------|----------|
| **IoUringServer** | io_uring + FFM | Linux, absolute max throughput |
| **RocketHttpServer** | NIO + SO_REUSEPORT | Portable, kernel load balancing |
| **BossWorkerHttpServer** | NIO Boss/Worker | Classic pattern, well understood |

### Tier 2: High Throughput (500-600K req/s)

| Server | Technology | Best For |
|--------|------------|----------|
| **HyperHttpServer** | NIO + Pipelining | Clients that batch requests |
| **RawHttpServer** | NIO Multi-EventLoop | Simple, multi-core |
| **TurboHttpServer** | NIO + selectNow | Low-latency when active |

### Tier 3: Specialized (300-500K req/s)

| Server | Technology | Best For |
|--------|------------|----------|
| **UltraHttpServer** | NIO Minimal | Smallest response, fire-and-forget |
| **ZeroCopyHttpServer** | NIO + Busy-poll | Direct memory, CPU-intensive |
| **UltraFastHttpServer** | Single-thread | Simple workloads, debugging |

### Tier 4: Framework-based (<300K req/s)

| Server | Technology | Best For |
|--------|------------|----------|
| **FastHttpServer** | Virtual Threads | Blocking I/O, database calls |
| **NettyHttpServer** | Netty | Ecosystem integration |
| **SpringBootHttpServer** | Spring WebFlux | Enterprise features, DI |

## Benchmarking

### Run All Servers

```bash
# Using the Java runner
java -cp target/classes:target/dependency/* \
  com.reactive.platform.http.server.BenchmarkRunner \
  --workers 16 --duration 6

# Using the shell script
./scripts/benchmark-servers.sh 16 6s ./results
```

### Run Specific Servers

```bash
java -cp target/classes \
  com.reactive.platform.http.server.BenchmarkRunner \
  --servers IoUringServer,RocketHttpServer
```

### Exclude Servers

```bash
java -cp target/classes:target/dependency/* \
  com.reactive.platform.http.server.BenchmarkRunner \
  --exclude SpringBootHttpServer,NettyHttpServer
```

## Recommendations

### For Event Ingestion (Fire-and-Forget)

Use **RocketHttpServer** or **BossWorkerHttpServer**:
- 600K+ req/s
- Pure Java, no dependencies
- Simple to maintain

### For Mixed Workloads

Use **FastHttpServer** with Virtual Threads:
- Handles blocking I/O well
- Good for database calls
- 200K+ req/s

### For Enterprise Requirements

Use **SpringBootHttpServer** as a sidecar:
- All Spring features (DI, security, actuator)
- Use high-performance server for hot paths
- Route admin/health to Spring

### Hybrid Architecture

```
                    ┌─────────────────────────┐
                    │    Load Balancer        │
                    └───────────┬─────────────┘
                                │
           ┌────────────────────┼────────────────────┐
           │                    │                    │
           ▼                    ▼                    ▼
    ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
    │ RocketHttp  │     │ RocketHttp  │     │ SpringBoot  │
    │ /events     │     │ /events     │     │ /admin      │
    │ 600K req/s  │     │ 600K req/s  │     │ /health     │
    └─────────────┘     └─────────────┘     └─────────────┘
```

## Adding a New Server

1. Implement the server (can extend existing patterns)
2. Add a `main()` method for standalone execution
3. Register in `ServerRegistry`:

```java
ServerRegistry.register(ServerInfo.standalone(
    "MyNewServer",
    "NIO + MyOptimization",
    "Description of what makes it special",
    "com.reactive.platform.http.MyNewServer"
));
```

4. Run benchmark to see where it ranks!

## Latest Benchmark Results

See `benchmark-results/RANKING.md` for the latest auto-generated rankings.
