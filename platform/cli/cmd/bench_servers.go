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
	benchServersList    bool
	benchServersOnly    string
	benchServersExclude string
	benchServersWorkers int
	benchServersDuration int
)

var benchServersCmd = &cobra.Command{
	Use:   "servers",
	Short: "Benchmark HTTP server implementations",
	Long: `Benchmark all registered HTTP server implementations.

This command uses the pluggable server architecture to benchmark different
HTTP server implementations and rank them by throughput.

Available Servers:
  - IoUringServer        (io_uring + FFM, Linux only)
  - RocketHttpServer     (NIO + SO_REUSEPORT)
  - BossWorkerHttpServer (NIO Boss/Worker pattern)
  - HyperHttpServer      (NIO + Pipelining)
  - RawHttpServer        (NIO Multi-EventLoop)
  - TurboHttpServer      (NIO + selectNow)
  - UltraHttpServer      (NIO Minimal)
  - ZeroCopyHttpServer   (NIO + Busy-poll)
  - UltraFastHttpServer  (Single-thread)
  - FastHttpServer       (Virtual Threads)
  - NettyHttpServer      (Netty NIO)
  - SpringBootHttpServer (Spring WebFlux)

Examples:
  reactive bench servers --list              # List all registered servers
  reactive bench servers                     # Benchmark all servers
  reactive bench servers -w 16 -d 10         # 16 workers, 10s duration
  reactive bench servers --only=Rocket,Boss  # Only specific servers
  reactive bench servers --exclude=Spring    # Exclude slow servers`,
	Run: runBenchServers,
}

func init() {
	benchServersCmd.Flags().BoolVarP(&benchServersList, "list", "l", false, "List available servers without benchmarking")
	benchServersCmd.Flags().StringVar(&benchServersOnly, "only", "", "Only benchmark specific servers (comma-separated)")
	benchServersCmd.Flags().StringVar(&benchServersExclude, "exclude", "", "Exclude specific servers (comma-separated)")
	benchServersCmd.Flags().IntVarP(&benchServersWorkers, "workers", "w", 16, "Number of server workers")
	benchServersCmd.Flags().IntVarP(&benchServersDuration, "duration", "d", 6, "Test duration in seconds")
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

	// Step 1: Compile platform module with dependencies
	printHeader("HTTP Server Benchmark Suite")
	fmt.Println()

	if benchServersList {
		listServers(projectRoot)
		return
	}

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

	// Compile and run GatewayFactory to list servers
	if !compilePlatformWithDependencies(projectRoot) {
		printError("Failed to compile platform module")
		return
	}

	javaCmd := exec.Command("java",
		"-cp", "target/classes:target/dependency/*",
		"com.reactive.platform.http.server.GatewayFactory",
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
	Long: `Benchmark the gateway component using a specific HTTP server implementation.

This allows comparing how different server implementations affect gateway throughput.

Examples:
  reactive bench gateway-server --server=RocketHttpServer
  reactive bench gateway-server --server=SpringBootHttpServer
  reactive bench gateway-server --server=all  # Compare all implementations`,
	Run: runBenchGatewayWithServer,
}

var benchGatewayServerImpl string

func init() {
	benchGatewayServerCmd.Flags().StringVar(&benchGatewayServerImpl, "server", "RocketHttpServer", "HTTP server implementation to use")
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

	if benchGatewayServerImpl == "all" {
		// Run gateway benchmark with all server implementations
		servers := []string{
			"RocketHttpServer",
			"BossWorkerHttpServer",
			"FastHttpServer",
		}

		for _, server := range servers {
			fmt.Printf("\n%s %s %s\n\n", strings.Repeat("━", 20), server, strings.Repeat("━", 20))
			runGatewayWithServer(projectRoot, server)
		}
	} else {
		runGatewayWithServer(projectRoot, benchGatewayServerImpl)
	}
}

func runGatewayWithServer(projectRoot, serverImpl string) {
	printInfo(fmt.Sprintf("Using HTTP_SERVER_IMPL=%s", serverImpl))

	// Set environment variable for GatewayFactory
	os.Setenv("HTTP_SERVER_IMPL", serverImpl)
	defer os.Unsetenv("HTTP_SERVER_IMPL")

	// For now, just show what would be done
	// Full implementation would start the gateway with the specified server
	// and run wrk against it
	printInfo("Gateway benchmark with pluggable servers is ready for implementation")
	printInfo(fmt.Sprintf("Server: %s", serverImpl))
	printInfo("The gateway would use GatewayFactory.createServerFromEnv() to select the server")
}
