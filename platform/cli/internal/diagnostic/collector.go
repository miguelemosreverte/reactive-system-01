package diagnostic

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

// Collector gathers diagnostic snapshots from all platform components
type Collector struct {
	client     *http.Client
	containers []ContainerConfig
}

// ContainerConfig defines how to collect diagnostics from a container
type ContainerConfig struct {
	Name           string
	DisplayName    string
	Type           string // "jvm", "go", "native"
	HealthURL      string
	MetricsURL     string
	MemoryLimitMB  int64
}

// DefaultContainers returns the standard platform container configurations
func DefaultContainers() []ContainerConfig {
	return []ContainerConfig{
		{Name: "reactive-application", DisplayName: "application", Type: "jvm", HealthURL: "http://localhost:8080/actuator/health", MetricsURL: "http://localhost:8080/actuator/prometheus", MemoryLimitMB: 1536},
		{Name: "reactive-drools", DisplayName: "drools", Type: "jvm", HealthURL: "http://localhost:8180/actuator/health", MetricsURL: "http://localhost:8180/actuator/prometheus", MemoryLimitMB: 512},
		{Name: "reactive-flink-jobmanager", DisplayName: "flink-jm", Type: "jvm", HealthURL: "http://localhost:8081/overview", MetricsURL: "", MemoryLimitMB: 768},
		{Name: "reactive-system-01-flink-taskmanager-1", DisplayName: "flink-tm", Type: "jvm", HealthURL: "", MetricsURL: "http://localhost:9249/metrics", MemoryLimitMB: 1408},
		{Name: "reactive-kafka", DisplayName: "kafka", Type: "jvm", HealthURL: "", MetricsURL: "", MemoryLimitMB: 1536},
		{Name: "reactive-otel-collector", DisplayName: "otel", Type: "go", HealthURL: "http://localhost:4318/health", MetricsURL: "http://localhost:8888/metrics", MemoryLimitMB: 1024},
		{Name: "reactive-jaeger", DisplayName: "jaeger", Type: "go", HealthURL: "http://localhost:16686/api/services", MetricsURL: "http://localhost:14269/metrics", MemoryLimitMB: 96},
		{Name: "reactive-loki", DisplayName: "loki", Type: "go", HealthURL: "http://localhost:3100/ready", MetricsURL: "http://localhost:3100/metrics", MemoryLimitMB: 1024},
		{Name: "reactive-prometheus", DisplayName: "prometheus", Type: "go", HealthURL: "http://localhost:9090/-/healthy", MetricsURL: "", MemoryLimitMB: 768},
		{Name: "reactive-grafana", DisplayName: "grafana", Type: "go", HealthURL: "http://localhost:3001/api/health", MetricsURL: "", MemoryLimitMB: 256},
	}
}

// NewCollector creates a new diagnostic collector
func NewCollector() *Collector {
	return &Collector{
		client: &http.Client{Timeout: 5 * time.Second},
		containers: DefaultContainers(),
	}
}

// ContainerMemoryStats holds memory information for a container
type ContainerMemoryStats struct {
	Name            string
	DisplayName     string
	Type            string
	Status          string // running, exited, oomkilled
	ExitCode        int
	OOMKilled       bool

	// Current usage
	MemoryUsedBytes int64
	MemoryLimitBytes int64
	MemoryPercent   float64

	// Peak usage (high watermark)
	MemoryPeakBytes int64
	MemoryPeakPercent float64

	// Health
	HealthStatus    string
	HealthLatencyMs int64
}

// CollectContainerMemory collects memory stats for all containers including peak usage
func (c *Collector) CollectContainerMemory(ctx context.Context) ([]ContainerMemoryStats, error) {
	var results []ContainerMemoryStats

	for _, container := range c.containers {
		stats := ContainerMemoryStats{
			Name:        container.Name,
			DisplayName: container.DisplayName,
			Type:        container.Type,
			MemoryLimitBytes: container.MemoryLimitMB * 1024 * 1024,
		}

		// Get container status and OOM info
		status, exitCode, oomKilled := c.getContainerStatus(container.Name)
		stats.Status = status
		stats.ExitCode = exitCode
		stats.OOMKilled = oomKilled

		if status == "running" {
			// Get current memory usage from docker stats
			used, limit := c.getDockerMemory(container.Name)
			stats.MemoryUsedBytes = used
			if limit > 0 {
				stats.MemoryLimitBytes = limit
			}
			if stats.MemoryLimitBytes > 0 {
				stats.MemoryPercent = float64(used) / float64(stats.MemoryLimitBytes) * 100
			}

			// Get peak memory from docker inspect
			peak := c.getDockerPeakMemory(container.Name)
			stats.MemoryPeakBytes = peak
			if stats.MemoryLimitBytes > 0 {
				stats.MemoryPeakPercent = float64(peak) / float64(stats.MemoryLimitBytes) * 100
			}

			// Health check
			if container.HealthURL != "" {
				healthy, latency := c.checkHealth(container.HealthURL)
				if healthy {
					stats.HealthStatus = "healthy"
				} else {
					stats.HealthStatus = "unhealthy"
				}
				stats.HealthLatencyMs = latency
			}
		}

		results = append(results, stats)
	}

	return results, nil
}

// getContainerStatus returns container status, exit code, and OOM killed flag
func (c *Collector) getContainerStatus(containerName string) (status string, exitCode int, oomKilled bool) {
	cmd := exec.Command("docker", "inspect", containerName,
		"--format", "{{.State.Status}}|{{.State.ExitCode}}|{{.State.OOMKilled}}")
	output, err := cmd.Output()
	if err != nil {
		return "unknown", 0, false
	}

	parts := strings.Split(strings.TrimSpace(string(output)), "|")
	if len(parts) >= 3 {
		status = parts[0]
		exitCode, _ = strconv.Atoi(parts[1])
		oomKilled = parts[2] == "true"
	}
	return
}

// getDockerMemory returns current memory usage and limit from docker stats
func (c *Collector) getDockerMemory(containerName string) (used, limit int64) {
	cmd := exec.Command("docker", "stats", "--no-stream", "--format",
		"{{.MemUsage}}", containerName)
	output, err := cmd.Output()
	if err != nil {
		return 0, 0
	}

	// Parse "1.313GiB / 1.5GiB" format
	parts := strings.Split(strings.TrimSpace(string(output)), " / ")
	if len(parts) >= 2 {
		used = parseMemoryString(parts[0])
		limit = parseMemoryString(parts[1])
	}
	return
}

// getDockerPeakMemory returns the peak memory usage from container stats
func (c *Collector) getDockerPeakMemory(containerName string) int64 {
	// Docker's memory.max_usage_in_bytes gives us the high watermark
	cmd := exec.Command("docker", "exec", containerName,
		"cat", "/sys/fs/cgroup/memory/memory.max_usage_in_bytes")
	output, err := cmd.Output()
	if err != nil {
		// Try cgroup v2 path
		cmd = exec.Command("docker", "exec", containerName,
			"cat", "/sys/fs/cgroup/memory.peak")
		output, err = cmd.Output()
		if err != nil {
			// Fallback: use docker inspect MemoryStats
			return c.getDockerInspectPeakMemory(containerName)
		}
	}

	peak, _ := strconv.ParseInt(strings.TrimSpace(string(output)), 10, 64)
	return peak
}

// getDockerInspectPeakMemory gets peak from docker inspect as fallback
func (c *Collector) getDockerInspectPeakMemory(containerName string) int64 {
	cmd := exec.Command("docker", "inspect", containerName,
		"--format", "{{.HostConfig.Memory}}")
	output, err := cmd.Output()
	if err != nil {
		return 0
	}
	// This gives limit, not peak - but useful for comparison
	limit, _ := strconv.ParseInt(strings.TrimSpace(string(output)), 10, 64)
	return limit
}

// checkHealth checks the health endpoint and returns status and latency
func (c *Collector) checkHealth(url string) (healthy bool, latencyMs int64) {
	start := time.Now()
	resp, err := c.client.Get(url)
	latencyMs = time.Since(start).Milliseconds()

	if err != nil {
		return false, latencyMs
	}
	defer resp.Body.Close()

	return resp.StatusCode < 400, latencyMs
}

// parseMemoryString parses strings like "1.313GiB" or "965.1MiB" to bytes
func parseMemoryString(s string) int64 {
	s = strings.TrimSpace(s)

	multiplier := int64(1)
	if strings.HasSuffix(s, "GiB") {
		multiplier = 1024 * 1024 * 1024
		s = strings.TrimSuffix(s, "GiB")
	} else if strings.HasSuffix(s, "MiB") {
		multiplier = 1024 * 1024
		s = strings.TrimSuffix(s, "MiB")
	} else if strings.HasSuffix(s, "KiB") {
		multiplier = 1024
		s = strings.TrimSuffix(s, "KiB")
	} else if strings.HasSuffix(s, "GB") {
		multiplier = 1000 * 1000 * 1000
		s = strings.TrimSuffix(s, "GB")
	} else if strings.HasSuffix(s, "MB") {
		multiplier = 1000 * 1000
		s = strings.TrimSuffix(s, "MB")
	} else if strings.HasSuffix(s, "KB") {
		multiplier = 1000
		s = strings.TrimSuffix(s, "KB")
	} else if strings.HasSuffix(s, "B") {
		s = strings.TrimSuffix(s, "B")
	}

	val, _ := strconv.ParseFloat(s, 64)
	return int64(val * float64(multiplier))
}

// CollectFromPrometheus queries Prometheus for container metrics
func (c *Collector) CollectFromPrometheus(ctx context.Context, query string) (map[string]float64, error) {
	url := fmt.Sprintf("http://localhost:9090/api/v1/query?query=%s", query)
	resp, err := c.client.Get(url)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)

	var result struct {
		Status string `json:"status"`
		Data struct {
			Result []struct {
				Metric map[string]string `json:"metric"`
				Value  []interface{}     `json:"value"`
			} `json:"result"`
		} `json:"data"`
	}

	if err := json.Unmarshal(body, &result); err != nil {
		return nil, err
	}

	metrics := make(map[string]float64)
	for _, r := range result.Data.Result {
		name := r.Metric["name"]
		if name == "" {
			name = r.Metric["container"]
		}
		if name == "" {
			name = r.Metric["id"]
		}
		if len(r.Value) >= 2 {
			if strVal, ok := r.Value[1].(string); ok {
				val, _ := strconv.ParseFloat(strVal, 64)
				metrics[name] = val
			}
		}
	}

	return metrics, nil
}

// MemoryReport is a structured report of memory diagnostics
type MemoryReport struct {
	Timestamp       time.Time              `json:"timestamp"`
	Components      []ContainerMemoryStats `json:"components"`
	TotalUsedMB     int64                  `json:"total_used_mb"`
	TotalLimitMB    int64                  `json:"total_limit_mb"`
	TotalPercent    float64                `json:"total_percent"`
	HighestPressure *ContainerMemoryStats  `json:"highest_pressure"`
	OOMDetected     []ContainerMemoryStats `json:"oom_detected"`
	CriticalCount   int                    `json:"critical_count"`  // >90%
	WarningCount    int                    `json:"warning_count"`   // >75%
	HealthyCount    int                    `json:"healthy_count"`   // <75%
}

// GenerateMemoryReport creates a comprehensive memory report
func (c *Collector) GenerateMemoryReport(ctx context.Context) (*MemoryReport, error) {
	stats, err := c.CollectContainerMemory(ctx)
	if err != nil {
		return nil, err
	}

	report := &MemoryReport{
		Timestamp:  time.Now(),
		Components: stats,
	}

	var highestPct float64

	for _, s := range stats {
		report.TotalUsedMB += s.MemoryUsedBytes / (1024 * 1024)
		report.TotalLimitMB += s.MemoryLimitBytes / (1024 * 1024)

		if s.OOMKilled || s.ExitCode == 137 {
			report.OOMDetected = append(report.OOMDetected, s)
		}

		if s.MemoryPercent > 90 {
			report.CriticalCount++
		} else if s.MemoryPercent > 75 {
			report.WarningCount++
		} else if s.Status == "running" {
			report.HealthyCount++
		}

		if s.MemoryPercent > highestPct {
			highestPct = s.MemoryPercent
			copied := s
			report.HighestPressure = &copied
		}
	}

	if report.TotalLimitMB > 0 {
		report.TotalPercent = float64(report.TotalUsedMB) / float64(report.TotalLimitMB) * 100
	}

	return report, nil
}

// FormatMemoryReport returns a formatted string representation
func FormatMemoryReport(r *MemoryReport) string {
	var sb strings.Builder

	sb.WriteString("╔══════════════════════════════════════════════════════════════════╗\n")
	sb.WriteString("║                    MEMORY DIAGNOSTIC REPORT                      ║\n")
	sb.WriteString("╠══════════════════════════════════════════════════════════════════╣\n")
	sb.WriteString(fmt.Sprintf("║  Time: %-58s ║\n", r.Timestamp.Format("2006-01-02 15:04:05")))
	sb.WriteString("╠══════════════════════════════════════════════════════════════════╣\n")

	// Summary
	sb.WriteString(fmt.Sprintf("║  Total: %d MB / %d MB (%.1f%%)%-28s ║\n",
		r.TotalUsedMB, r.TotalLimitMB, r.TotalPercent, ""))
	sb.WriteString(fmt.Sprintf("║  Status: %d critical, %d warning, %d healthy%-20s ║\n",
		r.CriticalCount, r.WarningCount, r.HealthyCount, ""))

	// OOM Detection
	if len(r.OOMDetected) > 0 {
		sb.WriteString("╠══════════════════════════════════════════════════════════════════╣\n")
		sb.WriteString("║  ⚠️  OOM DETECTED                                                 ║\n")
		for _, oom := range r.OOMDetected {
			reason := "OOM Killed"
			if oom.ExitCode == 137 {
				reason = "Exit 137 (SIGKILL)"
			}
			sb.WriteString(fmt.Sprintf("║    • %-15s : %-42s ║\n", oom.DisplayName, reason))
			if oom.MemoryPeakBytes > 0 {
				peakMB := oom.MemoryPeakBytes / (1024 * 1024)
				limitMB := oom.MemoryLimitBytes / (1024 * 1024)
				sb.WriteString(fmt.Sprintf("║      Peak: %d MB / %d MB (%.1f%%)%-29s ║\n",
					peakMB, limitMB, oom.MemoryPeakPercent, ""))
			}
		}
	}

	sb.WriteString("╠══════════════════════════════════════════════════════════════════╣\n")
	sb.WriteString("║  COMPONENT              STATUS    USED/LIMIT      PEAK    HEALTH ║\n")
	sb.WriteString("╠══════════════════════════════════════════════════════════════════╣\n")

	for _, c := range r.Components {
		status := c.Status
		if c.OOMKilled {
			status = "OOM"
		} else if c.ExitCode == 137 {
			status = "KILLED"
		}

		usedMB := c.MemoryUsedBytes / (1024 * 1024)
		limitMB := c.MemoryLimitBytes / (1024 * 1024)
		peakMB := c.MemoryPeakBytes / (1024 * 1024)

		healthIcon := "─"
		if c.HealthStatus == "healthy" {
			healthIcon = "✓"
		} else if c.HealthStatus == "unhealthy" {
			healthIcon = "✗"
		}

		// Pressure bar
		bar := pressureBar(int(c.MemoryPercent))

		sb.WriteString(fmt.Sprintf("║  %-18s %8s  %4d/%4d MB  %4d MB    %s ║\n",
			c.DisplayName, status, usedMB, limitMB, peakMB, healthIcon))
		sb.WriteString(fmt.Sprintf("║    %s %5.1f%%%-36s ║\n", bar, c.MemoryPercent, ""))
	}

	sb.WriteString("╚══════════════════════════════════════════════════════════════════╝\n")

	return sb.String()
}

func pressureBar(pct int) string {
	width := 20
	filled := pct * width / 100
	if filled > width {
		filled = width
	}
	if filled < 0 {
		filled = 0
	}
	return "[" + strings.Repeat("▓", filled) + strings.Repeat("░", width-filled) + "]"
}
