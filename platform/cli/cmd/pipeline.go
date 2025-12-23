package cmd

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os/exec"
	"sort"
	"strings"
	"time"

	"github.com/spf13/cobra"
)

var pipelineCmd = &cobra.Command{
	Use:   "pipeline",
	Short: "Hierarchical pipeline diagnostic",
	Long: `Comprehensive pipeline diagnostic combining all data sources.

Presents data hierarchically:
  Level 1: System Overview (saturation, resource usage)
  Level 2: Component Breakdown (gateway, kafka, flink, drools)
  Level 3: Operation Details (spans within each component)
  Level 4: Raw Data (for AI analysis)

The output is designed to make bottlenecks OBVIOUS from the data,
without requiring interpretation or guessing.

Examples:
  reactive pipeline              # Full hierarchical diagnostic
  reactive pipeline --level 2    # Stop at component level
  reactive pipeline --json       # JSON output for AI analysis`,
	Run: runPipeline,
}

var (
	pipelineLevel int
	pipelineJSON  bool
)

func init() {
	pipelineCmd.Flags().IntVar(&pipelineLevel, "level", 4, "Detail level (1-4)")
	pipelineCmd.Flags().BoolVar(&pipelineJSON, "json", false, "JSON output for AI analysis")
	rootCmd.AddCommand(pipelineCmd)
}

// Data structures for collecting all diagnostic data
type PipelineDiagnostic struct {
	Timestamp    string                    `json:"timestamp"`
	Level1System SystemOverview            `json:"systemOverview"`
	Level2Comps  map[string]ComponentStats `json:"components"`
	Level3Ops    map[string][]OperationStats `json:"operations"`
	Level4Spans  []SpanDetail              `json:"spans"`
}

type SystemOverview struct {
	TotalCPUPercent    float64 `json:"totalCpuPercent"`
	TotalMemoryPercent float64 `json:"totalMemoryPercent"`
	ContainerCount     int     `json:"containerCount"`
	HealthyCount       int     `json:"healthyCount"`
	Saturation         string  `json:"saturation"` // LOW, MEDIUM, HIGH, CRITICAL
	TopCPUComponent    string  `json:"topCpuComponent"`
	TopMemComponent    string  `json:"topMemComponent"`
}

type ComponentStats struct {
	Name          string  `json:"name"`
	CPUPercent    float64 `json:"cpuPercent"`
	MemoryPercent float64 `json:"memoryPercent"`
	MemoryUsage   string  `json:"memoryUsage"`
	SpanCount     int     `json:"spanCount"`
	AvgLatencyMs  float64 `json:"avgLatencyMs"`
	P99LatencyMs  float64 `json:"p99LatencyMs"`
	MaxLatencyMs  float64 `json:"maxLatencyMs"`
}

type OperationStats struct {
	Name         string  `json:"name"`
	Count        int     `json:"count"`
	AvgMs        float64 `json:"avgMs"`
	MinMs        float64 `json:"minMs"`
	MaxMs        float64 `json:"maxMs"`
	P50Ms        float64 `json:"p50Ms"`
	P99Ms        float64 `json:"p99Ms"`
	PercentOfTotal float64 `json:"percentOfTotal"`
}

type SpanDetail struct {
	TraceID       string  `json:"traceId"`
	Service       string  `json:"service"`
	Operation     string  `json:"operation"`
	DurationMs    float64 `json:"durationMs"`
	StartTime     int64   `json:"startTime"`
}

func runPipeline(cmd *cobra.Command, args []string) {
	diag := collectPipelineDiagnostic()

	if pipelineJSON {
		data, _ := json.MarshalIndent(diag, "", "  ")
		fmt.Println(string(data))
		return
	}

	// Level 1: System Overview
	printLevel1(diag)

	if pipelineLevel >= 2 {
		printLevel2(diag)
	}

	if pipelineLevel >= 3 {
		printLevel3(diag)
	}

	if pipelineLevel >= 4 {
		printLevel4(diag)
	}
}

func collectPipelineDiagnostic() PipelineDiagnostic {
	diag := PipelineDiagnostic{
		Timestamp:   time.Now().Format(time.RFC3339),
		Level2Comps: make(map[string]ComponentStats),
		Level3Ops:   make(map[string][]OperationStats),
	}

	// Collect container stats
	containerStats := getContainerStatsDetailed()

	// Calculate system overview
	diag.Level1System = calculateSystemOverview(containerStats)

	// Collect per-component stats
	for _, cs := range containerStats {
		component := normalizeComponentName(cs.Name)
		if component == "" {
			continue
		}
		diag.Level2Comps[component] = ComponentStats{
			Name:          component,
			CPUPercent:    cs.CPUPercent,
			MemoryPercent: float64(cs.MemPercent),
			MemoryUsage:   cs.MemUsage,
		}
	}

	// Collect trace data from Jaeger
	services := []string{"counter-application", "flink-taskmanager", "drools"}
	allSpans := []SpanDetail{}
	serviceSpans := make(map[string][]jaegerSpanFull)

	for _, svc := range services {
		traces := fetchJaegerTracesFull(svc, 50)
		for _, trace := range traces {
			for _, span := range trace.Spans {
				allSpans = append(allSpans, SpanDetail{
					TraceID:    trace.TraceID,
					Service:    svc,
					Operation:  span.OperationName,
					DurationMs: float64(span.Duration) / 1000,
					StartTime:  span.StartTime,
				})
				serviceSpans[svc] = append(serviceSpans[svc], span)
			}
		}
	}
	diag.Level4Spans = allSpans

	// Calculate operation stats per service
	for svc, spans := range serviceSpans {
		opMap := make(map[string][]int64)
		for _, span := range spans {
			opMap[span.OperationName] = append(opMap[span.OperationName], span.Duration)
		}

		var ops []OperationStats
		var totalDuration int64
		for _, durations := range opMap {
			for _, d := range durations {
				totalDuration += d
			}
		}

		for opName, durations := range opMap {
			if len(durations) == 0 {
				continue
			}
			sort.Slice(durations, func(i, j int) bool { return durations[i] < durations[j] })

			var sum int64
			for _, d := range durations {
				sum += d
			}
			avg := float64(sum) / float64(len(durations)) / 1000

			p50 := float64(durations[len(durations)/2]) / 1000
			p99Idx := int(float64(len(durations)) * 0.99)
			if p99Idx >= len(durations) {
				p99Idx = len(durations) - 1
			}
			p99 := float64(durations[p99Idx]) / 1000

			pct := 0.0
			if totalDuration > 0 {
				pct = float64(sum) / float64(totalDuration) * 100
			}

			ops = append(ops, OperationStats{
				Name:           opName,
				Count:          len(durations),
				AvgMs:          avg,
				MinMs:          float64(durations[0]) / 1000,
				MaxMs:          float64(durations[len(durations)-1]) / 1000,
				P50Ms:          p50,
				P99Ms:          p99,
				PercentOfTotal: pct,
			})
		}

		// Sort by avg latency descending
		sort.Slice(ops, func(i, j int) bool { return ops[i].AvgMs > ops[j].AvgMs })

		component := normalizeComponentName(svc)
		diag.Level3Ops[component] = ops

		// Update component stats with trace data
		if comp, ok := diag.Level2Comps[component]; ok {
			var totalAvg, maxLatency float64
			var spanCount int
			var p99Max float64
			for _, op := range ops {
				totalAvg += op.AvgMs
				spanCount += op.Count
				if op.MaxMs > maxLatency {
					maxLatency = op.MaxMs
				}
				if op.P99Ms > p99Max {
					p99Max = op.P99Ms
				}
			}
			comp.SpanCount = spanCount
			comp.AvgLatencyMs = totalAvg
			comp.MaxLatencyMs = maxLatency
			comp.P99LatencyMs = p99Max
			diag.Level2Comps[component] = comp
		}
	}

	return diag
}

type containerStatDetailed struct {
	Name       string
	CPUPercent float64
	MemUsage   string
	MemPercent int
}

func getContainerStatsDetailed() []containerStatDetailed {
	cmd := exec.Command("docker", "stats", "--no-stream", "--format", "{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}")
	output, err := cmd.Output()
	if err != nil {
		return nil
	}

	var stats []containerStatDetailed
	lines := strings.Split(strings.TrimSpace(string(output)), "\n")
	for _, line := range lines {
		if !strings.Contains(line, "reactive") && !strings.Contains(line, "flink") {
			continue
		}
		parts := strings.Split(line, "\t")
		if len(parts) < 4 {
			continue
		}

		cpuStr := strings.TrimSuffix(parts[1], "%")
		var cpu float64
		fmt.Sscanf(cpuStr, "%f", &cpu)

		memPctStr := strings.TrimSuffix(parts[3], "%")
		var memPct int
		fmt.Sscanf(memPctStr, "%d", &memPct)

		stats = append(stats, containerStatDetailed{
			Name:       parts[0],
			CPUPercent: cpu,
			MemUsage:   parts[2],
			MemPercent: memPct,
		})
	}
	return stats
}

func calculateSystemOverview(stats []containerStatDetailed) SystemOverview {
	overview := SystemOverview{
		ContainerCount: len(stats),
		HealthyCount:   len(stats), // Assume all running containers are healthy
	}

	var totalCPU, totalMem float64
	var topCPU, topMem float64
	var topCPUName, topMemName string

	for _, s := range stats {
		totalCPU += s.CPUPercent
		totalMem += float64(s.MemPercent)
		if s.CPUPercent > topCPU {
			topCPU = s.CPUPercent
			topCPUName = normalizeComponentName(s.Name)
		}
		if float64(s.MemPercent) > topMem {
			topMem = float64(s.MemPercent)
			topMemName = normalizeComponentName(s.Name)
		}
	}

	overview.TotalCPUPercent = totalCPU
	overview.TotalMemoryPercent = totalMem / float64(len(stats))
	overview.TopCPUComponent = topCPUName
	overview.TopMemComponent = topMemName

	// Determine saturation level
	if totalCPU > 400 || topMem > 90 {
		overview.Saturation = "CRITICAL"
	} else if totalCPU > 200 || topMem > 75 {
		overview.Saturation = "HIGH"
	} else if totalCPU > 100 || topMem > 50 {
		overview.Saturation = "MEDIUM"
	} else {
		overview.Saturation = "LOW"
	}

	return overview
}

func normalizeComponentName(name string) string {
	name = strings.TrimPrefix(name, "reactive-")
	name = strings.TrimSuffix(name, "-system-01")

	switch {
	case strings.Contains(name, "application") || strings.Contains(name, "gateway"):
		return "gateway"
	case strings.Contains(name, "flink-taskmanager") || strings.Contains(name, "taskmanager"):
		return "flink"
	case strings.Contains(name, "flink-jobmanager") || strings.Contains(name, "jobmanager"):
		return "flink-jm"
	case strings.Contains(name, "drools"):
		return "drools"
	case strings.Contains(name, "kafka"):
		return "kafka"
	case strings.Contains(name, "jaeger"):
		return "jaeger"
	case strings.Contains(name, "loki"):
		return "loki"
	case strings.Contains(name, "prometheus"):
		return "prometheus"
	case strings.Contains(name, "grafana"):
		return "grafana"
	case strings.Contains(name, "otel"):
		return "otel"
	case strings.Contains(name, "zookeeper"):
		return "zookeeper"
	case strings.Contains(name, "counter-application"):
		return "gateway"
	}
	return name
}

type jaegerTraceFull struct {
	TraceID string           `json:"traceID"`
	Spans   []jaegerSpanFull `json:"spans"`
}

type jaegerSpanFull struct {
	TraceID       string `json:"traceID"`
	SpanID        string `json:"spanID"`
	OperationName string `json:"operationName"`
	StartTime     int64  `json:"startTime"`
	Duration      int64  `json:"duration"` // microseconds
	ProcessID     string `json:"processID"`
}

func fetchJaegerTracesFull(service string, limit int) []jaegerTraceFull {
	url := fmt.Sprintf("http://localhost:16686/api/traces?service=%s&limit=%d&lookback=10m", service, limit)

	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Get(url)
	if err != nil {
		return nil
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	var response struct {
		Data []jaegerTraceFull `json:"data"`
	}
	if json.Unmarshal(body, &response) != nil {
		return nil
	}

	return response.Data
}

// Print functions for each level

func printLevel1(diag PipelineDiagnostic) {
	fmt.Println()
	fmt.Println("═══════════════════════════════════════════════════════════════════════════════")
	fmt.Println("  LEVEL 1: SYSTEM OVERVIEW")
	fmt.Println("═══════════════════════════════════════════════════════════════════════════════")
	fmt.Println()

	sys := diag.Level1System

	satColor := ""
	switch sys.Saturation {
	case "LOW":
		satColor = "LOW"
	case "MEDIUM":
		satColor = "MEDIUM"
	case "HIGH":
		satColor = "HIGH ⚠"
	case "CRITICAL":
		satColor = "CRITICAL ⚠⚠"
	}

	fmt.Printf("  System Saturation:     %s\n", satColor)
	fmt.Printf("  Total CPU Usage:       %.1f%% (across %d containers)\n", sys.TotalCPUPercent, sys.ContainerCount)
	fmt.Printf("  Avg Memory Pressure:   %.1f%%\n", sys.TotalMemoryPercent)
	fmt.Println()
	fmt.Printf("  Top CPU Consumer:      %s\n", sys.TopCPUComponent)
	fmt.Printf("  Top Memory Consumer:   %s\n", sys.TopMemComponent)
	fmt.Println()
}

func printLevel2(diag PipelineDiagnostic) {
	fmt.Println("═══════════════════════════════════════════════════════════════════════════════")
	fmt.Println("  LEVEL 2: COMPONENT BREAKDOWN")
	fmt.Println("═══════════════════════════════════════════════════════════════════════════════")
	fmt.Println()

	// Order: gateway -> kafka -> flink -> drools (pipeline order)
	order := []string{"gateway", "kafka", "flink", "flink-jm", "drools"}

	fmt.Printf("  %-15s %8s %8s %12s %10s %10s %10s\n",
		"Component", "CPU%", "Mem%", "Memory", "Spans", "Avg(ms)", "P99(ms)")
	fmt.Println("  " + strings.Repeat("-", 85))

	for _, name := range order {
		comp, ok := diag.Level2Comps[name]
		if !ok {
			continue
		}
		fmt.Printf("  %-15s %8.1f %8.1f %12s %10d %10.1f %10.1f\n",
			comp.Name, comp.CPUPercent, comp.MemoryPercent, comp.MemoryUsage,
			comp.SpanCount, comp.AvgLatencyMs, comp.P99LatencyMs)
	}

	// Print observability components separately
	fmt.Println()
	fmt.Println("  Observability:")
	fmt.Println("  " + strings.Repeat("-", 85))
	obsOrder := []string{"jaeger", "loki", "prometheus", "grafana", "otel"}
	for _, name := range obsOrder {
		comp, ok := diag.Level2Comps[name]
		if !ok {
			continue
		}
		fmt.Printf("  %-15s %8.1f %8.1f %12s\n",
			comp.Name, comp.CPUPercent, comp.MemoryPercent, comp.MemoryUsage)
	}
	fmt.Println()
}

func printLevel3(diag PipelineDiagnostic) {
	fmt.Println("═══════════════════════════════════════════════════════════════════════════════")
	fmt.Println("  LEVEL 3: OPERATION DETAILS (per component)")
	fmt.Println("═══════════════════════════════════════════════════════════════════════════════")
	fmt.Println()

	order := []string{"gateway", "flink", "drools"}

	for _, component := range order {
		ops, ok := diag.Level3Ops[component]
		if !ok || len(ops) == 0 {
			continue
		}

		fmt.Printf("  ┌─ %s\n", strings.ToUpper(component))
		fmt.Printf("  │  %-40s %8s %8s %8s %6s %8s\n",
			"Operation", "Avg(ms)", "P50(ms)", "P99(ms)", "Count", "% Time")
		fmt.Println("  │  " + strings.Repeat("-", 82))

		for _, op := range ops {
			fmt.Printf("  │  %-40s %8.1f %8.1f %8.1f %6d %7.1f%%\n",
				truncate(op.Name, 40), op.AvgMs, op.P50Ms, op.P99Ms, op.Count, op.PercentOfTotal)
		}
		fmt.Println("  │")
	}
	fmt.Println()
}

func printLevel4(diag PipelineDiagnostic) {
	fmt.Println("═══════════════════════════════════════════════════════════════════════════════")
	fmt.Println("  LEVEL 4: RAW SPAN DATA (recent samples)")
	fmt.Println("═══════════════════════════════════════════════════════════════════════════════")
	fmt.Println()

	// Group by service and show top 5 slowest per service
	serviceSpans := make(map[string][]SpanDetail)
	for _, span := range diag.Level4Spans {
		svc := normalizeComponentName(span.Service)
		serviceSpans[svc] = append(serviceSpans[svc], span)
	}

	for svc, spans := range serviceSpans {
		// Sort by duration descending
		sort.Slice(spans, func(i, j int) bool { return spans[i].DurationMs > spans[j].DurationMs })

		fmt.Printf("  %s (top 5 slowest):\n", strings.ToUpper(svc))
		limit := 5
		if len(spans) < limit {
			limit = len(spans)
		}
		for i := 0; i < limit; i++ {
			span := spans[i]
			fmt.Printf("    %.2fms  %s  [%s]\n", span.DurationMs, span.Operation, span.TraceID[:16])
		}
		fmt.Println()
	}

	fmt.Println("  For full span data, use: reactive pipeline --json")
	fmt.Println()
}
