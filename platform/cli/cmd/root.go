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
  memory [subcmd]     Memory diagnostics (overview, jvm, pressure, risk, verdict)

BENCHMARKING
  bench <target>      Run benchmark (http, kafka, flink, drools, gateway, full, all)
    bench doctor      Validate observability chain
    bench history     Manage benchmark history (save, list, show, compare)

TESTING
  e2e                 Run end-to-end tests
  send                Send test event

OBSERVABILITY
  trace [id]          Inspect distributed traces
  search [traceId]    Search logs in Loki
  replay <session>    Replay events with full tracing (for debugging)
    replay events     List stored events
    replay history    Show state transitions

CONFIGURATION
  config show         Display configuration summary
  config validate     Check for errors/warnings
  config env          Generate .env file from HOCON
  config json         Export as JSON

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
	rootCmd.AddCommand(replayCmd)

	// Note: Other commands are added via init() in their respective files:
	// - lifecycle.go: start, stop, restart, rebuild, down, clean, logs, dev, shell
	// - diagnostics.go: doctor, stats
	// - diagnose.go: memory
	// - bench.go: bench (with subcommands: doctor, history)
	// - config.go: config (with subcommands: show, validate, env, json)
	// - e2e.go: e2e
	// - status.go: status
}
