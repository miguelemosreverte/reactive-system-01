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
	benchServersList     bool
	benchServersOnly     string
	benchServersExclude  string
	benchServersWorkers  int
	benchServersDuration int
	benchServersTier     string
)

var benchServersCmd = &cobra.Command{
	Use:   "servers",
	Short: "Benchmark HTTP server implementations",
	Long: `Benchmark all registered HTTP server implementations.

Uses the type-safe Server enum for server discovery and selection.

Server Tiers:
  MAXIMUM_THROUGHPUT  (600K+ req/s)  - ROCKET, BOSS_WORKER, IO_URING
  HIGH_THROUGHPUT     (500-600K req/s) - HYPER, RAW, TURBO
  SPECIALIZED         (300-500K req/s) - ULTRA, ZERO_COPY, ULTRA_FAST
  FRAMEWORK           (<300K req/s)    - FAST, NETTY, SPRING_BOOT

Examples:
  reactive bench servers --list                    # List all servers with tiers
  reactive bench servers                           # Benchmark all servers
  reactive bench servers -w 16 -d 10               # 16 workers, 10s duration
  reactive bench servers --only=ROCKET,BOSS_WORKER # Type-safe server selection
  reactive bench servers --tier=MAXIMUM_THROUGHPUT # Only fastest tier
  reactive bench servers --exclude=SPRING_BOOT    # Exclude slow servers`,
	Run: runBenchServers,
}

func init() {
	benchServersCmd.Flags().BoolVarP(&benchServersList, "list", "l", false, "List available servers without benchmarking")
	benchServersCmd.Flags().StringVar(&benchServersOnly, "only", "", "Only benchmark specific servers (comma-separated enum names)")
	benchServersCmd.Flags().StringVar(&benchServersExclude, "exclude", "", "Exclude specific servers (comma-separated enum names)")
	benchServersCmd.Flags().IntVarP(&benchServersWorkers, "workers", "w", 16, "Number of server workers")
	benchServersCmd.Flags().IntVarP(&benchServersDuration, "duration", "d", 6, "Test duration in seconds")
	benchServersCmd.Flags().StringVar(&benchServersTier, "tier", "", "Only benchmark servers in specific tier")
	benchCmd.AddCommand(benchServersCmd)
}

func runBenchServers(cmd *cobra.Command, args []string) {
	projectRoot := findProjectRoot()
	if projectRoot == "" {
		printError("Project root not found")
		return
	}

	// Check for wrk
	if _, err := exec.LookPath("wrk"); err != nil {
		printError("wrk benchmark tool not found")
		printInfo("Install with: brew install wrk (macOS) or apt install wrk (Linux)")
		return
	}

	printHeader("HTTP Server Benchmark Suite")
	fmt.Println()

	if benchServersList {
		listServers(projectRoot)
		return
	}

	// Step 1: Compile platform module with dependencies
	printInfo("Compiling platform module...")
	if !compilePlatformWithDependencies(projectRoot) {
		printError("Failed to compile platform module")
		return
	}

	// Step 2: Run BenchmarkRunner
	printInfo(fmt.Sprintf("Running benchmarks: %d workers, %ds duration", benchServersWorkers, benchServersDuration))
	fmt.Println()

	runBenchmarkRunner(projectRoot)
}

func listServers(projectRoot string) {
	printHeader("Registered HTTP Servers")
	fmt.Println()

	// Compile and run Server.main() to list servers
	if !compilePlatformWithDependencies(projectRoot) {
		printError("Failed to compile platform module")
		return
	}

	javaCmd := exec.Command("java",
		"-cp", "target/classes:target/dependency/*",
		"com.reactive.platform.http.server.Server",
	)
	javaCmd.Dir = projectRoot + "/platform"
	javaCmd.Stdout = os.Stdout
	javaCmd.Stderr = os.Stderr
	javaCmd.Run()
}

func compilePlatformWithDependencies(projectRoot string) bool {
	platformDir := projectRoot + "/platform"

	// Compile
	mvnCmd := exec.Command("mvn", "compile", "-q", "-DskipTests")
	mvnCmd.Dir = platformDir
	if output, err := mvnCmd.CombinedOutput(); err != nil {
		fmt.Println(string(output))
		return false
	}

	// Copy dependencies
	depCmd := exec.Command("mvn", "dependency:copy-dependencies", "-q")
	depCmd.Dir = platformDir
	if output, err := depCmd.CombinedOutput(); err != nil {
		fmt.Println(string(output))
		return false
	}

	return true
}

func runBenchmarkRunner(projectRoot string) {
	start := time.Now()
	platformDir := projectRoot + "/platform"

	// Build command
	args := []string{
		"--enable-native-access=ALL-UNNAMED",
		"-cp", "target/classes:target/dependency/*",
		"com.reactive.platform.http.server.BenchmarkRunner",
		"--workers", fmt.Sprintf("%d", benchServersWorkers),
		"--duration", fmt.Sprintf("%d", benchServersDuration),
		"--output", "benchmark-results",
	}

	if benchServersOnly != "" {
		args = append(args, "--servers", benchServersOnly)
	}
	if benchServersExclude != "" {
		args = append(args, "--exclude", benchServersExclude)
	}
	if benchServersTier != "" {
		args = append(args, "--tier", benchServersTier)
	}

	javaCmd := exec.Command("java", args...)
	javaCmd.Dir = platformDir
	javaCmd.Stdout = os.Stdout
	javaCmd.Stderr = os.Stderr

	if err := javaCmd.Run(); err != nil {
		printError(fmt.Sprintf("Benchmark failed: %v", err))
		return
	}

	fmt.Println()
	printSuccess("HTTP server benchmarks completed")
	printInfo(fmt.Sprintf("Total time: %.2fs", time.Since(start).Seconds()))
	printInfo(fmt.Sprintf("Results: %s/platform/benchmark-results/RANKING.md", projectRoot))
}

// benchGatewayWithServerCmd benchmarks the gateway with a specific server implementation
var benchGatewayServerCmd = &cobra.Command{
	Use:   "gateway-server",
	Short: "Benchmark gateway with specific HTTP server",
	Long: `Benchmark the gateway using a specific HTTP server implementation.

Uses the Server enum for type-safe server selection.

Examples:
  reactive bench gateway-server --server=ROCKET
  reactive bench gateway-server --server=SPRING_BOOT
  reactive bench gateway-server --server=all  # Compare all implementations`,
	Run: runBenchGatewayWithServer,
}

var benchGatewayServerImpl string

func init() {
	benchGatewayServerCmd.Flags().StringVar(&benchGatewayServerImpl, "server", "ROCKET", "Server enum value (e.g., ROCKET, BOSS_WORKER)")
	benchCmd.AddCommand(benchGatewayServerCmd)
}

func runBenchGatewayWithServer(cmd *cobra.Command, args []string) {
	projectRoot := findProjectRoot()
	if projectRoot == "" {
		printError("Project root not found")
		return
	}

	printHeader(fmt.Sprintf("Gateway Benchmark with %s", benchGatewayServerImpl))
	fmt.Println()

	if strings.ToLower(benchGatewayServerImpl) == "all" {
		// Run gateway benchmark with all server implementations
		servers := []string{"ROCKET", "BOSS_WORKER", "FAST"}

		for _, server := range servers {
			fmt.Printf("\n%s %s %s\n\n", strings.Repeat("━", 20), server, strings.Repeat("━", 20))
			runGatewayWithServer(projectRoot, server)
		}
	} else {
		runGatewayWithServer(projectRoot, benchGatewayServerImpl)
	}
}

func runGatewayWithServer(projectRoot, serverImpl string) {
	// Convert to enum format (uppercase)
	serverImpl = strings.ToUpper(serverImpl)

	printInfo(fmt.Sprintf("Using HTTP_SERVER_IMPL=%s", serverImpl))

	// Set environment variable for GatewayFactory
	os.Setenv("HTTP_SERVER_IMPL", serverImpl)
	defer os.Unsetenv("HTTP_SERVER_IMPL")

	printInfo("Gateway benchmark with pluggable servers ready for implementation")
	printInfo(fmt.Sprintf("Server: %s (type-safe enum)", serverImpl))
	printInfo("The gateway would use GatewayFactory.fromEnv() to select the server")
}
