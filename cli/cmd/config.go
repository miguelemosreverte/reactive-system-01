package cmd

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/spf13/cobra"
)

// configCmd handles platform configuration operations
var configCmd = &cobra.Command{
	Use:   "config",
	Short: "Manage platform configuration",
	Long: `Platform configuration management using HOCON.

The configuration is defined in platform/src/main/resources/reference.conf
and can be exported for Docker, Go CLI, or validation.

Subcommands:
  show        Display current configuration summary
  validate    Check configuration for errors/warnings
  env         Generate .env file for Docker Compose
  json        Export configuration as JSON`,
}

var configShowCmd = &cobra.Command{
	Use:   "show",
	Short: "Display configuration summary",
	Run: func(cmd *cobra.Command, args []string) {
		runConfigGenerator("summary")
	},
}

var configValidateCmd = &cobra.Command{
	Use:   "validate",
	Short: "Validate configuration",
	Run: func(cmd *cobra.Command, args []string) {
		runConfigGenerator("validate")
	},
}

var configEnvCmd = &cobra.Command{
	Use:   "env",
	Short: "Generate .env file",
	Run: func(cmd *cobra.Command, args []string) {
		output := runConfigGenerator("env")
		if output != "" {
			// Write to .env.generated
			envPath := filepath.Join(projectRoot(), ".env.generated")
			if err := os.WriteFile(envPath, []byte(output), 0644); err != nil {
				fmt.Fprintf(os.Stderr, "Failed to write .env.generated: %v\n", err)
				return
			}
			fmt.Printf("Generated: %s\n", envPath)
		}
	},
}

var configJsonCmd = &cobra.Command{
	Use:   "json",
	Short: "Export as JSON",
	Run: func(cmd *cobra.Command, args []string) {
		runConfigGenerator("json")
	},
}

// runConfigGenerator executes the Java ConfigGenerator
func runConfigGenerator(command string) string {
	// Build and run via Docker to ensure consistent environment
	dockerCmd := exec.Command("docker", "compose", "run", "--rm", "--no-deps",
		"-e", "CLASSPATH=/app/platform.jar:/app/lib/*",
		"--entrypoint", "java",
		"benchmark",
		"com.reactive.platform.config.ConfigGenerator",
		command,
	)
	dockerCmd.Dir = projectRoot()

	output, err := dockerCmd.CombinedOutput()
	if err != nil {
		// Fallback: try to read cached config
		cached := readCachedConfig(command)
		if cached != "" {
			return cached
		}
		fmt.Fprintf(os.Stderr, "Config generator failed: %v\n%s\n", err, output)
		fmt.Fprintln(os.Stderr, "\nFallback: Reading from reference.conf directly...")
		printFallbackConfig(command)
		return ""
	}

	result := string(output)
	fmt.Print(result)

	// Cache the result
	cacheConfig(command, result)
	return result
}

// readCachedConfig reads previously cached configuration
func readCachedConfig(command string) string {
	cachePath := filepath.Join(projectRoot(), ".cache", "config", command+".txt")
	data, err := os.ReadFile(cachePath)
	if err != nil {
		return ""
	}
	return string(data)
}

// cacheConfig stores configuration for offline access
func cacheConfig(command string, content string) {
	cacheDir := filepath.Join(projectRoot(), ".cache", "config")
	os.MkdirAll(cacheDir, 0755)
	cachePath := filepath.Join(cacheDir, command+".txt")
	os.WriteFile(cachePath, []byte(content), 0644)
}

// printFallbackConfig prints a simplified config when Java isn't available
func printFallbackConfig(command string) {
	confPath := filepath.Join(projectRoot(), "platform", "src", "main", "resources", "reference.conf")
	data, err := os.ReadFile(confPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Cannot read reference.conf: %v\n", err)
		return
	}

	switch command {
	case "summary":
		fmt.Println("Platform Configuration (from reference.conf)")
		fmt.Println(strings.Repeat("=", 60))
		fmt.Println()
		printServiceMemoryFromHocon(string(data))
	case "validate":
		fmt.Println("Validation requires Java. Run './cli.sh rebuild benchmark' first.")
	default:
		fmt.Printf("Raw reference.conf:\n%s\n", data)
	}
}

// printServiceMemoryFromHocon extracts memory settings from HOCON (simplified parser)
func printServiceMemoryFromHocon(content string) {
	fmt.Println("Service Memory Allocation (parsed from HOCON):")
	fmt.Println(strings.Repeat("-", 50))

	services := []struct {
		name    string
		pattern string
	}{
		{"application", "application"},
		{"gateway", "gateway"},
		{"flink-jobmanager", "flink-jobmanager"},
		{"flink-taskmanager", "flink-taskmanager"},
		{"drools", "drools"},
		{"kafka", "kafka"},
		{"otel-collector", "otel-collector"},
		{"jaeger", "jaeger"},
		{"prometheus", "prometheus"},
		{"loki", "loki"},
		{"grafana", "grafana"},
		{"cadvisor", "cadvisor"},
		{"ui", "ui"},
	}

	for _, svc := range services {
		// Simple extraction - looks for "container = 1536m" pattern after service name
		if idx := strings.Index(content, svc.pattern+` {`); idx != -1 {
			section := content[idx:]
			if memIdx := strings.Index(section, "container = "); memIdx != -1 {
				end := strings.IndexAny(section[memIdx+12:], "\n}")
				if end > 0 {
					mem := strings.TrimSpace(section[memIdx+12 : memIdx+12+end])
					fmt.Printf("  %-22s %s\n", svc.name, mem)
				}
			}
		}
	}
}

// projectRoot returns the project root directory
func projectRoot() string {
	// Try to find docker-compose.yml to locate project root
	dir, _ := os.Getwd()
	for {
		if _, err := os.Stat(filepath.Join(dir, "docker-compose.yml")); err == nil {
			return dir
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			break
		}
		dir = parent
	}
	// Fallback to current directory
	dir, _ = os.Getwd()
	return dir
}

// PlatformConfig represents the parsed configuration for Go consumers
type PlatformConfig struct {
	TotalMemory string                    `json:"total-memory"`
	Services    map[string]ServiceConfig  `json:"services"`
	Benchmark   BenchmarkConfig           `json:"benchmark"`
	Network     NetworkConfig             `json:"network"`
}

type ServiceConfig struct {
	Description string       `json:"description"`
	Memory      MemoryConfig `json:"memory"`
}

type MemoryConfig struct {
	Container   string `json:"container"`
	Heap        string `json:"heap,omitempty"`
	GoMemLimit  string `json:"go-memlimit,omitempty"`
	ProcessSize string `json:"process-size,omitempty"`
}

type BenchmarkConfig struct {
	MinThroughput  int    `json:"min-throughput"`
	MaxP99Latency  int    `json:"max-p99-latency"`
	WarmupDuration string `json:"warmup-duration"`
}

type NetworkConfig struct {
	Ports map[string]int `json:"ports"`
}

// LoadConfig loads the platform configuration from cache or generates it
func LoadConfig() (*PlatformConfig, error) {
	// Try cached JSON first
	cached := readCachedConfig("json")
	if cached != "" {
		var config PlatformConfig
		if err := json.Unmarshal([]byte(cached), &config); err == nil {
			return &config, nil
		}
	}
	return nil, fmt.Errorf("configuration not available - run './cli.sh config json' first")
}

func init() {
	rootCmd.AddCommand(configCmd)
	configCmd.AddCommand(configShowCmd)
	configCmd.AddCommand(configValidateCmd)
	configCmd.AddCommand(configEnvCmd)
	configCmd.AddCommand(configJsonCmd)
}
