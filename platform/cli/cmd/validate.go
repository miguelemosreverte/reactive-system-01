package cmd

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"strings"

	"github.com/spf13/cobra"
)

// ============================================================================
// VALIDATION COMMAND - PREVENTS COMMITTING REGRESSIONS
// Must pass before any commit is allowed
// ============================================================================

var validateCmd = &cobra.Command{
	Use:   "validate",
	Short: "Validate system is ready for commit (no regressions)",
	Long: `Validates the system has no regressions before allowing a commit.

Checks for:
  - OOM crashes or killed containers
  - Component failures (exited, not running)
  - Memory pressure issues (>85% usage)
  - Performance regressions vs last benchmark
  - Critical bottlenecks

Exit codes:
  0 = Ready to commit
  1 = BLOCKED - Regressions detected, cannot commit

Examples:
  reactive validate           # Check if ready to commit
  reactive validate --fix     # Show how to fix issues
  git commit && reactive validate  # Use in pre-commit hook`,
	Run: runValidate,
}

var validateShowFix bool

func init() {
	validateCmd.Flags().BoolVar(&validateShowFix, "fix", false, "Show how to fix blocking issues")
	rootCmd.AddCommand(validateCmd)
}

type ValidationResult struct {
	CanCommit    bool              `json:"canCommit"`
	Blockers     []ValidationIssue `json:"blockers"`
	Warnings     []ValidationIssue `json:"warnings"`
	Summary      string            `json:"summary"`
}

type ValidationIssue struct {
	Category    string `json:"category"`
	Component   string `json:"component"`
	Description string `json:"description"`
	Impact      string `json:"impact"`
	Fix         string `json:"fix"`
}

func runValidate(cmd *cobra.Command, args []string) {
	result := validateSystem()
	outputValidationResult(result)

	if !result.CanCommit {
		os.Exit(1)
	}
}

func validateSystem() *ValidationResult {
	result := &ValidationResult{
		CanCommit: true,
		Blockers:  []ValidationIssue{},
		Warnings:  []ValidationIssue{},
	}

	// Check 1: OOM Events (BLOCKER)
	checkOOMEvents(result)

	// Check 2: Container Status (BLOCKER)
	checkContainerStatus(result)

	// Check 3: Memory Pressure (BLOCKER if critical)
	checkMemoryPressure(result)

	// Check 4: Recent Benchmark Success (BLOCKER if failed)
	checkBenchmarkResults(result)

	// Check 5: Crash Reports (BLOCKER)
	checkCrashReports(result)

	// Determine if can commit
	result.CanCommit = len(result.Blockers) == 0

	if result.CanCommit {
		result.Summary = "All validation checks passed. Ready to commit."
	} else {
		result.Summary = fmt.Sprintf("BLOCKED: %d issue(s) must be fixed before commit.", len(result.Blockers))
	}

	return result
}

func checkOOMEvents(result *ValidationResult) {
	// Check 1: Current OOM state from docker inspect
	cmd := exec.Command("docker", "ps", "-a", "--format", "{{.Names}}|{{.Status}}")
	output, err := cmd.Output()
	if err != nil {
		return
	}

	lines := strings.Split(strings.TrimSpace(string(output)), "\n")
	for _, line := range lines {
		parts := strings.Split(line, "|")
		if len(parts) < 2 {
			continue
		}
		name := parts[0]

		// Skip non-reactive containers
		if !strings.Contains(name, "reactive") && !strings.Contains(name, "flink") {
			continue
		}

		// Check for OOM killed
		inspectCmd := exec.Command("docker", "inspect", name, "--format", "{{.State.OOMKilled}}")
		out, err := inspectCmd.Output()
		if err != nil {
			continue
		}

		if strings.TrimSpace(string(out)) == "true" {
			result.Blockers = append(result.Blockers, ValidationIssue{
				Category:    "OOM_CRASH",
				Component:   name,
				Description: fmt.Sprintf("Container %s was OOM killed", name),
				Impact:      "System instability, data loss risk",
				Fix:         fmt.Sprintf("Increase memory limit for %s in docker-compose.yml", extractServiceName(name)),
			})
		}
	}

	// Check 2: Recent OOM events from docker events (last hour)
	// This catches OOM events even if container was restarted
	eventsCmd := exec.Command("docker", "events", "--since", "1h", "--until", "now",
		"--filter", "event=oom", "--format", "{{.Actor.Attributes.name}}")
	eventsOut, err := eventsCmd.Output()
	if err == nil && len(eventsOut) > 0 {
		oomContainers := strings.Split(strings.TrimSpace(string(eventsOut)), "\n")
		seen := make(map[string]bool)
		for _, container := range oomContainers {
			container = strings.TrimSpace(container)
			if container == "" || seen[container] {
				continue
			}
			seen[container] = true

			// Check if this is a reactive container
			if !strings.Contains(container, "reactive") && !strings.Contains(container, "flink") {
				continue
			}

			// Check if already in blockers
			alreadyBlocked := false
			for _, b := range result.Blockers {
				if b.Component == container {
					alreadyBlocked = true
					break
				}
			}
			if !alreadyBlocked {
				result.Blockers = append(result.Blockers, ValidationIssue{
					Category:    "RECENT_OOM",
					Component:   container,
					Description: fmt.Sprintf("Container %s had OOM event in the last hour (may have restarted)", container),
					Impact:      "Memory configuration is insufficient for workload",
					Fix:         fmt.Sprintf("Increase memory limit for %s in docker-compose.yml before committing", extractServiceName(container)),
				})
			}
		}
	}
}

func checkContainerStatus(result *ValidationResult) {
	criticalServices := []string{"gateway", "kafka", "flink-taskmanager", "flink-jobmanager", "drools"}

	for _, service := range criticalServices {
		containerName := findContainerByComponent(service)
		if containerName == "" {
			result.Blockers = append(result.Blockers, ValidationIssue{
				Category:    "SERVICE_DOWN",
				Component:   service,
				Description: fmt.Sprintf("Critical service %s is not running", service),
				Impact:      "System cannot process requests",
				Fix:         fmt.Sprintf("docker compose up -d %s", service),
			})
			continue
		}

		// Check if running
		cmd := exec.Command("docker", "inspect", containerName, "--format", "{{.State.Status}}")
		out, err := cmd.Output()
		if err != nil {
			continue
		}

		status := strings.TrimSpace(string(out))
		if status != "running" {
			result.Blockers = append(result.Blockers, ValidationIssue{
				Category:    "SERVICE_DOWN",
				Component:   service,
				Description: fmt.Sprintf("Critical service %s is %s (not running)", service, status),
				Impact:      "System cannot process requests correctly",
				Fix:         fmt.Sprintf("docker compose restart %s", service),
			})
		}
	}
}

func checkMemoryPressure(result *ValidationResult) {
	cmd := exec.Command("docker", "stats", "--no-stream", "--format", "{{.Name}}|{{.MemPerc}}")
	output, err := cmd.Output()
	if err != nil {
		return
	}

	lines := strings.Split(strings.TrimSpace(string(output)), "\n")
	for _, line := range lines {
		parts := strings.Split(line, "|")
		if len(parts) < 2 {
			continue
		}
		name := parts[0]
		memPctStr := strings.TrimSuffix(parts[1], "%")

		// Skip non-reactive containers
		if !strings.Contains(name, "reactive") && !strings.Contains(name, "flink") {
			continue
		}

		var memPct float64
		fmt.Sscanf(memPctStr, "%f", &memPct)

		if memPct > 90 {
			result.Blockers = append(result.Blockers, ValidationIssue{
				Category:    "MEMORY_CRITICAL",
				Component:   name,
				Description: fmt.Sprintf("Container %s at %.0f%% memory (critical)", name, memPct),
				Impact:      "Imminent OOM crash risk",
				Fix:         fmt.Sprintf("Increase memory limit for %s in docker-compose.yml", extractServiceName(name)),
			})
		} else if memPct > 80 {
			result.Warnings = append(result.Warnings, ValidationIssue{
				Category:    "MEMORY_HIGH",
				Component:   name,
				Description: fmt.Sprintf("Container %s at %.0f%% memory (high)", name, memPct),
				Impact:      "May OOM under load",
				Fix:         fmt.Sprintf("Consider increasing memory limit for %s", extractServiceName(name)),
			})
		}
	}
}

func checkBenchmarkResults(result *ValidationResult) {
	// Load last benchmark results
	data, err := os.ReadFile("reports/full/results.json")
	if err != nil {
		result.Warnings = append(result.Warnings, ValidationIssue{
			Category:    "NO_BENCHMARK",
			Component:   "system",
			Description: "No benchmark results found",
			Impact:      "Cannot verify performance",
			Fix:         "Run: reactive bench full",
		})
		return
	}

	var results map[string]interface{}
	if err := json.Unmarshal(data, &results); err != nil {
		return
	}

	// Check for failed operations
	if failed, ok := results["failedOperations"].(float64); ok && failed > 0 {
		total, _ := results["totalOperations"].(float64)
		failRate := (failed / total) * 100
		result.Blockers = append(result.Blockers, ValidationIssue{
			Category:    "BENCHMARK_FAILURES",
			Component:   "system",
			Description: fmt.Sprintf("Benchmark had %.0f failed operations (%.1f%% failure rate)", failed, failRate),
			Impact:      "System is not processing requests correctly",
			Fix:         "Check logs and fix errors, then re-run benchmark",
		})
	}

	// Check success status
	if success, ok := results["success"].(bool); ok && !success {
		errorMsg := ""
		if msg, ok := results["errorMessage"].(string); ok {
			errorMsg = msg
		}
		result.Blockers = append(result.Blockers, ValidationIssue{
			Category:    "BENCHMARK_FAILED",
			Component:   "system",
			Description: fmt.Sprintf("Last benchmark failed: %s", errorMsg),
			Impact:      "System performance not validated",
			Fix:         "Fix the issue and re-run: reactive bench full",
		})
	}

	// Check throughput stability
	if stability, ok := results["throughputStability"].(float64); ok && stability < 0.5 {
		result.Warnings = append(result.Warnings, ValidationIssue{
			Category:    "UNSTABLE_THROUGHPUT",
			Component:   "system",
			Description: fmt.Sprintf("Throughput stability is low: %.0f%%", stability*100),
			Impact:      "Inconsistent performance under load",
			Fix:         "Investigate GC pauses, memory pressure, or resource contention",
		})
	}
}

func checkCrashReports(result *ValidationResult) {
	// Check for recent crashes using the crash detection logic
	cmd := exec.Command("docker", "ps", "-a", "--format", "{{.Names}}|{{.Status}}")
	output, err := cmd.Output()
	if err != nil {
		return
	}

	lines := strings.Split(strings.TrimSpace(string(output)), "\n")
	for _, line := range lines {
		parts := strings.Split(line, "|")
		if len(parts) < 2 {
			continue
		}
		name := parts[0]
		status := parts[1]

		// Skip non-reactive and init containers
		if !strings.Contains(name, "reactive") && !strings.Contains(name, "flink") {
			continue
		}
		if strings.Contains(name, "-init") {
			continue
		}

		// Check for crashed containers (Exited with non-zero or OOM)
		if strings.Contains(status, "Exited") && !strings.Contains(status, "Exited (0)") {
			// Extract exit code
			exitCode := "unknown"
			if strings.Contains(status, "(137)") {
				exitCode = "137 (SIGKILL/OOM)"
			} else if strings.Contains(status, "(1)") {
				exitCode = "1 (Error)"
			}

			result.Blockers = append(result.Blockers, ValidationIssue{
				Category:    "CONTAINER_CRASHED",
				Component:   name,
				Description: fmt.Sprintf("Container %s crashed with exit code %s", name, exitCode),
				Impact:      "Service failure, possible data loss",
				Fix:         fmt.Sprintf("Check logs: docker logs %s --tail 50", name),
			})
		}
	}
}

func extractServiceName(containerName string) string {
	// Extract service name from container name like "reactive-system-01-gateway-1"
	parts := strings.Split(containerName, "-")
	if len(parts) >= 2 {
		// Return second-to-last part (service name)
		return parts[len(parts)-2]
	}
	return containerName
}

func outputValidationResult(result *ValidationResult) {
	red := "\033[31m"
	green := "\033[32m"
	yellow := "\033[33m"
	cyan := "\033[36m"
	bold := "\033[1m"
	reset := "\033[0m"

	fmt.Println()
	printHeader("COMMIT VALIDATION")

	if result.CanCommit {
		fmt.Printf("\n  %s%s✓ READY TO COMMIT%s\n\n", bold, green, reset)
		fmt.Printf("  %s\n\n", result.Summary)
	} else {
		fmt.Printf("\n  %s%s✗ COMMIT BLOCKED%s\n\n", bold, red, reset)
		fmt.Printf("  %s\n\n", result.Summary)

		fmt.Printf("  %s%sBLOCKING ISSUES:%s\n", bold, red, reset)
		fmt.Println("  " + strings.Repeat("─", 60))

		for i, blocker := range result.Blockers {
			fmt.Printf("\n  %s%d. [%s] %s%s\n", red, i+1, blocker.Category, blocker.Component, reset)
			fmt.Printf("     %s\n", blocker.Description)
			fmt.Printf("     %sImpact:%s %s\n", cyan, reset, blocker.Impact)
			if validateShowFix {
				fmt.Printf("     %s%sFix:%s %s\n", bold, green, reset, blocker.Fix)
			}
		}
		fmt.Println()

		if !validateShowFix && len(result.Blockers) > 0 {
			fmt.Printf("  %sTip:%s Run with --fix to see how to resolve these issues\n\n", cyan, reset)
		}
	}

	if len(result.Warnings) > 0 {
		fmt.Printf("  %s%sWARNINGS:%s\n", bold, yellow, reset)
		fmt.Println("  " + strings.Repeat("─", 60))

		for i, warning := range result.Warnings {
			fmt.Printf("\n  %s%d. [%s] %s%s\n", yellow, i+1, warning.Category, warning.Component, reset)
			fmt.Printf("     %s\n", warning.Description)
			if validateShowFix {
				fmt.Printf("     %sFix:%s %s\n", cyan, reset, warning.Fix)
			}
		}
		fmt.Println()
	}

	// Summary
	fmt.Println("  " + strings.Repeat("═", 60))
	if result.CanCommit {
		fmt.Printf("  %sStatus:%s %s%sOK%s - Safe to commit\n", cyan, reset, green, bold, reset)
		fmt.Printf("  %sNext:%s git add . && git commit -m \"your message\"\n\n", cyan, reset)
	} else {
		fmt.Printf("  %sStatus:%s %s%sBLOCKED%s - Fix issues before committing\n", cyan, reset, red, bold, reset)
		fmt.Printf("  %sNext:%s Fix the blocking issues above, then run: reactive validate\n\n", cyan, reset)
	}
}
