package cmd

import (
	"encoding/json"
	"fmt"
	"io"
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

  ISOLATION BENCHMARKS (component theoretical max):
  - http:           HTTP endpoint latency only (health check)
  - kafka-producer: Kafka producer throughput (fire-and-forget, no acks)
  - drools:         Direct Drools rule evaluation

  INTEGRATION BENCHMARKS (end-to-end paths):
  - kafka:   Kafka produce/consume round-trip (includes Flink)
  - flink:   Flink stream processing throughput
  - gateway: HTTP + Kafka publish (fire-and-forget)
  - full:    Complete E2E pipeline (HTTP → Kafka → Flink → Drools)

  SUBCOMMANDS:
  - servers:    Benchmark HTTP server implementations (see tiers below)
  - microbatch: Benchmark adaptive microbatching gateway
  - help:       Detailed benchmark guide

  HTTP SERVER TIERS:
  - MAXIMUM_THROUGHPUT  (600K+ req/s)   - ROCKET, BOSS_WORKER, IO_URING
  - HIGH_THROUGHPUT     (500-600K req/s) - HYPER, RAW, TURBO
  - SPECIALIZED         (300-500K req/s) - ULTRA, ZERO_COPY, ULTRA_FAST
  - FRAMEWORK           (<300K req/s)    - FAST, NETTY, SPRING_BOOT

  AGGREGATE:
  - all:     Run all benchmarks sequentially

Examples:
  reactive bench kafka-producer      # Kafka producer theoretical max
  reactive bench http                # HTTP layer theoretical max
  reactive bench servers --list      # List all HTTP servers with tiers
  reactive bench servers             # Benchmark all HTTP servers
  reactive bench microbatch          # Test adaptive microbatching
  reactive bench full -d 30          # 30s full pipeline benchmark
  reactive bench full --quick        # 5s quick benchmark
  reactive bench all                 # Run all components
  reactive bench help                # Detailed guide`,
	Args: cobra.MaximumNArgs(1),
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
	// If no target specified, show help
	if len(args) == 0 {
		cmd.Help()
		return
	}

	target := args[0]

	// Valid benchmark targets (subcommands are handled separately by Cobra)
	validTargets := []string{"http", "kafka-producer", "kafka", "flink", "drools", "gateway", "full", "all"}

	// Check if this is a subcommand (handled by Cobra, don't validate here)
	subcommands := []string{"servers", "microbatch", "help", "doctor", "history", "gateway-server", "gateway-compare"}
	for _, sub := range subcommands {
		if target == sub {
			// Let Cobra handle subcommand routing
			return
		}
	}

	valid := false
	for _, t := range validTargets {
		if t == target {
			valid = true
			break
		}
	}
	if !valid {
		printError(fmt.Sprintf("Invalid target: %s", target))
		printInfo("Valid targets: http, kafka-producer, kafka, flink, drools, gateway, full, all")
		printInfo("Or use subcommands: servers, microbatch, help, doctor, history")
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

	// Copy assets to reports directory
	assetsDir := filepath.Join(reportsDir, "assets")
	srcAssets := filepath.Join(projectRoot, "platform", "reports", "assets")
	fmt.Printf("→ Copying assets: %s -> %s\n", srcAssets, assetsDir)
	os.RemoveAll(assetsDir)
	if err := copyDir(srcAssets, assetsDir); err != nil {
		fmt.Printf("✗ Could not copy assets: %v\n", err)
	}

	// Step 1: Install all modules from root (ensures parent pom and all modules are available)
	printInfo("Installing modules from root pom...")
	if !runMavenCompile(projectRoot, network, ".", true) {
		printError("Failed to install modules")
		return
	}

	// Step 2: Compile target module
	printInfo(fmt.Sprintf("Compiling %s module...", module))
	if !runMavenCompile(projectRoot, network, module, false) {
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

	// Step 5: Bottleneck analysis (for full benchmark)
	if target == "full" && !benchQuick {
		analyzeBottleneck(filepath.Join(reportsDir, target, "results.json"))
	}

	fmt.Println()
	printSuccess("Benchmark completed")
	printInfo(fmt.Sprintf("Report: %s/%s/index.html", reportsDir, target))
	printInfo(fmt.Sprintf("Total time: %.2fs", time.Since(start).Seconds()))
}

func runAllBenchmarks(projectRoot string) {
	// Run isolation benchmarks first (theoretical max), then integration benchmarks
	targets := []string{"http", "kafka-producer", "drools", "kafka", "flink", "gateway", "full"}
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
		// Isolation benchmarks (component theoretical max)
		"http":           {"platform/deployment/docker/gateway", "com.reactive.gateway.benchmark.HttpBenchmark"},
		"kafka-producer": {"platform", "com.reactive.platform.benchmark.KafkaProducerBenchmark"},
		"drools":         {"platform/deployment/docker/drools", "com.reactive.drools.benchmark.DroolsBenchmark"},
		// Integration benchmarks
		"kafka":   {"application/counter", "com.reactive.counter.benchmark.KafkaBenchmark"},
		"flink":   {"application/counter", "com.reactive.counter.benchmark.FlinkBenchmark"},
		"gateway": {"platform/deployment/docker/gateway", "com.reactive.gateway.benchmark.GatewayBenchmark"},
		"full":    {"application/counter", "com.reactive.counter.benchmark.FullBenchmark"},
	}
	if cfg, ok := configs[target]; ok {
		return cfg[0], cfg[1]
	}
	return "", ""
}

func runMavenCompile(projectRoot, network, module string, install bool) bool {
	var goals []string
	if install {
		// -U forces update of snapshots and releases, ensuring reproducible builds
		goals = []string{"install", "-q", "-DskipTests", "-U"}
	} else {
		goals = []string{"compile", "test-compile", "-q", "-DskipTests", "-Pbenchmark"}
	}

	args := []string{
		"run", "--rm",
		"--network", network,
		"-v", fmt.Sprintf("%s:/app", projectRoot),
		"-v", "maven-repo:/root/.m2",
		"-w", "/app",
		"maven:3.9-eclipse-temurin-22",
		"mvn", "-f", fmt.Sprintf("%s/pom.xml", module),
	}
	args = append(args, goals...)

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
		"maven:3.9-eclipse-temurin-22",
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
		"maven:3.9-eclipse-temurin-22",
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
	components := []string{"http", "kafka-producer", "kafka", "flink", "drools", "gateway", "full"}
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

// analyzeBottleneck fetches traces from Jaeger and outputs span timing data for AI analysis.
// This function provides DATA only - interpretation should be done by the AI.
func analyzeBottleneck(resultsFile string) {
	// Read results to get total operations
	var totalOps int64
	data, err := os.ReadFile(resultsFile)
	if err == nil {
		var result struct {
			TotalOperations int64 `json:"totalOperations"`
		}
		json.Unmarshal(data, &result)
		totalOps = result.TotalOperations
	}

	fmt.Println()
	fmt.Println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
	fmt.Println("  PIPELINE ANALYSIS")
	fmt.Println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
	fmt.Println()
	fmt.Println("  Architecture: Fire-and-Forget with Async Processing")
	fmt.Println("  ─────────────────────────────────────────────────────────")
	fmt.Println("  1. THROUGHPUT PATH (sync):  HTTP → Kafka publish → response")
	fmt.Println("  2. PROCESSING PATH (async): Kafka → Flink → Drools (snapshots)")
	fmt.Println()

	// Fetch traces from Jaeger for each service
	services := []string{"counter-application", "flink-taskmanager", "drools"}
	spanStats := make(map[string][]int64)
	serviceSpans := make(map[string]map[string][]int64)

	for _, svc := range services {
		serviceSpans[svc] = make(map[string][]int64)
		traces := fetchJaegerTraces(svc, 20)
		for _, trace := range traces {
			for _, span := range trace.Spans {
				spanStats[span.OperationName] = append(spanStats[span.OperationName], span.Duration)
				serviceSpans[svc][span.OperationName] = append(serviceSpans[svc][span.OperationName], span.Duration)
			}
		}
	}

	if len(spanStats) == 0 {
		printWarning("No traces found in Jaeger. Ensure tracing is configured correctly.")
		return
	}

	// Calculate statistics for all spans
	type spanStat struct {
		name   string
		avgMs  float64
		minMs  float64
		maxMs  float64
		p50Ms  float64
		p99Ms  float64
		count  int
	}
	var spans []spanStat

	for name, durations := range spanStats {
		if len(durations) == 0 {
			continue
		}

		// Sort for percentiles
		sorted := make([]int64, len(durations))
		copy(sorted, durations)
		for i := 0; i < len(sorted)-1; i++ {
			for j := i + 1; j < len(sorted); j++ {
				if sorted[j] < sorted[i] {
					sorted[i], sorted[j] = sorted[j], sorted[i]
				}
			}
		}

		var sum int64
		for _, d := range sorted {
			sum += d
		}

		p50Idx := len(sorted) / 2
		p99Idx := int(float64(len(sorted)) * 0.99)
		if p99Idx >= len(sorted) {
			p99Idx = len(sorted) - 1
		}

		spans = append(spans, spanStat{
			name:  name,
			avgMs: float64(sum) / float64(len(sorted)) / 1000,
			minMs: float64(sorted[0]) / 1000,
			maxMs: float64(sorted[len(sorted)-1]) / 1000,
			p50Ms: float64(sorted[p50Idx]) / 1000,
			p99Ms: float64(sorted[p99Idx]) / 1000,
			count: len(sorted),
		})
	}

	// Sort by average duration (descending)
	for i := 0; i < len(spans)-1; i++ {
		for j := i + 1; j < len(spans); j++ {
			if spans[j].avgMs > spans[i].avgMs {
				spans[i], spans[j] = spans[j], spans[i]
			}
		}
	}

	// Categorize spans into throughput path vs processing path
	throughputOps := []string{"POST /api/counter", "CounterController.submit", "kafka.publish.fast", "counter-events publish"}

	isThroughputOp := func(name string) bool {
		for _, op := range throughputOps {
			if strings.Contains(name, op) || name == op {
				return true
			}
		}
		return false
	}

	// Print throughput path operations
	fmt.Println("  THROUGHPUT PATH (affects ops/s):")
	fmt.Println("  ─────────────────────────────────────────────────────────")
	fmt.Printf("  %-35s %8s %8s %6s\n", "Operation", "Avg(ms)", "P99(ms)", "Count")
	for _, s := range spans {
		if isThroughputOp(s.name) {
			fmt.Printf("  %-35s %8.1f %8.1f %6d\n", truncate(s.name, 35), s.avgMs, s.p99Ms, s.count)
		}
	}

	// Print processing path operations with batching ratio
	fmt.Println()
	fmt.Println("  PROCESSING PATH (async, snapshot-based):")
	fmt.Println("  ─────────────────────────────────────────────────────────")
	fmt.Printf("  %-35s %8s %8s %6s %10s\n", "Operation", "Avg(ms)", "P99(ms)", "Count", "Batch Ratio")
	for _, s := range spans {
		if !isThroughputOp(s.name) {
			batchRatio := ""
			if totalOps > 0 && s.count > 0 {
				ratio := float64(totalOps) / float64(s.count)
				if ratio > 10 {
					batchRatio = fmt.Sprintf("1:%d", int(ratio))
				}
			}
			fmt.Printf("  %-35s %8.1f %8.1f %6d %10s\n", truncate(s.name, 35), s.avgMs, s.p99Ms, s.count, batchRatio)
		}
	}

	// Summary with batch ratio explanation
	fmt.Println()
	fmt.Println("  ─────────────────────────────────────────────────────────")
	if totalOps > 0 {
		// Find drools call count
		droolsCount := 0
		for _, s := range spans {
			if strings.Contains(s.name, "drools") || s.name == "POST" {
				if s.count > droolsCount {
					droolsCount = s.count
				}
			}
		}
		if droolsCount > 0 {
			ratio := totalOps / int64(droolsCount)
			fmt.Printf("  Total events: %d | Drools calls: %d | Batch ratio: 1:%d\n", totalOps, droolsCount, ratio)
			fmt.Println("  → Drools latency does NOT limit throughput (snapshot-based)")
		}
	}

	// Add timing data to results.json for AI analysis
	timingData := make(map[string]interface{})
	for _, s := range spans {
		timingData[s.name] = map[string]interface{}{
			"avgMs":       s.avgMs,
			"minMs":       s.minMs,
			"maxMs":       s.maxMs,
			"p50Ms":       s.p50Ms,
			"p99Ms":       s.p99Ms,
			"count":       s.count,
			"isThroughput": isThroughputOp(s.name),
		}
	}

	// Update results.json with trace timing data
	updateResultsWithTraceData(resultsFile, timingData)

	fmt.Println()
	fmt.Println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
}

func updateResultsWithTraceData(resultsFile string, timingData map[string]interface{}) {
	data, err := os.ReadFile(resultsFile)
	if err != nil {
		return
	}

	var result map[string]interface{}
	if json.Unmarshal(data, &result) != nil {
		return
	}

	result["traceTimingData"] = timingData

	updatedData, err := json.MarshalIndent(result, "", "  ")
	if err != nil {
		return
	}

	os.WriteFile(resultsFile, updatedData, 0644)
}

type jaegerTrace struct {
	TraceID string       `json:"traceID"`
	Spans   []jaegerSpan `json:"spans"`
}

type jaegerSpan struct {
	OperationName string `json:"operationName"`
	Duration      int64  `json:"duration"` // microseconds
}

func fetchJaegerTraces(service string, limit int) []jaegerTrace {
	url := fmt.Sprintf("http://localhost:16686/api/traces?service=%s&limit=%d&lookback=5m", service, limit)

	cmd := exec.Command("curl", "-s", url)
	output, err := cmd.Output()
	if err != nil {
		return nil
	}

	var response struct {
		Data []jaegerTrace `json:"data"`
	}
	if json.Unmarshal(output, &response) != nil {
		return nil
	}

	return response.Data
}

func truncate(s string, maxLen int) string {
	if len(s) <= maxLen {
		return s
	}
	return s[:maxLen-3] + "..."
}

// copyDir recursively copies a directory
func copyDir(src, dst string) error {
	return filepath.Walk(src, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}

		relPath, err := filepath.Rel(src, path)
		if err != nil {
			return err
		}
		dstPath := filepath.Join(dst, relPath)

		if info.IsDir() {
			return os.MkdirAll(dstPath, info.Mode())
		}

		srcFile, err := os.Open(path)
		if err != nil {
			return err
		}
		defer srcFile.Close()

		dstFile, err := os.Create(dstPath)
		if err != nil {
			return err
		}
		defer dstFile.Close()

		_, err = io.Copy(dstFile, srcFile)
		return err
	})
}
