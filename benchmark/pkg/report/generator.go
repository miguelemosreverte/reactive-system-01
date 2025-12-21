package report

import (
	"encoding/json"
	"fmt"
	"html/template"
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
	// Wait for traces to propagate through the full pipeline (Gateway -> Kafka -> Flink -> Drools)
	// and for OpenTelemetry batch exporter to flush (default 5s interval)
	if len(result.SampleEvents) > 0 {
		log.Printf("Waiting 30s for traces to propagate through full pipeline...")
		time.Sleep(30 * time.Second)
		log.Printf("Fetching trace/log data for %d sample events...", len(result.SampleEvents))
		g.fetcher.EnrichSampleEvents(result.SampleEvents, result.StartTime, result.EndTime)
	}

	// Generate markdown
	if err := g.generateMarkdown(result, componentDir); err != nil {
		return err
	}

	// Generate HTML
	if err := g.generateHTML(result, componentDir); err != nil {
		return err
	}

	return nil
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

	// Prepare chart data
	throughputLabels := make([]string, len(result.ThroughputTimeline))
	for i := range throughputLabels {
		throughputLabels[i] = fmt.Sprintf("%ds", i+1)
	}

	data := map[string]interface{}{
		"Component":        result.Component,
		"Name":             result.Name,
		"Description":      result.Description,
		"PeakThroughput":   result.PeakThroughput,
		"AvgThroughput":    result.AvgThroughput,
		"TotalOps":         result.TotalOperations,
		"SuccessOps":       result.SuccessfulOperations,
		"FailedOps":        result.FailedOperations,
		"SuccessRate":      float64(result.SuccessfulOperations) / float64(result.TotalOperations) * 100,
		"Duration":         result.DurationMs / 1000,
		"LatencyMin":       result.Latency.Min,
		"LatencyP50":       result.Latency.P50,
		"LatencyP95":       result.Latency.P95,
		"LatencyP99":       result.Latency.P99,
		"LatencyMax":       result.Latency.Max,
		"PeakCPU":          result.PeakCPU,
		"PeakMemory":       result.PeakMemory,
		"GeneratedAt":      time.Now().Format("2006-01-02 15:04:05"),
		"Commit":           commit,
		"ThroughputData":   result.ThroughputTimeline,
		"ThroughputLabels": throughputLabels,
		"CPUData":          result.CPUTimeline,
		"MemoryData":       result.MemoryTimeline,
		"SampleEvents":     result.SampleEvents,
	}

	// Convert data to JSON for JavaScript
	throughputJSON, _ := json.Marshal(result.ThroughputTimeline)
	cpuJSON, _ := json.Marshal(result.CPUTimeline)
	memoryJSON, _ := json.Marshal(result.MemoryTimeline)
	labelsJSON, _ := json.Marshal(throughputLabels)
	sampleEventsJSON, _ := json.Marshal(result.SampleEvents)

	data["ThroughputJSON"] = template.JS(throughputJSON)
	data["CPUJSON"] = template.JS(cpuJSON)
	data["MemoryJSON"] = template.JS(memoryJSON)
	data["LabelsJSON"] = template.JS(labelsJSON)
	data["SampleEventsJSON"] = template.JS(sampleEventsJSON)

	tmpl := template.Must(template.New("report").Parse(htmlTemplate))

	f, err := os.Create(filepath.Join(dir, "index.html"))
	if err != nil {
		return err
	}
	defer f.Close()

	return tmpl.Execute(f, data)
}

// RankingItem represents a component in the throughput ranking
type RankingItem struct {
	ID             types.ComponentID
	Name           string
	PeakThroughput int64
	BarWidth       int
	IsBottleneck   bool
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

	// Generate navigation JS
	if err := os.WriteFile(filepath.Join(assetsDir, "navigation.js"), []byte(navigationJS), 0644); err != nil {
		return err
	}

	// Generate styles
	if err := os.WriteFile(filepath.Join(assetsDir, "styles.css"), []byte(stylesCSS), 0644); err != nil {
		return err
	}

	// Build components list and find max throughput for ranking
	components := []map[string]interface{}{}
	var maxThroughput int64 = 0
	var minThroughput int64 = 0

	for id, result := range results {
		if result != nil {
			components = append(components, map[string]interface{}{
				"ID":             id,
				"Name":           result.Name,
				"PeakThroughput": result.PeakThroughput,
				"LatencyP99":     result.Latency.P99,
				"Status":         result.Status,
			})
			if result.PeakThroughput > maxThroughput {
				maxThroughput = result.PeakThroughput
			}
			if minThroughput == 0 || result.PeakThroughput < minThroughput {
				minThroughput = result.PeakThroughput
			}
		}
	}

	// Build rankings - sorted by throughput ascending (bottleneck first)
	rankings := []RankingItem{}
	// Priority order for display: HTTP, Kafka, Flink, Drools, Gateway, Full
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
			// A component is a bottleneck if its throughput is less than 50% of max
			isBottleneck := result.PeakThroughput < maxThroughput/2
			rankings = append(rankings, RankingItem{
				ID:             id,
				Name:           result.Name,
				PeakThroughput: result.PeakThroughput,
				BarWidth:       barWidth,
				IsBottleneck:   isBottleneck,
			})
		}
	}

	// Sort rankings by throughput (ascending - so bottleneck appears first)
	for i := 0; i < len(rankings)-1; i++ {
		for j := i + 1; j < len(rankings); j++ {
			if rankings[j].PeakThroughput < rankings[i].PeakThroughput {
				rankings[i], rankings[j] = rankings[j], rankings[i]
			}
		}
	}

	data := map[string]interface{}{
		"Components":  components,
		"Rankings":    rankings,
		"GeneratedAt": time.Now().Format("2006-01-02 15:04:05"),
	}

	tmpl := template.Must(template.New("index").Parse(indexTemplate))

	f, err := os.Create(filepath.Join(g.outputDir, "index.html"))
	if err != nil {
		return err
	}
	defer f.Close()

	return tmpl.Execute(f, data)
}

const htmlTemplate = `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>{{.Name}} Report</title>
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #1a1a2e; color: #eee; padding: 20px; }
        .container { max-width: 1400px; margin: 0 auto; }
        h1 { color: #00d4ff; margin-bottom: 10px; }
        .description { color: #888; margin-bottom: 30px; font-size: 14px; }
        .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 20px; margin-bottom: 30px; }
        .card { background: #16213e; border-radius: 12px; padding: 20px; }
        .card h3 { color: #00d4ff; margin-bottom: 15px; font-size: 14px; text-transform: uppercase; }
        .metric { display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid #2a2a4a; }
        .metric:last-child { border-bottom: none; }
        .metric-label { color: #888; }
        .metric-value { font-weight: bold; color: #00d4ff; }
        .metric-value.success { color: #4ade80; }
        .metric-value.error { color: #f87171; }
        .chart-container { height: 250px; }
        table { width: 100%; border-collapse: collapse; margin-top: 15px; }
        th, td { padding: 10px; text-align: left; border-bottom: 1px solid #2a2a4a; }
        th { color: #00d4ff; font-size: 12px; text-transform: uppercase; }
        .status-success { color: #4ade80; }
        .status-error { color: #f87171; }
        .footer { margin-top: 30px; text-align: center; color: #666; font-size: 12px; }
        code { background: #2a2a4a; padding: 2px 6px; border-radius: 4px; font-size: 12px; }

        /* Buttons */
        .btn { padding: 4px 10px; border-radius: 4px; border: none; cursor: pointer; font-size: 11px; margin-right: 4px; }
        .btn-trace { background: #8b5cf6; color: white; }
        .btn-logs { background: #f59e0b; color: #1a1a2e; }
        .btn:hover { opacity: 0.8; }
        .btn:disabled { opacity: 0.4; cursor: not-allowed; }

        /* Modal */
        .modal { display: none; position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.8); z-index: 1000; overflow-y: auto; }
        .modal.active { display: flex; justify-content: center; align-items: flex-start; padding: 40px 20px; }
        .modal-content { background: #16213e; border-radius: 12px; max-width: 1000px; width: 100%; max-height: 90vh; overflow-y: auto; }
        .modal-header { display: flex; justify-content: space-between; align-items: center; padding: 15px 20px; border-bottom: 1px solid #2a2a4a; position: sticky; top: 0; background: #16213e; z-index: 10; }
        .modal-header h3 { color: #00d4ff; font-size: 14px; }
        .modal-close { background: none; border: none; color: #888; font-size: 24px; cursor: pointer; }
        .modal-close:hover { color: #fff; }
        .modal-body { padding: 20px; }

        /* Trace Viewer */
        .trace-viewer { font-family: monospace; font-size: 12px; }
        .span-row { display: flex; align-items: center; padding: 8px 0; border-bottom: 1px solid #2a2a4a; }
        .span-service { width: 120px; padding: 4px 8px; border-radius: 4px; text-align: center; font-size: 11px; font-weight: 500; flex-shrink: 0; }
        .span-service.gateway { background: #4ade80; color: #1a1a2e; }
        .span-service.kafka { background: #f59e0b; color: #1a1a2e; }
        .span-service.flink { background: #8b5cf6; color: white; }
        .span-service.drools { background: #f87171; color: #1a1a2e; }
        .span-service.unknown { background: #666; color: white; }
        .span-info { flex: 1; margin-left: 15px; }
        .span-operation { color: #eee; }
        .span-duration { color: #00d4ff; margin-left: 10px; }
        .span-bar-container { flex: 1; margin-left: 20px; height: 20px; background: #2a2a4a; border-radius: 4px; position: relative; min-width: 200px; }
        .span-bar { position: absolute; height: 100%; border-radius: 4px; min-width: 2px; }
        .span-bar.gateway { background: #4ade80; }
        .span-bar.kafka { background: #f59e0b; }
        .span-bar.flink { background: #8b5cf6; }
        .span-bar.drools { background: #f87171; }
        .span-bar.unknown { background: #666; }

        /* Log Viewer */
        .log-viewer { font-family: monospace; font-size: 11px; }
        .log-entry { padding: 8px 12px; border-bottom: 1px solid #2a2a4a; display: flex; gap: 10px; align-items: flex-start; }
        .log-entry:hover { background: #1a1a2e; }
        .log-time { color: #666; white-space: nowrap; flex-shrink: 0; font-size: 10px; }
        .log-level { padding: 2px 6px; border-radius: 3px; font-size: 9px; font-weight: bold; flex-shrink: 0; min-width: 45px; text-align: center; }
        .log-level.error { background: #f87171; color: #1a1a2e; }
        .log-level.warn { background: #f59e0b; color: #1a1a2e; }
        .log-level.info { background: #3b82f6; color: white; }
        .log-level.debug { background: #6b7280; color: white; }
        .log-service { padding: 2px 8px; border-radius: 4px; font-size: 10px; flex-shrink: 0; }
        .log-service.gateway { background: #4ade80; color: #1a1a2e; }
        .log-service.kafka { background: #f59e0b; color: #1a1a2e; }
        .log-service.flink { background: #8b5cf6; color: white; }
        .log-service.drools { background: #f87171; color: #1a1a2e; }
        .log-logger { color: #666; font-size: 10px; flex-shrink: 0; }
        .log-message { color: #eee; flex: 1; word-break: break-word; }

        .no-data { color: #666; text-align: center; padding: 40px; }
    </style>
</head>
<body>
    <div class="container">
        <h1>{{.Name}}</h1>
        <p class="description">{{.Description}}</p>

        <div class="grid">
            <div class="card">
                <h3>Throughput</h3>
                <div class="metric">
                    <span class="metric-label">Peak</span>
                    <span class="metric-value">{{.PeakThroughput}} ops/sec</span>
                </div>
                <div class="metric">
                    <span class="metric-label">Average</span>
                    <span class="metric-value">{{.AvgThroughput}} ops/sec</span>
                </div>
            </div>

            <div class="card">
                <h3>Operations</h3>
                <div class="metric">
                    <span class="metric-label">Total</span>
                    <span class="metric-value">{{.TotalOps}}</span>
                </div>
                <div class="metric">
                    <span class="metric-label">Successful</span>
                    <span class="metric-value success">{{.SuccessOps}}</span>
                </div>
                <div class="metric">
                    <span class="metric-label">Failed</span>
                    <span class="metric-value error">{{.FailedOps}}</span>
                </div>
                <div class="metric">
                    <span class="metric-label">Success Rate</span>
                    <span class="metric-value">{{printf "%.1f" .SuccessRate}}%</span>
                </div>
            </div>

            <div class="card">
                <h3>Latency (ms)</h3>
                <div class="metric">
                    <span class="metric-label">P50</span>
                    <span class="metric-value">{{.LatencyP50}}</span>
                </div>
                <div class="metric">
                    <span class="metric-label">P95</span>
                    <span class="metric-value">{{.LatencyP95}}</span>
                </div>
                <div class="metric">
                    <span class="metric-label">P99</span>
                    <span class="metric-value">{{.LatencyP99}}</span>
                </div>
                <div class="metric">
                    <span class="metric-label">Max</span>
                    <span class="metric-value">{{.LatencyMax}}</span>
                </div>
            </div>

            <div class="card">
                <h3>Resources</h3>
                <div class="metric">
                    <span class="metric-label">Peak CPU</span>
                    <span class="metric-value">{{printf "%.1f" .PeakCPU}}%</span>
                </div>
                <div class="metric">
                    <span class="metric-label">Peak Memory</span>
                    <span class="metric-value">{{printf "%.1f" .PeakMemory}}%</span>
                </div>
                <div class="metric">
                    <span class="metric-label">Duration</span>
                    <span class="metric-value">{{.Duration}}s</span>
                </div>
            </div>
        </div>

        <div class="grid">
            <div class="card">
                <h3>Throughput Over Time</h3>
                <div class="chart-container">
                    <canvas id="throughputChart"></canvas>
                </div>
            </div>
            <div class="card">
                <h3>Resource Usage</h3>
                <div class="chart-container">
                    <canvas id="resourceChart"></canvas>
                </div>
            </div>
        </div>

        {{if .SampleEvents}}
        <div class="card">
            <h3>Sample Events</h3>
            <table>
                <thead>
                    <tr>
                        <th>ID</th>
                        <th>Trace ID</th>
                        <th>Latency</th>
                        <th>Status</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody id="sample-events-table">
                </tbody>
            </table>
        </div>
        {{end}}

        <div class="footer">
            Generated: {{.GeneratedAt}} | Commit: {{.Commit}} | Go Benchmark Tool
        </div>
    </div>

    <!-- Trace Modal -->
    <div id="trace-modal" class="modal">
        <div class="modal-content">
            <div class="modal-header">
                <h3>Trace Viewer - <span id="trace-modal-id"></span></h3>
                <button class="modal-close" onclick="closeModal('trace-modal')">&times;</button>
            </div>
            <div class="modal-body">
                <div id="trace-viewer" class="trace-viewer"></div>
            </div>
        </div>
    </div>

    <!-- Logs Modal -->
    <div id="logs-modal" class="modal">
        <div class="modal-content">
            <div class="modal-header">
                <h3>Logs - <span id="logs-modal-id"></span></h3>
                <button class="modal-close" onclick="closeModal('logs-modal')">&times;</button>
            </div>
            <div class="modal-body">
                <div id="logs-viewer" class="log-viewer"></div>
            </div>
        </div>
    </div>

    <script>
        const labels = {{.LabelsJSON}};
        const throughputData = {{.ThroughputJSON}};
        const cpuData = {{.CPUJSON}};
        const memoryData = {{.MemoryJSON}};
        const sampleEvents = {{.SampleEventsJSON}};

        // Charts
        new Chart(document.getElementById('throughputChart'), {
            type: 'line',
            data: {
                labels: labels,
                datasets: [{
                    label: 'Throughput (ops/sec)',
                    data: throughputData,
                    borderColor: '#00d4ff',
                    backgroundColor: 'rgba(0, 212, 255, 0.1)',
                    fill: true,
                    tension: 0.3
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: { legend: { display: false } },
                scales: {
                    y: { beginAtZero: true, grid: { color: '#2a2a4a' }, ticks: { color: '#888' } },
                    x: { grid: { color: '#2a2a4a' }, ticks: { color: '#888' } }
                }
            }
        });

        new Chart(document.getElementById('resourceChart'), {
            type: 'line',
            data: {
                labels: labels,
                datasets: [
                    {
                        label: 'CPU %',
                        data: cpuData,
                        borderColor: '#f59e0b',
                        tension: 0.3
                    },
                    {
                        label: 'Memory %',
                        data: memoryData,
                        borderColor: '#8b5cf6',
                        tension: 0.3
                    }
                ]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: { legend: { labels: { color: '#888' } } },
                scales: {
                    y: { beginAtZero: true, max: 100, grid: { color: '#2a2a4a' }, ticks: { color: '#888' } },
                    x: { grid: { color: '#2a2a4a' }, ticks: { color: '#888' } }
                }
            }
        });

        // Render sample events table
        function renderSampleEventsTable() {
            const tbody = document.getElementById('sample-events-table');
            if (!tbody || !sampleEvents) return;

            tbody.innerHTML = sampleEvents.map((event, idx) => {
                const hasTrace = event.traceData && event.traceData.trace;
                const hasLogs = event.traceData && event.traceData.logs && event.traceData.logs.length > 0;
                return ` + "`" + `
                    <tr>
                        <td><code>${event.id || '-'}</code></td>
                        <td><code>${event.traceId || '-'}</code></td>
                        <td>${event.latencyMs}ms</td>
                        <td class="status-${event.status}">${event.status}</td>
                        <td>
                            <button class="btn btn-trace" onclick="showTrace(${idx})" ${hasTrace ? '' : 'disabled'}>
                                Trace ${hasTrace ? '(' + event.traceData.trace.spans.length + ')' : ''}
                            </button>
                            <button class="btn btn-logs" onclick="showLogs(${idx})" ${hasLogs ? '' : 'disabled'}>
                                Logs ${hasLogs ? '(' + event.traceData.logs.length + ')' : ''}
                            </button>
                        </td>
                    </tr>
                ` + "`" + `;
            }).join('');
        }

        // Get service class from service name
        function getServiceClass(serviceName) {
            const name = (serviceName || '').toLowerCase();
            if (name.includes('gateway')) return 'gateway';
            if (name.includes('kafka')) return 'kafka';
            if (name.includes('flink')) return 'flink';
            if (name.includes('drools')) return 'drools';
            return 'unknown';
        }

        // Show trace modal
        function showTrace(idx) {
            const event = sampleEvents[idx];
            if (!event || !event.traceData || !event.traceData.trace) return;

            const trace = event.traceData.trace;
            const viewer = document.getElementById('trace-viewer');
            document.getElementById('trace-modal-id').textContent = event.traceId;

            // Sort spans by start time
            const spans = [...trace.spans].sort((a, b) => a.startTime - b.startTime);
            if (spans.length === 0) {
                viewer.innerHTML = '<div class="no-data">No spans found</div>';
                openModal('trace-modal');
                return;
            }

            // Calculate timeline bounds
            const minTime = spans[0].startTime;
            const maxTime = Math.max(...spans.map(s => s.startTime + s.duration));
            const totalDuration = maxTime - minTime;

            viewer.innerHTML = spans.map(span => {
                const serviceName = trace.processes[span.processID]?.serviceName || 'unknown';
                const serviceClass = getServiceClass(serviceName);
                const durationMs = (span.duration / 1000).toFixed(2);
                const offsetPct = ((span.startTime - minTime) / totalDuration * 100).toFixed(2);
                const widthPct = Math.max((span.duration / totalDuration * 100), 0.5).toFixed(2);

                return ` + "`" + `
                    <div class="span-row">
                        <div class="span-service ${serviceClass}">${serviceName}</div>
                        <div class="span-info">
                            <span class="span-operation">${span.operationName}</span>
                            <span class="span-duration">${durationMs}ms</span>
                        </div>
                        <div class="span-bar-container">
                            <div class="span-bar ${serviceClass}" style="left: ${offsetPct}%; width: ${widthPct}%"></div>
                        </div>
                    </div>
                ` + "`" + `;
            }).join('');

            openModal('trace-modal');
        }

        // Show logs modal
        function showLogs(idx) {
            const event = sampleEvents[idx];
            if (!event || !event.traceData || !event.traceData.logs) return;

            const logs = event.traceData.logs;
            const viewer = document.getElementById('logs-viewer');
            document.getElementById('logs-modal-id').textContent = event.traceId;

            if (logs.length === 0) {
                viewer.innerHTML = '<div class="no-data">No logs found</div>';
                openModal('logs-modal');
                return;
            }

            viewer.innerHTML = logs.map(log => {
                const service = log.labels?.service || log.labels?.container || 'unknown';
                const serviceClass = getServiceClass(service);

                // Try to parse JSON log line
                let parsed = null;
                try {
                    parsed = JSON.parse(log.line);
                } catch (e) {}

                // Extract fields from parsed JSON or use raw line
                let time = '-';
                let level = 'INFO';
                let message = log.line;
                let logger = '';

                if (parsed) {
                    // Get timestamp from JSON
                    if (parsed.timestamp) {
                        time = parsed.timestamp.split('T')[1] || parsed.timestamp;
                    }
                    // Get level
                    level = (parsed.level || 'INFO').trim().toUpperCase();
                    // Get message
                    message = parsed.message || parsed.msg || log.line;
                    // Get logger (short form)
                    if (parsed.logger) {
                        const parts = parsed.logger.split('.');
                        logger = parts[parts.length - 1];
                    }
                } else if (log.timestamp) {
                    try {
                        const ts = parseInt(log.timestamp) / 1000000;
                        time = new Date(ts).toISOString().split('T')[1].replace('Z', '');
                    } catch (e) {}
                }

                // Level color class
                const levelClass = level === 'ERROR' ? 'error' : level === 'WARN' ? 'warn' : level === 'DEBUG' ? 'debug' : 'info';

                return ` + "`" + `
                    <div class="log-entry">
                        <span class="log-time">${time}</span>
                        <span class="log-level ${levelClass}">${level}</span>
                        <span class="log-service ${serviceClass}">${service}</span>
                        ${logger ? ` + "`" + `<span class="log-logger">${logger}</span>` + "`" + ` : ''}
                        <span class="log-message">${escapeHtml(message)}</span>
                    </div>
                ` + "`" + `;
            }).join('');

            openModal('logs-modal');
        }

        // Escape HTML to prevent XSS
        function escapeHtml(text) {
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
        }

        // Modal functions
        function openModal(id) {
            document.getElementById(id).classList.add('active');
            document.body.style.overflow = 'hidden';
        }

        function closeModal(id) {
            document.getElementById(id).classList.remove('active');
            document.body.style.overflow = '';
        }

        // Close modal on escape or background click
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                document.querySelectorAll('.modal.active').forEach(m => m.classList.remove('active'));
                document.body.style.overflow = '';
            }
        });

        document.querySelectorAll('.modal').forEach(modal => {
            modal.addEventListener('click', (e) => {
                if (e.target === modal) {
                    modal.classList.remove('active');
                    document.body.style.overflow = '';
                }
            });
        });

        // Initialize
        renderSampleEventsTable();
    </script>
</body>
</html>`

const indexTemplate = `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Benchmark Dashboard</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #1a1a2e; color: #eee; display: flex; height: 100vh; }
        .sidebar { width: 380px; background: #16213e; padding: 20px; overflow-y: auto; border-right: 1px solid #2a2a4a; }
        .sidebar h1 { color: #00d4ff; font-size: 18px; margin-bottom: 10px; }
        .sidebar .subtitle { color: #666; font-size: 12px; margin-bottom: 20px; }

        /* Architecture Diagram */
        .architecture { background: #1a1a2e; border-radius: 8px; padding: 15px; margin-bottom: 20px; }
        .architecture h2 { color: #00d4ff; font-size: 12px; margin-bottom: 15px; text-transform: uppercase; }
        .arch-flow { display: flex; align-items: center; justify-content: center; gap: 8px; flex-wrap: wrap; padding: 10px 0; }
        .arch-node { background: #16213e; border: 2px solid #2a2a4a; border-radius: 8px; padding: 10px 14px; text-align: center; min-width: 70px; }
        .arch-node.client { border-color: #00d4ff; }
        .arch-node.gateway { border-color: #4ade80; }
        .arch-node.kafka { border-color: #f59e0b; }
        .arch-node.flink { border-color: #8b5cf6; }
        .arch-node.drools { border-color: #f87171; }
        .arch-node .icon { font-size: 20px; margin-bottom: 4px; }
        .arch-node .label { font-size: 10px; color: #888; text-transform: uppercase; }
        .arch-arrow { color: #4ade80; font-size: 18px; }
        .arch-description { font-size: 11px; color: #666; margin-top: 12px; line-height: 1.5; }
        .arch-description strong { color: #00d4ff; }

        .bottleneck-section { background: #1a1a2e; border-radius: 8px; padding: 15px; margin-bottom: 20px; }
        .bottleneck-section h2 { color: #f59e0b; font-size: 12px; margin-bottom: 10px; text-transform: uppercase; }
        .bottleneck-item { display: flex; justify-content: space-between; align-items: center; padding: 8px 0; border-bottom: 1px solid #2a2a4a; }
        .bottleneck-item:last-child { border-bottom: none; }
        .bottleneck-item .name { font-size: 13px; }
        .bottleneck-item .throughput { font-weight: bold; font-size: 14px; }
        .bottleneck-item .bar { height: 4px; background: #2a2a4a; border-radius: 2px; margin-top: 4px; }
        .bottleneck-item .bar-fill { height: 100%; border-radius: 2px; }
        .bottleneck-item.bottleneck .throughput { color: #f87171; }
        .bottleneck-item.bottleneck .bar-fill { background: #f87171; }
        .bottleneck-item.ok .throughput { color: #4ade80; }
        .bottleneck-item.ok .bar-fill { background: #4ade80; }
        .attention-badge { background: #f87171; color: #1a1a2e; font-size: 10px; padding: 2px 6px; border-radius: 4px; margin-left: 8px; }

        .component-list { list-style: none; }
        .component-list li { padding: 12px 15px; margin-bottom: 8px; border-radius: 8px; cursor: pointer; transition: background 0.2s; }
        .component-list li:hover { background: #2a2a4a; }
        .component-list li.active { background: #00d4ff; color: #1a1a2e; }
        .component-list li .name { display: block; font-weight: 500; }
        .component-list li .stats { font-size: 12px; color: #888; display: flex; gap: 15px; margin-top: 4px; }
        .component-list li.active .stats { color: #1a1a2e; }

        main { flex: 1; display: flex; flex-direction: column; }
        iframe { flex: 1; border: none; }
        .footer { padding: 10px 20px; background: #16213e; font-size: 11px; color: #666; text-align: center; }
    </style>
</head>
<body>
    <nav class="sidebar">
        <h1>Benchmark Dashboard</h1>
        <p class="subtitle">Generated: {{.GeneratedAt}}</p>

        <div class="architecture">
            <h2>System Architecture</h2>
            <div class="arch-flow">
                <div class="arch-node client">
                    <div class="icon">🌐</div>
                    <div class="label">Client</div>
                </div>
                <span class="arch-arrow">→</span>
                <div class="arch-node gateway">
                    <div class="icon">⚡</div>
                    <div class="label">Gateway</div>
                </div>
                <span class="arch-arrow">→</span>
                <div class="arch-node kafka">
                    <div class="icon">📨</div>
                    <div class="label">Kafka</div>
                </div>
                <span class="arch-arrow">→</span>
                <div class="arch-node flink">
                    <div class="icon">🔄</div>
                    <div class="label">Flink</div>
                </div>
                <span class="arch-arrow">→</span>
                <div class="arch-node drools">
                    <div class="icon">📋</div>
                    <div class="label">Drools</div>
                </div>
            </div>
            <div class="arch-description">
                <strong>Full Pipeline:</strong> HTTP request → Gateway (Spring WebFlux) → Kafka (message queue with delivery guarantees) → Flink (stream processing) → Drools (rule evaluation) → Response with backpressure throughout.
            </div>
        </div>

        <div class="bottleneck-section">
            <h2>Throughput Ranking</h2>
            {{range .Rankings}}
            <div class="bottleneck-item {{if .IsBottleneck}}bottleneck{{else}}ok{{end}}">
                <div>
                    <span class="name">{{.Name}}{{if .IsBottleneck}}<span class="attention-badge">Bottleneck</span>{{end}}</span>
                    <div class="bar"><div class="bar-fill" style="width: {{.BarWidth}}%"></div></div>
                </div>
                <span class="throughput">{{.PeakThroughput}} ops/s</span>
            </div>
            {{end}}
        </div>

        <h2 style="color: #00d4ff; font-size: 12px; text-transform: uppercase; margin-bottom: 10px;">Component Reports</h2>
        <ul class="component-list">
            {{range .Components}}
            <li data-component="{{.ID}}" {{if eq .ID "full"}}class="active"{{end}}>
                <span class="name">{{.Name}}</span>
                <div class="stats">
                    <span>{{.PeakThroughput}} ops/s</span>
                    <span>P99: {{.LatencyP99}}ms</span>
                </div>
            </li>
            {{end}}
        </ul>
    </nav>
    <main>
        <iframe id="report-frame" src="full/index.html"></iframe>
        <div class="footer">Go Benchmark Tool | Reactive System with Delivery Guarantees</div>
    </main>
    <script>
    document.addEventListener('DOMContentLoaded', function() {
        const items = document.querySelectorAll('.component-list li');
        const frame = document.getElementById('report-frame');

        items.forEach(item => {
            item.addEventListener('click', function() {
                items.forEach(i => i.classList.remove('active'));
                this.classList.add('active');
                const component = this.dataset.component;
                frame.src = component + '/index.html';
            });
        });
    });
    </script>
</body>
</html>`

const navigationJS = `document.addEventListener('DOMContentLoaded', function() {
    const items = document.querySelectorAll('.sidebar li');
    const frame = document.getElementById('report-frame');

    items.forEach(item => {
        item.addEventListener('click', function() {
            items.forEach(i => i.classList.remove('active'));
            this.classList.add('active');
            const component = this.dataset.component;
            frame.src = component + '/index.html';
        });
    });

    // Set first item as active
    if (items.length > 0) {
        items[0].classList.add('active');
    }
});`

const stylesCSS = `* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #1a1a2e; color: #eee; display: flex; height: 100vh; }
.sidebar { width: 280px; background: #16213e; padding: 20px; overflow-y: auto; }
.sidebar h1 { color: #00d4ff; font-size: 18px; margin-bottom: 20px; }
.sidebar ul { list-style: none; }
.sidebar li { padding: 12px 15px; margin-bottom: 8px; border-radius: 8px; cursor: pointer; transition: background 0.2s; }
.sidebar li:hover { background: #2a2a4a; }
.sidebar li.active { background: #00d4ff; color: #1a1a2e; }
.sidebar li .name { display: block; font-weight: 500; }
.sidebar li .throughput { font-size: 12px; color: #888; }
.sidebar li.active .throughput { color: #1a1a2e; }
main { flex: 1; }
iframe { width: 100%; height: 100%; border: none; }`
