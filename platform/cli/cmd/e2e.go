package cmd

import (
	"os"
	"os/exec"
	"path/filepath"

	"github.com/spf13/cobra"
)

var e2eCmd = &cobra.Command{
	Use:   "e2e",
	Short: "Run end-to-end tests",
	Long: `Run end-to-end tests to validate the complete system.

Tests include:
  - Counter value → alert mapping (WARNING, CRITICAL, RESET)
  - WebSocket connectivity
  - API endpoint health

Examples:
  reactive e2e     # Run all E2E tests`,
	Run: runE2E,
}

func init() {
	rootCmd.AddCommand(e2eCmd)
}

func runE2E(cmd *cobra.Command, args []string) {
	printHeader("End-to-End Tests")

	projectRoot := findProjectRoot()
	if projectRoot == "" {
		printError("Project root not found")
		return
	}

	scriptPath := filepath.Join(projectRoot, "scripts", "e2e-test.sh")

	c := exec.Command(scriptPath)
	c.Stdout = os.Stdout
	c.Stderr = os.Stderr
	c.Dir = projectRoot
	err := c.Run()

	if err != nil {
		printError("E2E tests failed")
		os.Exit(1)
	}
}
