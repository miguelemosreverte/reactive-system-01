package cmd

import (
	"os"
	"os/exec"
	"path/filepath"

	"github.com/spf13/cobra"
)

var (
	benchDoctorJSON bool
	benchDoctorAPI  bool
)

var benchDoctorCmd = &cobra.Command{
	Use:   "doctor",
	Short: "Validate observability chain for benchmarks",
	Long: `Diagnose benchmark observability setup.

Checks:
  - Service health (Jaeger, Loki, application services)
  - Trace propagation across services
  - Log correlation with trace IDs
  - Component coverage in traces

Examples:
  reactive bench doctor          # Interactive colored output
  reactive bench doctor --json   # JSON output for programmatic use
  reactive bench doctor --api    # Use diagnostic API endpoint`,
	Run: runBenchDoctor,
}

func init() {
	benchDoctorCmd.Flags().BoolVar(&benchDoctorJSON, "json", false, "Output as JSON")
	benchDoctorCmd.Flags().BoolVar(&benchDoctorAPI, "api", false, "Use diagnostic API endpoint")
	benchCmd.AddCommand(benchDoctorCmd)
}

func runBenchDoctor(cmd *cobra.Command, args []string) {
	projectRoot := findProjectRoot()
	if projectRoot == "" {
		printError("Project root not found")
		return
	}

	scriptPath := filepath.Join(projectRoot, "scripts", "benchmark-doctor.sh")
	var scriptArgs []string

	if benchDoctorJSON {
		scriptArgs = append(scriptArgs, "--json")
	}
	if benchDoctorAPI {
		scriptArgs = append(scriptArgs, "--api")
	}

	c := exec.Command(scriptPath, scriptArgs...)
	c.Stdout = os.Stdout
	c.Stderr = os.Stderr
	c.Dir = projectRoot
	err := c.Run()

	if err != nil && !benchDoctorJSON {
		printError("Benchmark doctor found issues")
	}
}
