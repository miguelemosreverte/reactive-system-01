package cmd

import (
	"github.com/spf13/cobra"
)

var rootCmd = &cobra.Command{
	Use:   "reactive",
	Short: "Reactive System CLI",
	Long: `Reactive System CLI - Manage and debug your reactive system.

Quick Commands:
  reactive start              Start all services
  reactive stop               Stop all services
  reactive status             Show service status

Debugging:
  reactive trace <id>         Inspect a trace across all services
  reactive logs <traceId>     Find logs for a specific trace
  reactive send               Send a test event

Benchmarks:
  reactive benchmark run       Run all benchmarks
  reactive benchmark report    View benchmark reports
  reactive benchmark build-ui  Build React UI assets`,
}

func Execute() error {
	return rootCmd.Execute()
}

func init() {
	// Add subcommands
	rootCmd.AddCommand(traceCmd)
	rootCmd.AddCommand(logsCmd)
	rootCmd.AddCommand(sendCmd)
	rootCmd.AddCommand(statusCmd)
	rootCmd.AddCommand(benchmarkCmd)
}
