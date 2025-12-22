package cmd

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"github.com/spf13/cobra"
)

const (
	pressureLow      = 50
	pressureMedium   = 75
	pressureHigh     = 90
	pressureCritical = 95
)

var diagnoseTargetCmd = &cobra.Command{
	Use:   "memory [subcmd]",
	Short: "Memory and performance diagnostics",
	Long: `Full diagnostic suite including:
  - overview: Container memory usage
  - jvm: JVM heap details for Java services
  - pressure: Visual pressure bars per component
  - risk: Risk assessment with recommendations
  - crashes: Crash history tracking
  - verdict: Actionable conclusions
  - watch: Live memory monitoring
  - heap <svc>: Take heap dump
  - jfr <svc> [dur]: Java Flight Recorder

Examples:
  reactive memory             # Full report
  reactive memory pressure    # Just pressure view
  reactive memory jvm         # JVM details
  reactive memory heap drools # Take heap dump`,
	Run: runDiagnoseMemory,
}

func init() {
	rootCmd.AddCommand(diagnoseTargetCmd)
}

type containerStats struct {
	Name       string
	MemUsage   string
	MemPercent int
	CPUPercent string
}

func runDiagnoseMemory(cmd *cobra.Command, args []string) {
	subcmd := "overview"
	if len(args) > 0 {
		subcmd = args[0]
	}

	switch subcmd {
	case "overview", "status":
		showMemoryOverview()
	case "jvm":
		showJVMDetails()
	case "pressure", "summary":
		showPressureSummary()
	case "risk":
		showRiskAssessment()
	case "crashes", "history":
		showCrashHistory()
	case "verdict", "action":
		showVerdict()
	case "watch":
		watchMemory()
	case "heap":
		if len(args) < 2 {
			printError("Usage: reactive memory heap <service>")
			printInfo("Services: drools, flink-taskmanager")
			return
		}
		takeHeapDump(args[1])
	case "jfr":
		if len(args) < 2 {
			printError("Usage: reactive memory jfr <service> [duration_seconds]")
			printInfo("Services: drools, flink-taskmanager")
			return
		}
		duration := 60
		if len(args) > 2 {
			fmt.Sscanf(args[2], "%d", &duration)
		}
		startJFR(args[1], duration)
	case "recommend", "recommendations":
		showRecommendations()
	case "all":
		showMemoryOverview()
		showJVMDetails()
		showRecommendations()
	case "diagnose", "full":
		showPressureSummary()
		showRiskAssessment()
		showCrashHistory()
		showRecommendations()
		showVerdict()
	default:
		printError(fmt.Sprintf("Unknown subcommand: %s", subcmd))
		printInfo("Valid: overview, jvm, pressure, risk, crashes, verdict, watch, heap, jfr, recommend, all, diagnose")
	}
}

func getContainerStats() []containerStats {
	cmd := exec.Command("docker", "stats", "--no-stream", "--format", "{{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.CPUPerc}}")
	output, err := cmd.Output()
	if err != nil {
		return nil
	}

	var stats []containerStats
	lines := strings.Split(strings.TrimSpace(string(output)), "\n")
	for _, line := range lines {
		if !strings.Contains(line, "reactive") && !strings.Contains(line, "flink") {
			continue
		}
		parts := strings.Split(line, "\t")
		if len(parts) < 4 {
			continue
		}

		pct := 0
		pctStr := strings.TrimSuffix(parts[2], "%")
		fmt.Sscanf(pctStr, "%d", &pct)

		stats = append(stats, containerStats{
			Name:       parts[0],
			MemUsage:   parts[1],
			MemPercent: pct,
			CPUPercent: parts[3],
		})
	}
	return stats
}

func showMemoryOverview() {
	printHeader("Memory Overview - All Services")

	fmt.Printf("%-30s %-20s %-10s\n", "Container", "Memory Used", "Percent")
	fmt.Println(strings.Repeat("-", 65))

	stats := getContainerStats()
	if len(stats) == 0 {
		printError("No containers running")
		return
	}

	for _, s := range stats {
		fmt.Printf("%-30s %-20s %d%%\n", s.Name, s.MemUsage, s.MemPercent)
	}
	fmt.Println()
}

func showJVMDetails() {
	printHeader("JVM Memory Details")
	client := &http.Client{Timeout: 5 * time.Second}

	// Drools (Spring Boot Actuator)
	fmt.Println("── Drools (Spring Boot) ──")
	if heapUsed := getSpringMetric(client, "http://localhost:8180/actuator/metrics/jvm.memory.used", "area:heap"); heapUsed != "" {
		fmt.Printf("Heap Used:    %s\n", heapUsed)
	}
	if heapMax := getSpringMetric(client, "http://localhost:8180/actuator/metrics/jvm.memory.max", "area:heap"); heapMax != "" {
		fmt.Printf("Heap Max:     %s\n", heapMax)
	}
	if threads := getSpringMetricRaw(client, "http://localhost:8180/actuator/metrics/jvm.threads.live"); threads != "" {
		fmt.Printf("Threads:      %s\n", threads)
	}
	fmt.Println()

	// Flink TaskManager
	fmt.Println("── Flink TaskManager ──")
	if metrics := getFlinkMetrics(client); metrics != nil {
		if v, ok := metrics["flink_taskmanager_Status_JVM_Memory_Heap_Used"]; ok {
			fmt.Printf("Heap Used:    %.1f MB\n", v/1024/1024)
		}
		if v, ok := metrics["flink_taskmanager_Status_JVM_Memory_Heap_Max"]; ok {
			fmt.Printf("Heap Max:     %.1f MB\n", v/1024/1024)
		}
		if v, ok := metrics["flink_taskmanager_Status_JVM_Memory_NonHeap_Used"]; ok {
			fmt.Printf("Non-Heap:     %.1f MB\n", v/1024/1024)
		}
		if v, ok := metrics["flink_taskmanager_Status_JVM_Memory_Direct_Used"]; ok {
			fmt.Printf("Direct Mem:   %.1f MB\n", v/1024/1024)
		}
	} else {
		printWarning("Flink TaskManager metrics not reachable")
	}
	fmt.Println()
}

func getSpringMetric(client *http.Client, url, tag string) string {
	fullURL := url
	if tag != "" {
		fullURL = fmt.Sprintf("%s?tag=%s", url, tag)
	}

	resp, err := client.Get(fullURL)
	if err != nil {
		return ""
	}
	defer resp.Body.Close()

	var result struct {
		Measurements []struct {
			Value float64 `json:"value"`
		} `json:"measurements"`
	}
	body, _ := io.ReadAll(resp.Body)
	if json.Unmarshal(body, &result) == nil && len(result.Measurements) > 0 {
		return fmt.Sprintf("%.1f MB", result.Measurements[0].Value/1024/1024)
	}
	return ""
}

func getSpringMetricRaw(client *http.Client, url string) string {
	resp, err := client.Get(url)
	if err != nil {
		return ""
	}
	defer resp.Body.Close()

	var result struct {
		Measurements []struct {
			Value float64 `json:"value"`
		} `json:"measurements"`
	}
	body, _ := io.ReadAll(resp.Body)
	if json.Unmarshal(body, &result) == nil && len(result.Measurements) > 0 {
		return fmt.Sprintf("%.0f", result.Measurements[0].Value)
	}
	return ""
}

func getFlinkMetrics(client *http.Client) map[string]float64 {
	resp, err := client.Get("http://localhost:9249/metrics")
	if err != nil {
		return nil
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	metrics := make(map[string]float64)

	lines := strings.Split(string(body), "\n")
	for _, line := range lines {
		if strings.HasPrefix(line, "#") || line == "" {
			continue
		}
		parts := strings.Fields(line)
		if len(parts) >= 2 {
			var val float64
			fmt.Sscanf(parts[1], "%f", &val)
			metrics[parts[0]] = val
		}
	}
	return metrics
}

func pressureBar(pct int) string {
	width := 10
	filled := pct * width / 100
	if filled > width {
		filled = width
	}
	bar := strings.Repeat("▓", filled) + strings.Repeat("░", width-filled)
	return bar
}

func pressureLevel(pct int) string {
	if pct < pressureLow {
		return "LOW"
	} else if pct < pressureMedium {
		return "MEDIUM"
	} else if pct < pressureHigh {
		return "HIGH"
	}
	return "CRITICAL"
}

func showPressureSummary() {
	printHeader("Memory Pressure Summary")

	fmt.Printf("  %-25s %18s %12s %12s\n", "COMPONENT", "USAGE", "PRESSURE", "STATUS")
	fmt.Println("  " + strings.Repeat("-", 70))

	stats := getContainerStats()
	for _, s := range stats {
		shortName := strings.TrimPrefix(s.Name, "reactive-")
		shortName = strings.TrimSuffix(shortName, "-system-01")

		fmt.Printf("  %-25s %18s %s %s\n",
			shortName, s.MemUsage, pressureBar(s.MemPercent), pressureLevel(s.MemPercent))
	}
	fmt.Println()
}

func showRiskAssessment() {
	printHeader("Crash Risk Assessment")

	riskScore := 0
	var riskFactors []string

	stats := getContainerStats()
	for _, s := range stats {
		shortName := strings.TrimPrefix(s.Name, "reactive-")
		shortName = strings.TrimSuffix(shortName, "-system-01")

		if s.MemPercent > 90 {
			riskScore += 30
			riskFactors = append(riskFactors, fmt.Sprintf("%s at %d%% memory (CRITICAL)", shortName, s.MemPercent))
		} else if s.MemPercent > 75 {
			riskScore += 15
			riskFactors = append(riskFactors, fmt.Sprintf("%s at %d%% memory (HIGH)", shortName, s.MemPercent))
		} else if s.MemPercent > 60 {
			riskScore += 5
			riskFactors = append(riskFactors, fmt.Sprintf("%s at %d%% memory (MEDIUM)", shortName, s.MemPercent))
		}
	}

	riskLevel := "LOW"
	if riskScore > 50 {
		riskLevel = "CRITICAL"
	} else if riskScore > 30 {
		riskLevel = "HIGH"
	} else if riskScore > 15 {
		riskLevel = "MEDIUM"
	}

	fmt.Printf("  Overall Risk: %s (score: %d/100)\n\n", riskLevel, riskScore)

	if len(riskFactors) > 0 {
		fmt.Println("── Risk Factors ──")
		for _, f := range riskFactors {
			fmt.Printf("  ⚠ %s\n", f)
		}
		fmt.Println()
	}

	fmt.Println("── Recommendations ──")
	if riskScore > 30 {
		printError("Reduce load or increase memory limits immediately")
		printError("Check for memory leaks in high-pressure components")
	} else if riskScore > 15 {
		printWarning("Monitor closely during benchmarks")
		printWarning("Consider increasing memory limits")
	} else {
		printSuccess("System is stable, safe to run benchmarks")
	}
	fmt.Println()
}

func showCrashHistory() {
	printHeader("Crash History & Stability")

	// Check for OOM-killed containers
	cmd := exec.Command("docker", "ps", "-a", "--format", "{{.Names}}")
	output, _ := cmd.Output()
	containers := strings.Split(strings.TrimSpace(string(output)), "\n")

	oomCount := 0
	for _, container := range containers {
		if !strings.Contains(container, "reactive") && !strings.Contains(container, "flink") {
			continue
		}
		inspectCmd := exec.Command("docker", "inspect", container, "--format", "{{.State.OOMKilled}}")
		out, _ := inspectCmd.Output()
		if strings.TrimSpace(string(out)) == "true" {
			oomCount++
			printError(fmt.Sprintf("OOM Detected: %s", container))
		}
	}

	if oomCount == 0 {
		printSuccess("No OOM kills detected in current containers")
	}

	// Count healthy containers
	healthy := 0
	total := 0
	for _, container := range containers {
		if !strings.Contains(container, "reactive") && !strings.Contains(container, "flink") {
			continue
		}
		total++
		inspectCmd := exec.Command("docker", "inspect", container, "--format", "{{.State.Status}}")
		out, _ := inspectCmd.Output()
		if strings.TrimSpace(string(out)) == "running" {
			healthy++
		}
	}

	fmt.Println()
	if healthy == total {
		printSuccess(fmt.Sprintf("All %d containers running", total))
	} else {
		printWarning(fmt.Sprintf("%d/%d containers running", healthy, total))
	}
	fmt.Println()
}

func showVerdict() {
	printHeader("VERDICT: Action Required")

	var dominatedBy string
	maxPressure := 0

	stats := getContainerStats()
	for _, s := range stats {
		if s.MemPercent > maxPressure {
			maxPressure = s.MemPercent
			dominatedBy = strings.TrimPrefix(s.Name, "reactive-")
			dominatedBy = strings.TrimSuffix(dominatedBy, "-system-01")
		}
	}

	if maxPressure > 90 {
		printError(fmt.Sprintf("CRITICAL: %s at %d%% memory", dominatedBy, maxPressure))
		fmt.Println()
		fmt.Println("  Immediate Actions:")
		if strings.Contains(dominatedBy, "flink") || strings.Contains(dominatedBy, "taskmanager") {
			fmt.Println("    1. Reduce ASYNC_CAPACITY in docker-compose.yml")
			fmt.Println("    2. Increase taskmanager.memory.process.size")
			fmt.Println("    3. Check for state accumulation")
		} else if strings.Contains(dominatedBy, "drools") {
			fmt.Println("    1. Increase Drools memory limit")
			fmt.Println("    2. Check rule session leaks")
		} else if strings.Contains(dominatedBy, "jaeger") {
			fmt.Println("    1. Reduce MEMORY_MAX_TRACES")
			fmt.Println("    2. Increase Jaeger memory limit")
		} else {
			fmt.Println("    1. Increase memory limit for", dominatedBy)
			fmt.Println("    2. Check for memory leaks with heap dump")
		}
	} else if maxPressure > 75 {
		printWarning(fmt.Sprintf("WARNING: %s at %d%% memory", dominatedBy, maxPressure))
		fmt.Println()
		fmt.Println("  Recommended Actions:")
		fmt.Println("    1. Monitor", dominatedBy, "during benchmark")
		fmt.Println("    2. Consider increasing memory limit before heavy load")
	} else if maxPressure > 60 {
		printWarning(fmt.Sprintf("CAUTION: %s at %d%% memory", dominatedBy, maxPressure))
		fmt.Println()
		fmt.Println("  Optional Actions:")
		fmt.Println("    1. Watch:", dominatedBy)
	} else {
		printSuccess("ALL CLEAR: System healthy")
		fmt.Println()
		fmt.Printf("  Memory pressure is low across all components.\n")
		fmt.Printf("  Highest: %s at %d%%\n", dominatedBy, maxPressure)
		fmt.Println()
		fmt.Println("  Next Step: Run benchmark to measure throughput")
		fmt.Println("    reactive bench full -d 30")
	}
	fmt.Println()
}

func showRecommendations() {
	printHeader("Memory Tuning Recommendations")

	fmt.Println("Current container limits vs actual usage:")
	fmt.Println()

	stats := getContainerStats()
	for _, s := range stats {
		shortName := strings.TrimPrefix(s.Name, "reactive-")
		shortName = strings.TrimSuffix(shortName, "-system-01")

		if s.MemPercent > 80 {
			fmt.Printf("%s: %s (%d%% - CRITICAL)\n", shortName, s.MemUsage, s.MemPercent)
			fmt.Println("  -> Increase memory limit or reduce load")
		} else if s.MemPercent > 60 {
			fmt.Printf("%s: %s (%d%% - WARNING)\n", shortName, s.MemUsage, s.MemPercent)
			fmt.Println("  -> Monitor closely under load")
		} else {
			fmt.Printf("%s: %s (%d%% - OK)\n", shortName, s.MemUsage, s.MemPercent)
		}
	}

	fmt.Println()
	fmt.Println("Tuning suggestions:")
	fmt.Println("1. Jaeger: Increase MEMORY_MAX_TRACES if OOM, or reduce trace retention")
	fmt.Println("2. OTEL Collector: Increase sampling rate under load (already adaptive)")
	fmt.Println("3. Flink: Tune taskmanager.memory.* settings for your workload")
	fmt.Println("4. Drools: Increase -Xmx if heap pressure, tune rule sessions")
	fmt.Println("5. Loki: Reduce retention period or increase memory")
	fmt.Println()
}

func watchMemory() {
	printHeader("Live Memory Monitor (Ctrl+C to stop)")

	containers := []string{
		"reactive-gateway",
		"reactive-drools",
		"reactive-flink-jobmanager",
		"reactive-flink-taskmanager",
		"reactive-kafka",
		"reactive-jaeger",
		"reactive-otel-collector",
		"reactive-prometheus",
		"reactive-loki",
		"reactive-grafana",
	}

	args := append([]string{"stats", "--format", "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.NetIO}}"}, containers...)
	cmd := exec.Command("docker", args...)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	cmd.Run()
}

func takeHeapDump(service string) {
	container := "reactive-" + service
	timestamp := time.Now().Format("20060102_150405")
	dumpFile := fmt.Sprintf("/tmp/heapdump_%s_%s.hprof", service, timestamp)

	printHeader(fmt.Sprintf("Taking Heap Dump: %s", service))

	// Check if container exists
	checkCmd := exec.Command("docker", "ps", "--format", "{{.Names}}")
	output, _ := checkCmd.Output()
	if !strings.Contains(string(output), container) {
		printError(fmt.Sprintf("Container %s is not running", container))
		return
	}

	// Find Java PID
	printInfo("Finding Java PID in container...")
	pidCmd := exec.Command("docker", "exec", container, "pgrep", "-f", "java")
	pidOut, err := pidCmd.Output()
	if err != nil {
		printError(fmt.Sprintf("No Java process found in %s", container))
		return
	}
	pid := strings.TrimSpace(strings.Split(string(pidOut), "\n")[0])
	fmt.Printf("Java PID: %s\n", pid)

	// Take heap dump
	printInfo("Taking heap dump (this may take a moment)...")
	jmapCmd := exec.Command("docker", "exec", container, "jmap",
		fmt.Sprintf("-dump:format=b,file=/tmp/heapdump.hprof"), pid)
	jmapCmd.Stdout = os.Stdout
	jmapCmd.Stderr = os.Stderr
	if err := jmapCmd.Run(); err != nil {
		printError("Failed to take heap dump")
		return
	}

	// Copy to host
	printInfo("Copying heap dump to host...")
	cpCmd := exec.Command("docker", "cp", container+":/tmp/heapdump.hprof", dumpFile)
	if err := cpCmd.Run(); err != nil {
		printError("Failed to copy heap dump")
		return
	}

	// Cleanup container
	exec.Command("docker", "exec", container, "rm", "-f", "/tmp/heapdump.hprof").Run()

	// Get file size
	if fi, err := os.Stat(dumpFile); err == nil {
		printSuccess(fmt.Sprintf("Heap dump saved: %s (%d MB)", dumpFile, fi.Size()/1024/1024))
	}

	fmt.Println()
	fmt.Println("Analyze with:")
	fmt.Println("  - Eclipse MAT: https://www.eclipse.org/mat/")
	fmt.Println("  - VisualVM: visualvm --openfile", dumpFile)
	fmt.Println("  - jhat:", "jhat", dumpFile)
}

func startJFR(service string, duration int) {
	container := "reactive-" + service
	timestamp := time.Now().Format("20060102_150405")
	jfrFile := fmt.Sprintf("/tmp/recording_%s_%s.jfr", service, timestamp)

	printHeader(fmt.Sprintf("Starting Java Flight Recorder: %s", service))

	// Check if container exists
	checkCmd := exec.Command("docker", "ps", "--format", "{{.Names}}")
	output, _ := checkCmd.Output()
	if !strings.Contains(string(output), container) {
		printError(fmt.Sprintf("Container %s is not running", container))
		return
	}

	// Find Java PID
	printInfo("Finding Java PID in container...")
	pidCmd := exec.Command("docker", "exec", container, "pgrep", "-f", "java")
	pidOut, err := pidCmd.Output()
	if err != nil {
		printError(fmt.Sprintf("No Java process found in %s", container))
		return
	}
	pid := strings.TrimSpace(strings.Split(string(pidOut), "\n")[0])
	fmt.Printf("Java PID: %s\n", pid)

	// Start JFR
	printInfo(fmt.Sprintf("Starting %ds recording...", duration))
	jfrCmd := exec.Command("docker", "exec", container, "jcmd", pid, "JFR.start",
		"name=diag",
		fmt.Sprintf("duration=%ds", duration),
		"filename=/tmp/recording.jfr",
		"settings=profile")
	jfrCmd.Stdout = os.Stdout
	jfrCmd.Stderr = os.Stderr
	if err := jfrCmd.Run(); err != nil {
		printError("Failed to start JFR")
		return
	}

	fmt.Printf("Recording started. Waiting %d seconds...\n", duration)
	time.Sleep(time.Duration(duration) * time.Second)

	// Copy to host
	printInfo("Copying recording to host...")
	cpCmd := exec.Command("docker", "cp", container+":/tmp/recording.jfr", jfrFile)
	if err := cpCmd.Run(); err != nil {
		printError("Failed to copy JFR recording")
		return
	}

	// Cleanup container
	exec.Command("docker", "exec", container, "rm", "-f", "/tmp/recording.jfr").Run()

	// Get file size
	if fi, err := os.Stat(jfrFile); err == nil {
		printSuccess(fmt.Sprintf("JFR recording saved: %s (%d KB)", jfrFile, fi.Size()/1024))
	}

	fmt.Println()
	fmt.Println("Analyze with:")
	fmt.Println("  - JDK Mission Control: jmc")
	fmt.Println("  - IntelliJ IDEA: File > Open > select .jfr file")
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
