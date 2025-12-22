# Development Workflows

## Workflow 1: Refactoring

### Purpose
Clean up code to match standards, remove dead code, improve readability.

### Process
1. **Analyze** - Review all source files in target component
2. **Identify** - Dead code, abstraction leaks, null usage, third-party type propagation
3. **Fix** - Apply standards incrementally
4. **Validate** - Compile locally (fast feedback)
5. **Commit** - Small, focused commits

### Validation Commands
```bash
# Java components
mvn compile -q

# Go CLI
go build .

# TypeScript
npm run build
```

### Commit Style
```
refactor(<component>): <brief description>

- Removed dead code in X
- Wrapped null from third-party Y
- Extracted common pattern Z
```

---

## Workflow 2: Benchmarking

### Purpose
Improve throughput and latency through iterative optimization.

### Process (Recursive)
1. **Diagnose** - `./cli.sh doctor` - identify system state
2. **Benchmark** - `./cli.sh benchmark run` - capture baseline
3. **Analyze** - Identify bottlenecks from results
4. **Fix** - Apply targeted optimization
5. **Benchmark** - Verify improvement (no regressions)
6. **Commit** - Tag with deltas
7. **Repeat** - Go to step 1

### Commit Requirements

**Commits MUST include performance deltas:**
```
perf(<component>): <description>

Throughput: 14,200 → 15,800 evt/s (+11.3%)
P99 Latency: 45ms → 38ms (-15.6%)

- Optimization details here
```

**Rejection Criteria:**
- Commit rejected if throughput decreases
- Commit rejected if latency increases without throughput gain
- Throughput is the primary metric

### Key Metrics
| Metric | Description | Target |
|--------|-------------|--------|
| Throughput | Events per second | Maximize |
| P50 Latency | Median response time | Minimize |
| P99 Latency | Tail latency | Minimize |
| CPU Usage | Resource efficiency | Balance |
| Memory | Heap usage | Stable |
