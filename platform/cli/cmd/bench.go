package cmd

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"time"

	"github.com/spf13/cobra"
)

var (
	benchDuration       int
	benchConcurrency    int
	benchQuick          bool
	benchSkipEnrichment bool
)

var benchCmd = &cobra.Command{
	Use:   "bench <target>",
	Short: "Run performance benchmark",
	Long: `Run benchmark against:
  - http:    HTTP endpoint latency (health check)
  - kafka:   Kafka produce/consume round-trip
  - flink:   Flink stream processing throughput
  - drools:  Direct Drools rule evaluation
  - gateway: HTTP + Kafka publish (fire-and-forget)
  - full:    Complete E2E pipeline (HTTP → Kafka → Flink → Drools)
  - all:     Run all benchmarks sequentially

Examples:
  reactive bench drools              # 60s benchmark
  reactive bench full -d 30          # 30s benchmark
  reactive bench full --quick        # 5s quick benchmark
  reactive bench all                 # Run all components
  reactive bench drools -c 16        # 16 concurrent workers`,
	Args: cobra.ExactArgs(1),
	Run:  runBench,
}

func init() {
	benchCmd.Flags().IntVarP(&benchDuration, "duration", "d", 60, "Duration in seconds")
	benchCmd.Flags().IntVarP(&benchConcurrency, "concurrency", "c", 8, "Concurrent workers")
	benchCmd.Flags().BoolVarP(&benchQuick, "quick", "q", false, "Quick mode: 5s duration, skip enrichment")
	benchCmd.Flags().BoolVar(&benchSkipEnrichment, "skip-enrichment", false, "Skip trace/log fetching (faster)")
	rootCmd.AddCommand(benchCmd)
}

func runBench(cmd *cobra.Command, args []string) {
	target := args[0]

	// Validate target
	validTargets := []string{"http", "kafka", "flink", "drools", "gateway", "full", "all"}
	valid := false
	for _, t := range validTargets {
		if t == target {
			valid = true
			break
		}
	}
	if !valid {
		printError(fmt.Sprintf("Invalid target: %s", target))
		printInfo("Valid targets: http, kafka, flink, drools, gateway, full, all")
		return
	}

	start := time.Now()

	// Find project root
	projectRoot := findProjectRoot()
	if projectRoot == "" {
		printError("Project root not found")
		return
	}

	// Build arguments for the bash script
	scriptPath := projectRoot + "/scripts/run-benchmarks-java.sh"
	scriptArgs := []string{target}

	// Add flags
	if benchQuick {
		scriptArgs = append(scriptArgs, "--quick")
	} else {
		scriptArgs = append(scriptArgs, "--duration", fmt.Sprintf("%d", benchDuration))
		scriptArgs = append(scriptArgs, "--concurrency", fmt.Sprintf("%d", benchConcurrency))
		if benchSkipEnrichment {
			scriptArgs = append(scriptArgs, "--skip-enrichment")
		}
	}

	printHeader(fmt.Sprintf("Benchmark: %s", target))
	if benchQuick {
		printInfo("Mode: quick (5s, skip enrichment)")
	} else {
		printInfo(fmt.Sprintf("Duration: %ds | Concurrency: %d", benchDuration, benchConcurrency))
	}
	fmt.Println()

	// Run the benchmark script
	c := exec.Command(scriptPath, scriptArgs...)
	c.Stdout = os.Stdout
	c.Stderr = os.Stderr
	c.Dir = projectRoot
	err := c.Run()

	fmt.Println()
	if err != nil {
		printError("Benchmark failed")
	} else {
		printSuccess("Benchmark completed")
		printInfo(fmt.Sprintf("Reports: %s/reports/%s/index.html", projectRoot, target))
	}
	printInfo(fmt.Sprintf("Total time: %.2fs", time.Since(start).Seconds()))
}

func findProjectRoot() string {
	dir, _ := os.Getwd()
	for {
		if _, err := os.Stat(filepath.Join(dir, "docker-compose.yml")); err == nil {
			return dir
		}
		if _, err := os.Stat(filepath.Join(dir, "scripts", "run-benchmarks-java.sh")); err == nil {
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
