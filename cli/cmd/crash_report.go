package cmd

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/spf13/cobra"
)

// ============================================================================
// AUTOMATIC CRASH DETECTION AND REPORTING
// Generates detailed post-mortem reports when components crash
// ============================================================================

var crashCmd = &cobra.Command{
	Use:   "crashes",
	Short: "View and analyze component crashes",
	Long: `Automatic crash detection and post-mortem analysis.

Shows detailed reports for any crashed components including:
- What crashed and when
- Exit code and reason (OOM, error, etc.)
- Memory state before crash
- GC activity leading to crash
- Recent logs before crash
- Root cause analysis
- Recommended fixes

Examples:
  reactive crashes              # Show all recent crashes
  reactive crashes --watch      # Monitor for crashes in real-time
  reactive crashes --component gateway  # Show crashes for specific component`,
	Run: runCrashReport,
}

var (
	crashWatch     bool
	crashComponent string
	crashLimit     int
)

func init() {
	crashCmd.Flags().BoolVar(&crashWatch, "watch", false, "Watch for crashes in real-time")
	crashCmd.Flags().StringVar(&crashComponent, "component", "", "Filter by component")
	crashCmd.Flags().IntVar(&crashLimit, "limit", 10, "Maximum crashes to show")
	rootCmd.AddCommand(crashCmd)
}

// ============================================================================
// DATA STRUCTURES
// ============================================================================

type CrashReport struct {
	Timestamp      string          `json:"timestamp"`
	Component      string          `json:"component"`
	ContainerID    string          `json:"containerId"`
	ContainerName  string          `json:"containerName"`
	ExitCode       int             `json:"exitCode"`
	ExitReason     string          `json:"exitReason"`
	OOMKilled      bool            `json:"oomKilled"`
	RestartCount   int             `json:"restartCount"`
	Uptime         string          `json:"uptime"`
	MemoryAtCrash  *MemorySnapshot `json:"memoryAtCrash,omitempty"`
	GCBeforeCrash  *GCSnapshot     `json:"gcBeforeCrash,omitempty"`
	LastLogs       []string        `json:"lastLogs"`
	ErrorPatterns  []string        `json:"errorPatterns"`
	RootCause      string          `json:"rootCause"`
	Recommendation string          `json:"recommendation"`
	Severity       string          `json:"severity"`
}

type MemorySnapshot struct {
	UsedBytes       int64   `json:"usedBytes"`
	LimitBytes      int64   `json:"limitBytes"`
	UsagePercent    float64 `json:"usagePercent"`
	HeapUsedBytes   int64   `json:"heapUsedBytes,omitempty"`
	HeapMaxBytes    int64   `json:"heapMaxBytes,omitempty"`
	HeapPercent     float64 `json:"heapPercent,omitempty"`
	NonHeapBytes    int64   `json:"nonHeapBytes,omitempty"`
	DirectMemory    int64   `json:"directMemory,omitempty"`
	NativeMemory    int64   `json:"nativeMemory,omitempty"`
}

type GCSnapshot struct {
	RecentPauses     int     `json:"recentPauses"`
	TotalPauseMs     float64 `json:"totalPauseMs"`
	MaxPauseMs       float64 `json:"maxPauseMs"`
	GCOverhead       float64 `json:"gcOverhead"`
	FullGCCount      int     `json:"fullGCCount"`
	AllocationRate   string  `json:"allocationRate"`
	PromotionRate    string  `json:"promotionRate"`
	HeapBeforeGC     int64   `json:"heapBeforeGC"`
	HeapAfterGC      int64   `json:"heapAfterGC"`
	GCType           string  `json:"gcType"`
}

// ============================================================================
// MAIN COMMAND
// ============================================================================

func runCrashReport(cmd *cobra.Command, args []string) {
	if crashWatch {
		watchForCrashes()
		return
	}

	crashes := detectCrashes()

	if crashComponent != "" {
		filtered := []CrashReport{}
		for _, c := range crashes {
			if strings.Contains(strings.ToLower(c.Component), strings.ToLower(crashComponent)) {
				filtered = append(filtered, c)
			}
		}
		crashes = filtered
	}

	if len(crashes) > crashLimit {
		crashes = crashes[:crashLimit]
	}

	if len(crashes) == 0 {
		fmt.Println("\n  No crashes detected. All components are healthy.")
		fmt.Println()
		return
	}

	// Save crash reports to file
	saveCrashReports(crashes)

	// Output human-readable format
	outputCrashReports(crashes)
}

// ============================================================================
// CRASH DETECTION
// ============================================================================

func detectCrashes() []CrashReport {
	crashes := []CrashReport{}

	// Get all containers (including stopped ones)
	out, err := exec.Command("docker", "ps", "-a", "--format", "{{.ID}}|{{.Names}}|{{.Status}}|{{.State}}").Output()
	if err != nil {
		return crashes
	}

	lines := strings.Split(strings.TrimSpace(string(out)), "\n")
	for _, line := range lines {
		if line == "" {
			continue
		}
		parts := strings.Split(line, "|")
		if len(parts) < 4 {
			continue
		}

		containerID := parts[0]
		containerName := parts[1]
		status := parts[2]
		state := parts[3]

		// Check for crashed containers
		if state == "exited" || state == "dead" || strings.Contains(status, "Exited") {
			crash := analyzeCrash(containerID, containerName, status)
			if crash != nil {
				crashes = append(crashes, *crash)
			}
		}

		// Check for OOM killed containers that restarted
		if strings.Contains(status, "Restarting") || checkOOMHistory(containerName) {
			crash := analyzeOOMCrash(containerID, containerName)
			if crash != nil {
				crashes = append(crashes, *crash)
			}
		}
	}

	// Sort by timestamp (most recent first)
	sort.Slice(crashes, func(i, j int) bool {
		return crashes[i].Timestamp > crashes[j].Timestamp
	})

	return crashes
}

func analyzeCrash(containerID, containerName, status string) *CrashReport {
	// Get detailed container info
	inspectOut, err := exec.Command("docker", "inspect", containerID).Output()
	if err != nil {
		return nil
	}

	var inspectData []map[string]interface{}
	if err := json.Unmarshal(inspectOut, &inspectData); err != nil || len(inspectData) == 0 {
		return nil
	}

	info := inspectData[0]
	state := info["State"].(map[string]interface{})

	exitCode := int(state["ExitCode"].(float64))
	oomKilled := state["OOMKilled"].(bool)
	finishedAt := state["FinishedAt"].(string)

	// Skip normal exits (exit code 0) unless OOM killed
	// Also skip init containers and expected short-lived containers
	if exitCode == 0 && !oomKilled {
		return nil
	}

	// Skip SIGTERM (143) which is graceful shutdown
	if exitCode == 143 && !oomKilled {
		return nil
	}

	// Parse component name from container name
	component := extractComponentName(containerName)

	// Get restart count
	restartCount := 0
	if rc, ok := state["RestartCount"]; ok {
		restartCount = int(rc.(float64))
	}

	// Determine exit reason
	exitReason := determineExitReason(exitCode, oomKilled)
	severity := determineSeverity(exitCode, oomKilled, restartCount)

	crash := &CrashReport{
		Timestamp:     finishedAt,
		Component:     component,
		ContainerID:   containerID[:12],
		ContainerName: containerName,
		ExitCode:      exitCode,
		ExitReason:    exitReason,
		OOMKilled:     oomKilled,
		RestartCount:  restartCount,
		Severity:      severity,
	}

	// Get last logs before crash
	crash.LastLogs = getLastLogs(containerName, 50)
	crash.ErrorPatterns = extractErrorPatterns(crash.LastLogs)

	// Analyze memory state
	crash.MemoryAtCrash = analyzeMemoryBeforeCrash(containerName, crash.LastLogs)

	// Analyze GC state for JVM components
	if isJVMComponent(component) {
		crash.GCBeforeCrash = analyzeGCBeforeCrash(containerName, crash.LastLogs)
	}

	// Determine root cause and recommendation
	crash.RootCause, crash.Recommendation = determineRootCause(crash)

	return crash
}

func analyzeOOMCrash(containerID, containerName string) *CrashReport {
	component := extractComponentName(containerName)

	// Check docker events for OOM
	eventsOut, _ := exec.Command("docker", "events", "--filter", "container="+containerName,
		"--filter", "event=oom", "--since", "1h", "--until", "now", "--format", "{{.Time}}").Output()

	if len(eventsOut) == 0 {
		return nil
	}

	crash := &CrashReport{
		Timestamp:     time.Now().Format(time.RFC3339),
		Component:     component,
		ContainerName: containerName,
		ExitCode:      137,
		ExitReason:    "OOM Killed",
		OOMKilled:     true,
		Severity:      "CRITICAL",
	}

	crash.LastLogs = getLastLogs(containerName, 50)
	crash.ErrorPatterns = extractErrorPatterns(crash.LastLogs)
	crash.MemoryAtCrash = analyzeMemoryBeforeCrash(containerName, crash.LastLogs)

	if isJVMComponent(component) {
		crash.GCBeforeCrash = analyzeGCBeforeCrash(containerName, crash.LastLogs)
	}

	crash.RootCause, crash.Recommendation = determineRootCause(crash)

	return crash
}

func checkOOMHistory(containerName string) bool {
	// Check dmesg for OOM killer
	out, _ := exec.Command("docker", "inspect", "--format", "{{.State.OOMKilled}}", containerName).Output()
	return strings.TrimSpace(string(out)) == "true"
}

// ============================================================================
// ANALYSIS HELPERS
// ============================================================================

func extractComponentName(containerName string) string {
	// Extract component name from container name like "reactive-system-01-gateway-1"
	parts := strings.Split(containerName, "-")
	if len(parts) >= 2 {
		// Skip prefix and suffix number
		return parts[len(parts)-2]
	}
	return containerName
}

func determineExitReason(exitCode int, oomKilled bool) string {
	if oomKilled {
		return "OOM Killed - Container exceeded memory limit"
	}

	switch exitCode {
	case 0:
		return "Normal exit"
	case 1:
		return "Application error"
	case 137:
		return "SIGKILL (likely OOM or manual kill)"
	case 143:
		return "SIGTERM (graceful shutdown)"
	case 139:
		return "Segmentation fault"
	case 134:
		return "SIGABRT (abort signal)"
	case 255:
		return "Exit status out of range"
	default:
		return fmt.Sprintf("Exit code %d", exitCode)
	}
}

func determineSeverity(exitCode int, oomKilled bool, restartCount int) string {
	if oomKilled || exitCode == 137 {
		return "CRITICAL"
	}
	if restartCount > 3 {
		return "CRITICAL"
	}
	if exitCode != 0 && exitCode != 143 {
		return "HIGH"
	}
	if restartCount > 0 {
		return "MEDIUM"
	}
	return "LOW"
}

func getLastLogs(containerName string, lines int) []string {
	out, err := exec.Command("docker", "logs", "--tail", strconv.Itoa(lines), containerName).CombinedOutput()
	if err != nil {
		return []string{}
	}

	logLines := strings.Split(string(out), "\n")
	result := []string{}
	for _, line := range logLines {
		if strings.TrimSpace(line) != "" {
			result = append(result, line)
		}
	}
	return result
}

func extractErrorPatterns(logs []string) []string {
	patterns := []string{}
	errorPatterns := []string{
		`(?i)out\s*of\s*memory`,
		`(?i)java\.lang\.OutOfMemoryError`,
		`(?i)cannot allocate memory`,
		`(?i)killed\s*process`,
		`(?i)oom[-_]?kill`,
		`(?i)exception|error|fatal|panic`,
		`(?i)heap\s*space`,
		`(?i)gc\s*overhead\s*limit`,
		`(?i)metaspace`,
		`(?i)direct\s*buffer\s*memory`,
		`(?i)unable\s*to\s*create\s*new\s*native\s*thread`,
	}

	seen := make(map[string]bool)
	for _, log := range logs {
		for _, pattern := range errorPatterns {
			re := regexp.MustCompile(pattern)
			if matches := re.FindStringSubmatch(log); len(matches) > 0 {
				// Truncate long matches
				match := log
				if len(match) > 200 {
					match = match[:200] + "..."
				}
				if !seen[match] {
					seen[match] = true
					patterns = append(patterns, match)
				}
			}
		}
	}

	return patterns
}

func analyzeMemoryBeforeCrash(containerName string, logs []string) *MemorySnapshot {
	snapshot := &MemorySnapshot{}

	// Try to get memory info from logs
	for _, log := range logs {
		// Look for memory patterns in logs
		if strings.Contains(log, "Memory:") || strings.Contains(log, "Heap") {
			// Parse heap info from GC logs
			heapPattern := regexp.MustCompile(`(\d+)M->(\d+)M\((\d+)M\)`)
			if matches := heapPattern.FindStringSubmatch(log); len(matches) >= 4 {
				before, _ := strconv.ParseInt(matches[1], 10, 64)
				after, _ := strconv.ParseInt(matches[2], 10, 64)
				max, _ := strconv.ParseInt(matches[3], 10, 64)
				snapshot.HeapUsedBytes = after * 1024 * 1024
				snapshot.HeapMaxBytes = max * 1024 * 1024
				if max > 0 {
					snapshot.HeapPercent = float64(after) / float64(max) * 100
				}
				_ = before // suppress unused variable
			}
		}
	}

	// Get memory limit from docker inspect
	out, err := exec.Command("docker", "inspect", "--format",
		"{{.HostConfig.Memory}}", containerName).Output()
	if err == nil {
		limit, _ := strconv.ParseInt(strings.TrimSpace(string(out)), 10, 64)
		snapshot.LimitBytes = limit
	}

	return snapshot
}

func analyzeGCBeforeCrash(containerName string, logs []string) *GCSnapshot {
	snapshot := &GCSnapshot{}

	var pauses []float64
	fullGCCount := 0

	gcPattern := regexp.MustCompile(`GC\(\d+\).*?(\d+\.?\d*)ms`)
	fullGCPattern := regexp.MustCompile(`(?i)full\s*gc|major\s*gc`)
	heapPattern := regexp.MustCompile(`(\d+)M->(\d+)M`)

	for _, log := range logs {
		// Count GC pauses
		if matches := gcPattern.FindStringSubmatch(log); len(matches) >= 2 {
			if pause, err := strconv.ParseFloat(matches[1], 64); err == nil {
				pauses = append(pauses, pause)
			}
		}

		// Count Full GCs
		if fullGCPattern.MatchString(log) {
			fullGCCount++
		}

		// Get heap before/after
		if matches := heapPattern.FindStringSubmatch(log); len(matches) >= 3 {
			before, _ := strconv.ParseInt(matches[1], 10, 64)
			after, _ := strconv.ParseInt(matches[2], 10, 64)
			snapshot.HeapBeforeGC = before * 1024 * 1024
			snapshot.HeapAfterGC = after * 1024 * 1024
		}
	}

	snapshot.RecentPauses = len(pauses)
	snapshot.FullGCCount = fullGCCount

	if len(pauses) > 0 {
		var total float64
		var max float64
		for _, p := range pauses {
			total += p
			if p > max {
				max = p
			}
		}
		snapshot.TotalPauseMs = total
		snapshot.MaxPauseMs = max
	}

	return snapshot
}

func isJVMComponent(component string) bool {
	jvmComponents := []string{"gateway", "drools", "flink", "jobmanager", "taskmanager"}
	for _, jvm := range jvmComponents {
		if strings.Contains(strings.ToLower(component), jvm) {
			return true
		}
	}
	return false
}

func determineRootCause(crash *CrashReport) (string, string) {
	// OOM Analysis
	if crash.OOMKilled || crash.ExitCode == 137 {
		// Check if it's heap OOM
		for _, pattern := range crash.ErrorPatterns {
			if strings.Contains(strings.ToLower(pattern), "heap space") {
				return "JVM Heap exhaustion - Application allocated more objects than heap can hold",
					fmt.Sprintf("Increase heap size: -Xmx (current limit may be too low). Consider analyzing heap dump to find memory leaks.")
			}
			if strings.Contains(strings.ToLower(pattern), "metaspace") {
				return "Metaspace exhaustion - Too many classes loaded",
					"Increase metaspace: -XX:MaxMetaspaceSize=512m. Check for classloader leaks."
			}
			if strings.Contains(strings.ToLower(pattern), "direct buffer") {
				return "Direct buffer memory exhaustion - NIO buffers exceeded limit",
					"Increase direct memory: -XX:MaxDirectMemorySize=256m. Check for buffer leaks in Netty/NIO code."
			}
			if strings.Contains(strings.ToLower(pattern), "native thread") {
				return "Native thread exhaustion - Too many threads created",
					"Reduce thread pool sizes or increase container memory limit."
			}
		}

		// Check GC patterns
		if crash.GCBeforeCrash != nil {
			if crash.GCBeforeCrash.FullGCCount > 5 {
				return "Memory exhaustion with aggressive Full GC - Heap too small for workload",
					"Increase heap size by 50% or reduce memory-intensive operations."
			}
			if crash.GCBeforeCrash.MaxPauseMs > 1000 {
				return "Memory pressure causing long GC pauses before OOM",
					"Increase heap size and tune GC: -XX:MaxGCPauseMillis=200"
			}
		}

		// Generic container OOM
		return "Container memory limit exceeded - Total process memory (heap + native + buffers) exceeded limit",
			"Increase container memory limit in docker-compose.yml or reduce application memory usage."
	}

	// Application error analysis
	if crash.ExitCode == 1 {
		for _, pattern := range crash.ErrorPatterns {
			if strings.Contains(strings.ToLower(pattern), "connection refused") {
				return "Dependency connection failure - Required service not available",
					"Check that all dependent services (Kafka, databases) are running."
			}
			if strings.Contains(strings.ToLower(pattern), "timeout") {
				return "Operation timeout - Slow dependency or network issue",
					"Increase timeout configuration or check network connectivity."
			}
		}
		return "Application error - Check logs for stack trace",
			"Review error patterns in logs above to identify the specific issue."
	}

	// Segfault
	if crash.ExitCode == 139 {
		return "Segmentation fault - Native code crash, possibly JNI or native library issue",
			"Check for native library compatibility. Enable core dumps for analysis."
	}

	return "Unknown crash reason",
		"Review the logs above for more details."
}

// ============================================================================
// WATCH MODE
// ============================================================================

func watchForCrashes() {
	fmt.Println("\n  Watching for container crashes... (Ctrl+C to stop)")
	fmt.Println("  " + strings.Repeat("-", 60))

	cmd := exec.Command("docker", "events",
		"--filter", "event=die",
		"--filter", "event=oom",
		"--format", "{{.Time}} | {{.Actor.Attributes.name}} | {{.Action}}")

	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	cmd.Run()
}

// ============================================================================
// OUTPUT
// ============================================================================

func saveCrashReports(crashes []CrashReport) {
	crashDir := "reports/crashes"
	os.MkdirAll(crashDir, 0755)

	// Save individual crash reports
	for _, crash := range crashes {
		filename := fmt.Sprintf("%s/%s_%s.json",
			crashDir,
			crash.Component,
			strings.ReplaceAll(crash.Timestamp[:19], ":", "-"))

		data, _ := json.MarshalIndent(crash, "", "  ")
		os.WriteFile(filename, data, 0644)
	}

	// Save summary
	summaryFile := filepath.Join(crashDir, "summary.json")
	data, _ := json.MarshalIndent(crashes, "", "  ")
	os.WriteFile(summaryFile, data, 0644)
}

func outputCrashReports(crashes []CrashReport) {
	red := "\033[31m"
	yellow := "\033[33m"
	cyan := "\033[36m"
	bold := "\033[1m"
	reset := "\033[0m"

	fmt.Println()
	printHeader("CRASH REPORTS")

	for i, crash := range crashes {
		if i > 0 {
			fmt.Println("  " + strings.Repeat("─", 70))
		}

		// Severity color
		sevColor := yellow
		if crash.Severity == "CRITICAL" {
			sevColor = red
		}

		fmt.Printf("\n  %s%s[%s]%s %s%s%s\n",
			sevColor, bold, crash.Severity, reset,
			bold, crash.Component, reset)
		fmt.Printf("  Container: %s\n", crash.ContainerName)
		fmt.Printf("  Time: %s\n", crash.Timestamp)
		fmt.Printf("  Exit Code: %d (%s)\n", crash.ExitCode, crash.ExitReason)

		if crash.OOMKilled {
			fmt.Printf("  %sOOM Killed: YES%s\n", red, reset)
		}

		if crash.RestartCount > 0 {
			fmt.Printf("  Restart Count: %d\n", crash.RestartCount)
		}

		// Memory at crash
		if crash.MemoryAtCrash != nil && crash.MemoryAtCrash.HeapMaxBytes > 0 {
			fmt.Printf("\n  %sMemory State:%s\n", cyan, reset)
			fmt.Printf("    Heap: %.0f%% (%.0fMB / %.0fMB)\n",
				crash.MemoryAtCrash.HeapPercent,
				float64(crash.MemoryAtCrash.HeapUsedBytes)/1024/1024,
				float64(crash.MemoryAtCrash.HeapMaxBytes)/1024/1024)
		}

		// GC before crash
		if crash.GCBeforeCrash != nil && crash.GCBeforeCrash.RecentPauses > 0 {
			fmt.Printf("\n  %sGC Activity Before Crash:%s\n", cyan, reset)
			fmt.Printf("    Recent Pauses: %d (total %.1fms, max %.1fms)\n",
				crash.GCBeforeCrash.RecentPauses,
				crash.GCBeforeCrash.TotalPauseMs,
				crash.GCBeforeCrash.MaxPauseMs)
			if crash.GCBeforeCrash.FullGCCount > 0 {
				fmt.Printf("    Full GC Count: %s%d%s\n", red, crash.GCBeforeCrash.FullGCCount, reset)
			}
		}

		// Error patterns
		if len(crash.ErrorPatterns) > 0 {
			fmt.Printf("\n  %sError Patterns Found:%s\n", cyan, reset)
			for _, pattern := range crash.ErrorPatterns {
				if len(pattern) > 100 {
					pattern = pattern[:100] + "..."
				}
				fmt.Printf("    • %s\n", pattern)
			}
		}

		// Root cause and recommendation
		fmt.Printf("\n  %s%sRoot Cause:%s\n", bold, cyan, reset)
		fmt.Printf("    %s\n", crash.RootCause)

		fmt.Printf("\n  %s%sRecommended Fix:%s\n", bold, cyan, reset)
		fmt.Printf("    %s\n", crash.Recommendation)

		// Last logs (truncated)
		if len(crash.LastLogs) > 0 {
			fmt.Printf("\n  %sLast Logs (see full report in reports/crashes/):%s\n", cyan, reset)
			showLogs := crash.LastLogs
			if len(showLogs) > 5 {
				showLogs = showLogs[len(showLogs)-5:]
			}
			for _, log := range showLogs {
				if len(log) > 100 {
					log = log[:100] + "..."
				}
				fmt.Printf("    %s\n", log)
			}
		}

		fmt.Println()
	}

	// Summary
	printHeader("CRASH SUMMARY")

	oomCount := 0
	criticalCount := 0
	for _, c := range crashes {
		if c.OOMKilled || c.ExitCode == 137 {
			oomCount++
		}
		if c.Severity == "CRITICAL" {
			criticalCount++
		}
	}

	fmt.Printf("  Total Crashes: %d\n", len(crashes))
	fmt.Printf("  OOM Kills: %s%d%s\n", red, oomCount, reset)
	fmt.Printf("  Critical: %s%d%s\n", red, criticalCount, reset)
	fmt.Println()
	fmt.Println("  Full reports saved to: reports/crashes/")
	fmt.Println()
}
