package cmd

import (
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

// Service groups
var (
	infraServices = []string{"zookeeper", "kafka"}
	obsServices   = []string{"otel-collector", "jaeger", "grafana", "prometheus", "loki", "promtail", "cadvisor"}
	appServices   = []string{"drools", "flink-jobmanager", "flink-taskmanager", "gateway", "ui"}
)

var startCmd = &cobra.Command{
	Use:   "start [service]",
	Short: "Start all services or a specific one",
	Long: `Start Docker Compose services with smart caching.

Examples:
  reactive start           # Start all services
  reactive start gateway   # Start only gateway
  reactive start drools    # Start only drools`,
	Run: runStart,
}

var stopCmd = &cobra.Command{
	Use:   "stop [service]",
	Short: "Stop all services or a specific one",
	Run:   runStop,
}

var restartCmd = &cobra.Command{
	Use:   "restart [service]",
	Short: "Restart services without rebuild",
	Run:   runRestart,
}

var rebuildNoCache bool

var rebuildCmd = &cobra.Command{
	Use:   "rebuild <service>",
	Short: "Rebuild and restart a service",
	Long: `Rebuild with Docker cache, then restart.

Use --no-cache when source code has changed and you need a fresh build.

Examples:
  reactive rebuild gateway           # Rebuild with cache
  reactive rebuild flink             # Rebuild both flink-jobmanager and flink-taskmanager
  reactive rebuild flink --no-cache  # Fresh rebuild (clears BuildKit cache first)`,
	Args: cobra.ExactArgs(1),
	Run:  runRebuild,
}

var downCmd = &cobra.Command{
	Use:   "down",
	Short: "Stop and remove all containers",
	Run:   runDown,
}

var cleanCmd = &cobra.Command{
	Use:   "clean",
	Short: "Remove containers, images, and volumes",
	Run:   runClean,
}

var followCmd = &cobra.Command{
	Use:   "follow <service>",
	Short: "Follow Docker logs for a service",
	Long: `Follow Docker Compose logs for a specific service.

Examples:
  reactive follow gateway
  reactive follow drools`,
	Args:  cobra.ExactArgs(1),
	Run:   runLogsFollow,
}

var devCmd = &cobra.Command{
	Use:   "dev",
	Short: "Start in development mode",
	Long: `Start with development optimizations and show quick commands.`,
	Run:   runDev,
}

var shellCmd = &cobra.Command{
	Use:   "shell <service>",
	Short: "Enter container shell",
	Args:  cobra.ExactArgs(1),
	Run:   runShell,
}

func init() {
	rootCmd.AddCommand(startCmd)
	rootCmd.AddCommand(stopCmd)
	rootCmd.AddCommand(restartCmd)
	rootCmd.AddCommand(rebuildCmd)
	rootCmd.AddCommand(downCmd)
	rootCmd.AddCommand(cleanCmd)
	rootCmd.AddCommand(followCmd)
	rootCmd.AddCommand(devCmd)
	rootCmd.AddCommand(shellCmd)

	// Add --no-cache flag for rebuild
	rebuildCmd.Flags().BoolVar(&rebuildNoCache, "no-cache", false, "Clear BuildKit cache before building (use when source code changed)")
}

func runStart(cmd *cobra.Command, args []string) {
	start := time.Now()

	if len(args) == 0 {
		printHeader("Starting All Services")
		runDockerCompose("up", "-d", "--build")
		printSuccess("All services started")
		fmt.Println()
		printInfo("UI Portal:        http://localhost:3000")
		printInfo("Gateway API:      http://localhost:8080")
		printInfo("Jaeger (Traces):  http://localhost:16686")
		printInfo("Flink Dashboard:  http://localhost:8081")
	} else {
		service := args[0]
		printHeader(fmt.Sprintf("Starting %s", service))
		runDockerCompose("up", "-d", "--build", mapServiceName(service))
		printSuccess(fmt.Sprintf("%s started", service))
	}

	fmt.Println()
	printInfo(fmt.Sprintf("Duration: %.2fs", time.Since(start).Seconds()))
}

func runStop(cmd *cobra.Command, args []string) {
	if len(args) == 0 {
		printHeader("Stopping All Services")
		runDockerCompose("stop")
		printSuccess("All services stopped")
	} else {
		service := args[0]
		printHeader(fmt.Sprintf("Stopping %s", service))
		runDockerCompose("stop", mapServiceName(service))
		printSuccess(fmt.Sprintf("%s stopped", service))
	}
}

func runRestart(cmd *cobra.Command, args []string) {
	if len(args) == 0 {
		printHeader("Restarting All Services")
		runDockerCompose("restart")
		printSuccess("All services restarted")
	} else {
		service := args[0]
		printHeader(fmt.Sprintf("Restarting %s", service))
		runDockerCompose("restart", mapServiceName(service))
		printSuccess(fmt.Sprintf("%s restarted", service))
	}
}

func runRebuild(cmd *cobra.Command, args []string) {
	start := time.Now()
	service := args[0]

	printHeader(fmt.Sprintf("Rebuilding %s", service))

	// Flink requires special handling - full cluster restart for clean state
	if service == "flink" {
		rebuildFlink()
		printSuccess("Flink rebuilt and restarted with clean job state")
		fmt.Println()
		printInfo(fmt.Sprintf("Duration: %.2fs", time.Since(start).Seconds()))
		return
	}

	// Non-Flink services use standard rebuild
	services := []string{mapServiceName(service)}

	// If --no-cache, clear BuildKit cache first
	if rebuildNoCache {
		printInfo("Clearing BuildKit cache...")
		c := exec.Command("docker", "builder", "prune", "-af")
		c.Stdout = os.Stdout
		c.Stderr = os.Stderr
		c.Run()
	}

	// Build services
	printInfo("Building...")
	buildArgs := []string{"compose", "build"}
	if rebuildNoCache {
		buildArgs = append(buildArgs, "--no-cache")
	}
	buildArgs = append(buildArgs, services...)

	c := exec.Command("docker", buildArgs...)
	c.Env = append(os.Environ(), "DOCKER_BUILDKIT=1")
	c.Stdout = os.Stdout
	c.Stderr = os.Stderr
	if err := c.Run(); err != nil {
		printError(fmt.Sprintf("Build failed: %v", err))
		return
	}

	// Recreate and start all services
	printInfo("Starting...")
	upArgs := append([]string{"up", "-d", "--force-recreate"}, services...)
	runDockerCompose(upArgs...)

	printSuccess(fmt.Sprintf("%s rebuilt and restarted", service))
	fmt.Println()
	printInfo(fmt.Sprintf("Duration: %.2fs", time.Since(start).Seconds()))
}

// rebuildFlink handles Flink cluster rebuild with proper job cleanup
func rebuildFlink() {
	// Step 1: Stop job-submitter to prevent new job submissions
	printInfo("Stopping job-submitter...")
	exec.Command("docker", "stop", "reactive-flink-job-submitter").Run()

	// Step 2: Cancel ALL running jobs via REST API
	printInfo("Cancelling all Flink jobs...")
	cancelFlinkJobs()
	time.Sleep(2 * time.Second) // Give jobs time to cancel

	// Step 3: Stop entire Flink cluster
	printInfo("Stopping Flink cluster...")
	exec.Command("docker", "compose", "stop", "flink-taskmanager", "flink-jobmanager").Run()

	// Step 4: Build if needed
	if rebuildNoCache {
		printInfo("Clearing BuildKit cache...")
		c := exec.Command("docker", "builder", "prune", "-af")
		c.Stdout = os.Stdout
		c.Stderr = os.Stderr
		c.Run()
	}

	printInfo("Building Flink images...")
	buildArgs := []string{"compose", "build"}
	if rebuildNoCache {
		buildArgs = append(buildArgs, "--no-cache")
	}
	buildArgs = append(buildArgs, "flink-jobmanager", "flink-taskmanager", "flink-job-submitter")

	c := exec.Command("docker", buildArgs...)
	c.Env = append(os.Environ(), "DOCKER_BUILDKIT=1")
	c.Stdout = os.Stdout
	c.Stderr = os.Stderr
	if err := c.Run(); err != nil {
		printError(fmt.Sprintf("Build failed: %v", err))
		return
	}

	// Step 5: Start jobmanager first
	printInfo("Starting jobmanager...")
	runDockerCompose("up", "-d", "--force-recreate", "flink-jobmanager")

	// Step 6: Wait for jobmanager to be healthy
	printInfo("Waiting for jobmanager to be ready...")
	for i := 0; i < 30; i++ {
		resp, err := http.Get("http://localhost:8081/overview")
		if err == nil && resp.StatusCode == 200 {
			resp.Body.Close()
			break
		}
		if resp != nil {
			resp.Body.Close()
		}
		time.Sleep(1 * time.Second)
	}

	// Step 7: Start taskmanager
	printInfo("Starting taskmanager...")
	runDockerCompose("up", "-d", "--force-recreate", "flink-taskmanager")

	// Step 8: Wait for taskmanager to register
	printInfo("Waiting for taskmanager to register...")
	for i := 0; i < 30; i++ {
		resp, err := http.Get("http://localhost:8081/taskmanagers")
		if err == nil {
			body, _ := io.ReadAll(resp.Body)
			resp.Body.Close()
			if strings.Contains(string(body), `"taskmanagers":[{`) {
				break
			}
		}
		time.Sleep(1 * time.Second)
	}

	// Step 9: Start job-submitter (submits exactly one job)
	printInfo("Starting job-submitter...")
	runDockerCompose("up", "-d", "--force-recreate", "flink-job-submitter")

	// Step 10: Verify exactly one job is running
	time.Sleep(5 * time.Second)
	printInfo("Verifying job state...")
	resp, err := http.Get("http://localhost:8081/jobs")
	if err == nil {
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		var jobsResp struct {
			Jobs []struct {
				ID     string `json:"id"`
				Status string `json:"status"`
			} `json:"jobs"`
		}
		if json.Unmarshal(body, &jobsResp) == nil {
			running := 0
			for _, job := range jobsResp.Jobs {
				if job.Status == "RUNNING" {
					running++
					printInfo(fmt.Sprintf("Job %s: %s", job.ID[:8], job.Status))
				}
			}
			if running == 1 {
				printSuccess("Exactly 1 job running - cluster is healthy")
			} else if running == 0 {
				printWarning("No jobs running yet - job may still be starting")
			} else {
				printWarning(fmt.Sprintf("%d jobs running - expected 1", running))
			}
		}
	}
}

func runDown(cmd *cobra.Command, args []string) {
	printHeader("Stopping and Removing Containers")
	runDockerCompose("down")
	printSuccess("All containers removed")
}

func runClean(cmd *cobra.Command, args []string) {
	printHeader("Cleaning All Resources")
	runDockerCompose("down", "-v", "--rmi", "local")
	printSuccess("Cleaned up")
}

func runLogsFollow(cmd *cobra.Command, args []string) {
	service := args[0]
	runDockerComposeInteractive("logs", "-f", mapServiceName(service))
}

func runDev(cmd *cobra.Command, args []string) {
	printHeader("Development Mode")
	fmt.Println()
	printInfo("Starting with development optimizations...")
	fmt.Println()

	runDockerCompose("up", "-d", "--build")

	fmt.Println()
	printSuccess("Dev environment ready!")
	fmt.Println()
	printInfo("Quick commands:")
	fmt.Println("  reactive restart gateway   # Restart (no rebuild)")
	fmt.Println("  reactive rebuild gateway   # Rebuild and restart")
	fmt.Println("  reactive logs gateway      # Watch logs")
	fmt.Println("  reactive status            # Check health")
	fmt.Println()
}

func runShell(cmd *cobra.Command, args []string) {
	service := args[0]
	containerName := mapContainerName(service)

	printInfo(fmt.Sprintf("Entering %s shell...", service))

	// Try sh first, then bash
	c := exec.Command("docker", "exec", "-it", containerName, "/bin/sh")
	c.Stdin = os.Stdin
	c.Stdout = os.Stdout
	c.Stderr = os.Stderr
	if err := c.Run(); err != nil {
		c = exec.Command("docker", "exec", "-it", containerName, "/bin/bash")
		c.Stdin = os.Stdin
		c.Stdout = os.Stdout
		c.Stderr = os.Stderr
		c.Run()
	}
}

// Helper functions

func mapServiceName(shortName string) string {
	switch shortName {
	case "gateway":
		return "gateway"
	case "drools":
		return "drools"
	case "flink":
		return "flink-jobmanager"
	case "ui":
		return "ui"
	case "kafka":
		return "kafka"
	default:
		return shortName
	}
}

func mapContainerName(shortName string) string {
	switch shortName {
	case "gateway":
		return "reactive-gateway"
	case "drools":
		return "reactive-drools"
	case "flink":
		return "reactive-flink-jobmanager"
	case "ui":
		return "reactive-ui"
	case "kafka":
		return "reactive-kafka"
	default:
		return "reactive-" + shortName
	}
}

func runDockerCompose(args ...string) {
	c := exec.Command("docker", append([]string{"compose"}, args...)...)
	c.Stdout = os.Stdout
	c.Stderr = os.Stderr
	if err := c.Run(); err != nil {
		printError(fmt.Sprintf("Command failed: %v", err))
	}
}

func runDockerComposeEnv(env []string, args ...string) {
	c := exec.Command("docker", append([]string{"compose"}, args...)...)
	c.Env = append(os.Environ(), env...)
	c.Stdout = os.Stdout
	c.Stderr = os.Stderr
	c.Run()
}

func runDockerComposeInteractive(args ...string) {
	c := exec.Command("docker", append([]string{"compose"}, args...)...)
	c.Stdin = os.Stdin
	c.Stdout = os.Stdout
	c.Stderr = os.Stderr
	c.Run()
}

// Output helpers
func printHeader(msg string) {
	fmt.Println()
	fmt.Println(strings.Repeat("═", 60))
	fmt.Printf("  %s\n", msg)
	fmt.Println(strings.Repeat("═", 60))
	fmt.Println()
}

func printInfo(msg string) {
	fmt.Printf("→ %s\n", msg)
}

func printSuccess(msg string) {
	fmt.Printf("✓ %s\n", msg)
}

func printError(msg string) {
	fmt.Printf("✗ %s\n", msg)
}

func printWarning(msg string) {
	fmt.Printf("⚠ %s\n", msg)
}

// cancelFlinkJobs cancels all running Flink jobs via the REST API
func cancelFlinkJobs() {
	printInfo("Cancelling existing Flink jobs...")

	// Get list of running jobs
	resp, err := http.Get("http://localhost:8081/jobs")
	if err != nil {
		printWarning("Could not connect to Flink - may not be running yet")
		return
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return
	}

	var jobsResp struct {
		Jobs []struct {
			ID     string `json:"id"`
			Status string `json:"status"`
		} `json:"jobs"`
	}

	if err := json.Unmarshal(body, &jobsResp); err != nil {
		return
	}

	// Cancel each running job
	for _, job := range jobsResp.Jobs {
		if job.Status == "RUNNING" {
			printInfo(fmt.Sprintf("Cancelling job %s...", job.ID[:8]))
			req, _ := http.NewRequest("PATCH", fmt.Sprintf("http://localhost:8081/jobs/%s", job.ID), nil)
			client := &http.Client{Timeout: 10 * time.Second}
			client.Do(req)
		}
	}
}
