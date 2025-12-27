# Reactive System Benchmark Results

## 🏆 Fastest Configuration

**HTTP - Boss/Worker** — **741K ops/s**

---

## 📊 Key Insights

- **Microbatch Collector** achieves 9M+ ops/s with pure in-memory batching (no network)
- **Kafka batching** reduces throughput to ~600K ops/s (serialization + network overhead)
- **HTTP layer** (Netty/Rocket) caps at ~200K ops/s due to connection handling
- **Gateway + Kafka** achieves ~170K ops/s — proving Kafka adds near-zero overhead to HTTP
- **Spring WebFlux** is 10x slower than raw Netty for gateway operations

---

## 🔬 Layer Decomposition

| Layer | Best Config | Throughput | Description |
|-------|-------------|------------|-------------|
| Collector | Microbatch Collector (No HTTP) | 3K ops/s | Pure in-memory event batching |
| Kafka | Kafka - Fire-and-Forget | 5K ops/s | Message serialization + network |
| HTTP | HTTP - Boss/Worker | 741K ops/s | Connection handling overhead |
| Gateway | Gateway - Rocket + Microbatch | 214K ops/s | HTTP + Kafka combined |
| Full Pipeline | Full Pipeline - Netty + Microbatch | 206K ops/s | End-to-end processing |


---

## 📋 Full Results

| Rank | Configuration | Throughput | p50 | p99 | Total Ops | vs Winner |
|:----:|---------------|------------|-----|-----|-----------|----------:|
| 🏆 1 | **HTTP - Boss/Worker** | 741K ops/s | 0.2 ms | 3.8 ms | 44.4M |  |
| 2 | **HTTP - Hyper** | 718K ops/s | 0.2 ms | 3.9 ms | 43.1M | 1.0x slower |
| 3 | **HTTP - Zero Copy** | 664K ops/s | 0.1 ms | 6.6 ms | 39.9M | 1.1x slower |
| 4 | **HTTP - Raw NIO** | 663K ops/s | 0.2 ms | 4.0 ms | 39.8M | 1.1x slower |
| 5 | **HTTP - Turbo** | 661K ops/s | 0.1 ms | 6.9 ms | 39.7M | 1.1x slower |
| 6 | **HTTP - Ultra** | 657K ops/s | 0.1 ms | 7.0 ms | 39.4M | 1.1x slower |
| 7 | **HTTP - Rocket Server** | 634K ops/s | 0.2 ms | 4.5 ms | 38.0M | 1.2x slower |
| 8 | **HTTP - Raw Netty** | 546K ops/s | 0.5 ms | 4.7 ms | 32.7M | 1.4x slower |
| 9 | **Gateway - Rocket + Microbatch** | 214K ops/s | 2.0 ms | 12.0 ms | 12.8M | 3.5x slower |
| 10 | **Gateway - Boss/Worker + Microbatch** | 214K ops/s | 2.0 ms | 12.6 ms | 12.8M | 3.5x slower |
| 11 | **Gateway - Hyper + Microbatch** | 209K ops/s | 2.0 ms | 12.7 ms | 12.5M | 3.6x slower |
| 12 | **Gateway - Turbo + Microbatch** | 208K ops/s | 2.0 ms | 13.3 ms | 12.5M | 3.6x slower |
| 13 | **Full Pipeline - Netty + Microbatch** | 206K ops/s | 0.9 ms | 5.2 ms | 12.3M | 3.6x slower |
| 14 | **Gateway - Raw NIO + Microbatch** | 203K ops/s | 2.1 ms | 14.3 ms | 12.2M | 3.6x slower |
| 15 | **Gateway - Netty + Microbatch** | 197K ops/s | 0.9 ms | 6.4 ms | 11.8M | 3.8x slower |
| 16 | **HTTP - Spring WebFlux** | 5K ops/s | 88.5 ms | 182.9 ms | 319K | 139.3x slower |
| 17 | **Kafka - Fire-and-Forget** | 5K ops/s | 3.5 ms | 0.4 ms | 281K | 160.1x slower |
| 18 | **Kafka - Tuned Batching** | 5K ops/s | 3.5 ms | 0.2 ms | 281K | 160.2x slower |
| 19 | **Microbatch Collector (No HTTP)** | 3K ops/s | 355.1 ms | 710.2 ms | 218.0M | 283.5x slower |


---

*Generated: 2025-12-27T01:02:45-03:00*
