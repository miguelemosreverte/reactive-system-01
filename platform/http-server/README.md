# HTTP Server Module

High-performance HTTP server implementations for benchmarking and production use.

## Purpose

This module contains 9 HTTP server implementations, from enterprise baseline (Spring WebFlux) to custom NIO servers achieving 700K+ requests/second. Each implementation demonstrates different architectural patterns and performance trade-offs.

## Implementations

| Server | Architecture | Throughput | Use Case |
|--------|-------------|------------|----------|
| `SpringBootHttpServer` | Spring WebFlux | ~180K req/s | Enterprise baseline |
| `NettyHttpServer` | Netty boss/worker | ~400K req/s | Production standard |
| `BossWorkerHttpServer` | Accept/worker separation | ~450K req/s | Classic pattern |
| `RawHttpServer` | Pure NIO, multi-loop | ~500K req/s | Educational |
| `RocketHttpServer` | NIO + SO_REUSEPORT | ~777K req/s | High performance |
| `HyperHttpServer` | Lock-free + pipelining | ~600K req/s | Low latency |
| `TurboHttpServer` | Adaptive busy-polling | ~550K req/s | Latency sensitive |
| `UltraHttpServer` | Minimal response | ~650K req/s | Maximum throughput |
| `ZeroCopyHttpServer` | Direct buffers | ~500K req/s | Memory efficient |

## Quick Start

### Run a Benchmark

```bash
# Using the brochure system
./reactive bench brochure run http-rocket

# Direct execution
mvn exec:java -Dexec.mainClass="com.reactive.platform.benchmark.UnifiedHttpBenchmark" \
  -Dexec.args="ROCKET 60 100 /tmp"
```

### Use in Your Code

```java
import com.reactive.platform.http.RocketHttpServer;

HttpServer server = new RocketHttpServer(8080, 4);
server.start(request -> {
    return new Response(200, "{\"status\":\"ok\"}");
});
```

## Brochures

Located in `brochures/`:

| Brochure | Description |
|----------|-------------|
| `http-rocket` | NIO + SO_REUSEPORT benchmark |
| `http-netty` | Netty event loop benchmark |
| `http-spring` | Spring WebFlux baseline |
| `http-hyper` | Lock-free multi-core |
| `http-turbo` | Adaptive busy-polling |
| `http-ultra` | Minimal response overhead |
| `http-raw` | Pure Java NIO |
| `http-boss-worker` | Classic accept/worker pattern |
| `http-zero-copy` | Direct buffer optimization |

## Package Structure

```
com.reactive.platform.http/
├── HttpServer.java            # Common interface
├── RocketHttpServer.java      # SO_REUSEPORT + NIO
├── NettyHttpServer.java       # Netty-based
├── BossWorkerHttpServer.java  # Boss/worker pattern
├── HyperHttpServer.java       # Lock-free design
├── RawHttpServer.java         # Pure NIO
└── server/                    # Shared utilities
```

## Build

```bash
mvn compile                    # Compile
mvn test                       # Run tests
mvn test -Pbenchmark           # Run benchmarks
```

## Benchmarking Notes

- All benchmarks use same-container testing (client and server in same JVM)
- This eliminates network variance and isolates server performance
- Warmup: 5 seconds, then measure for specified duration
- Results show throughput (req/s) and latency percentiles (p50, p99)

## Dependencies

- Netty (for Netty-based servers)
- Spring WebFlux (for Spring server)
- JMH (benchmarking framework)
