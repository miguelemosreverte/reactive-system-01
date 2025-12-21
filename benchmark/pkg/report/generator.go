package report

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"github.com/reactive/benchmark/pkg/observability"
	"github.com/reactive/benchmark/pkg/types"
)

// Generator generates benchmark reports
type Generator struct {
	outputDir string
	fetcher   *observability.Fetcher
}

// NewGenerator creates a new report generator
func NewGenerator(outputDir string) *Generator {
	return &Generator{
		outputDir: outputDir,
		fetcher:   observability.NewFetcher(),
	}
}

// Generate creates HTML and Markdown reports for a benchmark result
func (g *Generator) Generate(result *types.Result) error {
	componentDir := filepath.Join(g.outputDir, string(result.Component))
	if err := os.MkdirAll(componentDir, 0755); err != nil {
		return err
	}

	// Enrich sample events with trace and log data from Jaeger/Loki
	if len(result.SampleEvents) > 0 {
		log.Printf("Waiting 30s for traces to propagate through full pipeline...")
		time.Sleep(30 * time.Second)
		log.Printf("Fetching trace/log data for %d sample events...", len(result.SampleEvents))
		g.fetcher.EnrichSampleEvents(result.SampleEvents, result.StartTime, result.EndTime)
	}

	// Generate JSON data file
	if err := g.generateJSON(result, componentDir); err != nil {
		return err
	}

	// Generate markdown
	if err := g.generateMarkdown(result, componentDir); err != nil {
		return err
	}

	// Generate HTML that loads React bundle
	if err := g.generateHTML(result, componentDir); err != nil {
		return err
	}

	return nil
}

func (g *Generator) generateJSON(result *types.Result, dir string) error {
	data, err := json.MarshalIndent(result, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(dir, "results.json"), data, 0644)
}

func (g *Generator) generateMarkdown(result *types.Result, dir string) error {
	md := fmt.Sprintf(`# Benchmark Report: %s

**%s**

| Metric | Value |
|--------|-------|
| Peak Throughput | **%d events/sec** |
| Average Throughput | %d events/sec |
| Total Operations | %d |
| Successful | %d |
| Failed | %d |
| Success Rate | %.0f%% |
| Duration | %ds |

## Latency

| Percentile | Value |
|------------|-------|
| Min | %dms |
| P50 (Median) | %dms |
| P95 | %dms |
| P99 | %dms |
| Max | %dms |

## Resources

| Metric | Value |
|--------|-------|
| Peak CPU | %.0f%% |
| Peak Memory | %.0f%% |

---
*Generated: %s | Go Benchmark Tool*
`,
		result.Name,
		result.Description,
		result.PeakThroughput,
		result.AvgThroughput,
		result.TotalOperations,
		result.SuccessfulOperations,
		result.FailedOperations,
		float64(result.SuccessfulOperations)/float64(result.TotalOperations)*100,
		result.DurationMs/1000,
		result.Latency.Min,
		result.Latency.P50,
		result.Latency.P95,
		result.Latency.P99,
		result.Latency.Max,
		result.PeakCPU,
		result.PeakMemory,
		time.Now().Format("2006-01-02 15:04:05"),
	)

	return os.WriteFile(filepath.Join(dir, "README.md"), []byte(md), 0644)
}

func (g *Generator) generateHTML(result *types.Result, dir string) error {
	// Get git commit if available
	commit := "unknown"
	if out, err := exec.Command("git", "rev-parse", "--short", "HEAD").Output(); err == nil {
		commit = strings.TrimSpace(string(out))
	}

	// Convert result to JSON for embedding
	resultJSON, err := json.Marshal(result)
	if err != nil {
		return err
	}

	html := fmt.Sprintf(`<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>%s Report</title>
    <link rel="icon" type="image/svg+xml" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><text y='.9em' font-size='90'>📊</text></svg>" />
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
        #root { min-height: 100vh; }
        .loading { display: flex; justify-content: center; align-items: center; height: 100vh; font-size: 18px; color: #666; }
    </style>
</head>
<body>
    <div id="root"><div class="loading">Loading benchmark report...</div></div>
    <script>
        window.__BENCHMARK_DATA__ = %s;
        window.__BENCHMARK_COMMIT__ = "%s";
    </script>
    <script src="../assets/benchmark-report.js"></script>
</body>
</html>`, result.Name, string(resultJSON), commit)

	return os.WriteFile(filepath.Join(dir, "index.html"), []byte(html), 0644)
}

// RankingItem represents a component in the throughput ranking
type RankingItem struct {
	ID             string `json:"id"`
	Name           string `json:"name"`
	PeakThroughput int64  `json:"peakThroughput"`
	BarWidth       int    `json:"barWidth"`
	IsBottleneck   bool   `json:"isBottleneck"`
}

// ComponentSummary represents a component in the index
type ComponentSummary struct {
	ID             string `json:"id"`
	Name           string `json:"name"`
	PeakThroughput int64  `json:"peakThroughput"`
	LatencyP99     int64  `json:"latencyP99"`
	Status         string `json:"status"`
}

// IndexData represents the data for the benchmark index
type IndexData struct {
	Components  []ComponentSummary `json:"components"`
	Rankings    []RankingItem      `json:"rankings"`
	GeneratedAt string             `json:"generatedAt"`
}

// GenerateIndex creates the main index.html with navigation
func (g *Generator) GenerateIndex(results map[types.ComponentID]*types.Result) error {
	if err := os.MkdirAll(g.outputDir, 0755); err != nil {
		return err
	}

	// Create assets directory
	assetsDir := filepath.Join(g.outputDir, "assets")
	if err := os.MkdirAll(assetsDir, 0755); err != nil {
		return err
	}

	// Build components list and find max throughput for ranking
	components := []ComponentSummary{}
	var maxThroughput int64 = 0

	for id, result := range results {
		if result != nil {
			components = append(components, ComponentSummary{
				ID:             string(id),
				Name:           result.Name,
				PeakThroughput: result.PeakThroughput,
				LatencyP99:     result.Latency.P99,
				Status:         result.Status,
			})
			if result.PeakThroughput > maxThroughput {
				maxThroughput = result.PeakThroughput
			}
		}
	}

	// Build rankings - sorted by throughput ascending (bottleneck first)
	rankings := []RankingItem{}
	// Priority order for display
	componentOrder := []types.ComponentID{
		types.ComponentHTTP,
		types.ComponentDrools,
		types.ComponentGateway,
		types.ComponentFull,
	}

	for _, id := range componentOrder {
		result := results[id]
		if result != nil {
			barWidth := 100
			if maxThroughput > 0 {
				barWidth = int(float64(result.PeakThroughput) / float64(maxThroughput) * 100)
			}
			isBottleneck := result.PeakThroughput < maxThroughput/2
			rankings = append(rankings, RankingItem{
				ID:             string(id),
				Name:           result.Name,
				PeakThroughput: result.PeakThroughput,
				BarWidth:       barWidth,
				IsBottleneck:   isBottleneck,
			})
		}
	}

	// Sort rankings by throughput ascending
	for i := 0; i < len(rankings)-1; i++ {
		for j := i + 1; j < len(rankings); j++ {
			if rankings[j].PeakThroughput < rankings[i].PeakThroughput {
				rankings[i], rankings[j] = rankings[j], rankings[i]
			}
		}
	}

	indexData := IndexData{
		Components:  components,
		Rankings:    rankings,
		GeneratedAt: time.Now().Format("2006-01-02 15:04:05"),
	}

	// Write index data as JSON
	indexJSON, err := json.MarshalIndent(indexData, "", "  ")
	if err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(g.outputDir, "index.json"), indexJSON, 0644); err != nil {
		return err
	}

	// Generate HTML that loads React bundle
	html := fmt.Sprintf(`<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Benchmark Dashboard</title>
    <link rel="icon" type="image/svg+xml" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><text y='.9em' font-size='90'>📊</text></svg>" />
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
        #root { min-height: 100vh; }
        .loading { display: flex; justify-content: center; align-items: center; height: 100vh; font-size: 18px; color: #666; }
    </style>
</head>
<body>
    <div id="root"><div class="loading">Loading benchmark dashboard...</div></div>
    <script>
        window.__BENCHMARK_INDEX__ = %s;
    </script>
    <script src="assets/benchmark-index.js"></script>
</body>
</html>`, string(indexJSON))

	return os.WriteFile(filepath.Join(g.outputDir, "index.html"), []byte(html), 0644)
}
