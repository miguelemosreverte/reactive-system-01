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

var (
	replayUpTo    string
	replayShowLog bool
)

var replayCmd = &cobra.Command{
	Use:   "replay <sessionId>",
	Short: "Replay events for debugging with full tracing",
	Long: `Replay all events for a session through the FSM with full tracing.

This is the primary debugging tool - it replays historical events and captures
a complete trace in Jaeger, allowing you to inspect the full execution path
without any side effects.

Commands:
  replay <sessionId>              Replay all events with full tracing
  replay <sessionId> --up-to X    Replay up to specific event
  replay events <sessionId>       List all stored events
  replay history <sessionId>      Show state transitions

Examples:
  reactive replay cli-test                    # Replay session with full trace
  reactive replay cli-test --logs             # Replay and show correlated logs
  reactive replay events cli-test             # List events for session
  reactive replay history cli-test            # Show state history`,
	Args: cobra.MinimumNArgs(1),
	Run:  runReplay,
}

var replayEventsCmd = &cobra.Command{
	Use:   "events <sessionId>",
	Short: "List stored events for a session",
	Args:  cobra.ExactArgs(1),
	Run:   runReplayEvents,
}

var replayHistoryCmd = &cobra.Command{
	Use:   "history <sessionId>",
	Short: "Show state transitions for a session",
	Args:  cobra.ExactArgs(1),
	Run:   runReplayHistory,
}

func init() {
	replayCmd.Flags().StringVar(&replayUpTo, "up-to", "", "Replay up to specific event ID")
	replayCmd.Flags().BoolVar(&replayShowLog, "logs", false, "Show correlated logs after replay")
	replayCmd.AddCommand(replayEventsCmd)
	replayCmd.AddCommand(replayHistoryCmd)
}

type ReplayResponse struct {
	Success        bool                   `json:"success"`
	SessionID      string                 `json:"sessionId"`
	EventsReplayed int                    `json:"eventsReplayed"`
	ReplayTraceID  string                 `json:"replayTraceId"`
	DurationMs     int64                  `json:"durationMs"`
	InitialState   map[string]interface{} `json:"initialState"`
	FinalState     map[string]interface{} `json:"finalState"`
	Error          string                 `json:"error"`
}

type EventsResponse struct {
	SessionID string        `json:"sessionId"`
	Count     int           `json:"count"`
	Events    []StoredEvent `json:"events"`
	Error     string        `json:"error"`
}

type StoredEvent struct {
	EventID   string `json:"eventId"`
	Action    string `json:"action"`
	Timestamp string `json:"timestamp"`
	TraceID   string `json:"traceId"`
}

type HistoryResponse struct {
	SessionID   string       `json:"sessionId"`
	Transitions []Transition `json:"transitions"`
	Error       string       `json:"error"`
}

type Transition struct {
	EventID     string                 `json:"eventId"`
	StateBefore map[string]interface{} `json:"stateBefore"`
	StateAfter  map[string]interface{} `json:"stateAfter"`
}

func runReplay(cmd *cobra.Command, args []string) {
	sessionID := args[0]

	printHeader("Event Replay")
	fmt.Printf("Session: %s\n", sessionID)
	if replayUpTo != "" {
		fmt.Printf("Up to:   %s\n", replayUpTo)
	}
	fmt.Println()

	// Build URL
	url := fmt.Sprintf("%s/api/replay/session/%s", gatewayURL, sessionID)
	if replayUpTo != "" {
		url += "?upToEvent=" + replayUpTo
	}

	// Make request
	resp, err := http.Post(url, "application/json", nil)
	if err != nil {
		printError(fmt.Sprintf("Cannot connect to gateway at %s", gatewayURL))
		printInfo("Make sure the system is running: ./cli.sh start")
		return
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	var result ReplayResponse
	if err := json.Unmarshal(body, &result); err != nil {
		printError(fmt.Sprintf("Error parsing response: %v", err))
		fmt.Printf("Raw response: %s\n", string(body))
		return
	}

	if result.Error != "" {
		printError(result.Error)
		return
	}

	// Show replay results
	printSuccess("Replay completed")
	fmt.Println()
	fmt.Printf("  Events replayed: %d\n", result.EventsReplayed)
	fmt.Printf("  Duration:        %dms\n", result.DurationMs)
	fmt.Printf("  Replay Trace ID: %s\n", result.ReplayTraceID)
	fmt.Println()

	// Show state change
	fmt.Println("State Transition:")
	fmt.Printf("  Initial: %v\n", formatState(result.InitialState))
	fmt.Printf("  Final:   %v\n", formatState(result.FinalState))
	fmt.Println()

	// Wait for trace propagation
	if result.ReplayTraceID != "" {
		fmt.Println("Waiting for trace propagation...")
		time.Sleep(2 * time.Second)
		fmt.Println()

		// Show the trace
		inspectTrace(result.ReplayTraceID)

		// Show logs if requested
		if replayShowLog {
			fmt.Println()
			showLogsForTrace(result.ReplayTraceID)
		}
	}

	fmt.Println()
	fmt.Println("Quick links:")
	fmt.Printf("  Jaeger: %s/trace/%s\n", jaegerURL, result.ReplayTraceID)
	fmt.Printf("  Logs:   reactive logs --trace %s\n", result.ReplayTraceID)
}

func runReplayEvents(cmd *cobra.Command, args []string) {
	sessionID := args[0]

	url := fmt.Sprintf("%s/api/replay/session/%s/events", gatewayURL, sessionID)
	resp, err := http.Get(url)
	if err != nil {
		printError(fmt.Sprintf("Cannot connect to gateway at %s", gatewayURL))
		return
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	var result EventsResponse
	if err := json.Unmarshal(body, &result); err != nil {
		printError(fmt.Sprintf("Error parsing response: %v", err))
		return
	}

	if result.Error != "" {
		printError(result.Error)
		return
	}

	printHeader(fmt.Sprintf("Events for session: %s", sessionID))
	fmt.Printf("Total: %d events\n\n", result.Count)

	if len(result.Events) == 0 {
		printInfo("No events found for this session")
		return
	}

	fmt.Printf("%-20s  %-12s  %-26s  %s\n", "Event ID", "Action", "Timestamp", "Trace ID")
	fmt.Println(strings.Repeat("-", 90))

	for _, event := range result.Events {
		eventID := event.EventID
		if len(eventID) > 20 {
			eventID = eventID[:17] + "..."
		}
		traceID := event.TraceID
		if len(traceID) > 20 {
			traceID = traceID[:17] + "..."
		}
		fmt.Printf("%-20s  %-12s  %-26s  %s\n", eventID, event.Action, event.Timestamp, traceID)
	}

	fmt.Println()
	fmt.Println("Replay all events:")
	fmt.Printf("  reactive replay %s\n", sessionID)
}

func runReplayHistory(cmd *cobra.Command, args []string) {
	sessionID := args[0]

	url := fmt.Sprintf("%s/api/replay/session/%s/history", gatewayURL, sessionID)
	resp, err := http.Get(url)
	if err != nil {
		printError(fmt.Sprintf("Cannot connect to gateway at %s", gatewayURL))
		return
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	var result HistoryResponse
	if err := json.Unmarshal(body, &result); err != nil {
		printError(fmt.Sprintf("Error parsing response: %v", err))
		return
	}

	if result.Error != "" {
		printError(result.Error)
		return
	}

	printHeader(fmt.Sprintf("State History: %s", sessionID))
	fmt.Printf("Transitions: %d\n\n", len(result.Transitions))

	if len(result.Transitions) == 0 {
		printInfo("No transitions found")
		return
	}

	for i, t := range result.Transitions {
		fmt.Printf("─── Event %d: %s ───\n", i+1, t.EventID)
		fmt.Printf("  Before: %v\n", formatState(t.StateBefore))
		fmt.Printf("  After:  %v\n", formatState(t.StateAfter))
		fmt.Println()
	}
}

func formatState(state map[string]interface{}) string {
	if state == nil {
		return "{}"
	}
	// Format as compact JSON-like string
	parts := make([]string, 0)
	for k, v := range state {
		parts = append(parts, fmt.Sprintf("%s=%v", k, v))
	}
	return "{" + strings.Join(parts, ", ") + "}"
}

func showLogsForTrace(traceID string) {
	fmt.Println("Correlated Logs:")
	fmt.Println(strings.Repeat("-", 60))

	// Query Loki for logs with this trace ID
	lokiURL := "http://localhost:3100"
	query := fmt.Sprintf(`{job=~".+"} |= "%s"`, traceID)
	url := fmt.Sprintf("%s/loki/api/v1/query_range?query=%s&limit=50", lokiURL, query)

	resp, err := http.Get(url)
	if err != nil {
		fmt.Printf("  Cannot connect to Loki: %v\n", err)
		return
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	var result map[string]interface{}
	if err := json.Unmarshal(body, &result); err != nil {
		fmt.Printf("  Error parsing Loki response\n")
		return
	}

	data, ok := result["data"].(map[string]interface{})
	if !ok {
		fmt.Printf("  No log data found\n")
		return
	}

	results, ok := data["result"].([]interface{})
	if !ok || len(results) == 0 {
		fmt.Printf("  No logs found for trace %s\n", traceID)
		return
	}

	logCount := 0
	for _, r := range results {
		stream, ok := r.(map[string]interface{})
		if !ok {
			continue
		}
		values, ok := stream["values"].([]interface{})
		if !ok {
			continue
		}
		for _, v := range values {
			if logCount >= 20 {
				fmt.Printf("  ... and more (showing first 20)\n")
				return
			}
			entry, ok := v.([]interface{})
			if !ok || len(entry) < 2 {
				continue
			}
			logLine := entry[1].(string)
			// Truncate long lines
			if len(logLine) > 120 {
				logLine = logLine[:117] + "..."
			}
			fmt.Printf("  %s\n", logLine)
			logCount++
		}
	}

	if logCount == 0 {
		fmt.Printf("  No logs found for trace %s\n", traceID)
	}
}
