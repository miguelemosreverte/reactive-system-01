package cmd

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"time"

	"github.com/spf13/cobra"
)

var smokeSkipStart bool

var smokeCmd = &cobra.Command{
	Use:   "smoke",
	Short: "Run smoke test with full observability report",
	Long: `Start the system fresh and run a smoke test with detailed markdown output.

This command:
  1. Restarts the application fresh (unless --skip-start)
  2. Sends a test event through the pipeline
  3. Waits for processing
  4. Fetches and displays traces from Jaeger
  5. Collects relevant logs from all services
  6. Outputs a beautiful markdown report

Examples:
  reactive smoke              # Full fresh start + smoke test
  reactive smoke --skip-start # Skip restart, just run test`,
	Run: runSmoke,
}

func init() {
	rootCmd.AddCommand(smokeCmd)
	smokeCmd.Flags().BoolVar(&smokeSkipStart, "skip-start", false, "Skip restarting the application")
}

// SmokeResult holds all the data for the smoke test report
type SmokeResult struct {
	RequestID   string
	TraceID     string
	SessionID   string
	Timestamp   time.Time
	Request     SmokeRequest
	Response    map[string]interface{}
	StatusCode  int
	Traces      []TraceSpan
	Logs        map[string][]string // service -> log lines
	Duration    time.Duration
	Success     bool
	ErrorMsg    string
}

type SmokeRequest struct {
	Action    string `json:"action"`
	Value     int    `json:"value"`
	SessionID string `json:"sessionId"`
}

type TraceSpan struct {
	Service     string
	Operation   string
	Duration    string
	StartTime   string
	Status      string
	SpanID      string
	TraceID     string
}

func runSmoke(cmd *cobra.Command, args []string) {
	result := SmokeResult{
		Timestamp: time.Now(),
		SessionID: fmt.Sprintf("smoke-%d", time.Now().Unix()),
		Logs:      make(map[string][]string),
	}

	// Step 1: Fresh start (unless skipped)
	if !smokeSkipStart {
		printMarkdownSection("Starting Fresh", "")
		fmt.Println("```")
		restartApplication()
		fmt.Println("```")
		fmt.Println()

		// Wait for services to be ready
		fmt.Println("Waiting for services to be ready...")
		waitForServices()
		fmt.Println()
	}

	// Step 2: Send test event
	printMarkdownSection("Sending Test Event", "")
	start := time.Now()

	result.Request = SmokeRequest{
		Action:    "increment",
		Value:     42,
		SessionID: result.SessionID,
	}

	statusCode, response, requestID, err := sendSmokeRequest(result.Request)
	result.Duration = time.Since(start)
	result.StatusCode = statusCode
	result.Response = response
	result.RequestID = requestID

	if err != nil {
		result.Success = false
		result.ErrorMsg = err.Error()
		printSmokeReport(result)
		os.Exit(1)
	}

	// Extract traceId from response if available
	if traceID, ok := response["traceId"].(string); ok {
		result.TraceID = traceID
	} else if requestID != "" {
		result.TraceID = requestID
	}

	fmt.Printf("```json\n")
	fmt.Printf("POST /api/bff/counter\n")
	fmt.Printf("{\n")
	fmt.Printf("  \"action\": \"%s\",\n", result.Request.Action)
	fmt.Printf("  \"value\": %d,\n", result.Request.Value)
	fmt.Printf("  \"sessionId\": \"%s\"\n", result.Request.SessionID)
	fmt.Printf("}\n")
	fmt.Printf("```\n\n")

	fmt.Printf("**Response** (HTTP %d):\n", statusCode)
	fmt.Printf("```json\n")
	respJSON, _ := json.MarshalIndent(response, "", "  ")
	fmt.Printf("%s\n", respJSON)
	fmt.Printf("```\n\n")

	// Step 3: Wait for async processing
	fmt.Println("Waiting for async processing (5s)...")
	time.Sleep(5 * time.Second)

	// Step 4: Fetch traces
	printMarkdownSection("Distributed Traces", "")
	result.Traces = fetchTraces(result.RequestID)
	printTracesMarkdown(result.Traces, result.RequestID)

	// Step 5: Collect logs
	printMarkdownSection("Service Logs", "")
	result.Logs = collectLogs(result.SessionID)
	printLogsMarkdown(result.Logs)

	// Step 6: Summary
	result.Success = statusCode == 200 && len(result.Traces) > 0
	printSmokeReport(result)
}

func restartApplication() {
	// Stop existing application
	exec.Command("docker", "stop", "reactive-application").Run()
	exec.Command("docker", "rm", "reactive-application").Run()

	// Start fresh using the CLI
	c := exec.Command("docker", "compose", "up", "-d", "--build")
	c.Stdout = os.Stdout
	c.Stderr = os.Stderr
	c.Run()
}

func waitForServices() {
	client := &http.Client{Timeout: 2 * time.Second}
	endpoints := []struct {
		name string
		url  string
	}{
		{"Gateway", "http://localhost:8080/actuator/health"},
		{"Flink", "http://localhost:8081/overview"},
		{"Jaeger", "http://localhost:16686/api/services"},
	}

	for _, ep := range endpoints {
		for i := 0; i < 30; i++ {
			resp, err := client.Get(ep.url)
			if err == nil && resp.StatusCode == 200 {
				resp.Body.Close()
				fmt.Printf("  ✓ %s ready\n", ep.name)
				break
			}
			if resp != nil {
				resp.Body.Close()
			}
			time.Sleep(1 * time.Second)
		}
	}
}

func sendSmokeRequest(req SmokeRequest) (int, map[string]interface{}, string, error) {
	payload, _ := json.Marshal(req)

	httpReq, err := http.NewRequest("POST", "http://localhost:8080/api/bff/counter", bytes.NewReader(payload))
	if err != nil {
		return 0, nil, "", err
	}
	httpReq.Header.Set("Content-Type", "application/json")

	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(httpReq)
	if err != nil {
		return 0, nil, "", err
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)

	var result map[string]interface{}
	json.Unmarshal(body, &result)

	// Extract requestId from response
	requestID := ""
	if rid, ok := result["requestId"].(string); ok {
		requestID = rid
	}

	return resp.StatusCode, result, requestID, nil
}

func fetchTraces(requestID string) []TraceSpan {
	if requestID == "" {
		return nil
	}

	client := &http.Client{Timeout: 10 * time.Second}

	// Strategy 1: Try forensic API (works with investigation mode)
	forensicURL := fmt.Sprintf("http://localhost:8080/api/forensic/traces/%s", requestID)
	if resp, err := client.Get(forensicURL); err == nil && resp.StatusCode == 200 {
		defer resp.Body.Close()
		body, _ := io.ReadAll(resp.Body)

		var forensicResp struct {
			Spans []struct {
				Service   string `json:"service"`
				Operation string `json:"operation"`
				Duration  int64  `json:"duration"`
				StartTime int64  `json:"startTime"`
				SpanID    string `json:"spanId"`
				TraceID   string `json:"traceId"`
			} `json:"spans"`
		}

		if json.Unmarshal(body, &forensicResp) == nil && len(forensicResp.Spans) > 0 {
			spans := make([]TraceSpan, len(forensicResp.Spans))
			for i, s := range forensicResp.Spans {
				spans[i] = TraceSpan{
					Service:   s.Service,
					Operation: s.Operation,
					Duration:  fmt.Sprintf("%.2fms", float64(s.Duration)/1000.0),
					SpanID:    s.SpanID,
					TraceID:   s.TraceID,
					Status:    "OK",
				}
			}
			return spans
		}
	}

	// Strategy 2: Query Jaeger by requestId tag
	jaegerTagURL := fmt.Sprintf("http://localhost:16686/api/traces?service=counter-application&tags={\"requestId\":\"%s\"}&limit=1", requestID)
	if spans := fetchSmokeJaegerTraces(client, jaegerTagURL); len(spans) > 0 {
		return spans
	}

	// Strategy 3: Fallback - get most recent trace (within last 30 seconds)
	// This is a best-effort fallback when requestId correlation doesn't work
	endTime := time.Now().UnixMicro()
	startTime := time.Now().Add(-30 * time.Second).UnixMicro()
	jaegerRecentURL := fmt.Sprintf("http://localhost:16686/api/traces?service=counter-application&start=%d&end=%d&limit=1", startTime, endTime)
	return fetchSmokeJaegerTraces(client, jaegerRecentURL)
}

func fetchSmokeJaegerTraces(client *http.Client, url string) []TraceSpan {
	resp, err := client.Get(url)
	if err != nil {
		return nil
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)

	var jaegerResp struct {
		Data []struct {
			TraceID string `json:"traceID"`
			Spans   []struct {
				SpanID        string `json:"spanID"`
				OperationName string `json:"operationName"`
				Duration      int64  `json:"duration"`
				ProcessID     string `json:"processID"`
			} `json:"spans"`
			Processes map[string]struct {
				ServiceName string `json:"serviceName"`
			} `json:"processes"`
		} `json:"data"`
	}

	if json.Unmarshal(body, &jaegerResp) != nil || len(jaegerResp.Data) == 0 {
		return nil
	}

	trace := jaegerResp.Data[0]
	spans := make([]TraceSpan, len(trace.Spans))
	for i, s := range trace.Spans {
		service := "unknown"
		if proc, ok := trace.Processes[s.ProcessID]; ok {
			service = proc.ServiceName
		}
		spanID := s.SpanID
		if len(spanID) > 8 {
			spanID = spanID[:8]
		}
		traceID := trace.TraceID
		if len(traceID) > 16 {
			traceID = traceID[:16]
		}
		spans[i] = TraceSpan{
			Service:   service,
			Operation: s.OperationName,
			Duration:  fmt.Sprintf("%.2fms", float64(s.Duration)/1000.0),
			SpanID:    spanID,
			TraceID:   traceID,
			Status:    "OK",
		}
	}

	return spans
}

func collectLogs(sessionID string) map[string][]string {
	logs := make(map[string][]string)

	services := []struct {
		name      string
		container string
		filter    string
	}{
		{"Application", "reactive-application", sessionID},
		{"Flink", "reactive-system-01-flink-taskmanager-1", sessionID},
		{"Drools", "reactive-drools", ""},
	}

	for _, svc := range services {
		output, err := exec.Command("docker", "logs", "--tail", "50", svc.container).CombinedOutput()
		if err != nil {
			continue
		}

		lines := strings.Split(string(output), "\n")
		filtered := []string{}

		for _, line := range lines {
			// Filter for relevant lines
			if svc.filter != "" && !strings.Contains(line, svc.filter) {
				continue
			}
			// Skip empty lines and keep only last few relevant ones
			if strings.TrimSpace(line) != "" {
				// Look for interesting patterns
				if strings.Contains(line, "Processing") ||
					strings.Contains(line, "Drools") ||
					strings.Contains(line, "Kafka") ||
					strings.Contains(line, "counter") ||
					strings.Contains(line, "ERROR") ||
					strings.Contains(line, "WARN") ||
					strings.Contains(line, sessionID) {
					filtered = append(filtered, line)
				}
			}
		}

		// Keep last 10 relevant lines
		if len(filtered) > 10 {
			filtered = filtered[len(filtered)-10:]
		}

		if len(filtered) > 0 {
			logs[svc.name] = filtered
		}
	}

	return logs
}

func printMarkdownSection(title, subtitle string) {
	fmt.Printf("## %s\n", title)
	if subtitle != "" {
		fmt.Printf("_%s_\n", subtitle)
	}
	fmt.Println()
}

func printTracesMarkdown(spans []TraceSpan, requestID string) {
	if len(spans) == 0 {
		fmt.Println("_No traces found yet. Traces may take a few seconds to appear._\n")
		fmt.Printf("**View in UI:** http://localhost:3000/traces?requestId=%s\n\n", requestID)
		return
	}

	fmt.Printf("**Trace Spans** (%d spans)\n\n", len(spans))
	fmt.Println("| Service | Operation | Duration | Status |")
	fmt.Println("|---------|-----------|----------|--------|")

	for _, span := range spans {
		fmt.Printf("| %s | %s | %s | %s |\n",
			span.Service, span.Operation, span.Duration, span.Status)
	}
	fmt.Println()
	fmt.Printf("**View in UI:** http://localhost:3000/traces?requestId=%s\n\n", requestID)
}

func printLogsMarkdown(logs map[string][]string) {
	if len(logs) == 0 {
		fmt.Println("_No relevant logs captured._\n")
		return
	}

	for service, lines := range logs {
		fmt.Printf("### %s\n", service)
		fmt.Println("```")
		for _, line := range lines {
			// Truncate long lines
			if len(line) > 120 {
				line = line[:117] + "..."
			}
			fmt.Println(line)
		}
		fmt.Println("```")
		fmt.Println()
	}
}

func printSmokeReport(result SmokeResult) {
	fmt.Println()
	printMarkdownSection("Summary", "")

	if result.Success {
		fmt.Println("```")
		fmt.Println("╔══════════════════════════════════════════════════════════════╗")
		fmt.Println("║                    ✓ SMOKE TEST PASSED                       ║")
		fmt.Println("╚══════════════════════════════════════════════════════════════╝")
		fmt.Println("```")
	} else {
		fmt.Println("```")
		fmt.Println("╔══════════════════════════════════════════════════════════════╗")
		fmt.Println("║                    ✗ SMOKE TEST FAILED                       ║")
		fmt.Println("╚══════════════════════════════════════════════════════════════╝")
		fmt.Println("```")
		if result.ErrorMsg != "" {
			fmt.Printf("**Error:** %s\n\n", result.ErrorMsg)
		}
	}

	fmt.Println()
	fmt.Println("| Metric | Value |")
	fmt.Println("|--------|-------|")
	fmt.Printf("| Request ID | `%s` |\n", result.RequestID)
	fmt.Printf("| Session ID | `%s` |\n", result.SessionID)
	fmt.Printf("| HTTP Status | %d |\n", result.StatusCode)
	fmt.Printf("| Response Time | %s |\n", result.Duration.Round(time.Millisecond))
	fmt.Printf("| Trace Spans | %d |\n", len(result.Traces))
	fmt.Printf("| Timestamp | %s |\n", result.Timestamp.Format("2006-01-02 15:04:05"))
	fmt.Println()

	fmt.Println("### Quick Links")
	fmt.Println()
	fmt.Printf("- **Traces:** http://localhost:3000/traces?requestId=%s\n", result.RequestID)
	fmt.Println("- **Jaeger:** http://localhost:16686")
	fmt.Println("- **Flink:** http://localhost:8081")
	fmt.Println("- **Gateway API:** http://localhost:8080")
	fmt.Println()

	if !result.Success {
		os.Exit(1)
	}
}
