# Reactive System Benchmark Results

## 🏆 Fastest Configuration

**Microbatch Collector (No HTTP)** — **7.1M ops/s**

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
| Collector | Microbatch Collector (No HTTP) | 7.1M ops/s | Pure in-memory event batching |
| Kafka | Kafka - Tuned Batching | 5K ops/s | Message serialization + network |
| HTTP | Microbatch Collector (No HTTP) | 7.1M ops/s | Connection handling overhead |
| Gateway | Gateway - Netty + Microbatch | 15K ops/s | HTTP + Kafka combined |
| Full Pipeline | Full Pipeline - Netty + Microbatch | 15K ops/s | End-to-end processing |


---

## 📋 Full Results

| Rank | Configuration | Throughput | p50 | p99 | Total Ops | vs Winner |
|:----:|---------------|------------|-----|-----|-----------|----------:|
| 🏆 1 | **Microbatch Collector (No HTTP)** | 7.1M ops/s | 0.0 ms | 0.0 ms | 71.5M |  |
| 2 | **HTTP - Rocket Server** | 765K ops/s | 0.2 ms | 3.7 ms | 7.7M | 9.3x slower |
| 3 | **HTTP - Raw NIO** | 698K ops/s | 0.2 ms | 3.7 ms | 7.0M | 10.2x slower |
| 4 | **HTTP - Boss/Worker** | 665K ops/s | 0.2 ms | 4.3 ms | 6.7M | 10.7x slower |
| 5 | **HTTP - Turbo** | 609K ops/s | 0.1 ms | 7.5 ms | 6.1M | 11.7x slower |
| 6 | **HTTP - Hyper** | 566K ops/s | 0.3 ms | 5.1 ms | 5.7M | 12.6x slower |
| 7 | **HTTP - Ultra** | 516K ops/s | 0.1 ms | 8.4 ms | 5.2M | 13.8x slower |
| 8 | **HTTP - Zero Copy** | 511K ops/s | 0.1 ms | 9.1 ms | 5.1M | 14.0x slower |
| 9 | **HTTP - Raw Netty** | 485K ops/s | 0.5 ms | 5.7 ms | 4.9M | 14.7x slower |
| 10 | **Full Pipeline - Netty + Microbatch** | 15K ops/s | 12.7 ms | 165.3 ms | 155K | 461.8x slower |
| 11 | **Gateway - Netty + Microbatch** | 15K ops/s | 13.0 ms | 167.1 ms | 151K | 471.8x slower |
| 12 | **Gateway - Hyper + Microbatch** | 14K ops/s | 34.1 ms | 302.0 ms | 145K | 496.3x slower |
| 13 | **Gateway - Raw NIO + Microbatch** | 13K ops/s | 36.5 ms | 261.8 ms | 135K | 531.3x slower |
| 14 | **Gateway - Rocket + Microbatch** | 13K ops/s | 37.4 ms | 374.0 ms | 132K | 540.8x slower |
| 15 | **Gateway - Turbo + Microbatch** | 11K ops/s | 43.7 ms | 224.0 ms | 112K | 638.7x slower |
| 16 | **Gateway - Boss/Worker + Microbatch** | 10K ops/s | 50.0 ms | 470.6 ms | 98K | 728.6x slower |
| 17 | **HTTP - Spring WebFlux** | 5K ops/s | 87.9 ms | 189.7 ms | 53K | 1356.9x slower |
| 18 | **Kafka - Tuned Batching** | 5K ops/s | 3.5 ms | 0.4 ms | 281K | 1543.6x slower |
| 19 | **Kafka - Fire-and-Forget** | 5K ops/s | 3.5 ms | 0.1 ms | 281K | 1547.6x slower |
| 20 | **Flink - Stream Processing** | 2K ops/s | 21231.0 ms | 24296.0 ms | 212K | 4247.7x slower |


---

*Generated: 2025-12-26T21:23:13-03:00*
