package cmd

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
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

	projectRoot := findProjectRoot()
	if projectRoot == "" {
		printError("Project root not found")
		return
	}

	// Handle "all" target
	if target == "all" {
		runAllBenchmarks(projectRoot)
		return
	}

	runSingleBenchmark(projectRoot, target)
}

func runSingleBenchmark(projectRoot, target string) {
	start := time.Now()

	duration := benchDuration
	if benchQuick {
		duration = 5
	}

	printHeader(fmt.Sprintf("Benchmark: %s", target))
	if benchQuick {
		printInfo("Mode: quick (5s, skip enrichment)")
	} else {
		printInfo(fmt.Sprintf("Duration: %ds | Concurrency: %d", duration, benchConcurrency))
	}
	fmt.Println()

	// Find Docker network
	network := findDockerNetwork()
	if network == "" {
		printError("Docker network not found. Is the system running?")
		printInfo("Start with: reactive start")
		return
	}

	// Get module and test class for target
	module, testClass := getBenchmarkConfig(target)
	if testClass == "" {
		printError(fmt.Sprintf("Unknown benchmark target: %s", target))
		return
	}

	reportsDir := filepath.Join(projectRoot, "reports")
	os.MkdirAll(filepath.Join(reportsDir, target), 0755)

	// Step 1: Compile platform module
	printInfo("Compiling platform module...")
	if !runMavenCompile(projectRoot, network, "platform") {
		printError("Failed to compile platform module")
		return
	}

	// Step 2: Compile target module
	printInfo(fmt.Sprintf("Compiling %s module...", module))
	if !runMavenCompile(projectRoot, network, module) {
		printError(fmt.Sprintf("Failed to compile %s module", module))
		return
	}

	// Step 3: Run benchmark
	printInfo("Running benchmark...")
	skipEnrich := benchSkipEnrichment || benchQuick
	if !runJavaBenchmark(projectRoot, network, module, testClass, target, duration, benchConcurrency, skipEnrich, reportsDir) {
		printError("Benchmark failed")
		return
	}

	// Step 4: Generate HTML report
	generateHTMLReport(reportsDir, target)

	fmt.Println()
	printSuccess("Benchmark completed")
	printInfo(fmt.Sprintf("Report: %s/%s/index.html", reportsDir, target))
	printInfo(fmt.Sprintf("Total time: %.2fs", time.Since(start).Seconds()))
}

func runAllBenchmarks(projectRoot string) {
	targets := []string{"http", "kafka", "flink", "drools", "gateway", "full"}
	start := time.Now()

	printHeader("Running All Benchmarks")
	fmt.Println()

	for _, target := range targets {
		fmt.Printf("\n%s Running %s benchmark %s\n", strings.Repeat("=", 20), target, strings.Repeat("=", 20))
		runSingleBenchmark(projectRoot, target)
	}

	// Generate dashboard
	reportsDir := filepath.Join(projectRoot, "reports")
	generateDashboard(reportsDir)

	fmt.Println()
	printSuccess("All benchmarks completed")
	printInfo(fmt.Sprintf("Dashboard: %s/index.html", reportsDir))
	printInfo(fmt.Sprintf("Total time: %.2fs", time.Since(start).Seconds()))
}

func getBenchmarkConfig(target string) (module, testClass string) {
	configs := map[string][2]string{
		"http":    {"platform/deployment/docker/gateway", "com.reactive.gateway.benchmark.HttpBenchmark"},
		"kafka":   {"application", "com.reactive.counter.benchmark.KafkaBenchmark"},
		"flink":   {"application", "com.reactive.counter.benchmark.FlinkBenchmark"},
		"drools":  {"platform/deployment/docker/drools", "com.reactive.drools.benchmark.DroolsBenchmark"},
		"gateway": {"platform/deployment/docker/gateway", "com.reactive.gateway.benchmark.GatewayBenchmark"},
		"full":    {"application", "com.reactive.counter.benchmark.FullBenchmark"},
	}
	if cfg, ok := configs[target]; ok {
		return cfg[0], cfg[1]
	}
	return "", ""
}

func runMavenCompile(projectRoot, network, module string) bool {
	args := []string{
		"run", "--rm",
		"--network", network,
		"-v", fmt.Sprintf("%s:/app", projectRoot),
		"-v", "maven-repo:/root/.m2",
		"-w", "/app",
		"maven:3.9-eclipse-temurin-21",
		"mvn", "-f", fmt.Sprintf("%s/pom.xml", module),
		"compile", "test-compile", "-q", "-DskipTests", "-Pbenchmark",
	}

	cmd := exec.Command("docker", args...)
	cmd.Dir = projectRoot
	output, err := cmd.CombinedOutput()
	if err != nil {
		fmt.Println(string(output))
		return false
	}
	return true
}

func runJavaBenchmark(projectRoot, network, module, testClass, target string, duration, concurrency int, skipEnrichment bool, reportsDir string) bool {
	// Get classpath
	cpArgs := []string{
		"run", "--rm",
		"--network", network,
		"-v", fmt.Sprintf("%s:/app", projectRoot),
		"-v", "maven-repo:/root/.m2",
		"-w", "/app",
		"maven:3.9-eclipse-temurin-21",
		"mvn", "-f", fmt.Sprintf("%s/pom.xml", module),
		"dependency:build-classpath", "-Dmdep.outputFile=/dev/stdout", "-q", "-Pbenchmark",
	}

	cpCmd := exec.Command("docker", cpArgs...)
	cpOutput, err := cpCmd.Output()
	if err != nil {
		return false
	}

	// Parse classpath (last line)
	lines := strings.Split(strings.TrimSpace(string(cpOutput)), "\n")
	classpath := lines[len(lines)-1]

	// Build full classpath
	fullCP := fmt.Sprintf("platform/target/classes:%s/target/classes:%s/target/test-classes:%s",
		module, module, classpath)

	// Determine URLs based on network
	gatewayURL := "http://gateway:3000"
	droolsURL := "http://drools:8080"

	// Build Java command
	javaArgs := []string{
		"run", "--rm",
		"--network", network,
		"-v", fmt.Sprintf("%s:/app", projectRoot),
		"-v", "maven-repo:/root/.m2",
		"-w", "/app",
		"-e", fmt.Sprintf("JAEGER_QUERY_URL=http://jaeger:16686"),
		"-e", fmt.Sprintf("LOKI_URL=http://loki:3100"),
		"maven:3.9-eclipse-temurin-21",
		"java", "-cp", fullCP,
		testClass,
		fmt.Sprintf("%d", duration*1000), // durationMs
		fmt.Sprintf("%d", concurrency),
		gatewayURL,
		droolsURL,
		fmt.Sprintf("/app/reports/%s", target),
		fmt.Sprintf("%t", skipEnrichment),
	}

	cmd := exec.Command("docker", javaArgs...)
	cmd.Dir = projectRoot
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	return cmd.Run() == nil
}

func generateHTMLReport(reportsDir, target string) {
	jsonFile := filepath.Join(reportsDir, target, "results.json")
	htmlFile := filepath.Join(reportsDir, target, "index.html")

	data, err := os.ReadFile(jsonFile)
	if err != nil {
		return
	}

	// Get git commit
	commit := "unknown"
	out, err := exec.Command("git", "rev-parse", "--short", "HEAD").Output()
	if err == nil {
		commit = strings.TrimSpace(string(out))
	}

	// Parse JSON to get name
	var result struct {
		Name string `json:"name"`
	}
	json.Unmarshal(data, &result)
	name := result.Name
	if name == "" {
		name = target + " Benchmark"
	}

	html := fmt.Sprintf(`<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>%s Report</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
        #root { min-height: 100vh; }
        .loading { display: flex; justify-content: center; align-items: center; height: 100vh; font-size: 18px; color: #666; }
    </style>
</head>
<body>
    <div id="root"><div class="loading">Loading benchmark report...</div></div>
    <script>
        window.__BENCHMARK_DATA__ = %s;
        window.__BENCHMARK_COMMIT__ = "%s";
    </script>
    <script src="../assets/benchmark-report.js"></script>
</body>
</html>`, name, string(data), commit)

	os.WriteFile(htmlFile, []byte(html), 0644)
}

func generateDashboard(reportsDir string) {
	components := []string{"http", "kafka", "flink", "drools", "gateway", "full"}
	var dashboardData []map[string]interface{}

	for _, comp := range components {
		jsonFile := filepath.Join(reportsDir, comp, "results.json")
		data, err := os.ReadFile(jsonFile)
		if err != nil {
			continue
		}

		var result map[string]interface{}
		if json.Unmarshal(data, &result) == nil {
			result["id"] = comp
			dashboardData = append(dashboardData, result)
		}
	}

	if len(dashboardData) == 0 {
		return
	}

	indexData, _ := json.MarshalIndent(map[string]interface{}{
		"components":  dashboardData,
		"generatedAt": time.Now().Format(time.RFC3339),
	}, "", "  ")

	html := fmt.Sprintf(`<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Benchmark Dashboard</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
        #root { min-height: 100vh; }
        .loading { display: flex; justify-content: center; align-items: center; height: 100vh; font-size: 18px; color: #666; }
    </style>
</head>
<body>
    <div id="root"><div class="loading">Loading benchmark dashboard...</div></div>
    <script>
        window.__BENCHMARK_INDEX__ = %s;
    </script>
    <script src="assets/benchmark-index.js"></script>
</body>
</html>`, string(indexData))

	os.WriteFile(filepath.Join(reportsDir, "index.html"), []byte(html), 0644)
}

func findDockerNetwork() string {
	cmd := exec.Command("docker", "network", "ls", "--format", "{{.Name}}")
	output, err := cmd.Output()
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
		if _, err := os.Stat(filepath.Join(dir, "docker-compose.yml")); err == nil {
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
