package cmd

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/spf13/cobra"
)

var statusCmd = &cobra.Command{
	Use:   "status",
	Short: "Show system status and health",
	Long: `Check the health status of all services.

Examples:
  reactive status              # Show all service status`,
	Run: runStatus,
}

type ServiceCheck struct {
	Name    string
	URL     string
	Status  string
	Details string
}

func runStatus(cmd *cobra.Command, args []string) {
	fmt.Println()
	fmt.Println("Reactive System Status")
	fmt.Println(strings.Repeat("=", 60))
	fmt.Println()

	checks := []ServiceCheck{
		{Name: "Gateway", URL: "http://localhost:8080/actuator/health"},
		{Name: "Drools", URL: "http://localhost:8180/actuator/health"},
		{Name: "Flink JobManager", URL: "http://localhost:8081/overview"},
		{Name: "Jaeger", URL: "http://localhost:16686/api/services"},
		{Name: "Loki", URL: "http://localhost:3100/ready"},
		{Name: "Grafana", URL: "http://localhost:3001/api/health"},
		{Name: "Kafka", URL: "http://localhost:8080/actuator/health"}, // Check via gateway
	}

	healthy := 0
	total := len(checks)

	fmt.Printf("%-20s %-10s %s\n", "Service", "Status", "Details")
	fmt.Println(strings.Repeat("-", 60))

	for _, check := range checks {
		status, details := checkService(check)
		check.Status = status
		check.Details = details

		statusIcon := "✗"
		if status == "UP" {
			statusIcon = "✓"
			healthy++
		}

		fmt.Printf("%-20s %-10s %s\n", check.Name, statusIcon+" "+status, details)
	}

	fmt.Println()
	fmt.Printf("Health: %d/%d services healthy\n", healthy, total)
	fmt.Println()

	if healthy < total {
		fmt.Println("Some services are unhealthy. Try:")
		fmt.Println("  ./cli.sh start    # Start all services")
		fmt.Println("  ./cli.sh doctor   # Detailed diagnostics")
	} else {
		fmt.Println("All services are healthy!")
		fmt.Println()
		fmt.Println("Try sending an event:")
		fmt.Println("  reactive send --wait")
	}
}

func checkService(check ServiceCheck) (string, string) {
	client := http.Client{
		Timeout: 2 * time.Second,
	}

	resp, err := client.Get(check.URL)
	if err != nil {
		return "DOWN", "connection failed"
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		return "DOWN", fmt.Sprintf("HTTP %d", resp.StatusCode)
	}

	// Try to parse health response
	body, _ := io.ReadAll(resp.Body)

	// Check for Spring Boot health response
	var healthResp struct {
		Status string `json:"status"`
	}
	if err := json.Unmarshal(body, &healthResp); err == nil && healthResp.Status != "" {
		return healthResp.Status, ""
	}

	// Check for Flink overview
	var flinkResp struct {
		TaskManagers int `json:"taskmanagers"`
		SlotsTotal   int `json:"slots-total"`
	}
	if err := json.Unmarshal(body, &flinkResp); err == nil && flinkResp.TaskManagers > 0 {
		return "UP", fmt.Sprintf("%d taskmanagers, %d slots", flinkResp.TaskManagers, flinkResp.SlotsTotal)
	}

	// Check for Jaeger services
	var jaegerResp struct {
		Data []string `json:"data"`
	}
	if err := json.Unmarshal(body, &jaegerResp); err == nil && len(jaegerResp.Data) > 0 {
		return "UP", fmt.Sprintf("%d services", len(jaegerResp.Data))
	}

	return "UP", ""
}
