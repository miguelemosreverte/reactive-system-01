# CLI Functionality Inventory & Go Migration Plan

## Executive Summary

**Current State:**
- `cli.sh` is 1,750 lines of Bash
- Contains 50+ commands across lifecycle, diagnostics, observability, and testing
- Some dead code referencing non-existent Maven project structures
- Confusing namespace system (`p/platform`, `a/app`) alongside flat commands

**Recommendation:** Migrate to Go for maintainability, type safety, and single-binary distribution.

---

## Part 1: Complete Functionality Inventory

### 1.1 Lifecycle Commands (KEEP ALL)

| Command | Function | Status | Description |
|---------|----------|--------|-------------|
| `start [service]` | `cmd_start` | ACTIVE | Start all or specific service with smart rebuild |
| `stop [service]` | `cmd_stop` | ACTIVE | Stop all or specific service |
| `restart [service]` | `cmd_restart` | ACTIVE | Restart without rebuild |
| `rebuild <service>` | `cmd_rebuild` | ACTIVE | Rebuild with cache, then restart |
| `full-rebuild <service>` | `cmd_full_rebuild` | ACTIVE | Rebuild without cache |
| `build [service]` | `cmd_build` | ACTIVE | Build only, no start |
| `logs <service>` | `cmd_logs` | ACTIVE | Follow service logs |
| `status` | `cmd_status` | ACTIVE | Show docker compose ps |
| `down` | `cmd_down` | ACTIVE | Stop and remove containers |
| `clean` | `cmd_clean` | ACTIVE | Remove containers, images, volumes |
| `quick <service>` | `cmd_quick` | ACTIVE | Fastest restart (no rebuild) |
| `dev` | `cmd_dev` | ACTIVE | Start in dev mode with tips |

### 1.2 Diagnostics Commands (KEEP ALL)

| Command | Function | Status | Description |
|---------|----------|--------|-------------|
| `doctor` | `cmd_doctor` | ACTIVE | Comprehensive health check (sources `scripts/doctor.sh`) |
| `stats` | `cmd_stats` | ACTIVE | Quick container CPU/memory view |
| `diagnose` | `cmd_memory` | ACTIVE | Full memory diagnostics (sources `scripts/memory-diagnostics.sh`) |
| `memory [subcmd]` | `cmd_memory` | ACTIVE | Alias for diagnose |
| `mem [subcmd]` | `cmd_memory` | ACTIVE | Alias for diagnose |

**Memory Diagnostics Sub-commands (from `scripts/memory-diagnostics.sh`):**

| Sub-command | Description |
|-------------|-------------|
| `overview` | Memory usage for all containers (default) |
| `pressure` | Visual pressure bars per component |
| `risk` | Risk assessment with score and recommendations |
| `crashes` | Crash history tracking |
| `kpis` | Benchmark stability KPIs (success rate, avg throughput) |
| `verdict` | Actionable conclusions with pinpoint accuracy |
| `diagnose` | Full report (all of the above) |
| `record <event>` | Record a crash/OOM event |

### 1.3 Benchmarking Commands (KEEP ALL)

| Command | Function | Status | Description |
|---------|----------|--------|-------------|
| `bench <target> [duration] [concurrency]` | `cmd_bench` | ACTIVE | Run Java benchmark (drools/full/gateway) |

**Benchmark Targets:**
- `drools` - Direct Drools API benchmark
- `full` - Full pipeline (Gateway → Kafka → Flink → Drools)
- `gateway` - Gateway HTTP endpoint

**Implementation:** Uses `scripts/Benchmark.java` running in Docker container.

### 1.4 Observability Commands (KEEP ALL)

| Command | Function | Status | Description |
|---------|----------|--------|-------------|
| `traces [id]` | `cmd_traces` | ACTIVE | Show recent traces or inspect specific trace |
| `search [traceId]` | `cmd_search` | ACTIVE | Search logs in Loki by traceId |

### 1.5 Testing Commands (KEEP ALL)

| Command | Function | Status | Description |
|---------|----------|--------|-------------|
| `send [session] [action] [value]` | `cmd_send` | ACTIVE | Send test event through pipeline |
| `e2e` | `cmd_e2e` | ACTIVE | Run end-to-end test |

### 1.6 Development Commands (KEEP ALL)

| Command | Function | Status | Description |
|---------|----------|--------|-------------|
| `shell <service>` | `cmd_shell` | ACTIVE | Enter container shell |
| `compile drools` | `cmd_compile` | ACTIVE | Recompile Drools rules |
| `watch <service>` | `cmd_watch` | PLACEHOLDER | Not implemented yet |

### 1.7 Namespace Commands (CONSOLIDATE)

**Platform namespace (`p` or `platform`):**

| Sub-command | Maps To | Recommendation |
|-------------|---------|----------------|
| `traces [id]` | `cmd_traces` | Promote to top-level |
| `logs <requestId>` | `cmd_search` | Promote to top-level as `search` |
| `jaeger` | opens browser | Promote to top-level |
| `grafana` | opens browser | Promote to top-level |
| `loki <query>` | `cmd_loki_query` | Keep as `loki` |
| `doctor` | `cmd_doctor` | Already top-level |
| `memory` | `cmd_memory` | Already top-level as `diagnose` |
| `benchmark` | OLD cmd_benchmark | DEAD CODE - references non-existent scripts |
| `replay <session>` | `cmd_replay` | Promote to top-level |
| `kafka` | `cmd_kafka_status` | Promote to top-level |
| `flink` | `cmd_flink_status` | Promote to top-level |
| `drools` | `cmd_drools_status` | Promote to top-level |

**App namespace (`a` or `app`):**

| Sub-command | Maps To | Recommendation |
|-------------|---------|----------------|
| `send [opts]` | `cmd_app_send` | Keep (has richer options than `cmd_send`) |
| `debug [opts]` | `cmd_app_debug` | Promote to top-level as `debug` |
| `status [session]` | `cmd_app_status` | Promote to top-level |
| `e2e` | `cmd_e2e` | Already top-level |
| `load [opts]` | NOT IMPLEMENTED | DEAD CODE - function doesn't exist |
| `bff-status` | curl command | Low priority |

### 1.8 Dead/Unused Code (REMOVE)

| Function | Lines | Reason |
|----------|-------|--------|
| `get_maven_cmd` | ~7 | References Maven which isn't used |
| `run_platform_benchmark` | ~60 | References `platform/pom.xml` which doesn't exist |
| `run_e2e_benchmark` | ~50 | References `application/pom.xml` which doesn't exist |
| `run_all_maven_benchmarks` | ~60 | References above functions |
| `open_benchmark_reports` | ~40 | References non-existent report files |
| `cmd_app_load` | Referenced | Function never defined |

### 1.9 Helper Scripts (KEEP)

| Script | Purpose | Status |
|--------|---------|--------|
| `scripts/utils.sh` | Print functions (print_info, print_error, etc.) | ACTIVE |
| `scripts/doctor.sh` | Comprehensive health check | ACTIVE |
| `scripts/memory-diagnostics.sh` | Memory pressure, crashes, KPIs, verdicts | ACTIVE |
| `scripts/Benchmark.java` | Async Java benchmark client | ACTIVE |
| `scripts/e2e-test.sh` | E2E test script | ACTIVE |
| `scripts/run-benchmarks-java.sh` | Complex Maven-based benchmarking | DEAD - doesn't work |
| `scripts/benchmark-doctor.sh` | Benchmark observability check | POTENTIALLY DEAD |
| `scripts/benchmark-history.sh` | Benchmark history management | POTENTIALLY DEAD |

---

## Part 2: Functionality Verification

### Was Any Functionality Lost?

**NO.** All requested functionality is preserved:

1. **Memory Diagnostics** - `./cli.sh diagnose` or `./cli.sh memory` still works
   - Pressure visualization
   - Risk assessment
   - Crash tracking
   - KPIs
   - Actionable verdicts

2. **Benchmarking** - `./cli.sh bench` works with the new simple implementation
   - Uses `scripts/Benchmark.java` directly
   - Targets: drools, full, gateway

3. **All lifecycle commands** - start, stop, rebuild, etc. unchanged

4. **Observability** - traces, search still work

**What Changed:**
- Simplified help message (shows essential commands first)
- `bench` command simplified to use Benchmark.java directly
- Removed broken references to Maven benchmarks

---

## Part 3: Go CLI Architecture Plan

### 3.1 Project Structure

```
cli/
├── go.mod
├── go.sum
├── main.go                 # Entry point
├── cmd/
│   ├── root.go            # Cobra root command
│   ├── lifecycle.go       # start, stop, restart, rebuild, logs, status
│   ├── diagnostics.go     # doctor, stats, diagnose, memory
│   ├── benchmark.go       # bench
│   ├── observe.go         # traces, search, jaeger, grafana, loki
│   ├── test.go            # send, e2e, debug
│   ├── dev.go             # shell, compile, watch
│   └── infra.go           # kafka, flink, drools (status commands)
├── internal/
│   ├── docker/
│   │   ├── compose.go     # Docker Compose wrapper
│   │   ├── stats.go       # Container stats
│   │   └── exec.go        # Docker exec helpers
│   ├── http/
│   │   └── client.go      # HTTP client for API calls
│   ├── output/
│   │   ├── printer.go     # Colored output (print_info, etc.)
│   │   └── table.go       # Table formatting
│   └── config/
│       └── config.go      # Service names, ports, URLs
└── Makefile               # Build targets
```

### 3.2 Command Mapping

```go
// root.go
var rootCmd = &cobra.Command{
    Use:   "reactive",
    Short: "Reactive System CLI",
}

func init() {
    // Lifecycle (most used)
    rootCmd.AddCommand(startCmd)
    rootCmd.AddCommand(stopCmd)
    rootCmd.AddCommand(restartCmd)
    rootCmd.AddCommand(rebuildCmd)
    rootCmd.AddCommand(logsCmd)
    rootCmd.AddCommand(statusCmd)

    // Diagnostics
    rootCmd.AddCommand(doctorCmd)
    rootCmd.AddCommand(statsCmd)
    rootCmd.AddCommand(diagnoseCmd)

    // Benchmarking
    rootCmd.AddCommand(benchCmd)

    // Observability
    rootCmd.AddCommand(tracesCmd)
    rootCmd.AddCommand(searchCmd)
    rootCmd.AddCommand(jaegerCmd)
    rootCmd.AddCommand(grafanaCmd)

    // Testing
    rootCmd.AddCommand(sendCmd)
    rootCmd.AddCommand(e2eCmd)

    // Development
    rootCmd.AddCommand(shellCmd)
    rootCmd.AddCommand(devCmd)

    // Infrastructure status
    rootCmd.AddCommand(kafkaCmd)
    rootCmd.AddCommand(flinkCmd)
    rootCmd.AddCommand(droolsCmd)

    // Cleanup
    rootCmd.AddCommand(downCmd)
    rootCmd.AddCommand(cleanCmd)
}
```

### 3.3 Command Details

#### Lifecycle Commands

```go
// lifecycle.go

var startCmd = &cobra.Command{
    Use:   "start [service]",
    Short: "Start all services or a specific one",
    Long:  "Starts Docker Compose services with smart rebuild caching",
    Args:  cobra.MaximumNArgs(1),
    Run:   runStart,
}

var rebuildCmd = &cobra.Command{
    Use:   "rebuild <service>",
    Short: "Rebuild and restart a service",
    Long:  "Rebuilds with Docker cache, then restarts",
    Args:  cobra.ExactArgs(1),
    Run:   runRebuild,
}
```

#### Diagnostics Commands

```go
// diagnostics.go

var diagnoseCmd = &cobra.Command{
    Use:   "diagnose [subcmd]",
    Short: "Memory and performance diagnostics",
    Long: `Full diagnostic suite including:
  - pressure: Visual pressure bars per component
  - risk: Risk assessment with recommendations
  - crashes: Crash history tracking
  - kpis: Benchmark stability KPIs
  - verdict: Actionable conclusions`,
    Run: runDiagnose,
}

func init() {
    diagnoseCmd.AddCommand(pressureCmd)
    diagnoseCmd.AddCommand(riskCmd)
    diagnoseCmd.AddCommand(crashesCmd)
    diagnoseCmd.AddCommand(kpisCmd)
    diagnoseCmd.AddCommand(verdictCmd)
}
```

#### Benchmark Commands

```go
// benchmark.go

var benchCmd = &cobra.Command{
    Use:   "bench <target> [duration] [concurrency]",
    Short: "Run performance benchmark",
    Long: `Run benchmark against:
  - drools: Direct Drools API
  - full: Complete pipeline (Gateway → Kafka → Flink → Drools)
  - gateway: Gateway HTTP endpoint`,
    Args: cobra.RangeArgs(1, 3),
    Run:  runBench,
}

func init() {
    benchCmd.Flags().IntP("duration", "d", 30, "Benchmark duration in seconds")
    benchCmd.Flags().IntP("concurrency", "c", 50, "Number of concurrent requests")
}
```

### 3.4 Dependencies

```go
// go.mod
module github.com/your-org/reactive-cli

go 1.21

require (
    github.com/spf13/cobra v1.8.0
    github.com/fatih/color v1.16.0
    github.com/olekukonko/tablewriter v0.0.5
)
```

### 3.5 Build & Distribution

```makefile
# Makefile

BINARY_NAME=reactive
VERSION=$(shell git describe --tags --always)

.PHONY: build install clean

build:
	go build -ldflags "-X main.Version=$(VERSION)" -o bin/$(BINARY_NAME) .

install: build
	cp bin/$(BINARY_NAME) /usr/local/bin/

# Cross-compile for CI/CD
build-all:
	GOOS=darwin GOARCH=amd64 go build -o bin/$(BINARY_NAME)-darwin-amd64 .
	GOOS=darwin GOARCH=arm64 go build -o bin/$(BINARY_NAME)-darwin-arm64 .
	GOOS=linux GOARCH=amd64 go build -o bin/$(BINARY_NAME)-linux-amd64 .
```

### 3.6 Wrapper Script

After Go CLI is built, `cli.sh` becomes a thin wrapper:

```bash
#!/bin/bash
# cli.sh - Wrapper for Go CLI

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLI_BINARY="$SCRIPT_DIR/bin/reactive"

if [[ ! -x "$CLI_BINARY" ]]; then
    echo "Building CLI..."
    (cd "$SCRIPT_DIR/cli" && go build -o "$CLI_BINARY" .)
fi

exec "$CLI_BINARY" "$@"
```

---

## Part 4: Migration Phases

### Phase 1: Core Commands (Week 1)
- [ ] Set up Go project with Cobra
- [ ] Implement lifecycle commands (start, stop, rebuild, logs, status)
- [ ] Implement docker package
- [ ] Test against existing functionality

### Phase 2: Diagnostics (Week 2)
- [ ] Implement doctor command
- [ ] Implement stats command
- [ ] Port memory-diagnostics.sh to Go or keep as external script
- [ ] Implement bench command

### Phase 3: Observability (Week 3)
- [ ] Implement traces command (Jaeger API)
- [ ] Implement search command (Loki API)
- [ ] Implement infrastructure status commands

### Phase 4: Testing & Polish (Week 4)
- [ ] Implement send, e2e, debug commands
- [ ] Add shell completions
- [ ] Add --help with full documentation
- [ ] Remove dead code from Bash version
- [ ] Update README

---

## Part 5: Full Command Reference (For Help Text)

```
Reactive System CLI

LIFECYCLE
  start [service]     Start all services or a specific one
  stop [service]      Stop all or specific service
  restart [service]   Restart without rebuild
  rebuild <service>   Rebuild with cache, then restart
  full-rebuild <svc>  Rebuild without cache
  logs <service>      Follow service logs
  status              Show running services
  down                Stop and remove containers
  clean               Remove containers, images, volumes
  quick <service>     Fastest restart (no rebuild)
  dev                 Start in development mode

DIAGNOSTICS
  doctor              Comprehensive health check
  stats               Quick container resource usage
  diagnose [subcmd]   Memory/performance diagnostics
    pressure          Visual pressure bars per component
    risk              Risk assessment with recommendations
    crashes           Crash history tracking
    kpis              Benchmark stability KPIs
    verdict           Actionable conclusions

BENCHMARKING
  bench <target>      Run performance benchmark
    drools            Direct Drools API
    full              Complete E2E pipeline
    gateway           Gateway HTTP endpoint
    Options:
      -d, --duration    Duration in seconds (default: 30)
      -c, --concurrency Concurrent requests (default: 50)

OBSERVABILITY
  traces [id]         Show recent traces or inspect by ID
  search [traceId]    Search logs in Loki
  jaeger              Open Jaeger UI
  grafana             Open Grafana
  loki <query>        Query Loki directly
  replay <session>    Replay events with tracing

INFRASTRUCTURE STATUS
  kafka               Kafka cluster status
  flink               Flink job manager status
  drools              Drools rule engine status

TESTING
  send [opts]         Send counter event
    --session, -s     Session ID
    --action, -a      Action: increment/decrement/set
    --value, -v       Value (default: 1)
  debug [opts]        Send with debug mode (trace + logs)
  e2e                 Run end-to-end test

DEVELOPMENT
  shell <service>     Enter container shell
  compile drools      Recompile Drools rules

URLS
  http://localhost:3000    UI Portal
  http://localhost:8080    Gateway API
  http://localhost:16686   Jaeger (Traces)
  http://localhost:3001    Grafana
  http://localhost:8081    Flink Dashboard
```

---

## Conclusion

**All functionality is preserved.** The Go migration will:
1. Improve maintainability (type safety, tests)
2. Provide single binary distribution
3. Enable better help/documentation
4. Remove dead code cleanly
5. Allow modular growth

The Bash `cli.sh` will remain as a thin wrapper during transition, eventually becoming just a bootstrap script.
