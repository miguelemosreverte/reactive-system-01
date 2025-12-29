# HTTP Server Benchmark Rankings

**Generated:** 2025-12-29T18:42:46.428738
**Config:** 16 workers, 6s duration, 12 wrk threads, 1000 connections

## Rankings

| Rank | Server | Throughput | Latency | Tier | Technology |
|------|--------|------------|---------|------|------------|
| 1 | RocketHttpServer | 94669 req/s | 10.11ms | 765K req/s | NIO + SO_REUSEPORT |
| 2 | SpringBootHttpServer | 23084 req/s | 42.03ms | ~5K req/s | Spring WebFlux |

## Available Servers

### MAXIMUM THROUGHPUT (765K req/s)

| Server | Technology | Notes |
|--------|------------|-------|
| RocketHttpServer | NIO + SO_REUSEPORT | Kernel-level connection distribution, multi-reactor |

### FRAMEWORK (~5K req/s)

| Server | Technology | Notes |
|--------|------------|-------|
| SpringBootHttpServer | Spring WebFlux | Enterprise framework, Netty under the hood |

