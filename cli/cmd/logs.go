package cmd

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"

	"github.com/spf13/cobra"
)

var (
	lokiURL     = "http://localhost:3100"
	logService  string
	logLimit    int
	logLookback string
)

var logsCmd = &cobra.Command{
	Use:   "logs [traceId]",
	Short: "Find logs by traceId",
	Long: `Find logs from all services for a specific traceId.

Examples:
  reactive logs 000e7ac8ede10d28...    # Logs for app.traceId
  reactive logs --service drools        # Recent drools logs
  reactive logs --limit 50              # More log lines`,
	Run: runLogs,
}

func init() {
	logsCmd.Flags().StringVarP(&logService, "service", "s", "", "Filter by service (gateway, flink, drools)")
	logsCmd.Flags().IntVarP(&logLimit, "limit", "n", 20, "Number of log lines")
	logsCmd.Flags().StringVarP(&logLookback, "lookback", "l", "10m", "Lookback period (e.g., 5m, 1h)")
}

// LokiResponse represents Loki query response
type LokiResponse struct {
	Status string `json:"status"`
	Data   struct {
		ResultType string       `json:"resultType"`
		Result     []LokiStream `json:"result"`
	} `json:"data"`
}

type LokiStream struct {
	Stream map[string]string `json:"stream"`
	Values [][]string        `json:"values"` // [timestamp, line]
}

type LogEntry struct {
	Timestamp time.Time
	Service   string
	Line      string
}

func runLogs(cmd *cobra.Command, args []string) {
	if len(args) > 0 {
		findLogsByTraceID(args[0])
	} else if logService != "" {
		showServiceLogs(logService)
	} else {
		showRecentLogs()
	}
}

func findLogsByTraceID(traceID string) {
	// Build query to search for traceId in log content
	query := fmt.Sprintf(`{compose_service=~"gateway|flink-taskmanager|drools|application"} |~ "%s"`, traceID)

	entries, err := queryLoki(query, logLimit)
	if err != nil {
		fmt.Printf("Error querying Loki: %v\n", err)
		fmt.Println("Make sure Loki is running: ./cli.sh start")
		return
	}

	if len(entries) == 0 {
		fmt.Printf("No logs found for traceId: %s\n", traceID)
		fmt.Println()
		fmt.Println("Tips:")
		fmt.Println("  - Check if the trace is recent (logs may have rotated)")
		fmt.Println("  - Verify the traceId is correct")
		fmt.Println("  - Try: reactive trace --app-id " + traceID)
		return
	}

	printLogs(entries, traceID)
}

func showServiceLogs(service string) {
	// Map friendly names to compose_service labels
	svcMap := map[string]string{
		"gateway":  "gateway|application",
		"flink":    "flink-taskmanager",
		"drools":   "drools",
		"all":      "gateway|flink-taskmanager|drools|application",
	}

	svc, ok := svcMap[service]
	if !ok {
		svc = service
	}

	query := fmt.Sprintf(`{compose_service=~"%s"}`, svc)
	entries, err := queryLoki(query, logLimit)
	if err != nil {
		fmt.Printf("Error querying Loki: %v\n", err)
		return
	}

	if len(entries) == 0 {
		fmt.Printf("No recent logs for service: %s\n", service)
		return
	}

	printLogs(entries, "")
}

func showRecentLogs() {
	query := `{compose_service=~"gateway|flink-taskmanager|drools|application"}`
	entries, err := queryLoki(query, logLimit)
	if err != nil {
		fmt.Printf("Error querying Loki: %v\n", err)
		fmt.Println("Make sure Loki is running: ./cli.sh start")
		return
	}

	if len(entries) == 0 {
		fmt.Println("No recent logs found")
		return
	}

	printLogs(entries, "")
}

func queryLoki(query string, limit int) ([]LogEntry, error) {
	// Calculate time range
	end := time.Now()
	start := end.Add(-10 * time.Minute) // Default 10 minutes

	// Build URL
	params := url.Values{}
	params.Set("query", query)
	params.Set("limit", fmt.Sprintf("%d", limit))
	params.Set("start", fmt.Sprintf("%d", start.UnixNano()))
	params.Set("end", fmt.Sprintf("%d", end.UnixNano()))

	reqURL := fmt.Sprintf("%s/loki/api/v1/query_range?%s", lokiURL, params.Encode())

	resp, err := http.Get(reqURL)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	var result LokiResponse
	if err := json.Unmarshal(body, &result); err != nil {
		return nil, fmt.Errorf("parse error: %v (response: %s)", err, string(body[:min(200, len(body))]))
	}

	if result.Status != "success" {
		return nil, fmt.Errorf("loki error: %s", string(body))
	}

	// Convert to LogEntry slice
	var entries []LogEntry
	for _, stream := range result.Data.Result {
		service := stream.Stream["compose_service"]
		if service == "" {
			service = stream.Stream["service"]
		}

		for _, val := range stream.Values {
			if len(val) < 2 {
				continue
			}

			// Parse timestamp (nanoseconds)
			var ts time.Time
			var nsec int64
			fmt.Sscanf(val[0], "%d", &nsec)
			ts = time.Unix(0, nsec)

			entries = append(entries, LogEntry{
				Timestamp: ts,
				Service:   service,
				Line:      val[1],
			})
		}
	}

	// Sort by timestamp
	sort.Slice(entries, func(i, j int) bool {
		return entries[i].Timestamp.Before(entries[j].Timestamp)
	})

	return entries, nil
}

func printLogs(entries []LogEntry, highlight string) {
	fmt.Println()

	// Group by service for summary
	byService := make(map[string]int)
	for _, e := range entries {
		byService[e.Service]++
	}

	// Print summary
	fmt.Printf("Found %d log entries from %d services\n", len(entries), len(byService))
	for svc, count := range byService {
		fmt.Printf("  %s: %d\n", svc, count)
	}
	fmt.Println()

	// Print logs
	fmt.Printf("%-12s %-20s %s\n", "Time", "Service", "Message")
	fmt.Println(strings.Repeat("-", 100))

	for _, entry := range entries {
		timeStr := entry.Timestamp.Format("15:04:05.000")
		svc := entry.Service
		if len(svc) > 20 {
			svc = svc[:17] + "..."
		}

		// Parse JSON log if possible
		line := extractLogMessage(entry.Line)
		if len(line) > 100 {
			line = line[:97] + "..."
		}

		// Highlight search term if present
		if highlight != "" && strings.Contains(line, highlight) {
			// Simple highlight - just mark it
			line = strings.ReplaceAll(line, highlight, "["+highlight+"]")
		}

		fmt.Printf("%-12s %-20s %s\n", timeStr, svc, line)
	}
	fmt.Println()
}

func extractLogMessage(line string) string {
	// Try to parse as JSON and extract message
	var logMap map[string]interface{}
	if err := json.Unmarshal([]byte(line), &logMap); err == nil {
		// Extract key fields
		var parts []string

		if level, ok := logMap["level"].(string); ok {
			parts = append(parts, fmt.Sprintf("[%s]", strings.ToUpper(strings.TrimSpace(level))))
		}

		if msg, ok := logMap["message"].(string); ok {
			parts = append(parts, msg)
		} else if msg, ok := logMap["msg"].(string); ok {
			parts = append(parts, msg)
		}

		if len(parts) > 0 {
			return strings.Join(parts, " ")
		}
	}

	// Return raw line if not JSON
	return line
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
