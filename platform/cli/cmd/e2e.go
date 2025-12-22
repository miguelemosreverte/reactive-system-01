package cmd

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"

	"github.com/spf13/cobra"
)

var e2eCmd = &cobra.Command{
	Use:   "e2e",
	Short: "Run end-to-end tests",
	Long: `Run end-to-end tests to validate the complete system.

Tests include:
  - Counter value → alert mapping (WARNING, CRITICAL, RESET)
  - API endpoint health

Examples:
  reactive e2e     # Run all E2E tests`,
	Run: runE2E,
}

func init() {
	rootCmd.AddCommand(e2eCmd)
}

type e2eTest struct {
	name          string
	action        string
	value         int
	expectedAlert string
}

type counterResponse struct {
	Alert string `json:"alert"`
	Value int    `json:"value"`
}

func runE2E(cmd *cobra.Command, args []string) {
	printHeader("End-to-End Tests")

	tests := []e2eTest{
		{"Counter=50 triggers WARNING", "set", 50, "WARNING"},
		{"Counter=150 triggers CRITICAL", "set", 150, "CRITICAL"},
		{"Counter=0 triggers RESET", "set", 0, "RESET"},
	}

	passed := 0
	failed := 0
	client := &http.Client{Timeout: 10 * time.Second}

	for i, test := range tests {
		fmt.Printf("\nTest %d: %s\n", i+1, test.name)

		// Send counter action
		payload := map[string]interface{}{
			"action": test.action,
			"value":  test.value,
		}
		payloadBytes, _ := json.Marshal(payload)

		req, err := http.NewRequest("POST", "http://localhost:8080/api/counter", bytes.NewReader(payloadBytes))
		if err != nil {
			printError(fmt.Sprintf("Failed to create request: %v", err))
			failed++
			continue
		}
		req.Header.Set("Content-Type", "application/json")

		resp, err := client.Do(req)
		if err != nil {
			printError(fmt.Sprintf("Failed to send request: %v", err))
			failed++
			continue
		}
		resp.Body.Close()

		// Wait for processing
		time.Sleep(2 * time.Second)

		// Check result
		statusResp, err := client.Get("http://localhost:8080/api/counter/status")
		if err != nil {
			printError(fmt.Sprintf("Failed to get status: %v", err))
			failed++
			continue
		}

		body, _ := io.ReadAll(statusResp.Body)
		statusResp.Body.Close()

		var result counterResponse
		if err := json.Unmarshal(body, &result); err != nil {
			printError(fmt.Sprintf("Failed to parse response: %v", err))
			failed++
			continue
		}

		if result.Alert == test.expectedAlert {
			printSuccess(fmt.Sprintf("PASSED: Got %s alert", result.Alert))
			passed++
		} else {
			printError(fmt.Sprintf("FAILED: Expected %s, got %s", test.expectedAlert, result.Alert))
			failed++
		}
	}

	// Test 4: Health endpoint
	fmt.Printf("\nTest %d: Health endpoint\n", len(tests)+1)
	healthResp, err := client.Get("http://localhost:8080/actuator/health")
	if err != nil {
		printError("FAILED: Cannot reach health endpoint")
		failed++
	} else {
		healthResp.Body.Close()
		if healthResp.StatusCode == 200 {
			printSuccess("PASSED: Health endpoint responsive")
			passed++
		} else {
			printError(fmt.Sprintf("FAILED: Health returned %d", healthResp.StatusCode))
			failed++
		}
	}

	// Summary
	fmt.Println()
	fmt.Println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
	fmt.Printf("Results: %d passed, %d failed\n", passed, failed)

	if failed > 0 {
		printError("E2E tests failed")
		os.Exit(1)
	} else {
		printSuccess("All E2E tests passed!")
	}
}
