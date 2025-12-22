# Reactive System: A Benchmarking-First Approach

**Repository:** https://github.com/miguelemosreverte/reactive-system-01

![](https://i.imgur.com/LfgkP7k.png)

## Introduction

This project implements a reactive event-driven system using Apache Flink, Kafka, Spring Boot, and React. The primary focus is on benchmarking methodology and observability infrastructure. The technology stack serves as the foundation; the measurement and optimization practices are the subject of study.

---

## Architecture Overview

### Platform and Application Separation

The codebase separates infrastructure concerns from domain logic:

```
┌─────────────────────────────────────────────────────────────┐
│                      APPLICATION                             │
│  Domain logic, Flink state machines, business rules          │
│  → Fast compilation, rapid iteration                         │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                        PLATFORM                              │
│  Kafka infrastructure, OTel integration, benchmark tooling   │
│  → Stable foundation                                         │
└─────────────────────────────────────────────────────────────┘
```

The platform layer contains Kafka publishers, OpenTelemetry hooks, and benchmark harnesses. The application layer contains Flink state machines and business rules specific to client requirements.

This separation provides two benefits:

1. **Fast compile cycles** during domain logic development
2. **Stable benchmark baselines** across experiments

When developing state machines for a client's domain, the platform remains unchanged. Compilation targets only the application module, reducing feedback time during iteration.

---

## Technology Stack

| Component | Technology | Purpose |
|-----------|------------|---------|
| Frontend | React 18 + Vite | Real-time UI with WebSocket |
| Gateway | Node.js | WebSocket-to-Kafka bridge |
| Stream Processing | Apache Flink 1.18 | Stateful event processing |
| Message Broker | Apache Kafka | Event distribution |
| Business Rules | Drools 8 | Declarative rule evaluation |
| Backend Services | Spring Boot, Java 17 | REST APIs, domain services |
| Tracing | OpenTelemetry + Jaeger | Distributed trace collection |
| Metrics | Prometheus + Grafana | Time-series metrics |
| Logs | Loki + Promtail | Centralized log aggregation |

---

## Observability Infrastructure

### Dashboard Integration

The system integrates with standard observability tools:

**Grafana** provides pre-built dashboards for throughput, latency percentiles, and system health metrics. Dashboards are provisioned automatically via configuration files.

**Loki** aggregates logs from all containers via Promtail. Logs can be queried by service, correlated with trace IDs, and filtered by time range.

**Jaeger** stores distributed traces. Each request generates spans across Gateway, Kafka, Flink, and Drools. The trace view shows timing breakdown per component.

**Prometheus** scrapes metrics from the OpenTelemetry Collector and application endpoints. Retention is configured for benchmark analysis rather than long-term storage.

### Sampling Configuration

Production tracing requires sampling to control overhead. The collector implements a two-tier strategy:

- **Default:** 0.1% sampling rate
- **On-demand:** 100% sampling via `?trace=true` query parameter or `/api/diagnostic/run` endpoint

This configuration maintains low overhead during normal operation while enabling full trace capture for debugging sessions.

---

## Benchmarking Methodology

### Component Isolation

The benchmark suite tests each component independently: HTTP layer, Gateway, Kafka, Drools, and full end-to-end. Component isolation identifies bottlenecks directly. Aggregate measurements alone would obscure which layer contributes most to latency.

### Bottleneck Analysis

The `BottleneckAnalyzer` class correlates trace spans to calculate per-component contribution to total latency:

```
Bottleneck Analysis (% of total latency):
┌──────────────┬────────────┐
│ Component    │ % of Total │
├──────────────┼────────────┤
│ Kafka        │ 42%        │  ← Primary bottleneck
│ Drools       │ 22%        │
│ Flink        │ 17%        │
│ Gateway      │ 13%        │
│ HTTP         │ 6%         │
└──────────────┴────────────┘
```

This breakdown immediately identifies where optimization effort should focus. In this case, Kafka messaging accounts for nearly half of end-to-end latency.

### Optimization Results

After identifying Kafka as the primary bottleneck, targeted producer tuning achieved a **10x throughput improvement**. The changes included batch size adjustments, linger time configuration, and partition count optimization.

The methodology works independently of absolute numbers. What matters is the ability to:
1. Identify which component limits performance
2. Make targeted changes
3. Measure the improvement

### Benchmark Execution

Benchmarks run via the CLI:

```bash
./cli.sh benchmark full
```

Each run produces:
- JSON results for programmatic comparison
- HTML dashboards for visual analysis
- Delta values against previous baselines

### Regression Testing

The benchmark infrastructure supports regression detection. Each optimization can be validated against the previous baseline. Regressions surface immediately rather than accumulating unnoticed.

---

## Implementation Details

### CQRS in the Flink Job

The `CounterJob` separates write and read paths:

- **Write path:** Immediate state updates routed to `counter-results` topic
- **Read path:** Bounded snapshot evaluation triggering Drools calls

This separation controls load on the Drools service. Write operations complete without waiting for rule evaluation.

### Adaptive Latency Control

The `AdaptiveLatencyController` implements an AIMD algorithm (Additive Increase, Multiplicative Decrease) to discover optimal batch timing at runtime. The controller adjusts automatically based on observed throughput and latency. Manual tuning parameters are not required.

### Async I/O for External Calls

The `AsyncDroolsEnricher` uses Flink's `AsyncDataStream` for non-blocking HTTP calls to the Drools service. Configuration:

- Configurable concurrent requests capacity
- Connection pooling per partition
- Unordered result collection for throughput

Trace context propagates through the async boundary via W3C Trace Context headers.

---

## Development Workflow

### Early Performance Measurement

Performance measurement begins before client requirements arrive. Latency and throughput are universal metrics. Baseline measurements establish the starting point; subsequent changes can be evaluated against this baseline.

Waiting for production problems to begin performance work creates pressure and limits options. Early measurement provides data for informed decisions throughout development.

### Iterative Optimization

Each optimization follows a cycle:

1. Measure current performance
2. Identify the bottleneck component
3. Implement a targeted change
4. Measure again
5. Compare against baseline

The benchmark infrastructure automates steps 1, 4, and 5. The developer focuses on analysis and implementation.

### Continuous Process

Optimization has no endpoint. Each improvement shifts the bottleneck. The Kafka tuning that achieved 10x throughput improvement revealed Flink checkpointing as the next constraint. Addressing checkpointing will surface another factor.

The benchmark infrastructure supports this indefinitely. Historical results accumulate in `benchmark-history.json` for trend analysis.

---

## Current Work: Memory Pressure

The recent throughput improvements introduced a secondary concern: memory pressure under sustained load.

Running benchmarks consecutively over extended periods causes memory to accumulate across the system. Individual benchmark runs complete successfully, but repeated execution without cooldown periods reveals growing memory consumption in several components.

Current investigation focuses on:

- **Trace buffer accumulation** in the OpenTelemetry Collector during high-throughput periods
- **JVM heap pressure** in the Flink TaskManager under sustained async I/O load
- **Kafka producer buffer management** when batching at aggressive settings

This is a typical pattern in performance work. Optimizing for throughput can shift pressure to memory. The observability infrastructure captures memory metrics alongside throughput, making the tradeoff visible.

The goal is sustainable throughput: performance that holds under continuous operation, not just short benchmark windows.

---

## Future Work

Potential improvements under consideration:

- Memory pressure stabilization for sustained benchmark runs
- Kafka partition rebalancing optimization
- Flink checkpoint interval tuning
- Connection pool sizing for Drools calls
- Failure injection testing

Each item will be measured before and after implementation.

---

## Summary

This project implements a reactive streaming system with comprehensive observability and benchmarking infrastructure. The platform/application separation enables fast iteration on domain logic. Component-level benchmarks identify bottlenecks. Regression testing preserves improvements across changes.

The measurement infrastructure provides control over system behavior. Performance characteristics are observable, reproducible, and comparable across versions.
