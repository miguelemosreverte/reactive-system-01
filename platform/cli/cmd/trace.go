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
	jaegerURL  = "http://localhost:16686"
	appTraceID string
)

var traceCmd = &cobra.Command{
	Use:   "trace [traceId]",
	Short: "Inspect distributed traces",
	Long: `Inspect distributed traces across all services.

Without arguments, shows recent traces.
With a traceId, shows detailed span timeline.

Examples:
  reactive trace                           # List recent traces
  reactive trace abc123                    # Inspect specific trace
  reactive trace --app-id 000e7ac8...      # Find by app traceId`,
	Run: runTrace,
}

func init() {
	traceCmd.Flags().StringVar(&appTraceID, "app-id", "", "Find trace by app.traceId tag")
}

func runTrace(cmd *cobra.Command, args []string) {
	if len(args) > 0 {
		inspectTrace(args[0])
	} else if appTraceID != "" {
		findByAppTraceID(appTraceID)
	} else {
		listRecentTraces()
	}
}

// JaegerTrace represents a trace from Jaeger API
type JaegerTrace struct {
	TraceID   string                 `json:"traceID"`
	Spans     []JaegerSpan           `json:"spans"`
	Processes map[string]JaegerProc  `json:"processes"`
}

type JaegerSpan struct {
	TraceID       string      `json:"traceID"`
	SpanID        string      `json:"spanID"`
	OperationName string      `json:"operationName"`
	StartTime     int64       `json:"startTime"`
	Duration      int64       `json:"duration"`
	ProcessID     string      `json:"processID"`
	Tags          []JaegerTag `json:"tags"`
}

type JaegerTag struct {
	Key   string      `json:"key"`
	Type  string      `json:"type"`
	Value interface{} `json:"value"`
}

type JaegerProc struct {
	ServiceName string `json:"serviceName"`
}

type JaegerResponse struct {
	Data []JaegerTrace `json:"data"`
}

func inspectTrace(traceID string) {
	resp, err := http.Get(fmt.Sprintf("%s/api/traces/%s", jaegerURL, traceID))
	if err != nil {
		fmt.Printf("Error: Cannot connect to Jaeger at %s\n", jaegerURL)
		fmt.Println("Make sure Jaeger is running: ./cli.sh start")
		return
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	var result JaegerResponse
	if err := json.Unmarshal(body, &result); err != nil || len(result.Data) == 0 {
		fmt.Println("Trace not found")
		return
	}

	trace := result.Data[0]
	printTraceTimeline(trace)
}

func findByAppTraceID(appID string) {
	// URL encode the tags parameter
	tags := url.QueryEscape(fmt.Sprintf(`{"app.traceId":"%s"}`, appID))
	url := fmt.Sprintf("%s/api/traces?service=counter-application&tags=%s&limit=1", jaegerURL, tags)

	resp, err := http.Get(url)
	if err != nil {
		fmt.Printf("Error: Cannot connect to Jaeger at %s\n", jaegerURL)
		return
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	var result JaegerResponse
	if err := json.Unmarshal(body, &result); err != nil || len(result.Data) == 0 {
		fmt.Printf("No trace found for app.traceId: %s\n", appID)
		return
	}

	trace := result.Data[0]
	printTraceTimeline(trace)
}

func listRecentTraces() {
	resp, err := http.Get(fmt.Sprintf("%s/api/traces?service=counter-application&limit=10&lookback=10m", jaegerURL))
	if err != nil {
		fmt.Printf("Error: Cannot connect to Jaeger at %s\n", jaegerURL)
		fmt.Println("Make sure Jaeger is running: ./cli.sh start")
		return
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	var result JaegerResponse
	if err := json.Unmarshal(body, &result); err != nil {
		fmt.Println("Error parsing Jaeger response")
		return
	}

	if len(result.Data) == 0 {
		fmt.Println("No traces found. Send some events first:")
		fmt.Println("  reactive send")
		return
	}

	fmt.Println()
	fmt.Printf("%-34s %5s  %-45s %s\n", "TraceID", "Spans", "Services", "Time")
	fmt.Println(strings.Repeat("-", 100))

	for _, trace := range result.Data {
		services := make(map[string]bool)
		for _, span := range trace.Spans {
			if proc, ok := trace.Processes[span.ProcessID]; ok {
				services[proc.ServiceName] = true
			}
		}

		svcList := make([]string, 0, len(services))
		for svc := range services {
			svcList = append(svcList, svc)
		}
		sort.Strings(svcList)
		svcStr := strings.Join(svcList, ", ")
		if len(svcStr) > 45 {
			svcStr = svcStr[:42] + "..."
		}

		// Get time from first span
		var timeStr string
		if len(trace.Spans) > 0 {
			t := time.UnixMicro(trace.Spans[0].StartTime)
			timeStr = t.Format("15:04:05")
		}

		// Mark full pipeline traces
		marker := " "
		if len(services) >= 3 {
			marker = "*"
		}

		fmt.Printf("%s%-33s %5d  %-45s %s\n", marker, trace.TraceID, len(trace.Spans), svcStr, timeStr)
	}

	fmt.Println()
	fmt.Println("* = full pipeline trace (3+ services)")
	fmt.Println()
	fmt.Println("Inspect a trace: reactive trace <traceId>")
}

// runTraceInspect is called from send command
func runTraceInspect(traceID string) {
	inspectTrace(traceID)
}

func printTraceTimeline(trace JaegerTrace) {
	// Sort spans by start time
	spans := trace.Spans
	sort.Slice(spans, func(i, j int) bool {
		return spans[i].StartTime < spans[j].StartTime
	})

	if len(spans) == 0 {
		fmt.Println("No spans in trace")
		return
	}

	// Collect services
	services := make(map[string]bool)
	for _, span := range spans {
		if proc, ok := trace.Processes[span.ProcessID]; ok {
			services[proc.ServiceName] = true
		}
	}
	svcList := make([]string, 0, len(services))
	for svc := range services {
		svcList = append(svcList, svc)
	}
	sort.Strings(svcList)

	baseTime := spans[0].StartTime

	fmt.Println()
	fmt.Printf("Trace: %s\n", trace.TraceID)
	fmt.Printf("Spans: %d | Services: %s\n", len(spans), strings.Join(svcList, ", "))
	fmt.Println()
	fmt.Printf("%8s  %-20s %-40s %10s\n", "Time", "Service", "Operation", "Duration")
	fmt.Println(strings.Repeat("-", 85))

	for _, span := range spans {
		svc := "unknown"
		if proc, ok := trace.Processes[span.ProcessID]; ok {
			svc = proc.ServiceName
		}
		if len(svc) > 20 {
			svc = svc[:17] + "..."
		}

		opName := span.OperationName
		if len(opName) > 40 {
			opName = opName[:37] + "..."
		}

		startMs := float64(span.StartTime-baseTime) / 1000.0
		durMs := float64(span.Duration) / 1000.0

		fmt.Printf("%7.1fms  %-20s %-40s %8.2fms\n", startMs, svc, opName, durMs)
	}

	fmt.Println()
	fmt.Printf("View in Jaeger: %s/trace/%s\n", jaegerURL, trace.TraceID)
}
