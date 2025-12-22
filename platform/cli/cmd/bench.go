package cmd

import (
	"fmt"
	"os"
	"os/exec"
	"strings"
	"time"

	"github.com/spf13/cobra"
)

var (
	benchDuration    int
	benchConcurrency int
)

var benchCmd = &cobra.Command{
	Use:   "bench <target>",
	Short: "Run performance benchmark",
	Long: `Run benchmark against:
  - drools: Direct Drools API
  - full: Complete pipeline (Gateway → Kafka → Flink → Drools)
  - gateway: Gateway HTTP endpoint

Examples:
  reactive bench drools            # 30s benchmark
  reactive bench full -d 60        # 60s benchmark
  reactive bench drools -d 30 -c 100  # 30s, 100 concurrent`,
	Args: cobra.ExactArgs(1),
	Run:  runBench,
}

func init() {
	benchCmd.Flags().IntVarP(&benchDuration, "duration", "d", 30, "Duration in seconds")
	benchCmd.Flags().IntVarP(&benchConcurrency, "concurrency", "c", 50, "Concurrent requests")
	rootCmd.AddCommand(benchCmd)
}

func runBench(cmd *cobra.Command, args []string) {
	target := args[0]

	// Validate target
	validTargets := []string{"drools", "full", "gateway"}
	valid := false
	for _, t := range validTargets {
		if t == target {
			valid = true
			break
		}
	}
	if !valid {
		printError(fmt.Sprintf("Invalid target: %s", target))
		printInfo("Valid targets: drools, full, gateway")
		return
	}

	start := time.Now()

	printHeader(fmt.Sprintf("Benchmark: %s", target))
	printInfo(fmt.Sprintf("Duration: %ds | Concurrency: %d", benchDuration, benchConcurrency))
	fmt.Println()

	// Find Docker network
	network := findDockerNetwork()
	if network == "" {
		printError("Docker network not found. Is the system running?")
		printInfo("Start with: reactive start")
		return
	}

	// Find project root
	projectRoot := findProjectRoot()
	if projectRoot == "" {
		printError("Project root not found")
		return
	}

	// Run benchmark in Docker
	dockerArgs := []string{
		"run", "--rm",
		"--network", network,
		"-e", "DROOLS_HOST=reactive-drools:8080",
		"-e", "GATEWAY_HOST=reactive-application:3000",
		"-v", fmt.Sprintf("%s/scripts:/scripts:ro", projectRoot),
		"eclipse-temurin:17-jdk",
		"java", "/scripts/Benchmark.java",
		target,
		fmt.Sprintf("%d", benchDuration),
		fmt.Sprintf("%d", benchConcurrency),
	}

	c := exec.Command("docker", dockerArgs...)
	c.Stdout = os.Stdout
	c.Stderr = os.Stderr
	err := c.Run()

	fmt.Println()
	if err != nil {
		printError("Benchmark failed")
	} else {
		printSuccess("Benchmark completed")
	}
	printInfo(fmt.Sprintf("Duration: %.2fs", time.Since(start).Seconds()))
}

func findDockerNetwork() string {
	c := exec.Command("docker", "network", "ls", "--format", "{{.Name}}")
	output, err := c.Output()
	if err != nil {
		return ""
	}

	lines := strings.Split(string(output), "\n")
	for _, line := range lines {
		if strings.Contains(line, "reactive") && strings.Contains(line, "network") {
			return strings.TrimSpace(line)
		}
	}
	return ""
}

func findProjectRoot() string {
	dir, _ := os.Getwd()
	for {
		if _, err := os.Stat(dir + "/docker-compose.yml"); err == nil {
			return dir
		}
		if _, err := os.Stat(dir + "/scripts/Benchmark.java"); err == nil {
			return dir
		}
		parent := dir[:strings.LastIndex(dir, "/")]
		if parent == dir {
			break
		}
		dir = parent
	}
	return ""
}
