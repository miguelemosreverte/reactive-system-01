package cmd

import (
	"fmt"
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

var rebuildCmd = &cobra.Command{
	Use:   "rebuild <service>",
	Short: "Rebuild and restart a service",
	Long: `Rebuild with Docker cache, then restart.

Examples:
  reactive rebuild gateway
  reactive rebuild drools`,
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
	serviceName := mapServiceName(service)

	// Build with cache
	fmt.Println("Building...")
	runDockerComposeEnv([]string{"DOCKER_BUILDKIT=1"}, "build", serviceName)

	// Recreate and start
	fmt.Println("Starting...")
	runDockerCompose("up", "-d", "--force-recreate", serviceName)

	printSuccess(fmt.Sprintf("%s rebuilt and restarted", service))
	fmt.Println()
	printInfo(fmt.Sprintf("Duration: %.2fs", time.Since(start).Seconds()))
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
