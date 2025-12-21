package cmd

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/spf13/cobra"
)

var (
	gatewayURL  = "http://localhost:8080"
	sendSession string
	sendAction  string
	sendValue   int
	sendWait    bool
)

var sendCmd = &cobra.Command{
	Use:   "send",
	Short: "Send a test event through the pipeline",
	Long: `Send a test event and optionally wait to inspect the trace.

Examples:
  reactive send                           # Send default increment
  reactive send --value 100               # Set counter to 100
  reactive send --action decrement        # Decrement counter
  reactive send --wait                    # Wait and show trace`,
	Run: runSend,
}

func init() {
	sendCmd.Flags().StringVarP(&sendSession, "session", "s", "cli-test", "Session ID")
	sendCmd.Flags().StringVarP(&sendAction, "action", "a", "increment", "Action (increment, decrement, set)")
	sendCmd.Flags().IntVarP(&sendValue, "value", "v", 1, "Value")
	sendCmd.Flags().BoolVarP(&sendWait, "wait", "w", false, "Wait and show trace after sending")
}

type SendRequest struct {
	SessionID string `json:"sessionId"`
	Action    string `json:"action"`
	Value     int    `json:"value"`
}

type SendResponse struct {
	Success     bool   `json:"success"`
	EventID     string `json:"eventId"`
	TraceID     string `json:"traceId"`
	OtelTraceID string `json:"otelTraceId"`
	Status      string `json:"status"`
}

func runSend(cmd *cobra.Command, args []string) {
	req := SendRequest{
		SessionID: sendSession,
		Action:    sendAction,
		Value:     sendValue,
	}

	body, _ := json.Marshal(req)

	fmt.Printf("Sending: %s %d (session: %s)\n", sendAction, sendValue, sendSession)

	resp, err := http.Post(
		fmt.Sprintf("%s/api/counter", gatewayURL),
		"application/json",
		bytes.NewReader(body),
	)
	if err != nil {
		fmt.Printf("Error: Cannot connect to gateway at %s\n", gatewayURL)
		fmt.Println("Make sure the system is running: ./cli.sh start")
		return
	}
	defer resp.Body.Close()

	respBody, _ := io.ReadAll(resp.Body)
	var result SendResponse
	if err := json.Unmarshal(respBody, &result); err != nil {
		fmt.Printf("Error parsing response: %v\n", err)
		fmt.Printf("Raw response: %s\n", string(respBody))
		return
	}

	if !result.Success {
		fmt.Printf("Request failed: %s\n", result.Status)
		return
	}

	fmt.Println()
	fmt.Printf("Event sent successfully!\n")
	fmt.Printf("  Event ID:      %s\n", result.EventID)
	fmt.Printf("  App Trace ID:  %s\n", result.TraceID)
	fmt.Printf("  OTel Trace ID: %s\n", result.OtelTraceID)
	fmt.Println()

	if sendWait {
		fmt.Println("Waiting for trace to propagate...")
		time.Sleep(3 * time.Second)
		fmt.Println()

		// Find and show the trace using OTel trace ID
		runTraceInspect(result.OtelTraceID)
	} else {
		fmt.Println("Inspect trace in Jaeger:")
		fmt.Printf("  reactive trace %s\n", result.OtelTraceID)
		fmt.Println()
		fmt.Println("Search logs by app.traceId:")
		fmt.Printf("  reactive logs %s\n", result.TraceID)
	}
}
