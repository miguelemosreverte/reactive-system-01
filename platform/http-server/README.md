# HTTP Server Module

## 765,000 Requests/Second

This module contains high-performance HTTP server implementations achieving **765K req/s** - near the theoretical maximum for single-machine HTTP processing.

## Implementations

| Server | Throughput | Description |
|--------|------------|-------------|
| **`RocketHttpServer`** | **765K req/s** | Production-ready, SO_REUSEPORT + NIO |
| `SpringBootHttpServer` | ~5K req/s | Enterprise baseline, familiar Spring ecosystem |

### RocketHttpServer - The Champion

```
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│  Reactor 0  │  │  Reactor 1  │  │  Reactor N  │
│  Accept+IO  │  │  Accept+IO  │  │  Accept+IO  │
│  Port 8080  │  │  Port 8080  │  │  Port 8080  │
└─────────────┘  └─────────────┘  └─────────────┘
      ↑               ↑               ↑
      └───────────────┴───────────────┘
                SO_REUSEPORT
         (kernel load balances connections)
```

**Key design decisions:**
- Multiple reactors sharing the same port via `SO_REUSEPORT`
- Each reactor handles both accept AND I/O (no handoff overhead)
- Zero allocation in hot path
- Pre-computed responses

## Quick Start

```java
import com.reactive.platform.http.RocketHttpServer;

// Start server with 4 reactors
try (var handle = RocketHttpServer.create()
        .reactors(4)
        .onBody(body -> processEvent(body))
        .start(8080)) {
    handle.awaitTermination();
}
```

## Brochures

| Brochure | Description |
|----------|-------------|
| `http-rocket` | RocketHttpServer benchmark (765K req/s) |
| `http-spring` | Spring WebFlux baseline |

## Build

```bash
mvn compile                    # Compile
mvn test                       # Run tests
```

## Recovering Removed Implementations

During cleanup, we removed 12 experimental HTTP server implementations that were used during the optimization journey. To recover them:

```bash
# Checkout the pre-cleanup commit
git checkout 2b77838 -- platform/http-server/src/main/java/com/reactive/platform/http/

# Removed implementations (historical reference):
# - BossWorkerHttpServer (665K req/s) - Boss/worker pattern
# - HyperHttpServer (566K req/s) - Lock-free + pipelining
# - TurboHttpServer (609K req/s) - Adaptive busy-polling
# - UltraHttpServer (516K req/s) - Minimal response
# - ZeroCopyHttpServer (511K req/s) - Direct buffers
# - RawHttpServer (698K req/s) - Pure NIO
# - NettyHttpServer (485K req/s) - Netty-based
# - FastHttpServer, Http2Server, IoUringServer, UltraFastServer, UltraFastHttpServer
```

These implementations represent the evolutionary path to 765K req/s. The final `RocketHttpServer` incorporates lessons learned from all of them.
