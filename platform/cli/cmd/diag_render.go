package cmd

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/spf13/cobra"
	"github.com/reactive-system/cli/internal/diagnostic"
)

var (
	diagOutputFormat string
	diagOutputFile   string
)

var diagRenderCmd = &cobra.Command{
	Use:   "render <json-file>",
	Short: "Render diagnostic data in various formats",
	Long: `Render diagnostic JSON data as HTML, Markdown, or formatted JSON.

Examples:
  reactive diag render snapshot.json                    # Output as Markdown to stdout
  reactive diag render snapshot.json -f html -o report.html  # Output as HTML file
  reactive diag render snapshot.json -f json            # Output as formatted JSON`,
	Args: cobra.ExactArgs(1),
	Run:  runDiagRender,
}

var diagCmd = &cobra.Command{
	Use:   "diag",
	Short: "Diagnostic tools",
	Long: `Diagnostic tools for analyzing system health.

Commands:
  render     Render diagnostic JSON as HTML/Markdown/JSON
  scenarios  List available demo scenarios`,
}

var diagScenariosCmd = &cobra.Command{
	Use:   "scenarios",
	Short: "List demo diagnostic scenarios",
	Long: `List available demo diagnostic scenarios for testing.

These scenarios demonstrate different system states:
  - Normal operation
  - Memory pressure (pre-OOM)
  - Dependency bottleneck
  - Thread contention
  - Kafka backpressure`,
	Run: runDiagScenarios,
}

func init() {
	diagRenderCmd.Flags().StringVarP(&diagOutputFormat, "format", "f", "markdown", "Output format: html, markdown, json")
	diagRenderCmd.Flags().StringVarP(&diagOutputFile, "output", "o", "", "Output file (default: stdout)")

	diagCmd.AddCommand(diagRenderCmd)
	diagCmd.AddCommand(diagScenariosCmd)
	rootCmd.AddCommand(diagCmd)
}

func runDiagRender(cmd *cobra.Command, args []string) {
	inputFile := args[0]

	// Load the diagnostic snapshot
	snapshot, err := diagnostic.LoadFromFile(inputFile)
	if err != nil {
		printError(fmt.Sprintf("Failed to load diagnostic data: %v", err))
		return
	}

	// Determine output destination
	var output *os.File
	if diagOutputFile != "" {
		output, err = os.Create(diagOutputFile)
		if err != nil {
			printError(fmt.Sprintf("Failed to create output file: %v", err))
			return
		}
		defer output.Close()
	} else {
		output = os.Stdout
	}

	// Render based on format
	switch strings.ToLower(diagOutputFormat) {
	case "html":
		renderer, err := diagnostic.NewHTMLRenderer()
		if err != nil {
			printError(fmt.Sprintf("Failed to create HTML renderer: %v", err))
			return
		}
		if err := renderer.Render(output, snapshot); err != nil {
			printError(fmt.Sprintf("Failed to render HTML: %v", err))
			return
		}

	case "json":
		renderer := diagnostic.NewJSONRenderer(true)
		if err := renderer.Render(output, snapshot); err != nil {
			printError(fmt.Sprintf("Failed to render JSON: %v", err))
			return
		}
		fmt.Fprintln(output) // Add newline

	case "markdown", "md":
		renderer := diagnostic.NewMarkdownRenderer()
		if err := renderer.Render(output, snapshot); err != nil {
			printError(fmt.Sprintf("Failed to render Markdown: %v", err))
			return
		}

	default:
		printError(fmt.Sprintf("Unknown format: %s (supported: html, markdown, json)", diagOutputFormat))
		return
	}

	if diagOutputFile != "" {
		printSuccess(fmt.Sprintf("Rendered %s to %s", diagOutputFormat, diagOutputFile))
	}
}

func runDiagScenarios(cmd *cobra.Command, args []string) {
	printHeader("Available Diagnostic Scenarios")

	// Find the testdata directory relative to the CLI
	scenarios := []struct {
		file        string
		name        string
		description string
	}{
		{"scenario_1_normal.json", "Normal Operation", "Healthy system at 1,523 events/sec"},
		{"scenario_2_memory_pressure.json", "Memory Pressure", "Pre-OOM state with GC thrashing"},
		{"scenario_3_dependency_bottleneck.json", "Dependency Bottleneck", "Drools service saturated at 100%"},
		{"scenario_4_thread_contention.json", "Thread Contention", "Lock contention blocking processing"},
		{"scenario_5_kafka_backpressure.json", "Kafka Backpressure", "Producer buffer exhausted"},
	}

	// Try to find the testdata directory
	testdataDir := findTestdataDir()

	fmt.Printf("%-35s %-25s %s\n", "File", "Scenario", "Description")
	fmt.Println(strings.Repeat("-", 90))

	for _, s := range scenarios {
		path := filepath.Join(testdataDir, s.file)
		exists := "✓"
		if _, err := os.Stat(path); os.IsNotExist(err) {
			exists = "✗"
		}
		fmt.Printf("%s %-33s %-25s %s\n", exists, s.file, s.name, s.description)
	}

	fmt.Println()
	fmt.Println("Usage:")
	fmt.Printf("  reactive diag render %s/%s\n", testdataDir, "scenario_1_normal.json")
	fmt.Printf("  reactive diag render %s/%s -f html -o report.html\n", testdataDir, "scenario_2_memory_pressure.json")
	fmt.Println()
}

func findTestdataDir() string {
	// Try common locations
	locations := []string{
		"testdata/diagnostics",
		"platform/cli/testdata/diagnostics",
		"../cli/testdata/diagnostics",
	}

	for _, loc := range locations {
		if _, err := os.Stat(loc); err == nil {
			return loc
		}
	}

	return "testdata/diagnostics"
}
