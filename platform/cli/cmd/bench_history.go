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

var historyCmd = &cobra.Command{
	Use:   "history <action> [commit]",
	Short: "Manage benchmark history and regression testing",
	Long: `Manage benchmark history indexed by git commit.

Actions:
  save              Save current benchmark results to history
  list              List all stored benchmark commits
  show [sha]        Show results for current or specific commit
  compare [sha]     Compare current with previous or specific commit

Examples:
  reactive bench history save           # Save current results
  reactive bench history list           # List all benchmarks
  reactive bench history show           # Show current commit results
  reactive bench history show abc123    # Show specific commit
  reactive bench history compare        # Compare with last run
  reactive bench history compare abc123 # Compare with specific commit`,
	Args: cobra.MinimumNArgs(1),
	Run:  runBenchHistory,
}

func init() {
	benchCmd.AddCommand(historyCmd)
}

type benchmarkSummary struct {
	Commit      string                       `json:"commit"`
	ShortCommit string                       `json:"shortCommit"`
	Message     string                       `json:"message"`
	Date        string                       `json:"date"`
	Timestamp   string                       `json:"timestamp"`
	Components  map[string]componentMetrics  `json:"components"`
}

type componentMetrics struct {
	PeakThroughput  int     `json:"peakThroughput"`
	AvgThroughput   int     `json:"avgThroughput"`
	LatencyP50      float64 `json:"latencyP50"`
	LatencyP99      float64 `json:"latencyP99"`
	TotalOperations int     `json:"totalOperations"`
	SuccessRate     float64 `json:"successRate"`
}

type benchmarkIndex struct {
	Benchmarks []struct {
		Commit      string   `json:"commit"`
		ShortCommit string   `json:"shortCommit"`
		Message     string   `json:"message"`
		Date        string   `json:"date"`
		Timestamp   string   `json:"timestamp"`
		Components  []string `json:"components"`
	} `json:"benchmarks"`
}

func runBenchHistory(cmd *cobra.Command, args []string) {
	action := args[0]

	projectRoot := findProjectRoot()
	if projectRoot == "" {
		printError("Project root not found")
		return
	}

	historyDir := filepath.Join(projectRoot, ".benchmark-history")
	versionsFile := filepath.Join(projectRoot, "benchmark-history.json")
	reportsDir := filepath.Join(projectRoot, "reports")

	switch action {
	case "save":
		historySave(historyDir, versionsFile, reportsDir)
	case "list":
		historyList(versionsFile)
	case "show":
		commit := ""
		if len(args) > 1 {
			commit = args[1]
		}
		historyShow(historyDir, commit)
	case "compare":
		commit := ""
		if len(args) > 1 {
			commit = args[1]
		}
		historyCompare(historyDir, versionsFile, commit)
	default:
		printError(fmt.Sprintf("Unknown action: %s", action))
		printInfo("Valid actions: save, list, show, compare")
	}
}

func getGitCommit() (full, short, message, date string) {
	// Full commit
	out, err := exec.Command("git", "rev-parse", "HEAD").Output()
	if err != nil {
		return "unknown", "unknown", "No message", "Unknown"
	}
	full = strings.TrimSpace(string(out))

	// Short commit
	out, _ = exec.Command("git", "rev-parse", "--short", "HEAD").Output()
	short = strings.TrimSpace(string(out))

	// Message
	out, _ = exec.Command("git", "log", "-1", "--format=%s").Output()
	message = strings.TrimSpace(string(out))

	// Date
	out, _ = exec.Command("git", "log", "-1", "--format=%ci").Output()
	dateStr := strings.TrimSpace(string(out))
	if len(dateStr) >= 10 {
		date = dateStr[:10]
	} else {
		date = "Unknown"
	}

	return
}

func historySave(historyDir, versionsFile, reportsDir string) {
	printHeader("Save Benchmark Results")

	full, short, message, date := getGitCommit()
	if full == "unknown" {
		printError("Not in a git repository")
		return
	}

	// Create history directory
	os.MkdirAll(historyDir, 0755)

	commitDir := filepath.Join(historyDir, short)
	os.MkdirAll(commitDir, 0755)

	// Copy benchmark results
	components := []string{"http", "kafka", "flink", "drools", "gateway", "full"}
	summary := benchmarkSummary{
		Commit:      full,
		ShortCommit: short,
		Message:     message,
		Date:        date,
		Timestamp:   time.Now().UTC().Format(time.RFC3339),
		Components:  make(map[string]componentMetrics),
	}

	saved := 0
	for _, comp := range components {
		srcFile := filepath.Join(reportsDir, comp, "results.json")
		if _, err := os.Stat(srcFile); err != nil {
			continue
		}

		// Read and parse
		data, err := os.ReadFile(srcFile)
		if err != nil {
			continue
		}

		// Copy file
		dstFile := filepath.Join(commitDir, comp+".json")
		os.WriteFile(dstFile, data, 0644)

		// Extract metrics
		var result struct {
			PeakThroughput       int `json:"peakThroughput"`
			AvgThroughput        int `json:"avgThroughput"`
			TotalOperations      int `json:"totalOperations"`
			SuccessfulOperations int `json:"successfulOperations"`
			Latency              struct {
				P50 float64 `json:"p50"`
				P99 float64 `json:"p99"`
			} `json:"latency"`
		}
		if json.Unmarshal(data, &result) == nil {
			successRate := 100.0
			if result.TotalOperations > 0 {
				successRate = float64(result.SuccessfulOperations) / float64(result.TotalOperations) * 100
			}
			summary.Components[comp] = componentMetrics{
				PeakThroughput:  result.PeakThroughput,
				AvgThroughput:   result.AvgThroughput,
				LatencyP50:      result.Latency.P50,
				LatencyP99:      result.Latency.P99,
				TotalOperations: result.TotalOperations,
				SuccessRate:     successRate,
			}
			saved++
		}
	}

	if saved == 0 {
		printError("No benchmark results found")
		os.RemoveAll(commitDir)
		return
	}

	// Write summary
	summaryData, _ := json.MarshalIndent(summary, "", "  ")
	os.WriteFile(filepath.Join(commitDir, "summary.json"), summaryData, 0644)

	// Update index
	var index benchmarkIndex
	if data, err := os.ReadFile(versionsFile); err == nil {
		json.Unmarshal(data, &index)
	}

	// Remove existing entry for this commit
	newBenchmarks := make([]struct {
		Commit      string   `json:"commit"`
		ShortCommit string   `json:"shortCommit"`
		Message     string   `json:"message"`
		Date        string   `json:"date"`
		Timestamp   string   `json:"timestamp"`
		Components  []string `json:"components"`
	}, 0)
	for _, b := range index.Benchmarks {
		if b.Commit != full {
			newBenchmarks = append(newBenchmarks, b)
		}
	}

	// Add new entry at beginning
	compList := make([]string, 0, len(summary.Components))
	for k := range summary.Components {
		compList = append(compList, k)
	}
	newEntry := struct {
		Commit      string   `json:"commit"`
		ShortCommit string   `json:"shortCommit"`
		Message     string   `json:"message"`
		Date        string   `json:"date"`
		Timestamp   string   `json:"timestamp"`
		Components  []string `json:"components"`
	}{
		Commit:      full,
		ShortCommit: short,
		Message:     message,
		Date:        date,
		Timestamp:   summary.Timestamp,
		Components:  compList,
	}
	index.Benchmarks = append([]struct {
		Commit      string   `json:"commit"`
		ShortCommit string   `json:"shortCommit"`
		Message     string   `json:"message"`
		Date        string   `json:"date"`
		Timestamp   string   `json:"timestamp"`
		Components  []string `json:"components"`
	}{newEntry}, newBenchmarks...)

	// Keep only last 50
	if len(index.Benchmarks) > 50 {
		index.Benchmarks = index.Benchmarks[:50]
	}

	indexData, _ := json.MarshalIndent(index, "", "  ")
	os.WriteFile(versionsFile, indexData, 0644)

	printSuccess(fmt.Sprintf("Saved %d component benchmarks for commit %s", saved, short))
	printInfo(fmt.Sprintf("Message: %s", message))
}

func historyList(versionsFile string) {
	data, err := os.ReadFile(versionsFile)
	if err != nil {
		printInfo("No benchmark history found")
		return
	}

	var index benchmarkIndex
	if json.Unmarshal(data, &index) != nil || len(index.Benchmarks) == 0 {
		printInfo("No benchmark history found")
		return
	}

	fmt.Println()
	fmt.Println(strings.Repeat("=", 80))
	fmt.Printf("%s\n", centerString("BENCHMARK HISTORY", 80))
	fmt.Println(strings.Repeat("=", 80))
	fmt.Println()
	fmt.Printf("%-10s %-12s %-20s %-35s\n", "Commit", "Date", "Components", "Message")
	fmt.Println(strings.Repeat("-", 80))

	limit := 20
	if len(index.Benchmarks) < limit {
		limit = len(index.Benchmarks)
	}

	for i := 0; i < limit; i++ {
		b := index.Benchmarks[i]
		comps := strings.Join(b.Components, ", ")
		if len(comps) > 18 {
			comps = comps[:18]
		}
		msg := b.Message
		if len(msg) > 33 {
			msg = msg[:33]
		}
		fmt.Printf("%-10s %-12s %-20s %-35s\n", b.ShortCommit, b.Date, comps, msg)
	}

	if len(index.Benchmarks) > 20 {
		fmt.Printf("\n... and %d more entries\n", len(index.Benchmarks)-20)
	}
	fmt.Printf("\nTotal stored: %d benchmark runs\n", len(index.Benchmarks))
}

func historyShow(historyDir, commit string) {
	if commit == "" {
		_, commit, _, _ = getGitCommit()
	}

	commitDir := filepath.Join(historyDir, commit)
	if _, err := os.Stat(commitDir); err != nil {
		// Try partial match
		entries, _ := os.ReadDir(historyDir)
		for _, e := range entries {
			if strings.HasPrefix(e.Name(), commit) {
				commitDir = filepath.Join(historyDir, e.Name())
				commit = e.Name()
				break
			}
		}
	}

	summaryFile := filepath.Join(commitDir, "summary.json")
	data, err := os.ReadFile(summaryFile)
	if err != nil {
		printError(fmt.Sprintf("No benchmark results found for commit: %s", commit))
		return
	}

	var summary benchmarkSummary
	if json.Unmarshal(data, &summary) != nil {
		printError("Failed to parse summary")
		return
	}

	fmt.Println()
	fmt.Println(strings.Repeat("=", 70))
	fmt.Printf("Benchmark Results: %s\n", summary.ShortCommit)
	fmt.Println(strings.Repeat("=", 70))
	fmt.Printf("Commit:  %s\n", summary.Commit)
	fmt.Printf("Message: %s\n", summary.Message)
	fmt.Printf("Date:    %s\n", summary.Date)
	fmt.Println(strings.Repeat("=", 70))
	fmt.Println()

	fmt.Printf("%-15s %-12s %-12s %-10s %-10s %-10s\n",
		"Component", "Peak TPS", "Avg TPS", "P50 (ms)", "P99 (ms)", "Success %")
	fmt.Println(strings.Repeat("-", 70))

	for comp, m := range summary.Components {
		fmt.Printf("%-15s %-12d %-12d %-10.1f %-10.1f %.1f%%\n",
			comp, m.PeakThroughput, m.AvgThroughput, m.LatencyP50, m.LatencyP99, m.SuccessRate)
	}
	fmt.Println()
}

func historyCompare(historyDir, versionsFile, targetCommit string) {
	// Get current results
	_, currentCommit, _, _ := getGitCommit()

	// Find previous commit to compare with
	data, err := os.ReadFile(versionsFile)
	if err != nil {
		printError("No benchmark history to compare with")
		return
	}

	var index benchmarkIndex
	if json.Unmarshal(data, &index) != nil || len(index.Benchmarks) < 2 {
		printError("Need at least 2 benchmark runs to compare")
		return
	}

	var previousCommit string
	if targetCommit != "" {
		previousCommit = targetCommit
	} else {
		// Find the most recent commit that isn't current
		for _, b := range index.Benchmarks {
			if b.ShortCommit != currentCommit {
				previousCommit = b.ShortCommit
				break
			}
		}
	}

	if previousCommit == "" {
		printError("No previous benchmark found to compare")
		return
	}

	// Load both summaries
	currentFile := filepath.Join(historyDir, currentCommit, "summary.json")
	previousFile := filepath.Join(historyDir, previousCommit, "summary.json")

	currentData, err := os.ReadFile(currentFile)
	if err != nil {
		printError(fmt.Sprintf("No results for current commit: %s", currentCommit))
		return
	}
	previousData, err := os.ReadFile(previousFile)
	if err != nil {
		printError(fmt.Sprintf("No results for commit: %s", previousCommit))
		return
	}

	var current, previous benchmarkSummary
	json.Unmarshal(currentData, &current)
	json.Unmarshal(previousData, &previous)

	fmt.Println()
	fmt.Println(strings.Repeat("=", 90))
	fmt.Printf("Comparing: %s → %s\n", previous.ShortCommit, current.ShortCommit)
	fmt.Println(strings.Repeat("=", 90))
	fmt.Println()

	fmt.Printf("%-12s %-15s %-15s %-15s\n", "Component", "Previous TPS", "Current TPS", "Change")
	fmt.Println(strings.Repeat("-", 60))

	for comp, curr := range current.Components {
		prev, ok := previous.Components[comp]
		if !ok {
			fmt.Printf("%-12s %-15s %-15d %s\n", comp, "N/A", curr.PeakThroughput, "NEW")
			continue
		}

		delta := 0.0
		if prev.PeakThroughput > 0 {
			delta = float64(curr.PeakThroughput-prev.PeakThroughput) / float64(prev.PeakThroughput) * 100
		}

		arrow := "→"
		if delta > 5 {
			arrow = "↑"
		} else if delta < -5 {
			arrow = "↓"
		}

		fmt.Printf("%-12s %-15d %-15d %s %+.1f%%\n",
			comp, prev.PeakThroughput, curr.PeakThroughput, arrow, delta)
	}
	fmt.Println()
}

func centerString(s string, width int) string {
	if len(s) >= width {
		return s
	}
	padding := (width - len(s)) / 2
	return strings.Repeat(" ", padding) + s
}
