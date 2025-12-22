# Reactive System CLI

A Go-based command-line interface for managing, diagnosing, and benchmarking the reactive system.

## Installation

```bash
# Requires Go 1.21+
brew install go  # macOS

# Run via wrapper (auto-compiles)
./cli.sh <command>

# Or build binary directly
cd platform/cli && go build -o reactive .
```

## Usage

```bash
reactive <command> [flags]
```

### REPL Mode

Running `reactive` without arguments or with `repl` enters interactive mode:

```bash
$ ./cli.sh
reactive> doctor
reactive> bench full -d 30
reactive> memory pressure
reactive> exit
```

Use arrow keys to navigate menus:
- ↑/↓: Move selection
- →/Enter: Select/enter submenu
- ←: Go back
- q: Quit

## Commands

### Lifecycle

| Command | Description |
|---------|-------------|
| `start [service]` | Start all services or a specific one |
| `stop [service]` | Stop services |
| `restart [service]` | Restart without rebuild |
| `rebuild <service>` | Rebuild and restart a service |
| `logs <service>` | Follow service logs |
| `status` | Show service health |
| `down` | Stop and remove containers |
| `clean` | Remove everything including volumes |

### Diagnostics

| Command | Description |
|---------|-------------|
| `doctor` | Comprehensive health check of all services |
| `stats` | Container CPU and memory usage |
| `memory [subcmd]` | Memory diagnostics suite |

**Memory subcommands:**

| Subcommand | Description |
|------------|-------------|
| `overview` | Container memory usage table |
| `jvm` | JVM heap details for Drools and Flink |
| `pressure` | Visual pressure bars per component |
| `risk` | Crash risk assessment with recommendations |
| `crashes` | OOM history and container health |
| `verdict` | Actionable conclusions |
| `watch` | Live memory monitoring (Ctrl+C to stop) |
| `heap <service>` | Take heap dump (drools, flink-taskmanager) |
| `jfr <service> [duration]` | Java Flight Recorder profiling |

### Benchmarking

| Command | Description |
|---------|-------------|
| `bench <target>` | Run performance benchmark |
| `bench doctor` | Validate observability chain for benchmarks |
| `bench history <action>` | Manage benchmark history |

**Benchmark targets:**

| Target | Description |
|--------|-------------|
| `http` | HTTP endpoint latency (health check) |
| `kafka` | Kafka produce/consume round-trip |
| `flink` | Flink stream processing throughput |
| `drools` | Direct Drools rule evaluation |
| `gateway` | HTTP + Kafka publish (fire-and-forget) |
| `full` | Complete E2E pipeline (HTTP → Kafka → Flink → Drools) |
| `all` | Run all benchmarks sequentially |

**Flags:**

| Flag | Description |
|------|-------------|
| `-d, --duration <seconds>` | Benchmark duration (default: 60) |
| `-c, --concurrency <workers>` | Concurrent workers (default: 8) |
| `-q, --quick` | Quick mode: 5s duration, skip trace enrichment |
| `--skip-enrichment` | Skip trace/log fetching for faster results |

**History actions:**

| Action | Description |
|--------|-------------|
| `save` | Save current results indexed by git commit |
| `list` | List all stored benchmark commits |
| `show [sha]` | Show results for current or specific commit |
| `compare [sha]` | Compare current with previous or specific commit |

### Testing

| Command | Description |
|---------|-------------|
| `e2e` | Run end-to-end tests (counter value → alert mapping) |
| `send` | Send a test event to the system |

### Observability

| Command | Description |
|---------|-------------|
| `trace [id]` | Inspect distributed traces |
| `search [traceId]` | Search logs in Loki |

### Development

| Command | Description |
|---------|-------------|
| `shell <service>` | Enter container shell |
| `dev` | Start in development mode |

---

## Architecture: Delegation to Java Benchmarks

The CLI follows a deliberate architectural pattern for benchmarking. The Go CLI is an **orchestrator** that delegates actual measurement to Java code running in the same environment as the system under test.

```
┌─────────────────────────────────────────────────────────────────┐
│                        Go CLI (Orchestrator)                     │
│  • Compiles Java modules via Docker Maven                        │
│  • Resolves classpaths                                           │
│  • Invokes Java benchmark classes                                │
│  • Generates HTML reports from JSON results                      │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Java Benchmark Engine                         │
│  platform/src/main/java/com/reactive/platform/benchmark/        │
│  • BaseBenchmark.java - Base class with tracking logic           │
│  • BenchmarkResult.java - Result data structures                 │
│  • BenchmarkReportGenerator.java - JSON report generation        │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                  Benchmark Implementations                       │
│  • HttpBenchmark.java      - HTTP endpoint latency               │
│  • KafkaBenchmark.java     - Kafka round-trip                    │
│  • FlinkBenchmark.java     - Flink throughput                    │
│  • DroolsBenchmark.java    - Rule evaluation                     │
│  • GatewayBenchmark.java   - Gateway fire-and-forget             │
│  • FullBenchmark.java      - Complete E2E pipeline               │
└─────────────────────────────────────────────────────────────────┘
```

### Why Java Benchmarks?

The benchmarks are implemented in Java deliberately:

1. **Same Runtime Environment**: The benchmark code runs in the same JVM environment as the code being measured, eliminating cross-language overhead from measurements.

2. **Access to JVM Internals**: Java benchmarks can access JVM metrics (heap usage, GC pauses, thread counts) that would be opaque to an external tool.

3. **Trace Context Propagation**: The Java benchmarks use the same OpenTelemetry instrumentation as production code, ensuring trace IDs flow correctly through the system.

4. **Accurate Latency Measurement**: Measuring from within Java avoids network stack variations and gives true application-level latency.

### Benchmark Execution Flow

When you run `reactive bench full -d 30`:

1. **Find Docker network** - Locates `reactive-*-network`
2. **Compile platform module** - `mvn compile test-compile -Pbenchmark`
3. **Compile target module** - Same for `application/`
4. **Get classpath** - `mvn dependency:build-classpath`
5. **Run Java benchmark** - Executes `com.reactive.counter.benchmark.FullBenchmark`
6. **Generate HTML report** - Wraps JSON results in HTML with embedded JS visualizations

The Go CLI's role is purely orchestration. The actual benchmarking logic (sending requests, measuring latency, tracking throughput, enriching with traces/logs) is all done in Java.

---

## Project Structure

```
platform/cli/
├── cmd/
│   ├── root.go           # Root command and help text
│   ├── lifecycle.go      # start, stop, restart, rebuild, etc.
│   ├── diagnostics.go    # doctor, stats
│   ├── diagnose.go       # memory command (pressure, risk, heap, jfr)
│   ├── bench.go          # bench command with Docker Maven execution
│   ├── bench_doctor.go   # bench doctor subcommand
│   ├── bench_history.go  # bench history subcommand
│   ├── e2e.go            # e2e tests
│   ├── status.go         # status command
│   ├── trace.go          # trace inspection
│   ├── logs.go           # log searching
│   └── send.go           # send test events
├── main.go               # Entry point
├── go.mod
└── README.md             # This file
```

---

## Examples

```bash
# Check system health
reactive doctor

# Run a quick benchmark
reactive bench full --quick

# Run full benchmark suite with history tracking
reactive bench all -d 60
reactive bench history save

# Compare with previous run
reactive bench history compare

# Diagnose memory pressure before benchmarking
reactive memory pressure
reactive memory verdict

# Take a heap dump if issues detected
reactive memory heap drools

# Profile with Java Flight Recorder
reactive memory jfr flink-taskmanager 60

# Run end-to-end tests
reactive e2e
```

---

## Service URLs

| Service | URL |
|---------|-----|
| UI Portal | http://localhost:3000 |
| Gateway API | http://localhost:8080 |
| Jaeger (Traces) | http://localhost:16686 |
| Flink Dashboard | http://localhost:8081 |
| Grafana | http://localhost:3001 |
| Prometheus | http://localhost:9090 |
