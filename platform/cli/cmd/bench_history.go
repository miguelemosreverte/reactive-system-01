package cmd

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"

	"github.com/spf13/cobra"
)

var historyCmd = &cobra.Command{
	Use:   "history <action>",
	Short: "Manage benchmark history and regression testing",
	Long: `Manage benchmark history indexed by git commit.

Actions:
  save              Save current benchmark results to history
  compare [sha]     Compare with previous or specific commit
  list              List all stored benchmark commits
  show <sha>        Show results for a specific commit
  report            Generate regression report

Examples:
  reactive bench history save           # Save current results
  reactive bench history compare        # Compare with last commit
  reactive bench history compare abc123 # Compare with specific commit
  reactive bench history list           # List all benchmarks
  reactive bench history report         # Generate regression report`,
	Args: cobra.MinimumNArgs(1),
	Run:  runBenchHistory,
}

func init() {
	benchCmd.AddCommand(historyCmd)
}

func runBenchHistory(cmd *cobra.Command, args []string) {
	action := args[0]

	validActions := []string{"save", "compare", "list", "show", "report"}
	valid := false
	for _, a := range validActions {
		if a == action {
			valid = true
			break
		}
	}
	if !valid {
		printError(fmt.Sprintf("Invalid action: %s", action))
		printInfo("Valid actions: save, compare, list, show, report")
		return
	}

	projectRoot := findProjectRoot()
	if projectRoot == "" {
		printError("Project root not found")
		return
	}

	scriptPath := filepath.Join(projectRoot, "scripts", "benchmark-history.sh")
	scriptArgs := args // Pass all args including action

	c := exec.Command(scriptPath, scriptArgs...)
	c.Stdout = os.Stdout
	c.Stderr = os.Stderr
	c.Dir = projectRoot
	err := c.Run()

	if err != nil {
		printError(fmt.Sprintf("History command failed: %v", err))
	}
}
