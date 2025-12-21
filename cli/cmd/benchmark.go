package cmd

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os/exec"
	"runtime"
	"strings"
	"time"

	"github.com/spf13/cobra"
)

var (
	benchmarkURL     = "http://localhost:8090"
	benchmarkAPIKey  = "reactive-admin-key"
	benchmarkDuration int
	benchmarkComponent string
)

var benchmarkCmd = &cobra.Command{
	Use:   "benchmark",
	Short: "Run and manage benchmarks",
	Long: `Run benchmarks and view reports.

Examples:
  reactive benchmark run              # Run full benchmark
  reactive benchmark run --component drools
  reactive benchmark run --duration 30
  reactive benchmark status           # Check if benchmark is running
  reactive benchmark report           # Open benchmark report
  reactive benchmark doctor           # Diagnose benchmark results`,
}

var benchmarkRunCmd = &cobra.Command{
	Use:   "run",
	Short: "Run a benchmark",
	Long: `Run a benchmark for a specific component or full pipeline.

Components: full, http, kafka, flink, drools, gateway`,
	Run: runBenchmark,
}

var benchmarkStatusCmd = &cobra.Command{
	Use:   "status",
	Short: "Check benchmark status",
	Run:   runBenchmarkStatus,
}

var benchmarkReportCmd = &cobra.Command{
	Use:   "report",
	Short: "Open benchmark report in browser",
	Run:   runBenchmarkReport,
}

var benchmarkDoctorCmd = &cobra.Command{
	Use:   "doctor",
	Short: "Diagnose benchmark results",
	Run:   runBenchmarkDoctor,
}

var benchmarkBuildUICmd = &cobra.Command{
	Use:   "build-ui",
	Short: "Build benchmark UI assets",
	Long: `Build the React-based benchmark report UI.

This compiles the React components into static JavaScript bundles
that are loaded by the generated HTML reports.`,
	Run: runBenchmarkBuildUI,
}

func init() {
	benchmarkRunCmd.Flags().IntVarP(&benchmarkDuration, "duration", "d", 30, "Duration in seconds")
	benchmarkRunCmd.Flags().StringVarP(&benchmarkComponent, "component", "c", "full", "Component to benchmark (full, http, kafka, flink, drools, gateway)")

	benchmarkCmd.AddCommand(benchmarkRunCmd)
	benchmarkCmd.AddCommand(benchmarkStatusCmd)
	benchmarkCmd.AddCommand(benchmarkReportCmd)
	benchmarkCmd.AddCommand(benchmarkDoctorCmd)
	benchmarkCmd.AddCommand(benchmarkBuildUICmd)
}

type BenchmarkConfig struct {
	DurationMs  int64 `json:"durationMs"`
	WarmupMs    int64 `json:"warmupMs"`
	CooldownMs  int64 `json:"cooldownMs"`
	Concurrency int   `json:"concurrency"`
}

type BenchmarkResult struct {
	Component            string  `json:"component"`
	Status               string  `json:"status"`
	TotalOperations      int64   `json:"totalOperations"`
	SuccessfulOperations int64   `json:"successfulOperations"`
	FailedOperations     int64   `json:"failedOperations"`
	AvgThroughput        int64   `json:"avgThroughput"`
	PeakThroughput       int64   `json:"peakThroughput"`
	Latency              struct {
		P50 int64 `json:"p50"`
		P95 int64 `json:"p95"`
		P99 int64 `json:"p99"`
		Max int64 `json:"max"`
	} `json:"latency"`
	SampleEvents []struct {
		ID          string `json:"id"`
		TraceID     string `json:"traceId"`
		OtelTraceID string `json:"otelTraceId"`
		Status      string `json:"status"`
		LatencyMs   int64  `json:"latencyMs"`
		TraceData   struct {
			Trace struct {
				Spans     []interface{}          `json:"spans"`
				Processes map[string]interface{} `json:"processes"`
			} `json:"trace"`
			Logs []interface{} `json:"logs"`
		} `json:"traceData"`
	} `json:"sampleEvents"`
}

func runBenchmark(cmd *cobra.Command, args []string) {
	config := BenchmarkConfig{
		DurationMs:  int64(benchmarkDuration * 1000),
		WarmupMs:    3000,
		CooldownMs:  2000,
		Concurrency: 4,
	}

	configJSON, _ := json.Marshal(config)

	url := fmt.Sprintf("%s/api/benchmark/%s", benchmarkURL, benchmarkComponent)
	req, err := http.NewRequest("POST", url, bytes.NewReader(configJSON))
	if err != nil {
		fmt.Printf("Error creating request: %v\n", err)
		return
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-API-Key", benchmarkAPIKey)

	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		fmt.Printf("Error: Cannot connect to benchmark service at %s\n", benchmarkURL)
		fmt.Println("Make sure the benchmark service is running")
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode == 409 {
		fmt.Println("A benchmark is already running. Use 'reactive benchmark status' to check.")
		return
	}

	if resp.StatusCode != 200 {
		body, _ := io.ReadAll(resp.Body)
		fmt.Printf("Error: %s\n", string(body))
		return
	}

	fmt.Printf("Starting %s benchmark (duration: %ds)...\n", benchmarkComponent, benchmarkDuration)
	fmt.Println()

	// Poll for completion
	startTime := time.Now()
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			elapsed := time.Since(startTime)

			// Check status
			statusReq, _ := http.NewRequest("GET", benchmarkURL+"/api/benchmark/status", nil)
			statusReq.Header.Set("X-API-Key", benchmarkAPIKey)
			statusResp, err := client.Do(statusReq)
			if err != nil {
				continue
			}

			var status struct {
				Running bool `json:"running"`
			}
			json.NewDecoder(statusResp.Body).Decode(&status)
			statusResp.Body.Close()

			if !status.Running {
				fmt.Printf("\rBenchmark completed in %.0fs\n", elapsed.Seconds())
				fmt.Println()
				showBenchmarkResults(benchmarkComponent)
				return
			}

			fmt.Printf("\rRunning... %.0fs elapsed", elapsed.Seconds())
		}

		if time.Since(startTime) > time.Duration(benchmarkDuration+60)*time.Second {
			fmt.Println("\nBenchmark timed out")
			return
		}
	}
}

func showBenchmarkResults(component string) {
	url := fmt.Sprintf("%s/api/benchmark/results/%s", benchmarkURL, component)
	req, _ := http.NewRequest("GET", url, nil)
	req.Header.Set("X-API-Key", benchmarkAPIKey)

	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		fmt.Printf("Error fetching results: %v\n", err)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != 200 {
		fmt.Println("No results found")
		return
	}

	var result BenchmarkResult
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		fmt.Printf("Error parsing results: %v\n", err)
		return
	}

	fmt.Println("Benchmark Results")
	fmt.Println(strings.Repeat("=", 60))
	fmt.Printf("Component:    %s\n", result.Component)
	fmt.Printf("Status:       %s\n", result.Status)
	fmt.Println()

	fmt.Println("Throughput")
	fmt.Println(strings.Repeat("-", 40))
	fmt.Printf("  Total Operations:    %d\n", result.TotalOperations)
	fmt.Printf("  Successful:          %d\n", result.SuccessfulOperations)
	fmt.Printf("  Failed:              %d\n", result.FailedOperations)
	fmt.Printf("  Avg Throughput:      %d ops/sec\n", result.AvgThroughput)
	fmt.Printf("  Peak Throughput:     %d ops/sec\n", result.PeakThroughput)
	fmt.Println()

	fmt.Println("Latency (ms)")
	fmt.Println(strings.Repeat("-", 40))
	fmt.Printf("  P50:   %d\n", result.Latency.P50)
	fmt.Printf("  P95:   %d\n", result.Latency.P95)
	fmt.Printf("  P99:   %d\n", result.Latency.P99)
	fmt.Printf("  Max:   %d\n", result.Latency.Max)
	fmt.Println()

	if len(result.SampleEvents) > 0 {
		fmt.Println("Sample Events")
		fmt.Println(strings.Repeat("-", 40))
		for _, sample := range result.SampleEvents {
			spanCount := len(sample.TraceData.Trace.Spans)
			logCount := len(sample.TraceData.Logs)

			services := []string{}
			for _, proc := range sample.TraceData.Trace.Processes {
				if p, ok := proc.(map[string]interface{}); ok {
					if svc, ok := p["serviceName"].(string); ok {
						// Dedupe
						found := false
						for _, s := range services {
							if s == svc {
								found = true
								break
							}
						}
						if !found {
							services = append(services, svc)
						}
					}
				}
			}

			status := "✓"
			if sample.Status != "success" {
				status = "✗"
			}
			fmt.Printf("  %s %s: %d spans, %d logs\n", status, sample.ID[:20], spanCount, logCount)
			if spanCount > 0 {
				fmt.Printf("      Services: %s\n", strings.Join(services, ", "))
			}
			if sample.OtelTraceID != "" {
				fmt.Printf("      Trace: reactive trace %s\n", sample.OtelTraceID)
			}
		}
	}
	fmt.Println()
	fmt.Println("View full report: reactive benchmark report")
}

func runBenchmarkStatus(cmd *cobra.Command, args []string) {
	req, _ := http.NewRequest("GET", benchmarkURL+"/api/benchmark/status", nil)
	req.Header.Set("X-API-Key", benchmarkAPIKey)

	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		fmt.Printf("Error: Cannot connect to benchmark service at %s\n", benchmarkURL)
		return
	}
	defer resp.Body.Close()

	var status struct {
		Running    bool     `json:"running"`
		Components []string `json:"components"`
	}
	json.NewDecoder(resp.Body).Decode(&status)

	if status.Running {
		fmt.Println("Benchmark is running")
	} else {
		fmt.Println("No benchmark running")
	}
	fmt.Printf("Available components: %s\n", strings.Join(status.Components, ", "))
}

func runBenchmarkReport(cmd *cobra.Command, args []string) {
	reportURL := "http://localhost:8090/reports/full/index.html"

	var openCmd string
	var openArgs []string

	switch runtime.GOOS {
	case "darwin":
		openCmd = "open"
		openArgs = []string{reportURL}
	case "linux":
		openCmd = "xdg-open"
		openArgs = []string{reportURL}
	case "windows":
		openCmd = "cmd"
		openArgs = []string{"/c", "start", reportURL}
	default:
		fmt.Printf("Open in browser: %s\n", reportURL)
		return
	}

	if err := exec.Command(openCmd, openArgs...).Start(); err != nil {
		fmt.Printf("Could not open browser. Visit: %s\n", reportURL)
	} else {
		fmt.Printf("Opening report in browser: %s\n", reportURL)
	}
}

func runBenchmarkDoctor(cmd *cobra.Command, args []string) {
	fmt.Println("Benchmark Diagnostics")
	fmt.Println(strings.Repeat("=", 60))
	fmt.Println()

	// Fetch results
	req, _ := http.NewRequest("GET", benchmarkURL+"/api/benchmark/results/full", nil)
	req.Header.Set("X-API-Key", benchmarkAPIKey)

	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		fmt.Printf("Error: Cannot connect to benchmark service at %s\n", benchmarkURL)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != 200 {
		fmt.Println("No benchmark results found. Run a benchmark first:")
		fmt.Println("  reactive benchmark run")
		return
	}

	var result BenchmarkResult
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		fmt.Printf("Error parsing results: %v\n", err)
		return
	}

	// Analyze results
	issues := []string{}

	if result.FailedOperations > 0 {
		failRate := float64(result.FailedOperations) / float64(result.TotalOperations) * 100
		issues = append(issues, fmt.Sprintf("%.1f%% failure rate (%d/%d)", failRate, result.FailedOperations, result.TotalOperations))
	}

	if result.Latency.P99 > 100 {
		issues = append(issues, fmt.Sprintf("High P99 latency: %dms", result.Latency.P99))
	}

	// Check sample events
	samplesWithTraces := 0
	samplesWithFullTraces := 0
	samplesWithLogs := 0

	for _, sample := range result.SampleEvents {
		spanCount := len(sample.TraceData.Trace.Spans)
		logCount := len(sample.TraceData.Logs)

		if spanCount > 0 {
			samplesWithTraces++

			// Count services
			services := make(map[string]bool)
			for _, proc := range sample.TraceData.Trace.Processes {
				if p, ok := proc.(map[string]interface{}); ok {
					if svc, ok := p["serviceName"].(string); ok {
						services[svc] = true
					}
				}
			}
			if len(services) >= 3 {
				samplesWithFullTraces++
			}
		}

		if logCount > 0 {
			samplesWithLogs++
		}
	}

	fmt.Println("Sample Events Analysis")
	fmt.Println(strings.Repeat("-", 40))
	fmt.Printf("  Total samples:        %d\n", len(result.SampleEvents))
	fmt.Printf("  With traces:          %d\n", samplesWithTraces)
	fmt.Printf("  With full traces:     %d (3+ services)\n", samplesWithFullTraces)
	fmt.Printf("  With logs:            %d\n", samplesWithLogs)
	fmt.Println()

	if samplesWithTraces < len(result.SampleEvents) {
		issues = append(issues, fmt.Sprintf("Only %d/%d samples have traces", samplesWithTraces, len(result.SampleEvents)))
	}

	if samplesWithFullTraces < samplesWithTraces {
		issues = append(issues, fmt.Sprintf("Only %d/%d traces are complete (3+ services)", samplesWithFullTraces, samplesWithTraces))
	}

	if samplesWithLogs < len(result.SampleEvents) {
		issues = append(issues, fmt.Sprintf("Only %d/%d samples have logs", samplesWithLogs, len(result.SampleEvents)))
	}

	if len(issues) > 0 {
		fmt.Println("Issues Found")
		fmt.Println(strings.Repeat("-", 40))
		for _, issue := range issues {
			fmt.Printf("  ⚠ %s\n", issue)
		}
		fmt.Println()
	} else {
		fmt.Println("✓ No issues found")
		fmt.Println()
	}

	// Show sample details
	fmt.Println("Sample Details")
	fmt.Println(strings.Repeat("-", 40))
	for i, sample := range result.SampleEvents {
		spanCount := len(sample.TraceData.Trace.Spans)
		logCount := len(sample.TraceData.Logs)

		services := []string{}
		for _, proc := range sample.TraceData.Trace.Processes {
			if p, ok := proc.(map[string]interface{}); ok {
				if svc, ok := p["serviceName"].(string); ok {
					found := false
					for _, s := range services {
						if s == svc {
							found = true
							break
						}
					}
					if !found {
						services = append(services, svc)
					}
				}
			}
		}

		fmt.Printf("Sample %d:\n", i+1)
		fmt.Printf("  TraceID:  %s\n", sample.OtelTraceID)
		fmt.Printf("  Spans:    %d\n", spanCount)
		fmt.Printf("  Logs:     %d\n", logCount)
		fmt.Printf("  Services: %s\n", strings.Join(services, ", "))
		fmt.Println()
	}
}

func runBenchmarkBuildUI(cmd *cobra.Command, args []string) {
	fmt.Println("Building Benchmark UI Assets")
	fmt.Println(strings.Repeat("=", 60))
	fmt.Println()

	// Find the ui directory - try common paths
	uiPaths := []string{"ui", "../ui", "../../ui"}
	var uiDir string
	for _, path := range uiPaths {
		checkCmd := exec.Command("ls", path+"/package.json")
		if err := checkCmd.Run(); err == nil {
			uiDir = path
			break
		}
	}

	if uiDir == "" {
		fmt.Println("Error: Could not find ui directory")
		fmt.Println("Please run this command from the project root")
		return
	}

	fmt.Printf("Found UI directory: %s\n", uiDir)
	fmt.Println()

	// Check if npm is installed
	if _, err := exec.LookPath("npm"); err != nil {
		fmt.Println("Error: npm is not installed")
		fmt.Println("Please install Node.js and npm first")
		return
	}

	// Run npm install
	fmt.Println("Installing dependencies...")
	installCmd := exec.Command("npm", "install")
	installCmd.Dir = uiDir
	if err := installCmd.Run(); err != nil {
		fmt.Printf("Warning: npm install had issues: %v\n", err)
	}
	fmt.Println("  Done")
	fmt.Println()

	// Run npm run build:benchmark
	fmt.Println("Building benchmark bundles...")
	buildCmd := exec.Command("npm", "run", "build:benchmark")
	buildCmd.Dir = uiDir
	output, err := buildCmd.CombinedOutput()
	if err != nil {
		fmt.Printf("Error building UI: %v\n", err)
		fmt.Println(string(output))
		return
	}
	fmt.Println("  Done")
	fmt.Println()

	fmt.Println("Build completed successfully!")
	fmt.Println()
	fmt.Println("Generated files:")
	fmt.Println("  reports/assets/benchmark-report.js  (for individual reports)")
	fmt.Println("  reports/assets/benchmark-index.js   (for dashboard)")
}
