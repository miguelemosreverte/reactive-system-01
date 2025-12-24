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

---

## Workflow 3: Diagnostic-Driven Optimization (Dogfooding)

### Purpose
Use the diagnostic system as the primary tool for identifying and fixing bottlenecks. The diagnostic evolves alongside the benchmark - improving both the tool and the system it observes.

### Philosophy
> "You're going to read your diagnosis, see if there are actionable items or if you need more data. If you have questions about it, improve the diagnostic tool so you have better data to work with."

This is **dogfooding** - using your own diagnostic tools to guide optimization:
1. The diagnostic tells you what's wrong
2. If the diagnostic is unclear, improve the diagnostic first
3. Only then fix the actual problem
4. The benchmark is the regression test - commits only allowed if throughput improves

### Process (Iterative)

```
┌─────────────────────────────────────────────────────────────┐
│  1. READ DIAGNOSTIC                                          │
│     └─ ./cli.sh doctor OR curl diagnostics endpoint         │
│                                                              │
│  2. ANALYZE                                                  │
│     ├─ Actionable items found? → Go to step 4               │
│     └─ Need more data? → Go to step 3                        │
│                                                              │
│  3. IMPROVE DIAGNOSTIC                                       │
│     ├─ Add missing metrics to diagnostic schema              │
│     ├─ Update Markdown rendering for readability             │
│     ├─ Update HTML rendering for visual clarity              │
│     └─ Return to step 1                                      │
│                                                              │
│  4. IMPLEMENT FIX                                            │
│     └─ Apply targeted optimization based on diagnostic       │
│                                                              │
│  5. BENCHMARK (Regression Test)                              │
│     ├─ Throughput improved? → COMMIT (step 6)                │
│     └─ Throughput decreased? → REJECT (revert, analyze)      │
│                                                              │
│  6. COMMIT                                                   │
│     ├─ Include before/after metrics                          │
│     ├─ Tag with throughput delta                             │
│     └─ Return to step 1                                      │
└─────────────────────────────────────────────────────────────┘
```

### Regression Test Gate

**The benchmark IS the regression test.** A commit can only be made if:

1. Throughput is **equal or higher** than the previous baseline
2. The commit message includes the delta: `Throughput: X → Y (+Z%)`
3. If throughput decreases, the commit is rejected and changes must be reverted

```bash
# Example regression test gate
BASELINE=1500  # ops/s from previous commit
CURRENT=$(./cli.sh bench http --quick | grep throughput | awk '{print $2}')

if [ $CURRENT -ge $BASELINE ]; then
    echo "✓ Regression test PASSED: $CURRENT >= $BASELINE"
    # Allow commit
else
    echo "✗ Regression test FAILED: $CURRENT < $BASELINE"
    # Reject commit, revert changes
fi
```

### Dual Output: Markdown + HTML

When improving diagnostics, always update **both** outputs:

1. **Markdown** - For terminal/CLI consumption (`/api/diagnostics/summary`)
2. **HTML** - For browser/visual consumption (report pages)

This ensures the diagnostic is useful in all contexts.

### Commit Frequency

Target **20-40 small commits** per optimization session:
- Each commit = one focused improvement
- Each commit passes regression test
- Progress is incremental and measurable

### Example Session

```
Commit 1:  perf(flink): reduce async buffer size
           Throughput: 1,200 → 1,350 (+12.5%)

Commit 2:  fix(diagnostic): add async queue depth metric
           (No throughput change - diagnostic improvement)

Commit 3:  perf(gateway): increase Kafka batch size
           Throughput: 1,350 → 1,520 (+12.6%)

Commit 4:  perf(drools): enable rule caching
           Throughput: 1,520 → 1,680 (+10.5%)

... (continue until diminishing returns)
```

### Handling Rejected Optimizations (Cemetery)

When an optimization is rejected due to regression, **preserve the work** in the Cemetery:

1. **Create a cemetery branch** with the rejected code:
   ```bash
   git checkout -b cemetery/<component>-<brief-description>
   # Apply the rejected change
   git commit -m "perf(<component>): <description> [REJECTED]"
   git checkout main
   ```

2. **Document in CEMETERY.md**:
   - What was changed and why it seemed promising
   - Benchmark results showing the regression
   - Root cause analysis explaining why it failed
   - Conditions that might change the decision

3. **Branch naming convention**:
   ```
   cemetery/<component>-<brief-description>
   ```
   Examples:
   - `cemetery/flink-kafka-linger-5ms`
   - `cemetery/drools-parallel-sessions`
   - `cemetery/gateway-http2-enabled`

**Why preserve rejected changes?**
- Prevents repeating failed experiments
- Captures valuable insights about system behavior
- Allows future re-evaluation when conditions change
- The code compiles and was benchmarked - that work has value

See [CEMETERY.md](./CEMETERY.md) for the full list of rejected optimizations.

### Success Criteria

The session is complete when:
- Throughput plateaus (diminishing returns on optimizations)
- Diagnostic shows all components healthy (no actionable items)
- System is stable under load (no OOM, no crashes)
