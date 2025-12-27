package cmd

import (
	"fmt"
	"os"
	"os/exec"
	"time"

	"github.com/spf13/cobra"
)

var (
	microbatchMode       string
	microbatchDuration   int
	microbatchConcurrency int
)

var benchMicrobatchCmd = &cobra.Command{
	Use:   "microbatch",
	Short: "Benchmark adaptive microbatching gateway",
	Long: `Benchmark the adaptive microbatching gateway components.

The microbatching gateway optimizes throughput by collecting HTTP requests
and sending them to Kafka in optimal batches. It dynamically calibrates
batch size and flush interval based on observed performance.

Modes:
  collector   - Benchmark MicrobatchCollector (no network, pure throughput)
  gateway     - Benchmark full HTTP + Kafka gateway
  calibration - Test calibration convergence over time

Examples:
  reactive bench microbatch                    # Default: collector mode, 10s
  reactive bench microbatch -m collector -d 30 # 30s collector benchmark
  reactive bench microbatch -m gateway -c 200  # Gateway with 200 concurrent clients
  reactive bench microbatch -m calibration     # Watch calibration converge

The gateway uses:
  - RocketHttpServer (600K+ req/s capability)
  - Adaptive batch sizing (learns optimal configuration)
  - SQLite persistence (remembers calibration across restarts)`,
	Run: runBenchMicrobatch,
}

func init() {
	benchMicrobatchCmd.Flags().StringVarP(&microbatchMode, "mode", "m", "collector", "Benchmark mode: collector, gateway, calibration")
	benchMicrobatchCmd.Flags().IntVarP(&microbatchDuration, "duration", "d", 10, "Duration in seconds")
	benchMicrobatchCmd.Flags().IntVarP(&microbatchConcurrency, "concurrency", "c", 100, "Number of concurrent workers")
	benchCmd.AddCommand(benchMicrobatchCmd)
}

func runBenchMicrobatch(cmd *cobra.Command, args []string) {
	projectRoot := findProjectRoot()
	if projectRoot == "" {
		printError("Project root not found")
		return
	}

	printHeader("Microbatch Benchmark")
	fmt.Println()
	printInfo(fmt.Sprintf("Mode: %s | Duration: %ds | Concurrency: %d",
		microbatchMode, microbatchDuration, microbatchConcurrency))
	fmt.Println()

	start := time.Now()

	// Step 1: Compile platform module
	printInfo("Compiling platform module...")
	if !compilePlatformWithDependencies(projectRoot) {
		printError("Failed to compile platform module")
		return
	}

	// Step 2: Run benchmark
	printInfo("Running benchmark...")
	runMicrobatchBenchmark(projectRoot)

	fmt.Println()
	printSuccess("Benchmark completed")
	printInfo(fmt.Sprintf("Total time: %.2fs", time.Since(start).Seconds()))
}

func runMicrobatchBenchmark(projectRoot string) {
	platformDir := projectRoot + "/platform"

	args := []string{
		"--enable-native-access=ALL-UNNAMED",
		"-cp", "target/classes:target/dependency/*",
		"com.reactive.platform.gateway.microbatch.MicrobatchBenchmark",
		microbatchMode,
		fmt.Sprintf("%d", microbatchDuration),
		fmt.Sprintf("%d", microbatchConcurrency),
	}

	javaCmd := exec.Command("java", args...)
	javaCmd.Dir = platformDir
	javaCmd.Stdout = os.Stdout
	javaCmd.Stderr = os.Stderr

	if err := javaCmd.Run(); err != nil {
		printError(fmt.Sprintf("Benchmark failed: %v", err))
	}
}

// benchGatewayMicrobatchCmd compares regular gateway vs microbatching gateway
var benchGatewayCompareCmd = &cobra.Command{
	Use:   "gateway-compare",
	Short: "Compare regular vs microbatching gateway",
	Long: `Compare throughput between regular and microbatching gateway implementations.

This benchmark runs the same load against both gateway types and shows
the throughput improvement from adaptive microbatching.

The test:
1. Starts regular FastGateway
2. Benchmarks with wrk
3. Starts MicrobatchingGateway
4. Benchmarks with wrk
5. Shows comparison

Requires: wrk, running Kafka`,
	Run: runBenchGatewayCompare,
}

func init() {
	benchCmd.AddCommand(benchGatewayCompareCmd)
}

func runBenchGatewayCompare(cmd *cobra.Command, args []string) {
	// Check for wrk
	if _, err := exec.LookPath("wrk"); err != nil {
		printError("wrk benchmark tool not found")
		printInfo("Install with: brew install wrk (macOS) or apt install wrk (Linux)")
		return
	}

	printHeader("Gateway Comparison Benchmark")
	fmt.Println()
	printInfo("Comparing regular FastGateway vs MicrobatchingGateway")
	fmt.Println()

	// This would be implemented to actually start/stop gateways and run wrk
	printWarning("Full implementation requires running Kafka and gateway containers")
	printInfo("Use 'reactive bench gateway' for gateway-only benchmarks")
	printInfo("Use 'reactive bench microbatch -m gateway' for microbatch benchmarks")
}

// Add comprehensive help about benchmarks
var benchHelpCmd = &cobra.Command{
	Use:   "help",
	Short: "Show detailed benchmark help",
	Long:  `Show detailed help about benchmark commands and strategies.`,
	Run: func(cmd *cobra.Command, args []string) {
		fmt.Println(`
╔══════════════════════════════════════════════════════════════════════════════╗
║                         BENCHMARK GUIDE                                       ║
╚══════════════════════════════════════════════════════════════════════════════╝

ISOLATION BENCHMARKS (Component Theoretical Maximum)
────────────────────────────────────────────────────
These benchmarks measure the maximum throughput of individual components
in isolation, without network or downstream dependencies.

  reactive bench http             HTTP server throughput (GET /health)
  reactive bench kafka-producer   Kafka producer throughput (fire-and-forget)
  reactive bench servers          Compare all HTTP server implementations
  reactive bench servers --list   List available HTTP servers with tiers

HTTP Server Tiers:
  MAXIMUM_THROUGHPUT  (600K+ req/s)   - ROCKET, BOSS_WORKER, IO_URING
  HIGH_THROUGHPUT     (500-600K req/s) - HYPER, RAW, TURBO
  SPECIALIZED         (300-500K req/s) - ULTRA, ZERO_COPY, ULTRA_FAST
  FRAMEWORK           (<300K req/s)    - FAST, NETTY, SPRING_BOOT

INTEGRATION BENCHMARKS (End-to-End Paths)
─────────────────────────────────────────
These benchmarks measure full paths through the system, including
network, serialization, and downstream processing.

  reactive bench kafka            Kafka produce + consume round-trip
  reactive bench flink            Flink stream processing
  reactive bench drools           Drools rule evaluation
  reactive bench gateway          HTTP → Kafka gateway
  reactive bench full             Complete E2E pipeline

MICROBATCHING BENCHMARKS
────────────────────────
Test the adaptive microbatching gateway that optimizes throughput
by dynamically finding optimal batch sizes.

  reactive bench microbatch -m collector    Collector throughput (no network)
  reactive bench microbatch -m gateway      Full HTTP + Kafka gateway
  reactive bench microbatch -m calibration  Watch calibration convergence

AGGREGATE BENCHMARKS
────────────────────
  reactive bench all              Run all benchmarks sequentially

OPTIONS
───────
  -d, --duration <seconds>        Test duration (default: 60, quick: 5)
  -c, --concurrency <workers>     Concurrent workers (default: 8)
  -q, --quick                     Quick mode (5s, skip enrichment)
  --skip-enrichment               Skip trace/log fetching

EXAMPLES
────────
  reactive bench http                    # Quick HTTP check
  reactive bench kafka-producer -d 30    # 30s Kafka producer test
  reactive bench full --quick            # Fast E2E verification
  reactive bench servers --tier=MAXIMUM_THROUGHPUT  # Only fastest servers
  reactive bench microbatch -m collector -c 200     # High concurrency test

REPORTS
───────
Reports are saved to ./reports/<target>/
  index.html    Interactive HTML report
  results.json  Raw data for analysis

Open dashboard: open reports/index.html
`)
	},
}

func init() {
	benchCmd.AddCommand(benchHelpCmd)
}
