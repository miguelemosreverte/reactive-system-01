package diagnostic

import (
	"encoding/json"
	"fmt"
	"html/template"
	"os"
	"path/filepath"
	"time"
)

// DashboardData contains all data for the unified dashboard
type DashboardData struct {
	GeneratedAt      time.Time
	GitCommit        string
	GitBranch        string
	GitDirty         bool
	ColimaMemoryGB   int
	ColimaCPUs       int

	// Component health (live)
	Components []ComponentHealth

	// Recent benchmarks
	RecentBenchmarks []BenchmarkSummary

	// Historical stats
	TotalBenchmarks  int
	SuccessRate      float64
	AvgThroughput    float64
	AvgP99Latency    float64
}

// ComponentHealth represents current health of a component
type ComponentHealth struct {
	Name          string
	DisplayName   string
	Status        string // healthy, warning, critical, crashed
	MemoryUsedMB  float64
	MemoryLimitMB float64
	MemoryPercent float64
	CPUPercent    float64
	Uptime        string
	LastCheck     time.Time
}

// BenchmarkSummary is a compact benchmark record for the dashboard
type BenchmarkSummary struct {
	RunID      string
	Date       string
	Time       string
	GitCommit  string
	Type       string
	Throughput float64
	P99Latency float64
	Success    bool
	Duration   int
}

// GenerateDashboard creates the unified dashboard HTML
func GenerateDashboard(reportsDir string, storage *Storage, components []ComponentHealth) error {
	data := &DashboardData{
		GeneratedAt: time.Now(),
		Components:  components,
	}

	// Get git info
	data.GitCommit, data.GitBranch, data.GitDirty = GetGitInfo()
	data.ColimaMemoryGB, data.ColimaCPUs = GetColimaInfo()

	// Get recent benchmarks from storage
	if storage != nil {
		runs, err := storage.GetRecentRuns(10)
		if err == nil {
			for _, run := range runs {
				metrics, _ := storage.GetMetricsForRun(run.RunID)
				summary := BenchmarkSummary{
					RunID:     run.RunID,
					Date:      run.StartTime.Format("2006-01-02"),
					Time:      run.StartTime.Format("15:04:05"),
					GitCommit: run.GitCommit,
					Type:      run.BenchmarkType,
					Success:   run.Success,
					Duration:  run.DurationSeconds,
				}
				if metrics != nil {
					summary.Throughput = metrics.ThroughputOpsPerSec
					summary.P99Latency = metrics.LatencyP99Ms
				}
				data.RecentBenchmarks = append(data.RecentBenchmarks, summary)
			}
			data.TotalBenchmarks = len(runs)

			// Calculate stats
			var successCount int
			var totalThroughput, totalLatency float64
			var metricCount int
			for _, run := range runs {
				if run.Success {
					successCount++
				}
				metrics, _ := storage.GetMetricsForRun(run.RunID)
				if metrics != nil {
					totalThroughput += metrics.ThroughputOpsPerSec
					totalLatency += metrics.LatencyP99Ms
					metricCount++
				}
			}
			if len(runs) > 0 {
				data.SuccessRate = float64(successCount) / float64(len(runs)) * 100
			}
			if metricCount > 0 {
				data.AvgThroughput = totalThroughput / float64(metricCount)
				data.AvgP99Latency = totalLatency / float64(metricCount)
			}
		}
	}

	// Generate HTML
	outputPath := filepath.Join(reportsDir, "dashboard.html")
	return generateDashboardHTML(data, outputPath)
}

func generateDashboardHTML(data *DashboardData, outputPath string) error {
	dir := filepath.Dir(outputPath)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return fmt.Errorf("failed to create directory: %w", err)
	}

	funcs := template.FuncMap{
		"json": func(v interface{}) template.JS {
			b, _ := json.Marshal(v)
			return template.JS(b)
		},
		"statusClass": func(status string) string {
			switch status {
			case "healthy":
				return "success"
			case "warning":
				return "warning"
			case "critical", "crashed":
				return "danger"
			default:
				return "secondary"
			}
		},
		"statusIcon": func(status string) string {
			switch status {
			case "healthy":
				return "●"
			case "warning":
				return "●"
			case "critical":
				return "●"
			case "crashed":
				return "○"
			default:
				return "?"
			}
		},
	}

	tmpl, err := template.New("dashboard").Funcs(funcs).Parse(dashboardTemplate)
	if err != nil {
		return fmt.Errorf("failed to parse template: %w", err)
	}

	f, err := os.Create(outputPath)
	if err != nil {
		return fmt.Errorf("failed to create file: %w", err)
	}
	defer f.Close()

	return tmpl.Execute(f, data)
}

const dashboardTemplate = `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Reactive Platform - Dashboard</title>
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
    <style>
        :root {
            --bg-primary: #0d1117;
            --bg-secondary: #161b22;
            --bg-tertiary: #21262d;
            --bg-hover: #30363d;
            --text-primary: #c9d1d9;
            --text-secondary: #8b949e;
            --text-muted: #6e7681;
            --border-color: #30363d;
            --success: #3fb950;
            --warning: #d29922;
            --danger: #f85149;
            --info: #58a6ff;
            --purple: #a371f7;
        }

        * { box-sizing: border-box; margin: 0; padding: 0; }

        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif;
            background: var(--bg-primary);
            color: var(--text-primary);
            line-height: 1.5;
        }

        /* Navigation */
        .nav {
            background: var(--bg-secondary);
            border-bottom: 1px solid var(--border-color);
            padding: 12px 24px;
            display: flex;
            align-items: center;
            gap: 24px;
            position: sticky;
            top: 0;
            z-index: 100;
        }

        .nav-brand {
            font-weight: 600;
            font-size: 16px;
            color: var(--text-primary);
            text-decoration: none;
        }

        .nav-links {
            display: flex;
            gap: 8px;
        }

        .nav-link {
            color: var(--text-secondary);
            text-decoration: none;
            padding: 6px 12px;
            border-radius: 6px;
            font-size: 14px;
            transition: all 0.15s;
        }

        .nav-link:hover {
            background: var(--bg-tertiary);
            color: var(--text-primary);
        }

        .nav-link.active {
            background: var(--bg-tertiary);
            color: var(--text-primary);
        }

        .nav-meta {
            margin-left: auto;
            font-size: 12px;
            color: var(--text-muted);
            display: flex;
            gap: 16px;
        }

        .nav-meta code {
            background: var(--bg-tertiary);
            padding: 2px 6px;
            border-radius: 4px;
            font-family: 'SF Mono', Consolas, monospace;
        }

        /* Main container */
        .container {
            max-width: 1400px;
            margin: 0 auto;
            padding: 24px;
        }

        /* Grid layouts */
        .grid-2 { display: grid; grid-template-columns: repeat(2, 1fr); gap: 16px; }
        .grid-3 { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
        .grid-4 { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; }

        @media (max-width: 1200px) {
            .grid-4 { grid-template-columns: repeat(2, 1fr); }
            .grid-3 { grid-template-columns: repeat(2, 1fr); }
        }

        @media (max-width: 768px) {
            .grid-4, .grid-3, .grid-2 { grid-template-columns: 1fr; }
        }

        /* Cards */
        .card {
            background: var(--bg-secondary);
            border: 1px solid var(--border-color);
            border-radius: 8px;
            padding: 16px;
        }

        .card-header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            margin-bottom: 16px;
            padding-bottom: 12px;
            border-bottom: 1px solid var(--border-color);
        }

        .card-title {
            font-size: 14px;
            font-weight: 600;
            color: var(--text-primary);
        }

        .card-link {
            font-size: 12px;
            color: var(--info);
            text-decoration: none;
        }

        .card-link:hover { text-decoration: underline; }

        /* Stats */
        .stat-card {
            background: var(--bg-secondary);
            border: 1px solid var(--border-color);
            border-radius: 8px;
            padding: 16px;
        }

        .stat-label {
            font-size: 12px;
            color: var(--text-muted);
            text-transform: uppercase;
            letter-spacing: 0.5px;
            margin-bottom: 4px;
        }

        .stat-value {
            font-size: 28px;
            font-weight: 600;
        }

        .stat-value.success { color: var(--success); }
        .stat-value.warning { color: var(--warning); }
        .stat-value.danger { color: var(--danger); }
        .stat-value.info { color: var(--info); }

        .stat-sub {
            font-size: 12px;
            color: var(--text-muted);
            margin-top: 4px;
        }

        /* Component list */
        .component-list {
            display: flex;
            flex-direction: column;
            gap: 8px;
        }

        .component-item {
            display: flex;
            align-items: center;
            padding: 12px;
            background: var(--bg-tertiary);
            border-radius: 6px;
            gap: 12px;
        }

        .component-status {
            width: 10px;
            height: 10px;
            border-radius: 50%;
        }

        .component-status.success { background: var(--success); }
        .component-status.warning { background: var(--warning); }
        .component-status.danger { background: var(--danger); }
        .component-status.secondary { background: var(--text-muted); }

        .component-name {
            font-weight: 500;
            flex: 1;
        }

        .component-meta {
            font-size: 12px;
            color: var(--text-muted);
            text-align: right;
        }

        .progress-bar {
            width: 80px;
            height: 6px;
            background: var(--bg-primary);
            border-radius: 3px;
            overflow: hidden;
        }

        .progress-fill {
            height: 100%;
            border-radius: 3px;
            transition: width 0.3s;
        }

        .progress-fill.success { background: var(--success); }
        .progress-fill.warning { background: var(--warning); }
        .progress-fill.danger { background: var(--danger); }

        /* Benchmark table */
        .bench-table {
            width: 100%;
            border-collapse: collapse;
            font-size: 13px;
        }

        .bench-table th {
            text-align: left;
            padding: 8px 12px;
            font-weight: 500;
            color: var(--text-muted);
            border-bottom: 1px solid var(--border-color);
            font-size: 11px;
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }

        .bench-table td {
            padding: 10px 12px;
            border-bottom: 1px solid var(--border-color);
        }

        .bench-table tr:hover {
            background: var(--bg-tertiary);
        }

        .bench-table .mono {
            font-family: 'SF Mono', Consolas, monospace;
            font-size: 12px;
        }

        .badge {
            display: inline-block;
            padding: 2px 8px;
            border-radius: 12px;
            font-size: 11px;
            font-weight: 500;
        }

        .badge.success {
            background: rgba(63, 185, 80, 0.15);
            color: var(--success);
        }

        .badge.danger {
            background: rgba(248, 81, 73, 0.15);
            color: var(--danger);
        }

        /* Section spacing */
        .section {
            margin-bottom: 24px;
        }

        .section-title {
            font-size: 18px;
            font-weight: 600;
            margin-bottom: 16px;
            display: flex;
            align-items: center;
            gap: 8px;
        }

        .section-title::before {
            content: '';
            display: inline-block;
            width: 4px;
            height: 18px;
            background: var(--info);
            border-radius: 2px;
        }

        /* Chart container */
        .chart-container {
            position: relative;
            height: 200px;
        }

        /* Footer */
        footer {
            text-align: center;
            padding: 24px;
            color: var(--text-muted);
            font-size: 12px;
            border-top: 1px solid var(--border-color);
            margin-top: 24px;
        }
    </style>
</head>
<body>
    <nav class="nav">
        <a href="index.html" class="nav-brand">Reactive Platform</a>
        <div class="nav-links">
            <a href="dashboard.html" class="nav-link active">Dashboard</a>
            <a href="full/index.html" class="nav-link">Benchmark</a>
            <a href="history/index.html" class="nav-link">History</a>
        </div>
        <div class="nav-meta">
            <span>Commit: <code>{{.GitCommit}}{{if .GitDirty}}*{{end}}</code></span>
            <span>Colima: <code>{{.ColimaCPUs}} CPU / {{.ColimaMemoryGB}} GB</code></span>
        </div>
    </nav>

    <div class="container">
        <!-- Stats Overview -->
        <div class="section">
            <div class="grid-4">
                <div class="stat-card">
                    <div class="stat-label">Components</div>
                    <div class="stat-value info">{{len .Components}}</div>
                    <div class="stat-sub">services monitored</div>
                </div>
                <div class="stat-card">
                    <div class="stat-label">Total Benchmarks</div>
                    <div class="stat-value info">{{.TotalBenchmarks}}</div>
                    <div class="stat-sub">runs recorded</div>
                </div>
                <div class="stat-card">
                    <div class="stat-label">Success Rate</div>
                    <div class="stat-value {{if ge .SuccessRate 90.0}}success{{else if ge .SuccessRate 70.0}}warning{{else}}danger{{end}}">{{printf "%.0f" .SuccessRate}}%</div>
                    <div class="stat-sub">benchmark success</div>
                </div>
                <div class="stat-card">
                    <div class="stat-label">Avg Throughput</div>
                    <div class="stat-value success">{{printf "%.0f" .AvgThroughput}}</div>
                    <div class="stat-sub">ops/sec average</div>
                </div>
            </div>
        </div>

        <div class="grid-2">
            <!-- Component Health -->
            <div class="section">
                <div class="card">
                    <div class="card-header">
                        <span class="card-title">Component Health</span>
                        <a href="#" class="card-link">Refresh</a>
                    </div>
                    <div class="component-list">
                        {{range .Components}}
                        <div class="component-item">
                            <div class="component-status {{statusClass .Status}}"></div>
                            <div class="component-name">{{.DisplayName}}</div>
                            <div class="component-meta">
                                <div>{{printf "%.0f" .MemoryUsedMB}} / {{printf "%.0f" .MemoryLimitMB}} MB</div>
                                <div class="progress-bar">
                                    <div class="progress-fill {{if ge .MemoryPercent 90.0}}danger{{else if ge .MemoryPercent 75.0}}warning{{else}}success{{end}}" style="width: {{printf "%.0f" .MemoryPercent}}%"></div>
                                </div>
                            </div>
                        </div>
                        {{else}}
                        <div style="text-align: center; padding: 24px; color: var(--text-muted);">
                            No component data available. Run <code>./cli.sh diag fetch</code>
                        </div>
                        {{end}}
                    </div>
                </div>
            </div>

            <!-- Recent Benchmarks -->
            <div class="section">
                <div class="card">
                    <div class="card-header">
                        <span class="card-title">Recent Benchmarks</span>
                        <a href="history/index.html" class="card-link">View All →</a>
                    </div>
                    {{if .RecentBenchmarks}}
                    <table class="bench-table">
                        <thead>
                            <tr>
                                <th>Date</th>
                                <th>Commit</th>
                                <th>Throughput</th>
                                <th>P99</th>
                                <th>Status</th>
                            </tr>
                        </thead>
                        <tbody>
                            {{range .RecentBenchmarks}}
                            <tr>
                                <td class="mono">{{.Date}} {{.Time}}</td>
                                <td class="mono">{{.GitCommit}}</td>
                                <td class="mono">{{printf "%.0f" .Throughput}} ops/s</td>
                                <td class="mono">{{printf "%.1f" .P99Latency}} ms</td>
                                <td>
                                    {{if .Success}}
                                    <span class="badge success">Success</span>
                                    {{else}}
                                    <span class="badge danger">Failed</span>
                                    {{end}}
                                </td>
                            </tr>
                            {{end}}
                        </tbody>
                    </table>
                    {{else}}
                    <div style="text-align: center; padding: 24px; color: var(--text-muted);">
                        No benchmark history. Run <code>./cli.sh bench full</code> and save with <code>./cli.sh bench history save</code>
                    </div>
                    {{end}}
                </div>
            </div>
        </div>

        <!-- Quick Links -->
        <div class="section">
            <h2 class="section-title">Quick Links</h2>
            <div class="grid-4">
                <a href="full/index.html" class="card" style="text-decoration: none;">
                    <div class="stat-label">Full Pipeline</div>
                    <div style="color: var(--text-primary); margin-top: 8px;">Latest benchmark report</div>
                </a>
                <a href="history/index.html" class="card" style="text-decoration: none;">
                    <div class="stat-label">History</div>
                    <div style="color: var(--text-primary); margin-top: 8px;">Trends over time</div>
                </a>
                <a href="kafka/index.html" class="card" style="text-decoration: none;">
                    <div class="stat-label">Kafka</div>
                    <div style="color: var(--text-primary); margin-top: 8px;">Kafka benchmark</div>
                </a>
                <a href="gateway/index.html" class="card" style="text-decoration: none;">
                    <div class="stat-label">Gateway</div>
                    <div style="color: var(--text-primary); margin-top: 8px;">HTTP endpoint benchmark</div>
                </a>
            </div>
        </div>
    </div>

    <footer>
        Generated at {{.GeneratedAt.Format "2006-01-02 15:04:05 MST"}} | Reactive Platform Dashboard
    </footer>
</body>
</html>
`
