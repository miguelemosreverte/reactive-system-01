package cmd

import (
	"os"
	"path/filepath"
	"time"

	"github.com/reactive-system/cli/internal/usage"
	"github.com/spf13/cobra"
)

var (
	commandStart time.Time
	tracker      *usage.Tracker
)

func init() {
	// Initialize usage tracker (ignore errors - tracking is optional)
	tracker, _ = usage.New()

	// Add pre-run hook to all commands
	cobra.OnInitialize(initTracking)
}

func initTracking() {
	commandStart = time.Now()

	// Register post-run hook on root
	if rootCmd.PersistentPostRun == nil {
		rootCmd.PersistentPostRun = func(cmd *cobra.Command, args []string) {
			recordUsage(cmd, args, true)
		}
	}
}

func recordUsage(cmd *cobra.Command, args []string, success bool) {
	if tracker == nil {
		return
	}

	duration := time.Since(commandStart)
	commandName := cmd.Name()

	// Include parent command for subcommands
	if cmd.Parent() != nil && cmd.Parent() != rootCmd {
		commandName = cmd.Parent().Name() + " " + commandName
	}

	tracker.Record(commandName, args, duration, success)
}

// GenerateREADME generates CLI README with usage stats
func GenerateREADME() error {
	if tracker == nil {
		return nil
	}

	readme, err := tracker.GenerateReadme()
	if err != nil {
		return err
	}

	// Find CLI directory
	cliDir := findCLIDir()
	if cliDir == "" {
		return nil
	}

	readmePath := filepath.Join(cliDir, "USAGE.md")
	return os.WriteFile(readmePath, []byte(readme), 0644)
}

func findCLIDir() string {
	dir, _ := os.Getwd()
	for {
		if _, err := os.Stat(filepath.Join(dir, "cli", "go.mod")); err == nil {
			return filepath.Join(dir, "cli")
		}
		if _, err := os.Stat(filepath.Join(dir, "go.mod")); err == nil {
			// We're in the CLI dir
			return dir
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			break
		}
		dir = parent
	}
	return ""
}

// GetTopCommands returns top commands for help ordering
func GetTopCommands(n int) []string {
	if tracker == nil {
		return usage.DefaultRanking()
	}

	top, err := tracker.GetTopCommands(n)
	if err != nil || len(top) == 0 {
		return usage.DefaultRanking()
	}

	return usage.MergeRankings(top, usage.DefaultRanking())
}
