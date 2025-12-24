package diagnostic

import (
	"fmt"
	"html/template"
	"io"
	"time"
)

// HTMLRenderer outputs diagnostic data as static HTML
type HTMLRenderer struct {
	tmpl *template.Template
}

// NewHTMLRenderer creates a new HTML renderer
func NewHTMLRenderer() (*HTMLRenderer, error) {
	funcs := template.FuncMap{
		"formatBytes":    formatBytes,
		"formatDuration": formatDuration,
		"formatTime": func(ms int64) string {
			return time.UnixMilli(ms).UTC().Format("2006-01-02 15:04:05 UTC")
		},
		"formatPercent": func(v float64) string {
			return fmt.Sprintf("%.1f%%", v)
		},
		"formatFloat": func(v float64) string {
			return fmt.Sprintf("%.1f", v)
		},
		"healthClass": func(s *DiagnosticSnapshot) string {
			if s.Errors.CrashIndicators.OOMRisk || s.Errors.CrashIndicators.GCThrashing || s.Contention.DeadlockDetected {
				return "critical"
			}
			if s.Saturation.HeapUsedPercent > 85 || s.GC.GCOverheadPercent > 10 || s.Contention.LockContentionPercent > 50 {
				return "warning"
			}
			return "healthy"
		},
		"healthIcon": func(s *DiagnosticSnapshot) string {
			if s.Errors.CrashIndicators.OOMRisk || s.Errors.CrashIndicators.GCThrashing || s.Contention.DeadlockDetected {
				return "🔴"
			}
			if s.Saturation.HeapUsedPercent > 85 || s.GC.GCOverheadPercent > 10 || s.Contention.LockContentionPercent > 50 {
				return "🟡"
			}
			return "🟢"
		},
		"progressClass": func(pct float64) string {
			if pct >= 90 {
				return "critical"
			}
			if pct >= 75 {
				return "warning"
			}
			return "healthy"
		},
	}

	tmpl, err := template.New("diagnostic").Funcs(funcs).Parse(htmlTemplate)
	if err != nil {
		return nil, fmt.Errorf("failed to parse HTML template: %w", err)
	}

	return &HTMLRenderer{tmpl: tmpl}, nil
}

// Render outputs the diagnostic snapshot as HTML
func (r *HTMLRenderer) Render(w io.Writer, s *DiagnosticSnapshot) error {
	return r.tmpl.Execute(w, s)
}

const htmlTemplate = `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Diagnostic Report: {{.Component}}</title>
    <style>
        :root {
            --bg-primary: #0d1117;
            --bg-secondary: #161b22;
            --bg-tertiary: #21262d;
            --text-primary: #c9d1d9;
            --text-secondary: #8b949e;
            --text-muted: #6e7681;
            --border-color: #30363d;
            --accent-green: #3fb950;
            --accent-yellow: #d29922;
            --accent-red: #f85149;
            --accent-blue: #58a6ff;
        }

        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
        }

        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
            background-color: var(--bg-primary);
            color: var(--text-primary);
            line-height: 1.6;
            padding: 2rem;
        }

        .container {
            max-width: 1400px;
            margin: 0 auto;
        }

        header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 2rem;
            padding-bottom: 1rem;
            border-bottom: 1px solid var(--border-color);
        }

        h1 {
            font-size: 1.75rem;
            font-weight: 600;
        }

        .status-badge {
            padding: 0.5rem 1rem;
            border-radius: 6px;
            font-weight: 600;
            text-transform: uppercase;
            font-size: 0.875rem;
        }

        .status-badge.healthy { background-color: rgba(63, 185, 80, 0.2); color: var(--accent-green); }
        .status-badge.warning { background-color: rgba(210, 153, 34, 0.2); color: var(--accent-yellow); }
        .status-badge.critical { background-color: rgba(248, 81, 73, 0.2); color: var(--accent-red); }

        .meta {
            display: flex;
            gap: 2rem;
            color: var(--text-secondary);
            font-size: 0.875rem;
            margin-bottom: 2rem;
        }

        .grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
            gap: 1.5rem;
            margin-bottom: 2rem;
        }

        .card {
            background-color: var(--bg-secondary);
            border: 1px solid var(--border-color);
            border-radius: 8px;
            padding: 1.25rem;
        }

        .card-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 1rem;
            padding-bottom: 0.75rem;
            border-bottom: 1px solid var(--border-color);
        }

        .card-title {
            font-size: 1rem;
            font-weight: 600;
            color: var(--text-primary);
        }

        .metric {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: 0.5rem 0;
            border-bottom: 1px solid var(--border-color);
        }

        .metric:last-child {
            border-bottom: none;
        }

        .metric-label {
            color: var(--text-secondary);
            font-size: 0.875rem;
        }

        .metric-value {
            font-weight: 600;
            font-family: 'SF Mono', Monaco, monospace;
        }

        .progress-bar {
            width: 100%;
            height: 8px;
            background-color: var(--bg-tertiary);
            border-radius: 4px;
            overflow: hidden;
            margin-top: 0.25rem;
        }

        .progress-fill {
            height: 100%;
            border-radius: 4px;
            transition: width 0.3s ease;
        }

        .progress-fill.healthy { background-color: var(--accent-green); }
        .progress-fill.warning { background-color: var(--accent-yellow); }
        .progress-fill.critical { background-color: var(--accent-red); }

        table {
            width: 100%;
            border-collapse: collapse;
            font-size: 0.875rem;
        }

        th, td {
            padding: 0.75rem;
            text-align: left;
            border-bottom: 1px solid var(--border-color);
        }

        th {
            color: var(--text-secondary);
            font-weight: 500;
            background-color: var(--bg-tertiary);
        }

        td {
            font-family: 'SF Mono', Monaco, monospace;
        }

        .tag {
            display: inline-block;
            padding: 0.25rem 0.5rem;
            border-radius: 4px;
            font-size: 0.75rem;
            font-weight: 500;
        }

        .tag-bottleneck { background-color: rgba(248, 81, 73, 0.2); color: var(--accent-red); }
        .tag-backpressure { background-color: rgba(210, 153, 34, 0.2); color: var(--accent-yellow); }
        .tag-ok { background-color: rgba(63, 185, 80, 0.2); color: var(--accent-green); }

        .section {
            margin-bottom: 2rem;
        }

        .section-title {
            font-size: 1.25rem;
            font-weight: 600;
            margin-bottom: 1rem;
            color: var(--text-primary);
        }

        .latency-breakdown {
            display: flex;
            height: 24px;
            border-radius: 4px;
            overflow: hidden;
            margin: 1rem 0;
        }

        .latency-segment {
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 0.625rem;
            font-weight: 600;
            color: white;
            min-width: 30px;
        }

        .legend {
            display: flex;
            flex-wrap: wrap;
            gap: 1rem;
            margin-top: 0.5rem;
        }

        .legend-item {
            display: flex;
            align-items: center;
            gap: 0.5rem;
            font-size: 0.75rem;
            color: var(--text-secondary);
        }

        .legend-color {
            width: 12px;
            height: 12px;
            border-radius: 2px;
        }

        .alert {
            padding: 1rem;
            border-radius: 6px;
            margin-bottom: 1rem;
            display: flex;
            align-items: center;
            gap: 0.75rem;
        }

        .alert-critical {
            background-color: rgba(248, 81, 73, 0.1);
            border: 1px solid rgba(248, 81, 73, 0.3);
            color: var(--accent-red);
        }

        .alert-warning {
            background-color: rgba(210, 153, 34, 0.1);
            border: 1px solid rgba(210, 153, 34, 0.3);
            color: var(--accent-yellow);
        }

        .trace-card {
            background-color: var(--bg-tertiary);
            border-radius: 6px;
            padding: 1rem;
            margin-bottom: 1rem;
        }

        .trace-header {
            display: flex;
            justify-content: space-between;
            margin-bottom: 0.75rem;
        }

        .trace-id {
            font-family: 'SF Mono', Monaco, monospace;
            font-size: 0.875rem;
            color: var(--accent-blue);
        }

        .trace-status {
            font-size: 0.75rem;
            padding: 0.25rem 0.5rem;
            border-radius: 4px;
        }

        .trace-status.success { background-color: rgba(63, 185, 80, 0.2); color: var(--accent-green); }
        .trace-status.timeout { background-color: rgba(248, 81, 73, 0.2); color: var(--accent-red); }
        .trace-status.error { background-color: rgba(248, 81, 73, 0.2); color: var(--accent-red); }
    </style>
</head>
<body>
    <div class="container">
        <header>
            <div>
                <h1>{{healthIcon .}} {{.Component}}</h1>
            </div>
            <span class="status-badge {{healthClass .}}">
                {{if eq (healthClass .) "critical"}}Critical{{else if eq (healthClass .) "warning"}}Warning{{else}}Healthy{{end}}
            </span>
        </header>

        <div class="meta">
            <span><strong>Instance:</strong> {{.InstanceID}}</span>
            <span><strong>Version:</strong> {{.Version}}</span>
            <span><strong>Uptime:</strong> {{formatDuration .UptimeMs}}</span>
            <span><strong>Timestamp:</strong> {{formatTime .TimestampMs}}</span>
        </div>

        {{if .Errors.CrashIndicators.OOMRisk}}
        <div class="alert alert-critical">
            <span>🚨</span>
            <span><strong>OOM Risk:</strong> Memory at {{formatPercent .Errors.CrashIndicators.OOMRiskPercent}} - heap exhaustion imminent</span>
        </div>
        {{end}}

        {{if .Errors.CrashIndicators.GCThrashing}}
        <div class="alert alert-critical">
            <span>🚨</span>
            <span><strong>GC Thrashing:</strong> Excessive garbage collection detected - performance severely degraded</span>
        </div>
        {{end}}

        {{if .Contention.DeadlockDetected}}
        <div class="alert alert-critical">
            <span>🚨</span>
            <span><strong>Deadlock Detected:</strong> Threads are deadlocked - service may be unresponsive</span>
        </div>
        {{end}}

        <!-- Core Metrics -->
        <div class="grid">
            <div class="card">
                <div class="card-header">
                    <span class="card-title">Throughput</span>
                    <span class="metric-value">{{formatFloat .Throughput.EventsPerSecond}} events/sec</span>
                </div>
                <div class="metric">
                    <span class="metric-label">Capacity Utilization</span>
                    <span class="metric-value">{{formatPercent .Throughput.CapacityUtilizationPct}}</span>
                </div>
                <div class="progress-bar">
                    <div class="progress-fill {{progressClass .Throughput.CapacityUtilizationPct}}" style="width: {{.Throughput.CapacityUtilizationPct}}%"></div>
                </div>
                <div class="metric">
                    <span class="metric-label">Queue Depth</span>
                    <span class="metric-value">{{.Throughput.QueueDepth}} / {{.Throughput.QueueCapacity}}</span>
                </div>
                <div class="metric">
                    <span class="metric-label">Batch Efficiency</span>
                    <span class="metric-value">{{formatPercent .Throughput.BatchEfficiencyPct}}</span>
                </div>
                {{if or (gt .Throughput.RejectedCount 0) (gt .Throughput.DroppedCount 0)}}
                <div class="metric" style="color: var(--accent-red);">
                    <span class="metric-label">Rejected / Dropped</span>
                    <span class="metric-value">{{.Throughput.RejectedCount}} / {{.Throughput.DroppedCount}}</span>
                </div>
                {{end}}
            </div>

            <div class="card">
                <div class="card-header">
                    <span class="card-title">Latency</span>
                    <span class="metric-value">P50: {{formatFloat .Latency.TotalMsP50}}ms</span>
                </div>
                <div class="metric">
                    <span class="metric-label">P95</span>
                    <span class="metric-value">{{formatFloat .Latency.TotalMsP95}}ms</span>
                </div>
                <div class="metric">
                    <span class="metric-label">P99</span>
                    <span class="metric-value">{{formatFloat .Latency.TotalMsP99}}ms</span>
                </div>
                <div class="metric">
                    <span class="metric-label">Max</span>
                    <span class="metric-value">{{formatFloat .Latency.TotalMsMax}}ms</span>
                </div>
                {{if .Latency.BreakdownPercent}}
                <div style="margin-top: 0.75rem; padding-top: 0.75rem; border-top: 1px solid var(--border-color);">
                    <div style="font-size: 0.75rem; color: var(--text-secondary); margin-bottom: 0.5rem;">Where Time Goes</div>
                    <div class="latency-breakdown">
                        {{range $name, $pct := .Latency.BreakdownPercent}}
                        <div class="latency-segment" style="width: {{$pct}}%; background-color: {{if eq $name "cpu"}}#58a6ff{{else if eq $name "queue_wait"}}#d29922{{else if eq $name "gc_pause"}}#f85149{{else if eq $name "network"}}#3fb950{{else if eq $name "serialization"}}#a371f7{{else if eq $name "lock_wait"}}#f778ba{{else if eq $name "dependency_wait"}}#a5d6ff{{else}}#6e7681{{end}};" title="{{$name}}: {{printf "%.1f" $pct}}%">
                            {{if gt $pct 10.0}}{{printf "%.0f" $pct}}%{{end}}
                        </div>
                        {{end}}
                    </div>
                    <div class="legend">
                        {{range $name, $pct := .Latency.BreakdownPercent}}
                        <div class="legend-item">
                            <div class="legend-color" style="background-color: {{if eq $name "cpu"}}#58a6ff{{else if eq $name "queue_wait"}}#d29922{{else if eq $name "gc_pause"}}#f85149{{else if eq $name "network"}}#3fb950{{else if eq $name "serialization"}}#a371f7{{else if eq $name "lock_wait"}}#f778ba{{else if eq $name "dependency_wait"}}#a5d6ff{{else}}#6e7681{{end}};"></div>
                            <span>{{$name}}: {{printf "%.1f" $pct}}%</span>
                        </div>
                        {{end}}
                    </div>
                </div>
                {{end}}
            </div>

            <div class="card">
                <div class="card-header">
                    <span class="card-title">Memory</span>
                    <span class="metric-value">{{formatPercent .Saturation.HeapUsedPercent}}</span>
                </div>
                <div class="metric">
                    <span class="metric-label">Heap Used</span>
                    <span class="metric-value">{{formatBytes .Saturation.HeapUsedBytes}} / {{formatBytes .Saturation.HeapMaxBytes}}</span>
                </div>
                <div class="progress-bar">
                    <div class="progress-fill {{progressClass .Saturation.HeapUsedPercent}}" style="width: {{.Saturation.HeapUsedPercent}}%"></div>
                </div>
                <div class="metric">
                    <span class="metric-label">Old Gen</span>
                    <span class="metric-value">{{formatPercent .Saturation.OldGenPercent}}</span>
                </div>
                <div class="progress-bar">
                    <div class="progress-fill {{progressClass .Saturation.OldGenPercent}}" style="width: {{.Saturation.OldGenPercent}}%"></div>
                </div>
            </div>

            <div class="card">
                <div class="card-header">
                    <span class="card-title">GC</span>
                    <span class="metric-value">{{.GC.GCAlgorithm}}</span>
                </div>
                <div class="metric">
                    <span class="metric-label">GC Overhead</span>
                    <span class="metric-value">{{formatPercent .GC.GCOverheadPercent}}</span>
                </div>
                <div class="metric">
                    <span class="metric-label">Young GC</span>
                    <span class="metric-value">{{.GC.YoungGCCount}} (avg {{formatFloat .GC.AvgYoungGCPauseMs}}ms)</span>
                </div>
                <div class="metric">
                    <span class="metric-label">Old GC</span>
                    <span class="metric-value">{{.GC.OldGCCount}} (avg {{formatFloat .GC.AvgOldGCPauseMs}}ms)</span>
                </div>
                <div class="metric">
                    <span class="metric-label">Max Pause</span>
                    <span class="metric-value">{{formatFloat .GC.MaxGCPauseMs}}ms</span>
                </div>
            </div>
        </div>

        <!-- Resource Saturation -->
        <div class="section">
            <h2 class="section-title">Resource Saturation</h2>
            <div class="grid">
                <div class="card">
                    <div class="metric">
                        <span class="metric-label">CPU</span>
                        <span class="metric-value">{{formatPercent .Saturation.CPUPercent}}</span>
                    </div>
                    <div class="progress-bar">
                        <div class="progress-fill {{progressClass .Saturation.CPUPercent}}" style="width: {{.Saturation.CPUPercent}}%"></div>
                    </div>
                </div>
                <div class="card">
                    <div class="metric">
                        <span class="metric-label">Thread Pool</span>
                        <span class="metric-value">{{.Saturation.ThreadPoolActive}} / {{.Saturation.ThreadPoolMax}}</span>
                    </div>
                    <div class="metric">
                        <span class="metric-label">Queue Depth</span>
                        <span class="metric-value">{{.Saturation.ThreadPoolQueueDepth}}</span>
                    </div>
                </div>
                <div class="card">
                    <div class="metric">
                        <span class="metric-label">Connection Pool</span>
                        <span class="metric-value">{{.Saturation.ConnectionPoolActive}} / {{.Saturation.ConnectionPoolMax}}</span>
                    </div>
                    <div class="metric">
                        <span class="metric-label">Buffer Pool</span>
                        <span class="metric-value">{{formatPercent .Saturation.BufferPoolUsedPercent}}</span>
                    </div>
                </div>
                <div class="card">
                    <div class="metric">
                        <span class="metric-label">File Descriptors</span>
                        <span class="metric-value">{{.Saturation.FileDescriptorsUsed}} / {{.Saturation.FileDescriptorsMax}}</span>
                    </div>
                </div>
            </div>
        </div>

        <!-- Pipeline Stages -->
        {{if .Stages}}
        <div class="section">
            <h2 class="section-title">Pipeline Stages</h2>
            <div class="card">
                <table>
                    <thead>
                        <tr>
                            <th>Stage</th>
                            <th>Events/sec</th>
                            <th>P50</th>
                            <th>P99</th>
                            <th>Queue</th>
                            <th>Saturation</th>
                            <th>Status</th>
                        </tr>
                    </thead>
                    <tbody>
                        {{range .Stages}}
                        <tr>
                            <td>{{.Name}}</td>
                            <td>{{formatFloat .EventsPerSecond}}</td>
                            <td>{{formatFloat .LatencyMsP50}}ms</td>
                            <td>{{formatFloat .LatencyMsP99}}ms</td>
                            <td>{{.QueueDepth}}</td>
                            <td>{{formatPercent .SaturationPercent}}</td>
                            <td>
                                {{if .IsBottleneck}}<span class="tag tag-bottleneck">BOTTLENECK</span>
                                {{else if .BackpressureApplied}}<span class="tag tag-backpressure">Backpressure</span>
                                {{else}}<span class="tag tag-ok">OK</span>{{end}}
                            </td>
                        </tr>
                        {{end}}
                    </tbody>
                </table>
            </div>
        </div>
        {{end}}

        <!-- Dependencies -->
        {{if .Dependencies}}
        <div class="section">
            <h2 class="section-title">Dependencies</h2>
            <div class="card">
                <table>
                    <thead>
                        <tr>
                            <th>Dependency</th>
                            <th>Type</th>
                            <th>P50</th>
                            <th>P99</th>
                            <th>Calls/sec</th>
                            <th>Errors</th>
                            <th>Circuit</th>
                            <th>Saturation</th>
                        </tr>
                    </thead>
                    <tbody>
                        {{range .Dependencies}}
                        <tr>
                            <td>{{.Name}}</td>
                            <td>{{.Type}}</td>
                            <td>{{formatFloat .LatencyMsP50}}ms</td>
                            <td>{{formatFloat .LatencyMsP99}}ms</td>
                            <td>{{formatFloat .CallsPerSecond}}</td>
                            <td>{{formatPercent .ErrorRatePercent}}</td>
                            <td>
                                {{if eq .CircuitBreakerState "OPEN"}}<span class="tag tag-bottleneck">OPEN</span>
                                {{else if eq .CircuitBreakerState "HALF_OPEN"}}<span class="tag tag-backpressure">HALF-OPEN</span>
                                {{else}}<span class="tag tag-ok">CLOSED</span>{{end}}
                            </td>
                            <td>{{formatPercent .SaturationPercent}}</td>
                        </tr>
                        {{end}}
                    </tbody>
                </table>
            </div>
        </div>
        {{end}}

        <!-- Contention -->
        {{if gt .Contention.LockContentionPercent 0.0}}
        <div class="section">
            <h2 class="section-title">Lock Contention</h2>
            <div class="card">
                <div class="metric">
                    <span class="metric-label">Overall Contention</span>
                    <span class="metric-value">{{formatPercent .Contention.LockContentionPercent}}</span>
                </div>
                <div class="metric">
                    <span class="metric-label">Blocked / Waiting Threads</span>
                    <span class="metric-value">{{.Contention.BlockedThreads}} / {{.Contention.WaitingThreads}}</span>
                </div>
                {{if .Contention.MostContendedLocks}}
                <table style="margin-top: 1rem;">
                    <thead>
                        <tr>
                            <th>Lock</th>
                            <th>Contention</th>
                            <th>Wait Count</th>
                            <th>Avg Wait</th>
                        </tr>
                    </thead>
                    <tbody>
                        {{range .Contention.MostContendedLocks}}
                        <tr>
                            <td>{{.Name}}</td>
                            <td>{{formatPercent .ContentionPercent}}</td>
                            <td>{{.WaitCount}}</td>
                            <td>{{formatFloat .AvgWaitMs}}ms</td>
                        </tr>
                        {{end}}
                    </tbody>
                </table>
                {{end}}
            </div>
        </div>
        {{end}}

        <!-- Errors -->
        <div class="section">
            <h2 class="section-title">Errors</h2>
            <div class="grid">
                <div class="card">
                    <div class="metric">
                        <span class="metric-label">Total Errors</span>
                        <span class="metric-value">{{.Errors.TotalErrorCount}}</span>
                    </div>
                    <div class="metric">
                        <span class="metric-label">Error Rate</span>
                        <span class="metric-value">{{formatPercent .Errors.ErrorRatePercent}}</span>
                    </div>
                    {{if .Errors.ErrorsByType}}
                    <div style="margin-top: 0.5rem; font-size: 0.75rem; color: var(--text-secondary);">
                        {{range $type, $count := .Errors.ErrorsByType}}
                        <span style="margin-right: 1rem;">{{$type}}: {{$count}}</span>
                        {{end}}
                    </div>
                    {{end}}
                </div>
                <div class="card">
                    <div class="card-header">
                        <span class="card-title">Crash Risk</span>
                    </div>
                    <div class="metric">
                        <span class="metric-label">OOM Risk</span>
                        <span class="metric-value">{{if .Errors.CrashIndicators.OOMRisk}}⚠️ {{formatPercent .Errors.CrashIndicators.OOMRiskPercent}}{{else}}No{{end}}</span>
                    </div>
                    <div class="metric">
                        <span class="metric-label">GC Thrashing</span>
                        <span class="metric-value">{{if .Errors.CrashIndicators.GCThrashing}}⚠️ Yes{{else}}No{{end}}</span>
                    </div>
                    <div class="metric">
                        <span class="metric-label">Memory Leak</span>
                        <span class="metric-value">{{if .Errors.CrashIndicators.MemoryLeakSuspected}}⚠️ Suspected{{else}}No{{end}}</span>
                    </div>
                    <div class="metric">
                        <span class="metric-label">Thread Exhaustion</span>
                        <span class="metric-value">{{if .Errors.CrashIndicators.ThreadExhaustionRisk}}⚠️ Risk{{else}}No{{end}}</span>
                    </div>
                </div>
            </div>
            {{if .Errors.ErrorPatterns}}
            <div class="card" style="margin-top: 1rem;">
                <div class="card-header">
                    <span class="card-title">Error Patterns</span>
                </div>
                <table>
                    <thead>
                        <tr>
                            <th>Pattern</th>
                            <th>Count</th>
                            <th>Affected Stages</th>
                            <th>Sample Traces</th>
                        </tr>
                    </thead>
                    <tbody>
                        {{range .Errors.ErrorPatterns}}
                        <tr>
                            <td style="font-family: inherit;">{{.Pattern}}</td>
                            <td>{{.Count}}</td>
                            <td>{{range $i, $s := .AffectedStages}}{{if $i}}, {{end}}{{$s}}{{end}}</td>
                            <td style="font-size: 0.7rem;">{{range $i, $t := .SampleTraceIDs}}{{if $i}}, {{end}}{{$t}}{{end}}</td>
                        </tr>
                        {{end}}
                    </tbody>
                </table>
            </div>
            {{end}}
            {{if .Errors.RecentErrors}}
            <div class="card" style="margin-top: 1rem;">
                <div class="card-header">
                    <span class="card-title">Recent Errors</span>
                </div>
                {{range .Errors.RecentErrors}}
                <div style="padding: 0.75rem; border-bottom: 1px solid var(--border-color); font-size: 0.85rem;">
                    <div style="display: flex; justify-content: space-between; margin-bottom: 0.25rem;">
                        <span style="color: var(--accent-red); font-weight: 600;">{{.Type}}</span>
                        <span style="color: var(--text-muted); font-size: 0.75rem;">{{.Stage}} | trace:{{.TraceID}}</span>
                    </div>
                    <div style="color: var(--text-secondary);">{{.Message}}</div>
                </div>
                {{end}}
            </div>
            {{end}}
        </div>

        <!-- Logs -->
        {{if .Logs.TotalLogCount}}
        <div class="section">
            <h2 class="section-title">Logs</h2>
            <div class="grid">
                <div class="card">
                    <div class="metric">
                        <span class="metric-label">Total Logs</span>
                        <span class="metric-value">{{.Logs.TotalLogCount}}</span>
                    </div>
                    <div class="metric">
                        <span class="metric-label">Warnings/min</span>
                        <span class="metric-value">{{formatFloat .Logs.WarningsPerMin}}</span>
                    </div>
                    <div class="metric">
                        <span class="metric-label">Errors/min</span>
                        <span class="metric-value">{{formatFloat .Logs.ErrorsPerMin}}</span>
                    </div>
                    {{if .Logs.LogsByLevel}}
                    <div style="margin-top: 0.75rem;">
                        {{range $level, $count := .Logs.LogsByLevel}}
                        <span style="display: inline-block; margin-right: 0.75rem; font-size: 0.75rem; padding: 0.2rem 0.4rem; border-radius: 3px; {{if eq $level "ERROR"}}background: rgba(248, 81, 73, 0.2); color: var(--accent-red);{{else if eq $level "WARN"}}background: rgba(210, 153, 34, 0.2); color: var(--accent-yellow);{{else}}background: var(--bg-tertiary); color: var(--text-secondary);{{end}}">{{$level}}: {{$count}}</span>
                        {{end}}
                    </div>
                    {{end}}
                </div>
                <div class="card">
                    <div class="card-header">
                        <span class="card-title">Top Loggers</span>
                    </div>
                    {{range $logger, $count := .Logs.LogsByLogger}}
                    <div class="metric">
                        <span class="metric-label" style="font-size: 0.7rem;">{{$logger}}</span>
                        <span class="metric-value">{{$count}}</span>
                    </div>
                    {{end}}
                </div>
            </div>
            {{if .Logs.LogPatterns}}
            <div class="card" style="margin-top: 1rem;">
                <div class="card-header">
                    <span class="card-title">Log Patterns</span>
                </div>
                <table>
                    <thead>
                        <tr>
                            <th>Level</th>
                            <th>Pattern</th>
                            <th>Count</th>
                            <th>Sample Message</th>
                        </tr>
                    </thead>
                    <tbody>
                        {{range .Logs.LogPatterns}}
                        <tr>
                            <td><span class="tag {{if eq .Level "ERROR"}}tag-bottleneck{{else if eq .Level "WARN"}}tag-backpressure{{else}}tag-ok{{end}}">{{.Level}}</span></td>
                            <td style="font-family: inherit; font-size: 0.8rem;">{{.Pattern}}</td>
                            <td>{{.Count}}</td>
                            <td style="font-family: inherit; font-size: 0.75rem; color: var(--text-secondary);">{{.SampleMsg}}</td>
                        </tr>
                        {{end}}
                    </tbody>
                </table>
            </div>
            {{end}}
            {{if .Logs.RecentLogs}}
            <div class="card" style="margin-top: 1rem;">
                <div class="card-header">
                    <span class="card-title">Recent Logs</span>
                </div>
                {{range .Logs.RecentLogs}}
                <div style="padding: 0.5rem; border-bottom: 1px solid var(--border-color); font-size: 0.8rem;">
                    <div style="display: flex; gap: 0.5rem; align-items: center;">
                        <span class="tag {{if eq .Level "ERROR"}}tag-bottleneck{{else if eq .Level "WARN"}}tag-backpressure{{else}}tag-ok{{end}}" style="font-size: 0.65rem;">{{.Level}}</span>
                        <span style="color: var(--text-muted); font-size: 0.7rem;">{{.Logger}}</span>
                        {{if .TraceID}}<span style="color: var(--accent-blue); font-size: 0.65rem;">trace:{{.TraceID}}</span>{{end}}
                    </div>
                    <div style="color: var(--text-primary); margin-top: 0.25rem;">{{.Message}}</div>
                    {{if .Exception}}
                    <div style="margin-top: 0.5rem; padding: 0.5rem; background: rgba(248, 81, 73, 0.1); border-radius: 4px; font-size: 0.75rem;">
                        <div style="color: var(--accent-red);">{{.Exception.Type}}: {{.Exception.Message}}</div>
                        {{range .Exception.StackTrace}}
                        <div style="color: var(--text-muted); margin-left: 1rem;">{{.}}</div>
                        {{end}}
                    </div>
                    {{end}}
                </div>
                {{end}}
            </div>
            {{end}}
        </div>
        {{end}}

        <!-- Capacity -->
        <div class="section">
            <h2 class="section-title">Capacity Analysis</h2>
            <div class="card">
                <div class="metric">
                    <span class="metric-label">Current Throughput</span>
                    <span class="metric-value">{{formatFloat .Capacity.CurrentThroughput}} events/sec</span>
                </div>
                <div class="metric">
                    <span class="metric-label">Max Observed</span>
                    <span class="metric-value">{{formatFloat .Capacity.MaxObservedThroughput}} events/sec</span>
                </div>
                <div class="metric">
                    <span class="metric-label">Theoretical Max</span>
                    <span class="metric-value">{{formatFloat .Capacity.TheoreticalMaxThroughput}} events/sec</span>
                </div>
                <div class="metric">
                    <span class="metric-label">Headroom</span>
                    <span class="metric-value">{{formatPercent .Capacity.HeadroomPercent}}</span>
                </div>
                {{if .Capacity.LimitingFactors}}
                <table style="margin-top: 1rem;">
                    <thead>
                        <tr>
                            <th>Limiting Factor</th>
                            <th>Impact</th>
                            <th>Current</th>
                            <th>Threshold</th>
                            <th>Unit</th>
                        </tr>
                    </thead>
                    <tbody>
                        {{range .Capacity.LimitingFactors}}
                        <tr>
                            <td>{{.Factor}}</td>
                            <td>{{formatPercent .ImpactPercent}}</td>
                            <td>{{formatFloat .CurrentValue}}</td>
                            <td>{{formatFloat .ThresholdValue}}</td>
                            <td>{{.Unit}}</td>
                        </tr>
                        {{end}}
                    </tbody>
                </table>
                {{end}}
            </div>
        </div>

        <!-- Sampled Traces -->
        {{if .SampledRequests}}
        <div class="section">
            <h2 class="section-title">Sampled Request Traces</h2>
            {{range .SampledRequests}}
            <div class="trace-card">
                <div class="trace-header">
                    <span class="trace-id">{{.TraceID}}</span>
                    <div>
                        <span class="trace-status {{if eq .FinalStatus "SUCCESS"}}success{{else if eq .FinalStatus "TIMEOUT"}}timeout{{else}}error{{end}}">{{.FinalStatus}}</span>
                        <span style="margin-left: 0.5rem; font-size: 1.1rem; font-weight: 600;">{{formatFloat .TotalTimeMs}}ms</span>
                    </div>
                </div>

                <!-- Time Breakdown Visual Bar -->
                {{if .TimeBreakdown}}
                <div style="margin: 1rem 0;">
                    <div style="font-size: 0.75rem; color: var(--text-secondary); margin-bottom: 0.5rem;">Time Breakdown</div>
                    <div class="latency-breakdown">
                        {{range $name, $pct := .TimeBreakdown}}
                        <div class="latency-segment" style="width: {{$pct}}%; background-color: {{if eq $name "cpu"}}#58a6ff{{else if eq $name "queue_wait"}}#d29922{{else if eq $name "gc_pause"}}#f85149{{else if eq $name "network"}}#3fb950{{else if eq $name "serialization"}}#a371f7{{else if eq $name "lock_wait"}}#f778ba{{else}}#6e7681{{end}};" title="{{$name}}: {{printf "%.1f" $pct}}%">
                            {{if gt $pct 8.0}}{{printf "%.0f" $pct}}%{{end}}
                        </div>
                        {{end}}
                    </div>
                    <div class="legend">
                        {{range $name, $pct := .TimeBreakdown}}
                        <div class="legend-item">
                            <div class="legend-color" style="background-color: {{if eq $name "cpu"}}#58a6ff{{else if eq $name "queue_wait"}}#d29922{{else if eq $name "gc_pause"}}#f85149{{else if eq $name "network"}}#3fb950{{else if eq $name "serialization"}}#a371f7{{else if eq $name "lock_wait"}}#f778ba{{else}}#6e7681{{end}};"></div>
                            <span>{{$name}}: {{printf "%.1f" $pct}}%</span>
                        </div>
                        {{end}}
                    </div>
                </div>
                {{end}}

                <!-- Summary Metrics -->
                <div style="display: grid; grid-template-columns: repeat(4, 1fr); gap: 1rem; margin: 1rem 0; padding: 0.75rem; background: var(--bg-secondary); border-radius: 4px;">
                    <div>
                        <div style="font-size: 0.7rem; color: var(--text-muted);">GC Pauses</div>
                        <div style="font-weight: 600; {{if gt .GCPausesEncountered 0}}color: var(--accent-yellow);{{end}}">{{.GCPausesEncountered}}</div>
                    </div>
                    <div>
                        <div style="font-size: 0.7rem; color: var(--text-muted);">Retries</div>
                        <div style="font-weight: 600; {{if gt .Retries 0}}color: var(--accent-yellow);{{end}}">{{.Retries}}</div>
                    </div>
                    <div>
                        <div style="font-size: 0.7rem; color: var(--text-muted);">Spans</div>
                        <div style="font-weight: 600;">{{len .Spans}}</div>
                    </div>
                    <div>
                        <div style="font-size: 0.7rem; color: var(--text-muted);">Logs</div>
                        <div style="font-weight: 600;">{{len .Logs}}</div>
                    </div>
                </div>

                <!-- Span Waterfall -->
                {{if .Spans}}
                <div style="margin-top: 1rem;">
                    <div style="font-size: 0.75rem; color: var(--text-secondary); margin-bottom: 0.5rem;">Span Waterfall</div>
                    <div style="position: relative; padding-left: 1rem;">
                        {{range .Spans}}
                        <div style="display: flex; align-items: center; margin-bottom: 0.5rem; padding: 0.5rem; background: var(--bg-secondary); border-radius: 4px; border-left: 3px solid {{if eq .Status "ERROR"}}var(--accent-red){{else if eq .Status "OK"}}var(--accent-green){{else}}var(--accent-blue){{end}};">
                            <div style="flex: 1; min-width: 0;">
                                <div style="display: flex; align-items: center; gap: 0.5rem;">
                                    <span style="font-weight: 600; font-size: 0.85rem;">{{.Name}}</span>
                                    <span style="font-size: 0.7rem; color: var(--text-muted);">{{.Service}}</span>
                                    {{if eq .Status "ERROR"}}<span class="tag tag-bottleneck" style="font-size: 0.6rem;">ERROR</span>{{end}}
                                </div>
                                {{if .Attributes}}
                                <div style="display: flex; flex-wrap: wrap; gap: 0.5rem; margin-top: 0.25rem;">
                                    {{range $key, $val := .Attributes}}
                                    <span style="font-size: 0.65rem; color: var(--text-muted); background: var(--bg-tertiary); padding: 0.1rem 0.3rem; border-radius: 2px;">{{$key}}={{$val}}</span>
                                    {{end}}
                                </div>
                                {{end}}
                                {{if .Events}}
                                <div style="margin-top: 0.5rem; padding-top: 0.5rem; border-top: 1px dashed var(--border-color);">
                                    {{range .Events}}
                                    <div style="display: flex; align-items: center; gap: 0.5rem; font-size: 0.7rem; margin-bottom: 0.25rem;">
                                        <span style="padding: 0.1rem 0.3rem; border-radius: 2px; {{if eq .Name "exception"}}background: rgba(248, 81, 73, 0.2); color: var(--accent-red);{{else if eq .Name "gc_pause"}}background: rgba(210, 153, 34, 0.2); color: var(--accent-yellow);{{else if eq .Name "retry"}}background: rgba(88, 166, 255, 0.2); color: var(--accent-blue);{{else}}background: var(--bg-tertiary); color: var(--text-secondary);{{end}}">{{.Name}}</span>
                                        {{range $key, $val := .Attributes}}
                                        <span style="color: var(--text-muted);">{{$key}}: {{$val}}</span>
                                        {{end}}
                                    </div>
                                    {{end}}
                                </div>
                                {{end}}
                            </div>
                            <div style="text-align: right; padding-left: 1rem;">
                                <div style="font-weight: 600; font-family: 'SF Mono', Monaco, monospace;">{{formatFloat .DurationMs}}ms</div>
                                <div style="font-size: 0.65rem; color: var(--text-muted);">{{.SpanID}}</div>
                            </div>
                        </div>
                        {{end}}
                    </div>
                </div>
                {{end}}

                <!-- Trace Logs -->
                {{if .Logs}}
                <div style="margin-top: 1rem;">
                    <div style="font-size: 0.75rem; color: var(--text-secondary); margin-bottom: 0.5rem;">Trace Logs</div>
                    <div style="background: var(--bg-secondary); border-radius: 4px; padding: 0.5rem;">
                        {{range .Logs}}
                        <div style="display: flex; gap: 0.5rem; align-items: flex-start; padding: 0.25rem 0; font-size: 0.75rem; border-bottom: 1px solid var(--border-color);">
                            <span class="tag {{if eq .Level "ERROR"}}tag-bottleneck{{else if eq .Level "WARN"}}tag-backpressure{{else}}tag-ok{{end}}" style="font-size: 0.6rem; flex-shrink: 0;">{{.Level}}</span>
                            <span style="color: var(--text-muted); flex-shrink: 0;">{{.ThreadName}}</span>
                            <span style="color: var(--text-primary);">{{.Message}}</span>
                        </div>
                        {{end}}
                    </div>
                </div>
                {{end}}

                <!-- Attributes -->
                {{if .Attributes}}
                <div style="margin-top: 0.75rem; display: flex; flex-wrap: wrap; gap: 0.5rem;">
                    {{range $key, $val := .Attributes}}
                    <span style="font-size: 0.7rem; color: var(--text-muted); background: var(--bg-secondary); padding: 0.2rem 0.5rem; border-radius: 3px;">{{$key}}: {{$val}}</span>
                    {{end}}
                </div>
                {{end}}
            </div>
            {{end}}
        </div>
        {{end}}

        <footer style="text-align: center; margin-top: 3rem; padding-top: 1rem; border-top: 1px solid var(--border-color); color: var(--text-muted);">
            Generated at {{formatTime .TimestampMs}}
        </footer>
    </div>
</body>
</html>`
