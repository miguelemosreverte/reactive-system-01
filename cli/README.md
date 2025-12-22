# Reactive System CLI

A Go-based CLI for managing the Reactive System.

## Installation

```bash
# Requires Go 1.21+
brew install go  # macOS

# Run via wrapper (auto-compiles)
./cli.sh <command>

# Or build binary
cd cli && go build -o reactive .
```

## Usage

### Interactive Mode (REPL)

```bash
./cli.sh
# or
./cli.sh repl
```

Use arrow keys to navigate:
- ↑/↓: Move selection
- →/Enter: Select/enter submenu
- ←: Go back
- q: Quit

### Command Mode

```bash
./cli.sh <command> [args]
```

## Commands

### Lifecycle (Most Used)

| Command | Description |
|---------|-------------|
| `start [service]` | Start all or specific service |
| `stop [service]` | Stop services |
| `restart [service]` | Restart without rebuild |
| `rebuild <service>` | Rebuild and restart |
| `status` | Show service health |
| `down` | Stop and remove containers |
| `clean` | Remove everything |

### Diagnostics

| Command | Description |
|---------|-------------|
| `doctor` | Comprehensive health check |
| `stats` | Container CPU/memory usage |
| `diagnose [subcmd]` | Memory diagnostics |

Diagnose subcommands:
- `pressure` - Visual pressure bars
- `risk` - Risk assessment
- `crashes` - Crash history
- `kpis` - Stability KPIs
- `verdict` - Actionable conclusions

### Benchmarking

| Command | Description |
|---------|-------------|
| `bench drools` | Benchmark Drools API |
| `bench full` | Benchmark full pipeline |
| `bench gateway` | Benchmark Gateway |

Options:
- `-d, --duration` - Duration in seconds (default: 30)
- `-c, --concurrency` - Concurrent requests (default: 50)

### Observability

| Command | Description |
|---------|-------------|
| `trace [id]` | List or inspect traces |
| `logs [traceId]` | Search logs in Loki |

### Development

| Command | Description |
|---------|-------------|
| `dev` | Start in dev mode |
| `shell <service>` | Enter container shell |
| `follow <service>` | Follow Docker logs |

## URLs

| Service | URL |
|---------|-----|
| UI Portal | http://localhost:3000 |
| Gateway API | http://localhost:8080 |
| Jaeger | http://localhost:16686 |
| Flink | http://localhost:8081 |
| Grafana | http://localhost:3001 |

## Usage Tracking

Commands are tracked locally in `.usage.db` (not committed).
Run `./cli.sh stats-usage` to see your most-used commands.

---

## Command Popularity

*This section is auto-generated based on local usage.*

<!-- USAGE_STATS_START -->
No usage data yet. Start using commands to build your ranking!
<!-- USAGE_STATS_END -->
