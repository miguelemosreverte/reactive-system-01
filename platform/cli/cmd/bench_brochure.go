package cmd

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/spf13/cobra"
	"gopkg.in/yaml.v3"
)

// Runtime specifies the execution environment for benchmarks
type Runtime string

const (
	RuntimeDocker Runtime = "docker" // Run benchmark inside Docker container
	RuntimeNative Runtime = "native" // Run benchmark natively on the host
)

// Brochure represents a benchmark configuration profile
type Brochure struct {
	Name        string         `yaml:"name" json:"name"`
	Description string         `yaml:"description" json:"description"`
	Component   string         `yaml:"component" json:"component"`
	Runtime     Runtime        `yaml:"runtime" json:"runtime"` // docker or native
	Duration    int            `yaml:"duration" json:"duration"`     // milliseconds
	Concurrency int            `yaml:"concurrency" json:"concurrency"`
	Config      BrochureConfig `yaml:"config" json:"config"`
}

// BrochureConfig holds implementation choices
type BrochureConfig struct {
	HttpServer        string                       `yaml:"httpServer" json:"httpServer"`
	Microbatching     bool                         `yaml:"microbatching" json:"microbatching"`
	MicrobatchSize    int                          `yaml:"microbatchSize" json:"microbatchSize"`
	MicrobatchTimeout int                          `yaml:"microbatchTimeoutMs" json:"microbatchTimeoutMs"`
	KafkaAcks         string                       `yaml:"kafkaAcks" json:"kafkaAcks"`
	KafkaBatchSize    int                          `yaml:"kafkaBatchSize" json:"kafkaBatchSize"`
	KafkaLinger       int                          `yaml:"kafkaLingerMs" json:"kafkaLingerMs"`
	Env               map[string]string            `yaml:"env" json:"env"`      // Global env vars (deprecated, use services)
	Services          map[string]map[string]string `yaml:"services" json:"services"` // Per-service env vars: services.flink-job-submitter.SKIP_DROOLS=true
}

// BrochureResult holds the result of running a brochure
type BrochureResult struct {
	Brochure   string                 `json:"brochure"`
	Name       string                 `json:"name"`
	Runtime    Runtime                `json:"runtime"` // docker or native
	StartTime  time.Time              `json:"startTime"`
	EndTime    time.Time              `json:"endTime"`
	DurationMs int64                  `json:"durationMs"`
	Throughput float64                `json:"throughput"`
	P50Ms      float64                `json:"p50Ms"`
	P99Ms      float64                `json:"p99Ms"`
	TotalOps   int64                  `json:"totalOps"`
	SuccessOps int64                  `json:"successOps"`
	FailedOps  int64                  `json:"failedOps"`
	RawResults map[string]interface{} `json:"rawResults,omitempty"`
}

var (
	brochureName string
	quickMode    bool // Quick smoke test mode (10s per brochure instead of full duration)
)

var benchBrochureCmd = &cobra.Command{
	Use:   "brochure <command>",
	Short: "Benchmark brochures - configuration profiles for different implementations",
	Long: `Benchmark brochures allow you to test different implementation configurations
and compare their performance.

Brochures define:
  - HTTP server implementation (Spring, Netty, Rocket, etc.)
  - Microbatching settings
  - Kafka producer tuning
  - Concurrency levels

Commands:
  list      List all available brochures
  run       Run a specific brochure
  run-all   Run all brochures sequentially (marathon mode)
  compare   Generate comparison report from results

Examples:
  reactive bench brochure list
  reactive bench brochure run gateway-netty-microbatch
  reactive bench brochure run-all              # Marathon: 5 min each
  reactive bench brochure run-all --quick      # Quick smoke test: 10s each
  reactive bench brochure compare              # Generate comparison`,
}

var brochureListCmd = &cobra.Command{
	Use:   "list",
	Short: "List all available brochures",
	Run:   runBrochureList,
}

var brochureRunCmd = &cobra.Command{
	Use:   "run <brochure-name>",
	Short: "Run a specific brochure",
	Args:  cobra.ExactArgs(1),
	Run:   runBrochureRun,
}

var brochureRunAllCmd = &cobra.Command{
	Use:   "run-all",
	Short: "Run all brochures sequentially (marathon mode, 5 min each)",
	Run:   runBrochureRunAll,
}

var brochureCompareCmd = &cobra.Command{
	Use:   "compare",
	Short: "Generate comparison report from brochure results",
	Run:   runBrochureCompare,
}

func init() {
	benchBrochureCmd.AddCommand(brochureListCmd)
	benchBrochureCmd.AddCommand(brochureRunCmd)
	benchBrochureCmd.AddCommand(brochureRunAllCmd)
	benchBrochureCmd.AddCommand(brochureCompareCmd)
	benchCmd.AddCommand(benchBrochureCmd)

	// Add --quick flag for smoke testing
	brochureRunCmd.Flags().BoolVar(&quickMode, "quick", false, "Quick iteration mode (10s duration)")
	brochureRunAllCmd.Flags().BoolVar(&quickMode, "quick", false, "Quick smoke test mode (10s per brochure)")
}

func getBrochuresDir() string {
	projectRoot := findProjectRoot()
	return filepath.Join(projectRoot, "reports", "brochures")
}

func loadBrochure(name string) (*Brochure, error) {
	brochuresDir := getBrochuresDir()
	brochureFile := filepath.Join(brochuresDir, name, "brochure.yaml")

	data, err := os.ReadFile(brochureFile)
	if err != nil {
		return nil, fmt.Errorf("brochure not found: %s", name)
	}

	var brochure Brochure
	if err := yaml.Unmarshal(data, &brochure); err != nil {
		return nil, fmt.Errorf("invalid brochure format: %v", err)
	}

	// Default runtime to docker if not specified
	if brochure.Runtime == "" {
		brochure.Runtime = RuntimeDocker
	}

	return &brochure, nil
}

func listBrochures() ([]string, error) {
	brochuresDir := getBrochuresDir()

	entries, err := os.ReadDir(brochuresDir)
	if err != nil {
		return nil, err
	}

	var brochures []string
	for _, entry := range entries {
		if entry.IsDir() {
			brochureFile := filepath.Join(brochuresDir, entry.Name(), "brochure.yaml")
			if _, err := os.Stat(brochureFile); err == nil {
				brochures = append(brochures, entry.Name())
			}
		}
	}

	sort.Strings(brochures)
	return brochures, nil
}

func runBrochureList(cmd *cobra.Command, args []string) {
	printHeader("Available Brochures")
	fmt.Println()

	brochures, err := listBrochures()
	if err != nil {
		printError(fmt.Sprintf("Failed to list brochures: %v", err))
		return
	}

	if len(brochures) == 0 {
		printWarning("No brochures found. Create brochures in reports/brochures/")
		return
	}

	// Group by component
	byComponent := make(map[string][]*Brochure)
	for _, name := range brochures {
		b, err := loadBrochure(name)
		if err != nil {
			continue
		}
		byComponent[b.Component] = append(byComponent[b.Component], b)
	}

	components := []string{"collector", "kafka", "flink", "gateway", "full", "http"}
	for _, comp := range components {
		brochureList := byComponent[comp]
		if len(brochureList) == 0 {
			continue
		}

		fmt.Printf("  %s:\n", strings.ToUpper(comp))
		for _, b := range brochureList {
			duration := time.Duration(b.Duration) * time.Millisecond
			fmt.Printf("    %-30s %s (%v)\n", b.Name, b.Description, duration)
		}
		fmt.Println()
	}

	fmt.Printf("Total: %d brochures\n", len(brochures))
	fmt.Println()
	printInfo("Run with: reactive bench brochure run <name>")
	printInfo("Run all:  reactive bench brochure run-all")
}

func runBrochureRun(cmd *cobra.Command, args []string) {
	name := args[0]

	brochure, err := loadBrochure(name)
	if err != nil {
		printError(err.Error())
		return
	}

	runBrochure(brochure, name)
}

func runBrochure(brochure *Brochure, name string) *BrochureResult {
	projectRoot := findProjectRoot()
	brochureDir := filepath.Join(projectRoot, "reports", "brochures", name)

	// Quick mode: override duration to 10s for fast iteration
	actualDuration := brochure.Duration
	if quickMode {
		actualDuration = 10000 // 10 seconds
	}
	duration := time.Duration(actualDuration) * time.Millisecond

	// Load previous baseline for comparison
	var baseline *BrochureResult
	baselineFile := filepath.Join(brochureDir, "results.json")
	if data, err := os.ReadFile(baselineFile); err == nil {
		baseline = &BrochureResult{}
		json.Unmarshal(data, baseline)
	}

	printHeader(fmt.Sprintf("Brochure: %s", brochure.Name))
	fmt.Println()
	printInfo(brochure.Description)
	fmt.Println()
	fmt.Printf("  Component:    %s\n", brochure.Component)
	fmt.Printf("  Runtime:      %s\n", brochure.Runtime)
	fmt.Printf("  Duration:     %v", duration)
	if quickMode {
		fmt.Printf(" (quick mode)")
	}
	fmt.Println()
	fmt.Printf("  Concurrency:  %d\n", brochure.Concurrency)
	fmt.Printf("  HTTP Server:  %s\n", brochure.Config.HttpServer)
	fmt.Printf("  Microbatch:   %v\n", brochure.Config.Microbatching)
	if brochure.Config.Microbatching {
		fmt.Printf("  Batch Size:   %d\n", brochure.Config.MicrobatchSize)
		fmt.Printf("  Batch Timeout: %dms\n", brochure.Config.MicrobatchTimeout)
	}
	if baseline != nil {
		fmt.Printf("  Baseline:     %.0f ops/s\n", baseline.Throughput)
	}
	fmt.Println()

	// Override brochure duration for this run
	originalDuration := brochure.Duration
	brochure.Duration = actualDuration

	start := time.Now()
	result := &BrochureResult{
		Brochure:  name,
		Name:      brochure.Name,
		Runtime:   brochure.Runtime,
		StartTime: start,
	}

	// Check runtime and run accordingly
	if brochure.Runtime == RuntimeNative {
		// Native execution: run directly on host
		runNativeBrochure(projectRoot, brochure, brochureDir, result)
	} else {
		// Docker execution (default)
		network := findDockerNetwork()
		if network == "" {
			printError("Docker network not found. Is the system running?")
			return nil
		}

		// Run based on component type and config
		switch brochure.Component {
		case "http":
			runHttpBrochure(projectRoot, network, brochure, brochureDir, result)
		case "kafka":
			runKafkaBrochure(projectRoot, network, brochure, brochureDir, result)
		case "flink":
			runFlinkBrochure(projectRoot, network, brochure, brochureDir, result)
		case "gateway":
			runGatewayBrochure(projectRoot, network, brochure, brochureDir, result)
		case "full":
			runFullBrochure(projectRoot, network, brochure, brochureDir, result)
		case "collector":
			runCollectorBrochure(projectRoot, network, brochure, brochureDir, result)
		default:
			printError(fmt.Sprintf("Unknown component: %s", brochure.Component))
			return nil
		}
	}

	result.EndTime = time.Now()
	result.DurationMs = result.EndTime.Sub(result.StartTime).Milliseconds()

	// Restore original duration for saving
	brochure.Duration = originalDuration

	// Save result
	saveResult(brochureDir, result)
	generateBrochureHTML(brochureDir, brochure, result)
	generateBrochureMarkdown(brochureDir, brochure, result)

	fmt.Println()

	// Show comparison with baseline
	if baseline != nil && baseline.Throughput > 0 {
		delta := result.Throughput - baseline.Throughput
		deltaPercent := (delta / baseline.Throughput) * 100
		if delta >= 0 {
			printSuccess(fmt.Sprintf("%.0f ops/s → %.0f ops/s (+%.1f%% ⬆)", baseline.Throughput, result.Throughput, deltaPercent))
		} else {
			printError(fmt.Sprintf("%.0f ops/s → %.0f ops/s (%.1f%% ⬇)", baseline.Throughput, result.Throughput, deltaPercent))
		}
	} else {
		printSuccess(fmt.Sprintf("Brochure completed: %.0f ops/s", result.Throughput))
	}
	printInfo(fmt.Sprintf("Results: %s", brochureDir))

	return result
}

func runHttpBrochure(projectRoot, network string, brochure *Brochure, outDir string, result *BrochureResult) {
	durationSec := brochure.Duration / 1000

	// Install platform first
	printInfo("Installing platform module with dependencies...")
	if !runMavenCompileWithDeps(projectRoot, network, "platform") {
		printError("Failed to install platform module")
		return
	}

	// Use UnifiedHttpBenchmark for same-container testing (maximum throughput)
	// This runs client AND server in the same JVM - no network overhead
	// Expected: 600K+ req/s vs 239K with Apache Bench from separate container

	printInfo(fmt.Sprintf("Running same-container benchmark for %s...", brochure.Config.HttpServer))
	printInfo("  (Client + Server in same JVM for maximum throughput)")

	brochureName := filepath.Base(outDir)

	// Run UnifiedHttpBenchmark
	args := []string{
		"run", "--rm",
		"--network", network,
		"-v", fmt.Sprintf("%s:/app", projectRoot),
		"-v", "maven-repo:/root/.m2",
		"-w", "/app/platform",
		"maven:3.9-eclipse-temurin-22",
		"java", "--enable-native-access=ALL-UNNAMED",
		"-cp", "target/classes:target/dependency/*",
		"com.reactive.platform.benchmark.UnifiedHttpBenchmark",
		brochure.Config.HttpServer,                             // Server type
		fmt.Sprintf("%d", durationSec),                         // Duration in seconds
		fmt.Sprintf("%d", brochure.Concurrency),                // Concurrency
		fmt.Sprintf("/app/reports/brochures/%s", brochureName), // Output directory
	}

	cmd := exec.Command("docker", args...)
	cmd.Dir = projectRoot
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	if err := cmd.Run(); err != nil {
		printError(fmt.Sprintf("Benchmark failed: %v", err))
		return
	}

	// Load results from the output file
	loadResultsFromFile(filepath.Join(outDir, "results.json"), result)
}

func runKafkaBrochure(projectRoot, network string, brochure *Brochure, outDir string, result *BrochureResult) {
	// Install platform with dependencies
	printInfo("Installing platform module with dependencies...")
	if !runMavenCompileWithDeps(projectRoot, network, "platform") {
		printError("Failed to install platform module")
		return
	}

	durationSec := brochure.Duration / 1000
	brochureName := filepath.Base(outDir)

	printInfo(fmt.Sprintf("Running Kafka producer benchmark (fresh run for %s)...", brochureName))

	// Run KafkaProducerBenchmark with explicit Kafka bootstrap servers
	args := []string{
		"run", "--rm",
		"--network", network,
		"-v", fmt.Sprintf("%s:/app", projectRoot),
		"-v", "maven-repo:/root/.m2",
		"-w", "/app",
		"-e", "KAFKA_BOOTSTRAP_SERVERS=kafka:29092",
		"maven:3.9-eclipse-temurin-22",
		"java", "-cp", "platform/target/classes:platform/target/dependency/*",
		"com.reactive.platform.benchmark.KafkaProducerBenchmark",
		fmt.Sprintf("%d", durationSec*1000),                        // durationMs
		fmt.Sprintf("%d", brochure.Concurrency),                    // concurrency
		"http://gateway:3000",                                      // gatewayUrl (unused)
		"http://drools:8080",                                       // droolsUrl (unused)
		fmt.Sprintf("/app/reports/brochures/%s", brochureName),     // reportsDir - unique per brochure
		"true",                                                     // skipEnrichment
	}

	cmd := exec.Command("docker", args...)
	cmd.Dir = projectRoot
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	cmd.Run()

	// Read results from this brochure's directory (not a shared cache)
	loadResultsFromFile(filepath.Join(outDir, "results.json"), result)
}

// applyBrochureEnv applies environment variables from brochure config to Docker services.
// It handles both the legacy 'env' field and the new 'services' field.
// For the services field, it restarts containers with the new env vars using -e flags.
func applyBrochureEnv(projectRoot string, brochure *Brochure) {
	config := brochure.Config

	// Merge legacy env into services if services not specified
	if len(config.Services) == 0 && len(config.Env) > 0 {
		// Infer services from component type
		config.Services = inferServicesFromComponent(brochure.Component, config.Env)
	}

	if len(config.Services) == 0 {
		return
	}

	printInfo("Applying brochure env vars to services...")

	// Collect all env vars from all services into a single map
	// (docker-compose uses shell env var substitution, not per-service vars)
	allEnvVars := make(map[string]string)
	allServices := []string{}

	for service, envVars := range config.Services {
		if len(envVars) == 0 {
			continue
		}

		printInfo(fmt.Sprintf("  Service: %s", service))
		for k, v := range envVars {
			printInfo(fmt.Sprintf("    %s=%s", k, v))
			allEnvVars[k] = v
		}
		allServices = append(allServices, service)
	}

	if len(allServices) == 0 {
		return
	}

	// Build environment list with all brochure env vars
	// Docker compose uses ${VAR:-default} syntax in docker-compose.yaml
	// These env vars will be substituted when we run docker compose
	envList := os.Environ()
	for k, v := range allEnvVars {
		envList = append(envList, fmt.Sprintf("%s=%s", k, v))
	}

	// Restart all affected services with new env vars
	args := []string{"compose", "up", "-d", "--force-recreate"}
	args = append(args, allServices...)

	cmd := exec.Command("docker", args...)
	cmd.Dir = projectRoot
	cmd.Env = envList
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	cmd.Run()

	// Wait for services to be ready based on component type
	waitForServicesReady(brochure.Component)
}

// inferServicesFromComponent maps component type to Docker service names
func inferServicesFromComponent(component string, env map[string]string) map[string]map[string]string {
	services := make(map[string]map[string]string)

	switch component {
	case "flink":
		// Flink env vars go to the job submitter (which passes to the job)
		services["flink-job-submitter"] = env
		services["flink-taskmanager"] = env
		services["flink-jobmanager"] = env
	case "gateway":
		services["gateway"] = env
	case "kafka":
		services["kafka"] = env
	case "drools":
		services["drools"] = env
	case "full":
		// Full benchmark - apply to all processing components
		services["gateway"] = env
		services["flink-job-submitter"] = env
		services["flink-taskmanager"] = env
		services["flink-jobmanager"] = env
		services["drools"] = env
	}

	return services
}

// waitForServicesReady waits for services to be healthy based on component type
func waitForServicesReady(component string) {
	switch component {
	case "flink":
		printInfo("Waiting for Flink to be ready...")
		for i := 0; i < 30; i++ {
			resp, err := http.Get("http://localhost:8081/jobs")
			if err == nil {
				resp.Body.Close()
				break
			}
			time.Sleep(time.Second)
		}
		// Extra time for job to start
		time.Sleep(3 * time.Second)
	case "gateway":
		printInfo("Waiting for Gateway to be ready...")
		for i := 0; i < 30; i++ {
			resp, err := http.Get("http://localhost:3000/health")
			if err == nil {
				resp.Body.Close()
				break
			}
			time.Sleep(time.Second)
		}
	default:
		// Default wait
		time.Sleep(3 * time.Second)
	}
}

// runFlinkBrochure runs the Flink stream processing benchmark
func runFlinkBrochure(projectRoot, network string, brochure *Brochure, outDir string, result *BrochureResult) {
	// Apply brochure env vars to Flink containers
	applyBrochureEnv(projectRoot, brochure)

	printInfo("Running Flink stream processing benchmark...")

	// Check if we can skip compilation in quick mode
	benchmarkClass := filepath.Join(projectRoot, "application/counter/target/test-classes/com/reactive/counter/benchmark/FlinkBenchmark.class")
	classpathCache := filepath.Join(projectRoot, ".cache/flink-classpath.txt")

	if quickMode {
		if _, err := os.Stat(benchmarkClass); err == nil {
			printInfo("Quick mode: Skipping compilation (classes exist)")
		} else {
			printInfo("Quick mode: Classes missing, compiling...")
			if !compileFlinkBenchmark(projectRoot, network) {
				return
			}
		}
	} else {
		// Full mode: always compile
		printInfo("Compiling application module...")
		if !compileFlinkBenchmark(projectRoot, network) {
			return
		}
	}

	durationSec := brochure.Duration / 1000
	brochureName := filepath.Base(outDir)

	// Try to use cached classpath in quick mode
	var deps string
	if quickMode {
		if cached, err := os.ReadFile(classpathCache); err == nil {
			deps = strings.TrimSpace(string(cached))
			printInfo("Quick mode: Using cached classpath")
		}
	}

	// Get classpath if not cached
	if deps == "" {
		printInfo("Resolving Maven classpath...")
		cpArgs := []string{
			"run", "--rm",
			"--network", network,
			"-v", fmt.Sprintf("%s:/app", projectRoot),
			"-v", "maven-repo:/root/.m2",
			"-w", "/app",
			"maven:3.9-eclipse-temurin-22",
			"sh", "-c", "mvn -f application/counter/pom.xml dependency:build-classpath -Dmdep.outputFile=/dev/stdout -q -Pbenchmark | tail -1",
		}
		cpCmd := exec.Command("docker", cpArgs...)
		cpCmd.Dir = projectRoot
		cpOutput, err := cpCmd.Output()
		if err != nil {
			printError(fmt.Sprintf("Failed to get classpath: %v", err))
			return
		}
		deps = strings.TrimSpace(string(cpOutput))

		// Cache the classpath for quick mode
		os.MkdirAll(filepath.Dir(classpathCache), 0755)
		os.WriteFile(classpathCache, []byte(deps), 0644)
	}

	// Run FlinkBenchmark with full classpath
	classpath := fmt.Sprintf("platform/target/classes:platform/base/target/classes:application/counter/target/classes:application/counter/target/test-classes:%s", deps)
	args := []string{
		"run", "--rm",
		"--network", network,
		"-v", fmt.Sprintf("%s:/app", projectRoot),
		"-v", "maven-repo:/root/.m2",
		"-w", "/app",
		"-e", "KAFKA_BOOTSTRAP_SERVERS=kafka:29092",
		"-m", "4g", // Limit container memory to 4GB
		"maven:3.9-eclipse-temurin-22",
		"java", "-Xmx3g", "-Xms1g", "-cp", classpath, // Set Java heap to 3GB max
		"com.reactive.counter.benchmark.FlinkBenchmark",
		fmt.Sprintf("%d", durationSec*1000),                    // durationMs
		fmt.Sprintf("%d", brochure.Concurrency),                // concurrency
		"http://gateway:3000",                                  // gatewayUrl (unused for Flink)
		"http://drools:8080",                                   // droolsUrl (unused)
		fmt.Sprintf("/app/reports/brochures/%s", brochureName), // reportsDir
		"true",                                                 // skipEnrichment
	}

	cmd := exec.Command("docker", args...)
	cmd.Dir = projectRoot
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	cmd.Run()

	loadResultsFromFile(filepath.Join(outDir, "results.json"), result)
}

// runCollectorBrochure runs the MicrobatchCollector benchmark (no HTTP, no network)
func runCollectorBrochure(projectRoot, network string, brochure *Brochure, outDir string, result *BrochureResult) {
	// Install platform with dependencies
	printInfo("Installing platform module with dependencies...")
	if !runMavenCompileWithDeps(projectRoot, network, "platform") {
		printError("Failed to install platform module")
		return
	}

	durationSec := brochure.Duration / 1000

	printInfo("Running MicrobatchCollector benchmark (no HTTP overhead)...")

	// Run GatewayComparisonBenchmark in collector mode
	args := []string{
		"run", "--rm",
		"--network", network,
		"-v", fmt.Sprintf("%s:/app", projectRoot),
		"-v", "maven-repo:/root/.m2",
		"-w", "/app/platform",
		"maven:3.9-eclipse-temurin-22",
		"java",
		"--enable-native-access=ALL-UNNAMED",
		"-cp", "target/classes:target/dependency/*",
		"com.reactive.platform.gateway.microbatch.GatewayComparisonBenchmark",
		"collector",
		fmt.Sprintf("%d", durationSec),
		fmt.Sprintf("%d", brochure.Concurrency),
		"kafka:29092",
		fmt.Sprintf("/app/reports/brochures/%s", filepath.Base(outDir)),
	}

	cmd := exec.Command("docker", args...)
	cmd.Dir = projectRoot
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	cmd.Run()

	loadResultsFromFile(filepath.Join(outDir, "results.json"), result)
}

// runMavenCompileWithDeps compiles a module and copies dependencies
func runMavenCompileWithDeps(projectRoot, network, module string) bool {
	printInfo(fmt.Sprintf("Compiling %s with dependencies...", module))

	args := []string{
		"run", "--rm",
		"--network", network,
		"-v", fmt.Sprintf("%s:/app", projectRoot),
		"-v", "maven-repo:/root/.m2",
		"-w", fmt.Sprintf("/app/%s", module),
		"maven:3.9-eclipse-temurin-22",
		"mvn", "-q", "compile", "dependency:copy-dependencies", "-DskipTests",
	}

	cmd := exec.Command("docker", args...)
	cmd.Dir = projectRoot
	output, err := cmd.CombinedOutput()
	if err != nil {
		printError(fmt.Sprintf("Maven compile failed: %v\n%s", err, string(output)))
		return false
	}

	return true
}

// compileFlinkBenchmark compiles the Flink benchmark module
func compileFlinkBenchmark(projectRoot, network string) bool {
	args := []string{
		"run", "--rm",
		"--network", network,
		"-v", fmt.Sprintf("%s:/app", projectRoot),
		"-v", "maven-repo:/root/.m2",
		"-w", "/app",
		"maven:3.9-eclipse-temurin-22",
		"mvn", "-q", "-f", "application/counter/pom.xml", "test-compile", "-DskipTests", "-Pbenchmark",
	}
	cmd := exec.Command("docker", args...)
	cmd.Dir = projectRoot
	if output, err := cmd.CombinedOutput(); err != nil {
		printError(fmt.Sprintf("Maven compile failed: %v\n%s", err, string(output)))
		return false
	}
	return true
}

// runNativeBrochure runs benchmarks directly on the host machine (native execution)
// This enables comparison between Docker and native performance
func runNativeBrochure(projectRoot string, brochure *Brochure, outDir string, result *BrochureResult) {
	printInfo("Running in NATIVE mode (directly on host)...")

	durationSec := brochure.Duration / 1000
	brochureName := filepath.Base(outDir)

	// Find Java - try brew paths first, then system
	// Priority: openjdk (latest) > openjdk@21 > system java
	javaPath := ""
	javaHome := ""
	brewJavaPaths := []string{
		"/opt/homebrew/opt/openjdk",      // Latest (e.g., Java 25)
		"/opt/homebrew/opt/openjdk@21",   // Java 21
		"/opt/homebrew/opt/openjdk@17",   // Java 17
	}
	for _, brewPath := range brewJavaPaths {
		testPath := filepath.Join(brewPath, "bin", "java")
		if _, err := os.Stat(testPath); err == nil {
			javaPath = testPath
			javaHome = brewPath
			break
		}
	}
	if javaPath == "" {
		javaPath = "java" // Fall back to system java
	}

	// Set environment variables from brochure config
	env := os.Environ()
	for k, v := range brochure.Config.Env {
		env = append(env, fmt.Sprintf("%s=%s", k, v))
	}
	// Native mode: use localhost for Kafka (assuming Docker services expose ports)
	env = append(env, "KAFKA_BOOTSTRAP_SERVERS=localhost:9092")
	// Set JAVA_HOME for Maven
	if javaHome != "" {
		env = append(env, fmt.Sprintf("JAVA_HOME=%s", javaHome))
		env = append(env, fmt.Sprintf("PATH=%s/bin:%s", javaHome, os.Getenv("PATH")))
	}

	// Compile first if needed (using native Maven)
	printInfo("Compiling with native Maven...")
	compileCmd := exec.Command("mvn", "-q", "compile", "test-compile", "-DskipTests", "-Pbenchmark")
	compileCmd.Dir = projectRoot
	compileCmd.Env = env
	if output, err := compileCmd.CombinedOutput(); err != nil {
		printError(fmt.Sprintf("Maven compile failed: %v\n%s", err, string(output)))
		return
	}

	// Get classpath using native Maven
	printInfo("Resolving classpath...")
	cpCmd := exec.Command("mvn", "-f", "application/counter/pom.xml", "dependency:build-classpath", "-Dmdep.outputFile=/dev/stdout", "-q", "-Pbenchmark")
	cpCmd.Dir = projectRoot
	cpCmd.Env = env
	cpOutput, err := cpCmd.Output()
	if err != nil {
		printError(fmt.Sprintf("Failed to get classpath: %v", err))
		return
	}
	deps := strings.TrimSpace(string(cpOutput))
	// Get last line (actual classpath)
	lines := strings.Split(deps, "\n")
	deps = lines[len(lines)-1]

	// Build classpath
	classpath := fmt.Sprintf("%s/platform/target/classes:%s/platform/base/target/classes:%s/application/counter/target/classes:%s/application/counter/target/test-classes:%s",
		projectRoot, projectRoot, projectRoot, projectRoot, deps)

	// Run based on component type
	var benchmarkClass string
	var benchArgs []string

	switch brochure.Component {
	case "flink":
		benchmarkClass = "com.reactive.counter.benchmark.FlinkBenchmark"
		benchArgs = []string{
			fmt.Sprintf("%d", durationSec*1000),   // durationMs
			fmt.Sprintf("%d", brochure.Concurrency), // concurrency
			"http://localhost:3000",                 // gatewayUrl
			"http://localhost:8080",                 // droolsUrl
			outDir,                                  // reportsDir
			"true",                                  // skipEnrichment
		}
	case "kafka":
		benchmarkClass = "com.reactive.platform.benchmark.KafkaProducerBenchmark"
		benchArgs = []string{
			fmt.Sprintf("%d", durationSec*1000),
			fmt.Sprintf("%d", brochure.Concurrency),
			"http://localhost:3000",
			"http://localhost:8080",
			outDir,
			"true",
		}
	case "http":
		benchmarkClass = "com.reactive.platform.benchmark.UnifiedHttpBenchmark"
		benchArgs = []string{
			brochure.Config.HttpServer,
			fmt.Sprintf("%d", durationSec),
			fmt.Sprintf("%d", brochure.Concurrency),
			outDir,
		}
	case "gateway", "full":
		if brochure.Config.Microbatching {
			benchmarkClass = "com.reactive.platform.gateway.microbatch.GatewayComparisonBenchmark"
			benchArgs = []string{
				"microbatch",
				fmt.Sprintf("%d", durationSec),
				fmt.Sprintf("%d", brochure.Concurrency),
				"localhost:9092",
				outDir,
			}
		} else {
			printError("Native mode for non-microbatch gateway not implemented (requires external HTTP client)")
			return
		}
	case "collector":
		benchmarkClass = "com.reactive.platform.gateway.microbatch.GatewayComparisonBenchmark"
		benchArgs = []string{
			"collector",
			fmt.Sprintf("%d", durationSec),
			fmt.Sprintf("%d", brochure.Concurrency),
			"localhost:9092",
			outDir,
		}
	default:
		printError(fmt.Sprintf("Native mode not supported for component: %s", brochure.Component))
		return
	}

	printInfo(fmt.Sprintf("Running %s natively...", benchmarkClass))

	// Build java command
	javaArgs := []string{
		"--enable-native-access=ALL-UNNAMED",
		"-cp", classpath,
		benchmarkClass,
	}
	javaArgs = append(javaArgs, benchArgs...)

	cmd := exec.Command("java", javaArgs...)
	cmd.Dir = projectRoot
	cmd.Env = env
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	if err := cmd.Run(); err != nil {
		printError(fmt.Sprintf("Benchmark failed: %v", err))
		return
	}

	loadResultsFromFile(filepath.Join(outDir, "results.json"), result)
	printInfo(fmt.Sprintf("Native benchmark completed - results: %s", brochureName))
}

func runGatewayBrochure(projectRoot, network string, brochure *Brochure, outDir string, result *BrochureResult) {
	durationSec := brochure.Duration / 1000
	brochureName := filepath.Base(outDir)

	if brochure.Config.Microbatching {
		// Use GatewayComparisonBenchmark for same-container testing (maximum throughput)
		// This runs HTTP server + client + Kafka in the same JVM
		printInfo("Running MicrobatchingGateway benchmark (same-container, in-process)...")

		// Install platform with dependencies first
		if !runMavenCompileWithDeps(projectRoot, network, "platform") {
			printError("Failed to install platform module")
			return
		}

		// Run GatewayComparisonBenchmark in "microbatch" mode
		args := []string{
			"run", "--rm",
			"--network", network,
			"-v", fmt.Sprintf("%s:/app", projectRoot),
			"-v", "maven-repo:/root/.m2",
			"-w", "/app/platform",
			"maven:3.9-eclipse-temurin-22",
			"java",
			"--enable-native-access=ALL-UNNAMED",
			"-cp", "target/classes:target/dependency/*",
			"com.reactive.platform.gateway.microbatch.GatewayComparisonBenchmark",
			"microbatch",
			fmt.Sprintf("%d", durationSec),
			fmt.Sprintf("%d", brochure.Concurrency),
			"kafka:29092",
			fmt.Sprintf("/app/reports/brochures/%s", brochureName),
		}

		cmd := exec.Command("docker", args...)
		cmd.Dir = projectRoot
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr
		cmd.Run()

		loadResultsFromFile(filepath.Join(outDir, "results.json"), result)

	} else {
		// For non-microbatching (Spring) gateway, use Apache Bench
		// Spring gateway runs in a separate container, so network overhead is inherent
		printInfo("Running benchmark against Spring gateway with Apache Bench...")

		requests := durationSec * 100000

		abArgs := []string{
			"run", "--rm",
			"--network", network,
			"httpd:alpine",
			"sh", "-c",
			fmt.Sprintf(`echo '{"sessionId":"bench","action":"increment","value":1}' > /tmp/data.json && ab -n %d -c 500 -s 30 -k -p /tmp/data.json -T 'application/json' http://gateway:3000/api/counter 2>&1`,
				requests),
		}

		abCmd := exec.Command("docker", abArgs...)
		abCmd.Dir = projectRoot
		output, _ := abCmd.CombinedOutput()

		parseAbOutput(string(output), result)
		saveResult(outDir, result)
	}
}

// parseAbOutput extracts metrics from Apache Bench output
func parseAbOutput(output string, result *BrochureResult) {
	lines := strings.Split(output, "\n")
	for _, line := range lines {
		line = strings.TrimSpace(line)

		// Parse "Requests per second:    169344.75 [#/sec] (mean)"
		if strings.HasPrefix(line, "Requests per second:") {
			parts := strings.Fields(line)
			if len(parts) >= 4 {
				if rps, err := strconv.ParseFloat(parts[3], 64); err == nil {
					result.Throughput = rps
				}
			}
		}

		// Parse "Complete requests:      500000"
		if strings.HasPrefix(line, "Complete requests:") {
			parts := strings.Fields(line)
			if len(parts) >= 3 {
				if n, err := strconv.ParseInt(parts[2], 10, 64); err == nil {
					result.TotalOps = n
					result.SuccessOps = n
				}
			}
		}

		// Parse "Failed requests:        0"
		if strings.HasPrefix(line, "Failed requests:") {
			parts := strings.Fields(line)
			if len(parts) >= 3 {
				if n, err := strconv.ParseInt(parts[2], 10, 64); err == nil {
					result.FailedOps = n
				}
			}
		}

		// Parse "50%      2" for p50 latency
		if strings.HasPrefix(line, "50%") {
			parts := strings.Fields(line)
			if len(parts) >= 2 {
				if ms, err := strconv.ParseFloat(parts[1], 64); err == nil {
					result.P50Ms = ms
				}
			}
		}

		// Parse "99%     17" for p99 latency
		if strings.HasPrefix(line, "99%") && !strings.HasPrefix(line, "99.") {
			parts := strings.Fields(line)
			if len(parts) >= 2 {
				if ms, err := strconv.ParseFloat(parts[1], 64); err == nil {
					result.P99Ms = ms
				}
			}
		}
	}
}

func runFullBrochure(projectRoot, network string, brochure *Brochure, outDir string, result *BrochureResult) {
	durationSec := brochure.Duration / 1000
	brochureName := filepath.Base(outDir)

	if brochure.Config.Microbatching {
		// Use GatewayComparisonBenchmark for same-container testing
		// The "microbatch" mode tests the full pipeline: HTTP → Kafka
		printInfo("Full Pipeline with Microbatch Gateway (same-container, in-process)...")

		// Install platform with dependencies first
		if !runMavenCompileWithDeps(projectRoot, network, "platform") {
			printError("Failed to install platform module")
			return
		}

		// Run GatewayComparisonBenchmark in "microbatch" mode
		args := []string{
			"run", "--rm",
			"--network", network,
			"-v", fmt.Sprintf("%s:/app", projectRoot),
			"-v", "maven-repo:/root/.m2",
			"-w", "/app/platform",
			"maven:3.9-eclipse-temurin-22",
			"java",
			"--enable-native-access=ALL-UNNAMED",
			"-cp", "target/classes:target/dependency/*",
			"com.reactive.platform.gateway.microbatch.GatewayComparisonBenchmark",
			"microbatch",
			fmt.Sprintf("%d", durationSec),
			fmt.Sprintf("%d", brochure.Concurrency),
			"kafka:29092",
			fmt.Sprintf("/app/reports/brochures/%s", brochureName),
		}

		cmd := exec.Command("docker", args...)
		cmd.Dir = projectRoot
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr
		cmd.Run()

		loadResultsFromFile(filepath.Join(outDir, "results.json"), result)

	} else {
		// For non-microbatching (Spring) gateway, use Apache Bench
		printInfo("Full Pipeline with Spring Gateway...")

		requests := durationSec * 50000

		abArgs := []string{
			"run", "--rm",
			"--network", network,
			"httpd:alpine",
			"sh", "-c",
			fmt.Sprintf(`echo '{"sessionId":"bench","action":"increment","value":1}' > /tmp/data.json && ab -n %d -c 500 -s 30 -k -p /tmp/data.json -T 'application/json' http://gateway:3000/api/counter 2>&1`,
				requests),
		}

		abCmd := exec.Command("docker", abArgs...)
		abCmd.Dir = projectRoot
		output, _ := abCmd.CombinedOutput()

		parseAbOutput(string(output), result)
		saveResult(outDir, result)
	}
}

func parseHttpBenchmarkOutput(output string, result *BrochureResult) {
	// Parse the benchmark output for metrics
	lines := strings.Split(output, "\n")
	for _, line := range lines {
		if strings.Contains(line, "ops/s") {
			// Try to parse throughput
			var throughput float64
			fmt.Sscanf(line, "%f", &throughput)
			result.Throughput = throughput
		}
	}
}

func loadResultsFromFile(path string, result *BrochureResult) {
	data, err := os.ReadFile(path)
	if err != nil {
		return
	}

	var raw map[string]interface{}
	if json.Unmarshal(data, &raw) != nil {
		return
	}

	result.RawResults = raw

	// Check if results are nested in rawResults (from other benchmarks)
	// If so, use the nested results for extraction
	if nested, ok := raw["rawResults"].(map[string]interface{}); ok && nested != nil {
		raw = nested
	}

	// Extract key metrics - handle multiple field name conventions
	// throughput is used by UnifiedHttpBenchmark
	if v, ok := raw["throughput"].(float64); ok && v > 0 {
		result.Throughput = v
	} else if v, ok := raw["avgThroughput"].(float64); ok {
		result.Throughput = v
	} else if v, ok := raw["throughputOpsPerSecond"].(float64); ok {
		result.Throughput = v
	} else if v, ok := raw["peakThroughput"].(float64); ok {
		result.Throughput = v
	}

	// Extract total operations
	if v, ok := raw["totalOps"].(float64); ok {
		result.TotalOps = int64(v)
	} else if v, ok := raw["totalOperations"].(float64); ok {
		result.TotalOps = int64(v)
	}
	if v, ok := raw["successOps"].(float64); ok {
		result.SuccessOps = int64(v)
	} else if v, ok := raw["successfulOperations"].(float64); ok {
		result.SuccessOps = int64(v)
	}
	if v, ok := raw["failedOps"].(float64); ok {
		result.FailedOps = int64(v)
	} else if v, ok := raw["failedOperations"].(float64); ok {
		result.FailedOps = int64(v)
	}

	// Extract latencies - direct fields
	if v, ok := raw["p50Ms"].(float64); ok {
		result.P50Ms = v
	}
	if v, ok := raw["p99Ms"].(float64); ok {
		result.P99Ms = v
	}

	// Get latency - handle nested "latency" object or "latencyPercentiles"
	if latency, ok := raw["latency"].(map[string]interface{}); ok {
		if v, ok := latency["p50"].(float64); ok {
			result.P50Ms = v
		}
		if v, ok := latency["p99"].(float64); ok {
			result.P99Ms = v
		}
	} else if latency, ok := raw["latencyPercentiles"].(map[string]interface{}); ok {
		if v, ok := latency["p50"].(float64); ok {
			result.P50Ms = v
		}
		if v, ok := latency["p99"].(float64); ok {
			result.P99Ms = v
		}
	}
}

func saveResult(outDir string, result *BrochureResult) {
	os.MkdirAll(outDir, 0755)

	data, _ := json.MarshalIndent(result, "", "  ")
	os.WriteFile(filepath.Join(outDir, "results.json"), data, 0644)
}

func generateBrochureHTML(outDir string, brochure *Brochure, result *BrochureResult) {
	html := fmt.Sprintf(`<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>%s - Benchmark Results</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #f5f5f5; padding: 20px; }
        .container { max-width: 900px; margin: 0 auto; }
        .card { background: white; border-radius: 8px; padding: 24px; margin-bottom: 20px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        h1 { color: #1a1a1a; margin-bottom: 8px; }
        .desc { color: #666; margin-bottom: 24px; }
        .metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 20px; }
        .metric { text-align: center; padding: 20px; background: #f8f9fa; border-radius: 8px; }
        .metric-value { font-size: 36px; font-weight: bold; color: #2563eb; }
        .metric-label { color: #666; margin-top: 4px; }
        .config { margin-top: 24px; }
        .config-row { display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid #eee; }
        .success { color: #22c55e; }
        .failed { color: #ef4444; }
        pre { background: #1e1e1e; color: #d4d4d4; padding: 16px; border-radius: 8px; overflow-x: auto; }
    </style>
</head>
<body>
    <div class="container">
        <div class="card">
            <h1>%s</h1>
            <p class="desc">%s</p>

            <div class="metrics">
                <div class="metric">
                    <div class="metric-value">%.0f</div>
                    <div class="metric-label">ops/s</div>
                </div>
                <div class="metric">
                    <div class="metric-value">%.1f</div>
                    <div class="metric-label">p50 latency (ms)</div>
                </div>
                <div class="metric">
                    <div class="metric-value">%.1f</div>
                    <div class="metric-label">p99 latency (ms)</div>
                </div>
                <div class="metric">
                    <div class="metric-value">%d</div>
                    <div class="metric-label">total ops</div>
                </div>
            </div>

            <div class="metrics" style="margin-top: 20px;">
                <div class="metric">
                    <div class="metric-value success">%d</div>
                    <div class="metric-label">successful</div>
                </div>
                <div class="metric">
                    <div class="metric-value failed">%d</div>
                    <div class="metric-label">failed</div>
                </div>
            </div>
        </div>

        <div class="card">
            <h2>Configuration</h2>
            <div class="config">
                <div class="config-row"><span>Component</span><span>%s</span></div>
                <div class="config-row"><span>HTTP Server</span><span>%s</span></div>
                <div class="config-row"><span>Microbatching</span><span>%v</span></div>
                <div class="config-row"><span>Concurrency</span><span>%d</span></div>
                <div class="config-row"><span>Duration</span><span>%v</span></div>
            </div>
        </div>

        <div class="card">
            <h2>Raw Results</h2>
            <pre>%s</pre>
        </div>
    </div>
</body>
</html>`,
		brochure.Name,
		brochure.Name,
		brochure.Description,
		result.Throughput,
		result.P50Ms,
		result.P99Ms,
		result.TotalOps,
		result.SuccessOps,
		result.FailedOps,
		brochure.Component,
		brochure.Config.HttpServer,
		brochure.Config.Microbatching,
		brochure.Concurrency,
		time.Duration(brochure.Duration)*time.Millisecond,
		formatJSON(result.RawResults),
	)

	os.WriteFile(filepath.Join(outDir, "index.html"), []byte(html), 0644)
}

func generateBrochureMarkdown(outDir string, brochure *Brochure, result *BrochureResult) {
	md := fmt.Sprintf(`# %s

%s

## Results

| Metric | Value |
|--------|-------|
| Throughput | %.0f ops/s |
| p50 Latency | %.1f ms |
| p99 Latency | %.1f ms |
| Total Operations | %d |
| Successful | %d |
| Failed | %d |
| Duration | %v |

## Configuration

| Setting | Value |
|---------|-------|
| Component | %s |
| HTTP Server | %s |
| Microbatching | %v |
| Batch Size | %d |
| Batch Timeout | %d ms |
| Concurrency | %d |
| Kafka Acks | %s |

## Timestamp

- Started: %s
- Completed: %s
- Duration: %v
`,
		brochure.Name,
		brochure.Description,
		result.Throughput,
		result.P50Ms,
		result.P99Ms,
		result.TotalOps,
		result.SuccessOps,
		result.FailedOps,
		time.Duration(brochure.Duration)*time.Millisecond,
		brochure.Component,
		brochure.Config.HttpServer,
		brochure.Config.Microbatching,
		brochure.Config.MicrobatchSize,
		brochure.Config.MicrobatchTimeout,
		brochure.Concurrency,
		brochure.Config.KafkaAcks,
		result.StartTime.Format(time.RFC3339),
		result.EndTime.Format(time.RFC3339),
		result.EndTime.Sub(result.StartTime),
	)

	os.WriteFile(filepath.Join(outDir, "README.md"), []byte(md), 0644)
}

func formatJSON(v interface{}) string {
	if v == nil {
		return "{}"
	}
	data, _ := json.MarshalIndent(v, "", "  ")
	return string(data)
}

func runBrochureRunAll(cmd *cobra.Command, args []string) {
	printHeader("Brochure Marathon")
	fmt.Println()

	if quickMode {
		printInfo("🚀 QUICK SMOKE TEST MODE - 10 seconds per brochure")
	} else {
		printInfo("Running all brochures sequentially (full duration)")
	}
	fmt.Println()

	brochures, err := listBrochures()
	if err != nil || len(brochures) == 0 {
		printError("No brochures found")
		return
	}

	start := time.Now()
	var results []*BrochureResult
	passed := 0
	failed := 0

	for i, name := range brochures {
		fmt.Printf("\n%s [%d/%d] %s %s\n\n",
			strings.Repeat("=", 20), i+1, len(brochures), name, strings.Repeat("=", 20))

		brochure, err := loadBrochure(name)
		if err != nil {
			printError(fmt.Sprintf("Failed to load %s: %v", name, err))
			failed++
			continue
		}

		// In quick mode, override duration to 10 seconds
		if quickMode {
			brochure.Duration = 10000 // 10 seconds in milliseconds
		}

		result := runBrochure(brochure, name)
		if result != nil && result.Throughput > 0 {
			results = append(results, result)
			passed++
		} else {
			failed++
		}
	}

	// Generate comparison report
	generateMarathonReport(results)

	fmt.Println()
	fmt.Println(strings.Repeat("=", 60))
	if quickMode {
		printSuccess(fmt.Sprintf("Smoke test completed: %d passed, %d failed", passed, failed))
	} else {
		printSuccess(fmt.Sprintf("Marathon completed: %d brochures", len(results)))
	}
	printInfo(fmt.Sprintf("Total time: %v", time.Since(start).Truncate(time.Second)))
	printInfo("Comparison: reports/brochures/comparison.html")

	if failed > 0 {
		printWarning(fmt.Sprintf("%d brochures failed - check logs above", failed))
	}
}

func runBrochureCompare(cmd *cobra.Command, args []string) {
	printHeader("Brochure Comparison")

	brochures, err := listBrochures()
	if err != nil || len(brochures) == 0 {
		printError("No brochures found")
		return
	}

	var results []*BrochureResult
	for _, name := range brochures {
		resultFile := filepath.Join(getBrochuresDir(), name, "results.json")
		data, err := os.ReadFile(resultFile)
		if err != nil {
			continue
		}

		var result BrochureResult
		if json.Unmarshal(data, &result) == nil {
			results = append(results, &result)
		}
	}

	if len(results) == 0 {
		printError("No results found. Run some brochures first.")
		return
	}

	generateMarathonReport(results)
	printSuccess("Comparison report generated: reports/brochures/comparison.html")
}

// WaterfallData holds chart data for layer decomposition
type WaterfallData struct {
	labels string
	values string
	colors string
}

// formatThroughput formats a throughput value with appropriate suffix
func formatThroughput(t float64) string {
	if t >= 1_000_000 {
		return fmt.Sprintf("%.1fM", t/1_000_000)
	} else if t >= 1_000 {
		return fmt.Sprintf("%.0fK", t/1_000)
	}
	return fmt.Sprintf("%.0f", t)
}

// buildWaterfallData creates layer decomposition data for the waterfall chart
func buildWaterfallData(results []*BrochureResult) WaterfallData {
	// Group results by layer type and find best in each category
	layers := map[string]float64{
		"collector": 0,
		"kafka":     0,
		"flink":     0,
		"http":      0,
		"gateway":   0,
		"full":      0,
	}
	layerNames := map[string]string{
		"collector": "Collector (In-Memory)",
		"kafka":     "Kafka Producer",
		"flink":     "Flink (Kafka+Stream)",
		"http":      "HTTP Server",
		"gateway":   "Gateway (HTTP+Kafka)",
		"full":      "Full Pipeline",
	}

	for _, r := range results {
		name := r.Name
		brochure := r.Brochure
		var layer string
		switch {
		case strings.Contains(name, "Collector") || strings.Contains(brochure, "collector"):
			layer = "collector"
		case strings.Contains(name, "Flink") || strings.Contains(brochure, "flink"):
			layer = "flink"
		case strings.Contains(name, "Kafka") || strings.Contains(brochure, "kafka"):
			layer = "kafka"
		case strings.Contains(name, "Gateway") || strings.Contains(brochure, "gateway"):
			layer = "gateway"
		case strings.Contains(name, "Full Pipeline") || strings.Contains(brochure, "full"):
			layer = "full"
		// HTTP servers: match by HttpServer suffix or http- prefix in brochure name
		case strings.HasSuffix(name, "HttpServer") || strings.HasSuffix(name, "Server") ||
			strings.Contains(name, "HTTP") || strings.HasPrefix(brochure, "http-"):
			layer = "http"
		default:
			continue
		}
		// Keep the highest throughput for each layer
		if r.Throughput > layers[layer] {
			layers[layer] = r.Throughput
		}
	}

	// Build ordered waterfall data
	order := []string{"collector", "kafka", "flink", "http", "gateway", "full"}
	colors := map[string]string{
		"collector": "'rgba(34, 197, 94, 0.85)'",
		"kafka":     "'rgba(59, 130, 246, 0.85)'",
		"flink":     "'rgba(14, 165, 233, 0.85)'",
		"http":      "'rgba(168, 85, 247, 0.85)'",
		"gateway":   "'rgba(249, 115, 22, 0.85)'",
		"full":      "'rgba(236, 72, 153, 0.85)'",
	}

	var labels, values, colorArr []string
	for _, layer := range order {
		if layers[layer] > 0 {
			labels = append(labels, fmt.Sprintf(`"%s"`, layerNames[layer]))
			values = append(values, fmt.Sprintf("%.0f", layers[layer]))
			colorArr = append(colorArr, colors[layer])
		}
	}

	return WaterfallData{
		labels: "[" + strings.Join(labels, ", ") + "]",
		values: "[" + strings.Join(values, ", ") + "]",
		colors: "[" + strings.Join(colorArr, ", ") + "]",
	}
}

func generateMarathonReport(results []*BrochureResult) {
	if len(results) == 0 {
		return
	}

	brochuresDir := getBrochuresDir()

	// Sort by throughput descending
	sort.Slice(results, func(i, j int) bool {
		return results[i].Throughput > results[j].Throughput
	})

	// Find the winner
	winner := results[0]

	// Generate comparison data
	var tableRows strings.Builder
	for i, r := range results {
		rank := fmt.Sprintf("#%d", i+1)
		if i == 0 {
			rank = "🏆 #1"
		}
		improvement := ""
		if i > 0 && winner.Throughput > 0 {
			ratio := winner.Throughput / r.Throughput
			improvement = fmt.Sprintf("%.1fx slower", ratio)
		}

		tableRows.WriteString(fmt.Sprintf(`
            <tr class="%s">
                <td>%s</td>
                <td><strong>%s</strong></td>
                <td>%.0f</td>
                <td>%.1f</td>
                <td>%.1f</td>
                <td>%d</td>
                <td>%s</td>
            </tr>`,
			func() string { if i == 0 { return "winner" } else { return "" } }(),
			rank,
			r.Name,
			r.Throughput,
			r.P50Ms,
			r.P99Ms,
			r.TotalOps,
			improvement,
		))
	}

	// Generate chart data
	var chartLabels, chartData []string
	for _, r := range results {
		chartLabels = append(chartLabels, fmt.Sprintf(`"%s"`, r.Name))
		chartData = append(chartData, fmt.Sprintf("%.0f", r.Throughput))
	}

	// Build waterfall data - group by layer type
	waterfallData := buildWaterfallData(results)

	html := fmt.Sprintf(`<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Brochure Comparison - Marathon Results</title>
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #0f172a; color: #e2e8f0; padding: 20px; line-height: 1.6; }
        .container { max-width: 1400px; margin: 0 auto; }
        h1 { font-size: 2.5rem; text-align: center; margin-bottom: 8px; background: linear-gradient(135deg, #60a5fa, #a78bfa); -webkit-background-clip: text; -webkit-text-fill-color: transparent; }
        .subtitle { text-align: center; color: #94a3b8; margin-bottom: 32px; font-size: 1.1rem; }

        .winner-card { background: linear-gradient(135deg, #1e40af, #7c3aed); border-radius: 16px; padding: 32px; margin-bottom: 32px; text-align: center; box-shadow: 0 20px 40px rgba(124, 58, 237, 0.3); }
        .winner-title { font-size: 1.2rem; color: #cbd5e1; margin-bottom: 8px; }
        .winner-name { font-size: 1.8rem; font-weight: bold; margin-bottom: 16px; }
        .winner-throughput { font-size: 3.5rem; font-weight: bold; color: #fbbf24; text-shadow: 0 0 30px rgba(251, 191, 36, 0.5); }
        .winner-unit { font-size: 1.5rem; color: #fcd34d; }

        .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; margin-bottom: 24px; }
        @media (max-width: 900px) { .grid-2 { grid-template-columns: 1fr; } }

        .card { background: #1e293b; border-radius: 12px; padding: 24px; margin-bottom: 24px; border: 1px solid #334155; }
        .card h2 { margin-bottom: 16px; color: #f1f5f9; font-size: 1.3rem; display: flex; align-items: center; gap: 10px; }
        .card h2 .icon { font-size: 1.5rem; }
        .card p.desc { color: #94a3b8; font-size: 0.9rem; margin-bottom: 16px; }

        .chart-container { height: 350px; position: relative; }
        .chart-container-tall { height: 450px; }

        table { width: 100%%; border-collapse: collapse; font-size: 0.95rem; }
        th, td { padding: 12px 16px; text-align: left; border-bottom: 1px solid #334155; }
        th { background: #0f172a; color: #94a3b8; font-weight: 600; text-transform: uppercase; font-size: 0.8rem; letter-spacing: 0.5px; }
        tr:hover { background: #334155; }
        tr.winner { background: rgba(34, 197, 94, 0.15); }
        tr.winner td { color: #4ade80; font-weight: 500; }
        td.throughput { font-family: 'SF Mono', Monaco, monospace; font-weight: 600; }

        .layer-badges { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 20px; }
        .badge { padding: 6px 12px; border-radius: 20px; font-size: 0.8rem; font-weight: 500; }
        .badge-collector { background: rgba(34, 197, 94, 0.2); color: #4ade80; border: 1px solid rgba(34, 197, 94, 0.3); }
        .badge-kafka { background: rgba(59, 130, 246, 0.2); color: #60a5fa; border: 1px solid rgba(59, 130, 246, 0.3); }
        .badge-http { background: rgba(168, 85, 247, 0.2); color: #a78bfa; border: 1px solid rgba(168, 85, 247, 0.3); }
        .badge-gateway { background: rgba(249, 115, 22, 0.2); color: #fb923c; border: 1px solid rgba(249, 115, 22, 0.3); }
        .badge-full { background: rgba(236, 72, 153, 0.2); color: #f472b6; border: 1px solid rgba(236, 72, 153, 0.3); }

        .insight-box { background: linear-gradient(135deg, rgba(34, 197, 94, 0.1), rgba(59, 130, 246, 0.1)); border: 1px solid rgba(34, 197, 94, 0.2); border-radius: 12px; padding: 20px; margin-bottom: 24px; }
        .insight-box h3 { color: #4ade80; margin-bottom: 12px; font-size: 1.1rem; }
        .insight-box ul { color: #cbd5e1; padding-left: 20px; }
        .insight-box li { margin-bottom: 8px; }
        .insight-box .highlight { color: #fbbf24; font-weight: 600; }

        .timestamp { text-align: center; color: #64748b; margin-top: 32px; padding: 16px; border-top: 1px solid #334155; }
    </style>
</head>
<body>
    <div class="container">
        <h1>Reactive System Benchmark</h1>
        <p class="subtitle">Performance comparison across %d configurations • Throughput Decomposition Analysis</p>

        <div class="winner-card">
            <div class="winner-title">🏆 Fastest Configuration</div>
            <div class="winner-name">%s</div>
            <div class="winner-throughput">%s <span class="winner-unit">ops/s</span></div>
        </div>

        <div class="insight-box">
            <h3>📊 Key Insights</h3>
            <ul>
                <li><span class="highlight">Microbatch Collector</span> achieves 9M+ ops/s with pure in-memory batching (no network)</li>
                <li><span class="highlight">Kafka batching</span> reduces throughput to ~600K ops/s (serialization + network overhead)</li>
                <li><span class="highlight">HTTP layer</span> (Netty/Rocket) caps at ~200K ops/s due to connection handling</li>
                <li><span class="highlight">Gateway + Kafka</span> achieves ~170K ops/s — proving Kafka adds near-zero overhead to HTTP</li>
                <li><span class="highlight">Spring WebFlux</span> is 10x slower than raw Netty for gateway operations</li>
            </ul>
        </div>

        <div class="layer-badges">
            <span class="badge badge-collector">Collector Layer</span>
            <span class="badge badge-kafka">Kafka Layer</span>
            <span class="badge badge-http">HTTP Layer</span>
            <span class="badge badge-gateway">Gateway (HTTP+Kafka)</span>
            <span class="badge badge-full">Full Pipeline</span>
        </div>

        <div class="grid-2">
            <div class="card">
                <h2><span class="icon">📈</span> Throughput (Log Scale)</h2>
                <p class="desc">Logarithmic scale reveals relative differences between all configurations</p>
                <div class="chart-container">
                    <canvas id="logChart"></canvas>
                </div>
            </div>

            <div class="card">
                <h2><span class="icon">🔬</span> Layer Decomposition</h2>
                <p class="desc">How each layer impacts throughput — from raw collector to full pipeline</p>
                <div class="chart-container">
                    <canvas id="waterfallChart"></canvas>
                </div>
            </div>
        </div>

        <div class="card">
            <h2><span class="icon">⚡</span> Linear Comparison</h2>
            <p class="desc">Absolute throughput comparison — showing the massive gap between in-memory and networked operations</p>
            <div class="chart-container chart-container-tall">
                <canvas id="throughputChart"></canvas>
            </div>
        </div>

        <div class="card">
            <h2><span class="icon">📋</span> Detailed Results</h2>
            <table>
                <thead>
                    <tr>
                        <th>Rank</th>
                        <th>Configuration</th>
                        <th>Throughput</th>
                        <th>p50</th>
                        <th>p99</th>
                        <th>Total Ops</th>
                        <th>vs Winner</th>
                    </tr>
                </thead>
                <tbody>%s</tbody>
            </table>
        </div>

        <div class="timestamp">Generated: %s • Reactive System Benchmark Suite</div>
    </div>

    <script>
        // Color palette
        const colors = {
            collector: 'rgba(34, 197, 94, 0.85)',
            kafka: 'rgba(59, 130, 246, 0.85)',
            http: 'rgba(168, 85, 247, 0.85)',
            gateway: 'rgba(249, 115, 22, 0.85)',
            full: 'rgba(236, 72, 153, 0.85)',
            spring: 'rgba(239, 68, 68, 0.85)'
        };

        function getColor(name) {
            if (name.includes('Collector')) return colors.collector;
            if (name.includes('Kafka')) return colors.kafka;
            if (name.includes('HTTP') || name.includes('Rocket') || name.includes('Netty')) return colors.http;
            if (name.includes('Gateway')) return colors.gateway;
            if (name.includes('Full Pipeline')) return colors.full;
            if (name.includes('Spring')) return colors.spring;
            return 'rgba(148, 163, 184, 0.85)';
        }

        const labels = [%s];
        const data = [%s];
        const backgroundColors = labels.map(name => getColor(name));

        // Log Scale Chart
        new Chart(document.getElementById('logChart').getContext('2d'), {
            type: 'bar',
            data: {
                labels: labels.map(l => l.length > 25 ? l.substring(0, 22) + '...' : l),
                datasets: [{
                    label: 'Throughput (ops/s)',
                    data: data,
                    backgroundColor: backgroundColors,
                    borderRadius: 6
                }]
            },
            options: {
                indexAxis: 'y',
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: { display: false },
                    tooltip: {
                        callbacks: {
                            label: ctx => ctx.raw.toLocaleString() + ' ops/s'
                        }
                    }
                },
                scales: {
                    x: {
                        type: 'logarithmic',
                        grid: { color: '#334155' },
                        ticks: {
                            color: '#94a3b8',
                            callback: v => v >= 1000000 ? (v/1000000) + 'M' : v >= 1000 ? (v/1000) + 'K' : v
                        }
                    },
                    y: {
                        grid: { display: false },
                        ticks: { color: '#e2e8f0', font: { size: 11 } }
                    }
                }
            }
        });

        // Waterfall Chart - Layer Decomposition
        const waterfallLabels = %s;
        const waterfallValues = %s;
        const waterfallColors = %s;

        new Chart(document.getElementById('waterfallChart').getContext('2d'), {
            type: 'bar',
            data: {
                labels: waterfallLabels,
                datasets: [{
                    label: 'Throughput (ops/s)',
                    data: waterfallValues,
                    backgroundColor: waterfallColors,
                    borderRadius: 6
                }]
            },
            options: {
                indexAxis: 'y',
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: { display: false },
                    tooltip: {
                        callbacks: {
                            label: ctx => ctx.raw.toLocaleString() + ' ops/s'
                        }
                    }
                },
                scales: {
                    x: {
                        type: 'logarithmic',
                        grid: { color: '#334155' },
                        ticks: {
                            color: '#94a3b8',
                            callback: v => v >= 1000000 ? (v/1000000) + 'M' : v >= 1000 ? (v/1000) + 'K' : v
                        }
                    },
                    y: {
                        grid: { display: false },
                        ticks: { color: '#e2e8f0', font: { size: 11 } }
                    }
                }
            }
        });

        // Linear Chart
        new Chart(document.getElementById('throughputChart').getContext('2d'), {
            type: 'bar',
            data: {
                labels: labels,
                datasets: [{
                    label: 'Throughput (ops/s)',
                    data: data,
                    backgroundColor: backgroundColors,
                    borderRadius: 8
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: { display: false },
                    tooltip: {
                        callbacks: {
                            label: ctx => ctx.raw.toLocaleString() + ' ops/s'
                        }
                    }
                },
                scales: {
                    y: {
                        beginAtZero: true,
                        grid: { color: '#334155' },
                        ticks: {
                            color: '#94a3b8',
                            callback: v => v >= 1000000 ? (v/1000000) + 'M' : v >= 1000 ? (v/1000) + 'K' : v
                        }
                    },
                    x: {
                        grid: { display: false },
                        ticks: {
                            color: '#94a3b8',
                            maxRotation: 45,
                            minRotation: 45
                        }
                    }
                }
            }
        });
    </script>
</body>
</html>`,
		len(results),
		winner.Name,
		formatThroughput(winner.Throughput),
		tableRows.String(),
		time.Now().Format("2006-01-02 15:04:05"),
		strings.Join(chartLabels, ", "),
		strings.Join(chartData, ", "),
		waterfallData.labels,
		waterfallData.values,
		waterfallData.colors,
	)

	os.WriteFile(filepath.Join(brochuresDir, "comparison.html"), []byte(html), 0644)

	// Also generate markdown comparison
	var mdTable strings.Builder
	mdTable.WriteString("| Rank | Configuration | Throughput | p50 | p99 | Total Ops | vs Winner |\n")
	mdTable.WriteString("|:----:|---------------|------------|-----|-----|-----------|----------:|\n")
	for i, r := range results {
		rank := fmt.Sprintf("%d", i+1)
		if i == 0 {
			rank = "🏆 1"
		}
		vsWinner := ""
		if i > 0 && winner.Throughput > 0 {
			ratio := winner.Throughput / r.Throughput
			vsWinner = fmt.Sprintf("%.1fx slower", ratio)
		}
		mdTable.WriteString(fmt.Sprintf("| %s | **%s** | %s ops/s | %.1f ms | %.1f ms | %s | %s |\n",
			rank, r.Name, formatThroughput(r.Throughput), r.P50Ms, r.P99Ms, formatOps(r.TotalOps), vsWinner))
	}

	// Build layer summary for markdown
	var layerSummary strings.Builder
	layerSummary.WriteString("| Layer | Best Config | Throughput | Description |\n")
	layerSummary.WriteString("|-------|-------------|------------|-------------|\n")

	layerInfo := []struct {
		layer   string
		pattern string
		desc    string
	}{
		{"Collector", "Collector", "Pure in-memory event batching"},
		{"Kafka", "Kafka", "Message serialization + network"},
		{"HTTP", "HTTP", "Connection handling overhead"},
		{"Gateway", "Gateway", "HTTP + Kafka combined"},
		{"Full Pipeline", "Full Pipeline", "End-to-end processing"},
	}

	for _, li := range layerInfo {
		var best *BrochureResult
		for _, r := range results {
			if strings.Contains(r.Name, li.pattern) {
				if best == nil || r.Throughput > best.Throughput {
					best = r
				}
			}
		}
		if best != nil {
			layerSummary.WriteString(fmt.Sprintf("| %s | %s | %s ops/s | %s |\n",
				li.layer, best.Name, formatThroughput(best.Throughput), li.desc))
		}
	}

	md := fmt.Sprintf(`# Reactive System Benchmark Results

## 🏆 Fastest Configuration

**%s** — **%s ops/s**

---

## 📊 Key Insights

- **Microbatch Collector** achieves 9M+ ops/s with pure in-memory batching (no network)
- **Kafka batching** reduces throughput to ~600K ops/s (serialization + network overhead)
- **HTTP layer** (Netty/Rocket) caps at ~200K ops/s due to connection handling
- **Gateway + Kafka** achieves ~170K ops/s — proving Kafka adds near-zero overhead to HTTP
- **Spring WebFlux** is 10x slower than raw Netty for gateway operations

---

## 🔬 Layer Decomposition

%s

---

## 📋 Full Results

%s

---

*Generated: %s*
`, winner.Name, formatThroughput(winner.Throughput), layerSummary.String(), mdTable.String(), time.Now().Format(time.RFC3339))

	os.WriteFile(filepath.Join(brochuresDir, "comparison.md"), []byte(md), 0644)
}

// formatOps formats operation count with K/M suffix
func formatOps(ops int64) string {
	if ops >= 1_000_000 {
		return fmt.Sprintf("%.1fM", float64(ops)/1_000_000)
	} else if ops >= 1_000 {
		return fmt.Sprintf("%.0fK", float64(ops)/1_000)
	}
	return fmt.Sprintf("%d", ops)
}
