package main

import (
	"flag"
	"log"
	"os"

	"github.com/reactive/benchmark/pkg/api"
	"github.com/reactive/benchmark/pkg/runner"
)

func main() {
	port := flag.String("port", "8090", "HTTP server port")
	reportDir := flag.String("report-dir", "/reports", "Directory for benchmark reports")
	flag.Parse()

	// Allow environment variable overrides
	if envPort := os.Getenv("PORT"); envPort != "" {
		*port = envPort
	}
	if envReportDir := os.Getenv("REPORT_DIR"); envReportDir != "" {
		*reportDir = envReportDir
	}

	// Create report directory
	if err := os.MkdirAll(*reportDir, 0755); err != nil {
		log.Fatalf("Failed to create report directory: %v", err)
	}

	// Initialize registry and server
	registry := runner.NewRegistry()
	server := api.NewServer(registry, *reportDir)

	log.Printf("Benchmark service starting on port %s", *port)
	log.Printf("Reports will be saved to %s", *reportDir)
	log.Printf("Available components: %v", registry.ListComponents())

	if err := server.Start(":" + *port); err != nil {
		log.Fatalf("Server error: %v", err)
	}
}
