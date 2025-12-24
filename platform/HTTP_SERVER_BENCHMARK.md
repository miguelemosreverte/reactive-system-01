# HTTP Server Benchmark Results

**Date:** 2025-12-24
**Configuration:** 16 workers, 12 wrk threads, 1000 connections, 6s duration
**Platform:** Linux (Docker) with 4 CPUs, io_uring enabled

## Final Rankings

| Rank | Server | Throughput | Technology | Avg Latency | Notes |
|------|--------|------------|------------|-------------|-------|
| **1** | **IoUringServer** | **674,498 req/s** | io_uring + FFM API | 1.51ms | Linux kernel async I/O |
| 2 | RawHttpServer | 609,188 req/s | NIO Multi-EventLoop | 1.72ms | Multiple event loops |
| 3 | HyperHttpServer | 605,133 req/s | NIO + Pipelining | 1.69ms | HTTP pipelining support |
| 4 | BossWorkerHttpServer | 573,335 req/s | NIO Boss/Worker | 1.73ms | Netty-style pattern |
| 5 | RocketHttpServer | 540,310 req/s | NIO + SO_REUSEPORT | 1.67ms | Kernel load balancing |
| 6 | UltraHttpServer | 485,550 req/s | NIO Minimal | 2.70ms | Minimal response bytes |
| 7 | TurboHttpServer | 477,118 req/s | NIO + selectNow | 2.56ms | Busy-polling optimization |
| 8 | ZeroCopyHttpServer | 471,315 req/s | NIO + Busy-poll | 2.76ms | Direct buffers |
| 9 | UltraFastHttpServer | 290,052 req/s | NIO Single-thread | 48.68ms | Redis-style single loop |
| 10 | FastHttpServer | 213,380 req/s | NIO + VirtualThreads | 75.14ms | Project Loom threads |

## Server Implementations

| Server | Class | Technology | Description |
|--------|-------|------------|-------------|
| IoUringServer | `com.reactive.platform.http.IoUringServer` | io_uring + FFM API | Uses Linux io_uring via Foreign Function & Memory API for zero-syscall I/O |
| RawHttpServer | `com.reactive.platform.http.RawHttpServer` | NIO Multi-EventLoop | Pure Java NIO with multiple independent event loops |
| HyperHttpServer | `com.reactive.platform.http.HyperHttpServer` | NIO + Pipelining | Supports HTTP pipelining for batched requests |
| BossWorkerHttpServer | `com.reactive.platform.http.BossWorkerHttpServer` | NIO Boss/Worker | Classic Netty-style architecture with accept/worker separation |
| RocketHttpServer | `com.reactive.platform.http.RocketHttpServer` | NIO + SO_REUSEPORT | Uses kernel-level load balancing across reactors |
| UltraHttpServer | `com.reactive.platform.http.UltraHttpServer` | NIO Minimal | Minimal HTTP response, aggressive selectNow() |
| TurboHttpServer | `com.reactive.platform.http.TurboHttpServer` | NIO + selectNow | Busy-polling with selectNow() when active |
| ZeroCopyHttpServer | `com.reactive.platform.http.ZeroCopyHttpServer` | NIO + Busy-poll | Direct ByteBuffers with adaptive busy-polling |
| UltraFastHttpServer | `com.reactive.platform.http.UltraFastHttpServer` | NIO Single-thread | Redis-style single event loop (CPU-bound) |
| FastHttpServer | `com.reactive.platform.http.FastHttpServer` | NIO + VirtualThreads | Uses Project Loom virtual threads |
| NettyHttpServer | `com.reactive.platform.http.NettyHttpServer` | Netty NIO | Industry standard (requires Netty dependency) |
| SpringBootHttpServer | `com.reactive.platform.http.SpringBootHttpServer` | Spring WebFlux | Enterprise framework (requires Spring Boot dependency) |

## Key Findings

### 1. io_uring Dominates
**IoUringServer achieves 674K req/s** - 11% faster than the best NIO implementation. io_uring eliminates syscall overhead by:
- Batching I/O operations in submission/completion queues
- Avoiding user/kernel context switches
- Allowing kernel-side polling (optional)

### 2. NIO Implementations Cluster Around 500-600K req/s
Well-optimized NIO servers achieve similar throughput:
- **RawHttpServer**: 609K (multi-event-loop)
- **HyperHttpServer**: 605K (pipelining)
- **BossWorkerHttpServer**: 573K (boss/worker)
- **RocketHttpServer**: 540K (SO_REUSEPORT)

### 3. Single-Threaded Designs Cap at ~300K req/s
**UltraFastHttpServer** (single event loop like Redis) maxes out at 290K req/s because it's CPU-bound on one core.

### 4. Virtual Threads Add Overhead for Fire-and-Forget
**FastHttpServer** using Virtual Threads achieves only 213K req/s. For high-throughput scenarios with minimal per-request processing, the VT scheduling overhead is not beneficial.

### 5. Latency vs Throughput Trade-off
| Server | Throughput | Avg Latency |
|--------|------------|-------------|
| IoUringServer | 674K | 1.51ms |
| UltraFastHttpServer | 290K | 48.68ms |
| FastHttpServer | 213K | 75.14ms |

## Usage

### Running a Server
```bash
# IoUringServer (requires Linux with io_uring)
java --enable-native-access=ALL-UNNAMED -cp target/classes \
     com.reactive.platform.http.IoUringServer 9999 16 false false

# Any NIO server
java -cp target/classes \
     com.reactive.platform.http.RawHttpServer 9999 16
```

### Running the Benchmark
```bash
./scripts/benchmark-servers.sh [workers] [duration] [output_dir]

# Example
./scripts/benchmark-servers.sh 16 10s ./results
```

### Standardized Interface
All servers accept the same command-line arguments:
```
java -cp target/classes com.reactive.platform.http.<ServerName> <port> [workers]
```

## Test Environment

- **JVM**: OpenJDK 22 with ZGC
- **Container**: Docker with privileged mode (for io_uring)
- **CPUs**: 4 (Docker limit)
- **Memory**: 256MB heap per server
- **Benchmark Tool**: wrk with POST requests

## Conclusion

For maximum throughput on Linux:
1. **Use IoUringServer** if you need the absolute best performance
2. **Use RawHttpServer or HyperHttpServer** for portable pure-Java solution
3. **Avoid single-threaded designs** unless CPU is not a constraint
4. **Avoid Virtual Threads** for fire-and-forget high-throughput workloads
