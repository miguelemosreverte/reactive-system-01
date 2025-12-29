# Reactive System Benchmark Results

## 🏆 Fastest Configuration

**Microbatch Collector (No HTTP)** — **13.0M ops/s**

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
| Collector | Microbatch Collector (No HTTP) | 13.0M ops/s | Pure in-memory event batching |
| Kafka | Kafka BULK - Sustained Rate (Docker) | 448K ops/s | Message serialization + network |
| HTTP | Microbatch Collector (No HTTP) | 13.0M ops/s | Connection handling overhead |
| Gateway | Gateway - Netty + Microbatch | 310K ops/s | HTTP + Kafka combined |
| Full Pipeline | Full Pipeline - Netty + Microbatch | 257K ops/s | End-to-end processing |


---

## 📋 Full Results

| Rank | Configuration | Throughput | p50 | p99 | Total Ops | vs Winner |
|:----:|---------------|------------|-----|-----|-----------|----------:|
| 🏆 1 | **Microbatch Collector (No HTTP)** | 13.0M ops/s | 10.5 ms | 21.0 ms | 137.9M |  |
| 2 | **Kafka BULK - Sustained Rate (Docker)** | 448K ops/s | 0.0 ms | 0.0 ms | 4.5M | 29.1x slower |
| 3 | **Kafka - Tuned Batching** | 433K ops/s | 0.3 ms | 0.0 ms | 4.3M | 30.1x slower |
| 4 | **Kafka - Fire-and-Forget** | 430K ops/s | 0.3 ms | 0.0 ms | 4.4M | 30.3x slower |
| 5 | **Gateway - Netty + Microbatch** | 310K ops/s | 0.6 ms | 4.6 ms | 3.1M | 41.9x slower |
| 6 | **Gateway - Rocket + Microbatch** | 294K ops/s | 1.5 ms | 8.1 ms | 2.9M | 44.3x slower |
| 7 | **HTTP - Rocket Server** | 269K ops/s | 0.7 ms | 10.6 ms | 2.7M | 48.4x slower |
| 8 | **Full Pipeline - Netty + Microbatch** | 257K ops/s | 0.7 ms | 5.0 ms | 2.6M | 50.8x slower |
| 9 | **Gateway - Spring WebFlux** | 7K ops/s | 10.0 ms | 74.0 ms | 50K | 1741.9x slower |
| 10 | **Flink - Stream Processing** | 3K ops/s | 14073.0 ms | 20799.0 ms | 110K | 3784.4x slower |
| 11 | **Flink - Stream Processing (Native)** | 3K ops/s | 16636.0 ms | 26063.0 ms | 123K | 3958.2x slower |
| 12 | **Full Pipeline - Spring** | 3K ops/s | 16.0 ms | 229.0 ms | 50K | 4004.2x slower |
| 13 | **Flink - Stream Processing (High Throughput)** | 3K ops/s | 19015.0 ms | 28023.0 ms | 109K | 4679.5x slower |
| 14 | **HTTP - Spring WebFlux** | 3K ops/s | 165.3 ms | 440.4 ms | 27K | 4817.4x slower |
| 15 | **Flink - Production Grade (High Throughput + Checkpointing)** | 2K ops/s | 21187.0 ms | 26662.0 ms | 95K | 5213.6x slower |
| 16 | **Flink - Observable (Production with Tracing)** | 2K ops/s | 20593.0 ms | 29969.0 ms | 95K | 5939.1x slower |


---

*Generated: 2025-12-29T14:51:25-03:00*
