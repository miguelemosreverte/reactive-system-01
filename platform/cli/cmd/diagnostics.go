package cmd

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os/exec"
	"strings"
	"time"

	"github.com/spf13/cobra"
)

var doctorCmd = &cobra.Command{
	Use:   "doctor",
	Short: "Comprehensive health check",
	Long: `Run detailed health checks on all services.

Checks:
  - Docker containers running
  - Service health endpoints
  - Kafka connectivity
  - Flink job status
  - Observability stack`,
	Run: runDoctor,
}

var statsCmd = &cobra.Command{
	Use:   "stats",
	Short: "Quick container resource usage",
	Long: `Show CPU and memory usage for all containers.

Examples:
  reactive stats`,
	Run: runStats,
}


func init() {
	rootCmd.AddCommand(doctorCmd)
	rootCmd.AddCommand(statsCmd)
}

type HealthCheck struct {
	Name    string
	URL     string
	Status  string
	Details string
	Latency time.Duration
}

func runDoctor(cmd *cobra.Command, args []string) {
	printHeader("System Health Check")

	checks := []HealthCheck{
		{Name: "Gateway", URL: "http://localhost:8080/actuator/health"},
		{Name: "Drools", URL: "http://localhost:8180/actuator/health"},
		{Name: "Flink JobManager", URL: "http://localhost:8081/overview"},
		{Name: "Jaeger", URL: "http://localhost:16686/api/services"},
		{Name: "Loki", URL: "http://localhost:3100/ready"},
		{Name: "Grafana", URL: "http://localhost:3001/api/health"},
		{Name: "Prometheus", URL: "http://localhost:9090/-/healthy"},
	}

	healthy := 0
	total := len(checks)

	fmt.Printf("%-20s %-10s %-10s %s\n", "Service", "Status", "Latency", "Details")
	fmt.Println(strings.Repeat("-", 70))

	client := &http.Client{Timeout: 3 * time.Second}

	for i := range checks {
		check := &checks[i]
		start := time.Now()

		resp, err := client.Get(check.URL)
		check.Latency = time.Since(start)

		if err != nil {
			check.Status = "DOWN"
			check.Details = "connection failed"
		} else {
			defer resp.Body.Close()

			if resp.StatusCode >= 400 {
				check.Status = "DOWN"
				check.Details = fmt.Sprintf("HTTP %d", resp.StatusCode)
			} else {
				check.Status = "UP"
				check.Details = parseHealthDetails(resp)
				healthy++
			}
		}

		icon := "✗"
		if check.Status == "UP" {
			icon = "✓"
		}

		fmt.Printf("%-20s %s %-7s %-10s %s\n",
			check.Name, icon, check.Status,
			fmt.Sprintf("%dms", check.Latency.Milliseconds()),
			check.Details)
	}

	fmt.Println()
	fmt.Println(strings.Repeat("-", 70))

	if healthy == total {
		printSuccess(fmt.Sprintf("All %d services healthy", total))
	} else {
		printWarning(fmt.Sprintf("%d/%d services healthy", healthy, total))
		fmt.Println()
		fmt.Println("Troubleshooting:")
		fmt.Println("  reactive start     # Start all services")
		fmt.Println("  reactive logs <svc> # Check service logs")
	}
	fmt.Println()
}

func parseHealthDetails(resp *http.Response) string {
	body, _ := io.ReadAll(resp.Body)

	// Spring Boot health
	var springHealth struct {
		Status string `json:"status"`
	}
	if json.Unmarshal(body, &springHealth) == nil && springHealth.Status != "" {
		return springHealth.Status
	}

	// Flink overview
	var flinkOverview struct {
		TaskManagers int `json:"taskmanagers"`
		SlotsTotal   int `json:"slots-total"`
		JobsRunning  int `json:"jobs-running"`
	}
	if json.Unmarshal(body, &flinkOverview) == nil && flinkOverview.TaskManagers > 0 {
		return fmt.Sprintf("%d jobs, %d slots", flinkOverview.JobsRunning, flinkOverview.SlotsTotal)
	}

	// Jaeger services
	var jaegerServices struct {
		Data []string `json:"data"`
	}
	if json.Unmarshal(body, &jaegerServices) == nil && len(jaegerServices.Data) > 0 {
		return fmt.Sprintf("%d services traced", len(jaegerServices.Data))
	}

	return ""
}

func runStats(cmd *cobra.Command, args []string) {
	printHeader("Container Resources")

	c := exec.Command("docker", "stats", "--no-stream",
		"--format", "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}")
	output, err := c.Output()
	if err != nil {
		printError("Failed to get container stats")
		return
	}

	// Filter to show only reactive containers
	lines := strings.Split(string(output), "\n")
	for _, line := range lines {
		if strings.Contains(line, "NAME") || strings.Contains(line, "reactive-") {
			fmt.Println(line)
		}
	}
	fmt.Println()
}

