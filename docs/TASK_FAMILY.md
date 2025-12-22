# Task Family: CLI Rewrite & Project Reorganization

## Overview

Transform the project from scattered Bash scripts to a clean Go CLI with proper project structure enabling GitHub Pages, DRY patterns, and continued performance optimization.

---

## Task Family 1: Go CLI Implementation

### 1.1 Core CLI Structure
- [ ] Create `cli/` folder with Go module
- [ ] Set up Cobra command framework
- [ ] Implement command hierarchy (see CLI_INVENTORY.md)
- [ ] Entry point: `cli.sh` that runs `go run cli/main.go "$@"`

### 1.2 Popularity Tracking
- [ ] SQLite database in `cli/.usage.db` (gitignored)
- [ ] Track: command, timestamp, duration, success/failure
- [ ] On each command execution, log usage
- [ ] Generate `cli/README.md` with ranked command list
- [ ] Help menu shows top 10 most used commands first
- [ ] Fallback ranking if no local usage data

### 1.3 REPL Mode
- [ ] Interactive mode: `./cli.sh` with no args enters REPL
- [ ] Arrow navigation:
  - ↑/↓: Navigate menu items
  - Enter: Select item
  - ←: Go back to parent menu
  - →: Expand submenu
- [ ] Menu structure mirrors command hierarchy
- [ ] Fuzzy search with `/` prefix
- [ ] Exit with `q` or Ctrl+C

### 1.4 Command Implementation
- [ ] Lifecycle: start, stop, restart, rebuild, logs, status, down, clean
- [ ] Diagnostics: doctor, stats, diagnose (with subcommands)
- [ ] Benchmark: bench <target> [duration] [concurrency]
- [ ] Observability: traces, search, jaeger, grafana, loki
- [ ] Testing: send, e2e, debug
- [ ] Development: shell, compile
- [ ] Infrastructure: kafka, flink, drools (status)

### 1.5 Dead Code Removal
- [ ] Remove from cli.sh:
  - `get_maven_cmd()`
  - `run_platform_benchmark()`
  - `run_e2e_benchmark()`
  - `run_all_maven_benchmarks()`
  - `open_benchmark_reports()`
- [ ] Remove unused scripts:
  - `scripts/run-benchmarks-java.sh`
  - `scripts/benchmark-doctor.sh` (verify first)
  - `scripts/benchmark-history.sh` (verify first)
- [ ] Keep and integrate:
  - `scripts/Benchmark.java`
  - `scripts/doctor.sh`
  - `scripts/memory-diagnostics.sh`
  - `scripts/utils.sh`

---

## Task Family 2: Project Structure Reorganization

### 2.1 Target Structure
```
reactive-system/
├── README.md                 # Project overview
├── index.html               # GitHub Pages entry (links to platform/app)
├── cli.sh                   # Entry point (go run wrapper)
├── cli/                     # Go CLI source
│   ├── go.mod
│   ├── main.go
│   ├── cmd/
│   ├── internal/
│   ├── README.md            # CLI docs with popularity ranking
│   └── .usage.db            # SQLite (gitignored)
├── platform/                # Infrastructure layer
│   ├── README.md
│   ├── ui/                  # Platform backoffice UI
│   ├── cli/                 # Platform-specific commands (optional)
│   ├── docker/              # Docker Compose, Dockerfiles
│   │   ├── docker-compose.yml
│   │   ├── drools/
│   │   ├── flink/
│   │   ├── gateway/
│   │   └── observability/
│   ├── terraform/           # Infrastructure as code
│   └── k8s/                 # Kubernetes manifests (future)
└── application/             # Business logic layer
    ├── README.md
    ├── ui/                  # Application frontend
    ├── benchmarks/          # Performance benchmarks
    │   ├── Benchmark.java
    │   └── results/
    ├── reports/             # Generated reports
    └── tests/               # E2E tests
```

### 2.2 Migration Steps
- [ ] Create new folder structure
- [ ] Move Docker files to `platform/docker/`
- [ ] Move UI to `application/ui/`
- [ ] Move benchmarks to `application/benchmarks/`
- [ ] Move reports to `application/reports/`
- [ ] Update all paths in docker-compose.yml
- [ ] Update CLI paths
- [ ] Create root index.html for GitHub Pages
- [ ] Test everything works

### 2.3 index.html (GitHub Pages)
```html
<!DOCTYPE html>
<html>
<head>
    <title>Reactive System</title>
    <style>
        body { font-family: system-ui; max-width: 800px; margin: 50px auto; }
        .card { border: 1px solid #ddd; padding: 20px; margin: 10px; border-radius: 8px; }
        a { text-decoration: none; color: #0066cc; }
    </style>
</head>
<body>
    <h1>Reactive System</h1>
    <div class="card">
        <h2><a href="platform/ui/">Platform Backoffice</a></h2>
        <p>Infrastructure monitoring, observability, benchmarks</p>
    </div>
    <div class="card">
        <h2><a href="application/ui/">Application</a></h2>
        <p>Business application interface</p>
    </div>
</body>
</html>
```

---

## Task Family 3: DRY & Functional Patterns

### 3.1 Bracket-Style Tracing (Scala-inspired)
```java
// BEFORE (repetitive)
public Response processEvent(Request req) {
    Span span = tracer.spanBuilder("processEvent").startSpan();
    try (Scope scope = span.makeCurrent()) {
        span.setAttribute("event.id", req.getId());
        // ... business logic ...
        return response;
    } catch (Exception e) {
        span.recordException(e);
        throw e;
    } finally {
        span.end();
    }
}

// AFTER (bracket pattern)
public Response processEvent(Request req) {
    return Traced.bracket("processEvent")
        .attribute("event.id", req.getId())
        .execute(() -> {
            // ... business logic ...
            return response;
        });
}
```

### 3.2 Implementation
- [ ] Create `platform/docker/gateway/src/main/java/com/reactive/tracing/Traced.java`
- [ ] Bracket API: `Traced.bracket(name).attribute(k,v).execute(supplier)`
- [ ] Auto-exception recording
- [ ] Auto-span closing
- [ ] Support for sampling rate
- [ ] Support for force-trace header

### 3.3 Default Tracing for All Endpoints
- [ ] Spring WebFlux filter that wraps all requests
- [ ] Sampling configuration: `tracing.sample-rate=0.1` (10% in prod)
- [ ] Header override: `X-Force-Trace: true` for full tracing
- [ ] Consistent span naming: `http.{method}.{path}`

### 3.4 Refactoring Passes
- [ ] Pass 1: Identify repetitive patterns
  - Logging setup/teardown
  - Error handling
  - Metric recording
  - ...
- [ ] Pass 2: Extract utilities
  - `Traced.bracket()` for tracing
  - `Logged.bracket()` for logging
  - `Metrics.timed()` for metrics
  - ...
- [ ] Pass 3: Apply patterns across codebase
  - Gateway
  - Drools
  - Flink
  - ...

---

## Task Family 4: Performance Optimization (Continued)

### 4.1 Current State
- Drools: ~16,000 req/s (after StatelessKieSession optimization)
- Full pipeline: ~10,600 req/s

### 4.2 Next Targets
- [ ] Profile Kafka latency
- [ ] Profile Flink processing time
- [ ] Identify next bottleneck with new tooling
- [ ] Optimize based on findings
- [ ] Target: 20,000+ req/s full pipeline

### 4.3 Tooling Improvements
- [ ] Better benchmark reporting
- [ ] Component-level timing breakdown
- [ ] Automated bottleneck identification
- [ ] Historical comparison

---

## Execution Order

```
Phase 1: CLI Foundation
├── 1.1 Create Go CLI structure
├── 1.2 Implement core commands
├── 1.3 Remove dead bash code
└── 1.4 Test CLI functionality

Phase 2: REPL & Popularity
├── 2.1 Add SQLite tracking
├── 2.2 Implement REPL mode
├── 2.3 Generate ranked README
└── 2.4 Update help menus

Phase 3: Project Restructure
├── 3.1 Create new folder structure
├── 3.2 Migrate files
├── 3.3 Update paths
├── 3.4 Create index.html
└── 3.5 Test everything

Phase 4: DRY Patterns
├── 4.1 Create Traced.bracket()
├── 4.2 Apply to gateway
├── 4.3 Apply to drools
├── 4.4 Apply to flink
└── 4.5 Refactoring passes

Phase 5: Performance
├── 5.1 Profile with new tooling
├── 5.2 Identify bottlenecks
├── 5.3 Optimize
└── 5.4 Iterate
```

---

## Notes

- **Go run vs Build**: Use `go run` for development (always latest code), `go build` for distribution
- **SQLite location**: `cli/.usage.db` (gitignored, local to each user)
- **Gateway**: Confirmed Java/Spring Boot WebFlux (NOT Node)
- **Sampling**: 10% in prod, 100% with `X-Force-Trace` header
