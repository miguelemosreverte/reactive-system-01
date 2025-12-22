package cmd

import (
	"github.com/spf13/cobra"
)

var rootCmd = &cobra.Command{
	Use:   "reactive",
	Short: "Reactive System CLI",
	Long: `Reactive System CLI - Manage your reactive system.

LIFECYCLE
  start [service]     Start all services or a specific one
  stop [service]      Stop services
  restart [service]   Restart without rebuild
  rebuild <service>   Rebuild and restart
  logs <service>      Follow service logs
  status              Show service health
  down                Stop and remove containers
  clean               Remove everything

DIAGNOSTICS
  doctor              Comprehensive health check
  stats               Container resource usage
  diagnose [subcmd]   Memory/performance diagnostics

BENCHMARKING
  bench <target>      Run benchmark (drools, full, gateway)

OBSERVABILITY
  trace [id]          Inspect distributed traces
  search [traceId]    Search logs in Loki

TESTING
  send                Send test event

DEVELOPMENT
  shell <service>     Enter container shell
  dev                 Start in development mode

URLS
  http://localhost:3000    UI Portal
  http://localhost:8080    Gateway API
  http://localhost:16686   Jaeger (Traces)
  http://localhost:8081    Flink Dashboard`,
}

func Execute() error {
	return rootCmd.Execute()
}

func init() {
	// Observability commands (defined in other files via their init())
	rootCmd.AddCommand(traceCmd)
	rootCmd.AddCommand(logsCmd)
	rootCmd.AddCommand(sendCmd)

	// Note: Other commands are added via init() in their respective files:
	// - lifecycle.go: start, stop, restart, rebuild, down, clean, logs, dev, shell
	// - diagnostics.go: doctor, stats, diagnose
	// - bench.go: bench
	// - status.go: status
	// - benchmark.go: benchmark (old, to be deprecated)
}
