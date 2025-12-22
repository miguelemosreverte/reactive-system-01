package cmd

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/spf13/cobra"
)

var (
	benchDoctorJSON bool
)

var benchDoctorCmd = &cobra.Command{
	Use:   "doctor",
	Short: "Validate observability chain for benchmarks",
	Long: `Diagnose benchmark observability setup.

Checks:
  - Service health (Gateway, Drools, Jaeger, Loki, OTel Collector)
  - Flink job status
  - Trace propagation across services
  - Jaeger service registry
  - Log correlation in Loki

Examples:
  reactive bench doctor          # Interactive colored output
  reactive bench doctor --json   # JSON output for programmatic use`,
	Run: runBenchDoctor,
}

func init() {
	benchDoctorCmd.Flags().BoolVar(&benchDoctorJSON, "json", false, "Output as JSON")
	benchCmd.AddCommand(benchDoctorCmd)
}

type checkResult struct {
	Category string `json:"category"`
	Name     string `json:"name"`
	Status   string `json:"status"` // pass, fail, warn
	Message  string `json:"message"`
}

type doctorReport struct {
	Checks  []checkResult `json:"checks"`
	Summary struct {
		Pass int `json:"pass"`
		Fail int `json:"fail"`
		Warn int `json:"warn"`
	} `json:"summary"`
}

var expectedServices = []string{"counter-application", "flink-taskmanager", "drools"}

func runBenchDoctor(cmd *cobra.Command, args []string) {
	report := &doctorReport{}
	client := &http.Client{Timeout: 10 * time.Second}

	if !benchDoctorJSON {
		fmt.Println()
		fmt.Println("╔════════════════════════════════════════════════════════════╗")
		fmt.Println("║  Benchmark Doctor                                          ║")
		fmt.Println("╚════════════════════════════════════════════════════════════╝")
		fmt.Println()
	}

	// 1. Service Health
	checkServiceHealth(client, report)

	// 2. Trace Propagation
	checkTracePropagation(client, report)

	// 3. Jaeger Services
	checkJaegerServices(client, report)

	// 4. Log Correlation
	checkLogCorrelation(client, report)

	// Output
	if benchDoctorJSON {
		jsonBytes, _ := json.MarshalIndent(report, "", "  ")
		fmt.Println(string(jsonBytes))
	} else {
		fmt.Println()
		fmt.Println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
		fmt.Printf("Summary: %d passed, %d failed, %d warnings\n",
			report.Summary.Pass, report.Summary.Fail, report.Summary.Warn)

		if report.Summary.Fail > 0 {
			printError("Observability chain has issues")
		} else if report.Summary.Warn > 0 {
			printWarning("Observability chain has warnings")
		} else {
			printSuccess("Observability chain is healthy")
		}
	}
}

func addCheck(report *doctorReport, category, name, status, message string) {
	report.Checks = append(report.Checks, checkResult{
		Category: category,
		Name:     name,
		Status:   status,
		Message:  message,
	})
	switch status {
	case "pass":
		report.Summary.Pass++
		if !benchDoctorJSON {
			fmt.Printf("  ✓ %s\n", message)
		}
	case "fail":
		report.Summary.Fail++
		if !benchDoctorJSON {
			fmt.Printf("  ✗ %s\n", message)
		}
	case "warn":
		report.Summary.Warn++
		if !benchDoctorJSON {
			fmt.Printf("  ⚠ %s\n", message)
		}
	}
}

func checkServiceHealth(client *http.Client, report *doctorReport) {
	if !benchDoctorJSON {
		fmt.Println("1. Service Health")
	}

	services := []struct {
		name string
		url  string
	}{
		{"Gateway", "http://localhost:8080/actuator/health"},
		{"Drools", "http://localhost:8180/health"},
		{"Jaeger", "http://localhost:16686"},
		{"Loki", "http://localhost:3100/ready"},
		{"OTel Collector", "http://localhost:13133"},
	}

	for _, svc := range services {
		resp, err := client.Get(svc.url)
		if err != nil {
			addCheck(report, "services", svc.name, "fail", fmt.Sprintf("%s is not responding", svc.name))
			continue
		}
		resp.Body.Close()
		if resp.StatusCode < 400 {
			addCheck(report, "services", svc.name, "pass", fmt.Sprintf("%s is healthy", svc.name))
		} else {
			addCheck(report, "services", svc.name, "fail", fmt.Sprintf("%s returned %d", svc.name, resp.StatusCode))
		}
	}

	// Check Flink jobs
	resp, err := client.Get("http://localhost:8081/jobs/overview")
	if err != nil {
		addCheck(report, "services", "Flink", "fail", "Flink JobManager not responding")
	} else {
		defer resp.Body.Close()
		body, _ := io.ReadAll(resp.Body)
		var jobs struct {
			Jobs []struct {
				State string `json:"state"`
			} `json:"jobs"`
		}
		if json.Unmarshal(body, &jobs) == nil {
			running := 0
			for _, j := range jobs.Jobs {
				if j.State == "RUNNING" {
					running++
				}
			}
			if running > 0 {
				addCheck(report, "services", "Flink", "pass", fmt.Sprintf("Flink has %d running job(s)", running))
			} else {
				addCheck(report, "services", "Flink", "fail", "No Flink jobs running")
			}
		}
	}
}

func checkTracePropagation(client *http.Client, report *doctorReport) {
	if !benchDoctorJSON {
		fmt.Println("\n2. Trace Propagation")
	}

	// Send test request
	sessionID := fmt.Sprintf("doctor-test-%d", time.Now().Unix())
	payload := map[string]interface{}{
		"sessionId": sessionID,
		"action":    "increment",
		"value":     100,
	}
	payloadBytes, _ := json.Marshal(payload)

	resp, err := client.Post("http://localhost:8080/api/counter",
		"application/json", bytes.NewReader(payloadBytes))
	if err != nil {
		addCheck(report, "trace", "request", "fail", "Gateway not responding")
		return
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)

	var response struct {
		RequestID   string `json:"requestId"`
		OtelTraceID string `json:"otelTraceId"`
	}
	if json.Unmarshal(body, &response) != nil || response.OtelTraceID == "" {
		addCheck(report, "trace", "traceId", "fail", "No otelTraceId in response")
		return
	}

	addCheck(report, "trace", "traceId", "pass",
		fmt.Sprintf("Got trace ID: %s...", response.OtelTraceID[:16]))

	// Wait for propagation
	if !benchDoctorJSON {
		fmt.Println("  → Waiting 5s for trace propagation...")
	}
	time.Sleep(5 * time.Second)

	// Fetch trace from Jaeger
	traceResp, err := client.Get(fmt.Sprintf("http://localhost:16686/api/traces/%s", response.OtelTraceID))
	if err != nil {
		addCheck(report, "trace", "jaeger", "fail", "Could not fetch trace from Jaeger")
		return
	}
	defer traceResp.Body.Close()
	traceBody, _ := io.ReadAll(traceResp.Body)

	var traceData struct {
		Data []struct {
			Spans     []json.RawMessage      `json:"spans"`
			Processes map[string]interface{} `json:"processes"`
		} `json:"data"`
	}
	if json.Unmarshal(traceBody, &traceData) != nil || len(traceData.Data) == 0 {
		addCheck(report, "trace", "spans", "fail", "Trace has no data")
		return
	}

	trace := traceData.Data[0]
	spanCount := len(trace.Spans)

	// Extract services from processes
	services := make(map[string]bool)
	for _, proc := range trace.Processes {
		if p, ok := proc.(map[string]interface{}); ok {
			if svc, ok := p["serviceName"].(string); ok {
				services[svc] = true
			}
		}
	}

	if !benchDoctorJSON {
		fmt.Printf("  → Found %d spans\n", spanCount)
	}

	// Check expected services
	for _, expected := range expectedServices {
		if services[expected] {
			addCheck(report, "trace", expected, "pass", fmt.Sprintf("Trace includes %s spans", expected))
		} else {
			addCheck(report, "trace", expected, "fail", fmt.Sprintf("Trace MISSING %s spans", expected))
		}
	}
}

func checkJaegerServices(client *http.Client, report *doctorReport) {
	if !benchDoctorJSON {
		fmt.Println("\n3. Jaeger Service Registry")
	}

	resp, err := client.Get("http://localhost:16686/api/services")
	if err != nil {
		addCheck(report, "jaeger", "registry", "fail", "Cannot fetch Jaeger services")
		return
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)

	var services struct {
		Data []string `json:"data"`
	}
	if json.Unmarshal(body, &services) != nil {
		addCheck(report, "jaeger", "registry", "fail", "Cannot parse Jaeger services")
		return
	}

	serviceSet := make(map[string]bool)
	for _, s := range services.Data {
		serviceSet[s] = true
	}

	for _, expected := range expectedServices {
		if serviceSet[expected] {
			addCheck(report, "jaeger", expected, "pass", fmt.Sprintf("%s registered in Jaeger", expected))
		} else {
			addCheck(report, "jaeger", expected, "fail", fmt.Sprintf("%s NOT in Jaeger", expected))
		}
	}
}

func checkLogCorrelation(client *http.Client, report *doctorReport) {
	if !benchDoctorJSON {
		fmt.Println("\n4. Log Correlation (Loki)")
	}

	// Check Loki ready
	resp, err := client.Get("http://localhost:3100/ready")
	if err != nil {
		addCheck(report, "logs", "loki", "fail", "Loki not ready")
		return
	}
	resp.Body.Close()
	addCheck(report, "logs", "loki", "pass", "Loki is ready")

	// Query each service
	services := []struct {
		display string
		loki    string
	}{
		{"Gateway", "application"},
		{"Flink", "flink-taskmanager"},
		{"Drools", "drools"},
	}

	endTime := time.Now().UnixNano()
	startTime := endTime - (5 * 60 * 1000000000) // 5 minutes ago

	for _, svc := range services {
		query := fmt.Sprintf(`{service="%s"}`, svc.loki)
		params := url.Values{}
		params.Set("query", query)
		params.Set("start", fmt.Sprintf("%d", startTime))
		params.Set("end", fmt.Sprintf("%d", endTime))
		params.Set("limit", "10")

		lokiURL := fmt.Sprintf("http://localhost:3100/loki/api/v1/query_range?%s", params.Encode())
		resp, err := client.Get(lokiURL)
		if err != nil {
			addCheck(report, "logs", svc.display, "warn", fmt.Sprintf("%s logs query failed", svc.display))
			continue
		}

		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()

		var result struct {
			Data struct {
				Result []struct {
					Values [][]string `json:"values"`
				} `json:"result"`
			} `json:"data"`
		}

		if json.Unmarshal(body, &result) != nil {
			addCheck(report, "logs", svc.display, "warn", fmt.Sprintf("%s logs parse failed", svc.display))
			continue
		}

		logCount := 0
		hasRequestID := false
		for _, stream := range result.Data.Result {
			logCount += len(stream.Values)
			for _, v := range stream.Values {
				if len(v) > 1 && strings.Contains(v[1], "requestId") {
					hasRequestID = true
				}
			}
		}

		if logCount > 0 {
			if hasRequestID {
				addCheck(report, "logs", svc.display, "pass",
					fmt.Sprintf("%s logs have requestId correlation (%d logs)", svc.display, logCount))
			} else {
				addCheck(report, "logs", svc.display, "warn",
					fmt.Sprintf("%s logs found but missing requestId (%d logs)", svc.display, logCount))
			}
		} else {
			addCheck(report, "logs", svc.display, "warn",
				fmt.Sprintf("%s has no recent logs in Loki", svc.display))
		}
	}
}
