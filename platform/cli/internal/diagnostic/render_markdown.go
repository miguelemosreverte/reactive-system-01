package diagnostic

import (
	"fmt"
	"io"
	"sort"
	"strings"
	"time"
)

// MarkdownRenderer outputs diagnostic data as Markdown for AI analysis
type MarkdownRenderer struct{}

// NewMarkdownRenderer creates a new Markdown renderer
func NewMarkdownRenderer() *MarkdownRenderer {
	return &MarkdownRenderer{}
}

// Render outputs the diagnostic snapshot as Markdown
func (r *MarkdownRenderer) Render(w io.Writer, s *DiagnosticSnapshot) error {
	var b strings.Builder

	// Header
	b.WriteString(fmt.Sprintf("# Diagnostic Report: %s\n\n", s.Component))
	b.WriteString(fmt.Sprintf("**Instance:** %s  \n", s.InstanceID))
	b.WriteString(fmt.Sprintf("**Timestamp:** %s  \n", time.UnixMilli(s.TimestampMs).UTC().Format(time.RFC3339)))
	b.WriteString(fmt.Sprintf("**Uptime:** %s  \n", formatDuration(s.UptimeMs)))
	b.WriteString(fmt.Sprintf("**Version:** %s\n\n", s.Version))

	// Quick Summary (pure data, no interpretation)
	b.WriteString("## Summary\n\n")
	b.WriteString(fmt.Sprintf("**Throughput:** %.1f events/sec (%.1f%% of capacity)  \n",
		s.Throughput.EventsPerSecond, s.Throughput.CapacityUtilizationPct))
	b.WriteString(fmt.Sprintf("**Latency:** P50=%.1fms, P99=%.1fms, Max=%.1fms  \n",
		s.Latency.TotalMsP50, s.Latency.TotalMsP99, s.Latency.TotalMsMax))
	b.WriteString(fmt.Sprintf("**Memory:** %.1f%% heap, %.1f%% old gen  \n",
		s.Saturation.HeapUsedPercent, s.Saturation.OldGenPercent))
	b.WriteString(fmt.Sprintf("**Errors:** %d total (%.2f%% rate)  \n",
		s.Errors.TotalErrorCount, s.Errors.ErrorRatePercent))
	b.WriteString("\n")

	// Core Metrics
	b.WriteString("## Core Metrics\n\n")
	b.WriteString("### Throughput\n\n")
	b.WriteString("| Metric | Value | Capacity | Utilization |\n")
	b.WriteString("|--------|-------|----------|-------------|\n")
	b.WriteString(fmt.Sprintf("| Events/sec | %.1f | %.1f | %.1f%% |\n",
		s.Throughput.EventsPerSecond, s.Throughput.EventsPerSecondCapacity, s.Throughput.CapacityUtilizationPct))
	b.WriteString(fmt.Sprintf("| Bytes/sec | %s | - | - |\n", formatBytes(s.Throughput.BytesPerSecond)))
	b.WriteString(fmt.Sprintf("| Batches/sec | %.1f | - | %.1f%% efficiency |\n",
		s.Throughput.BatchesPerSecond, s.Throughput.BatchEfficiencyPct))
	b.WriteString(fmt.Sprintf("| Queue Depth | %d | %d | %.1f%% |\n",
		s.Throughput.QueueDepth, s.Throughput.QueueCapacity,
		float64(s.Throughput.QueueDepth)/float64(s.Throughput.QueueCapacity)*100))
	b.WriteString("\n")

	if s.Throughput.RejectedCount > 0 || s.Throughput.DroppedCount > 0 {
		b.WriteString(fmt.Sprintf("⚠️ **Rejected:** %d, **Dropped:** %d\n\n",
			s.Throughput.RejectedCount, s.Throughput.DroppedCount))
	}

	b.WriteString("### Latency Distribution\n\n")
	b.WriteString("| Percentile | Value |\n")
	b.WriteString("|------------|-------|\n")
	b.WriteString(fmt.Sprintf("| P50 | %.1fms |\n", s.Latency.TotalMsP50))
	b.WriteString(fmt.Sprintf("| P95 | %.1fms |\n", s.Latency.TotalMsP95))
	b.WriteString(fmt.Sprintf("| P99 | %.1fms |\n", s.Latency.TotalMsP99))
	b.WriteString(fmt.Sprintf("| Max | %.1fms |\n", s.Latency.TotalMsMax))
	b.WriteString("\n")

	b.WriteString("### Latency Breakdown\n\n")
	b.WriteString("| Component | Time (ms) | Percentage |\n")
	b.WriteString("|-----------|-----------|------------|\n")

	// Sort breakdown by percentage descending
	type breakdownEntry struct {
		name    string
		percent float64
	}
	var entries []breakdownEntry
	for name, pct := range s.Latency.BreakdownPercent {
		entries = append(entries, breakdownEntry{name, pct})
	}
	sort.Slice(entries, func(i, j int) bool {
		return entries[i].percent > entries[j].percent
	})
	for _, e := range entries {
		b.WriteString(fmt.Sprintf("| %s | - | %.1f%% |\n", e.name, e.percent))
	}
	b.WriteString("\n")

	// Resource Saturation
	b.WriteString("## Resource Saturation\n\n")
	b.WriteString("| Resource | Used | Max | Utilization |\n")
	b.WriteString("|----------|------|-----|-------------|\n")
	b.WriteString(fmt.Sprintf("| CPU | - | - | %.1f%% |\n", s.Saturation.CPUPercent))
	b.WriteString(fmt.Sprintf("| Heap | %s | %s | %.1f%% |\n",
		formatBytes(s.Saturation.HeapUsedBytes), formatBytes(s.Saturation.HeapMaxBytes), s.Saturation.HeapUsedPercent))
	b.WriteString(fmt.Sprintf("| Old Gen | %s | %s | %.1f%% |\n",
		formatBytes(s.Saturation.OldGenBytes), formatBytes(s.Saturation.OldGenMaxBytes), s.Saturation.OldGenPercent))
	b.WriteString(fmt.Sprintf("| Thread Pool | %d | %d | %.1f%% |\n",
		s.Saturation.ThreadPoolActive, s.Saturation.ThreadPoolMax,
		float64(s.Saturation.ThreadPoolActive)/float64(s.Saturation.ThreadPoolMax)*100))
	b.WriteString(fmt.Sprintf("| Connection Pool | %d | %d | %.1f%% |\n",
		s.Saturation.ConnectionPoolActive, s.Saturation.ConnectionPoolMax,
		float64(s.Saturation.ConnectionPoolActive)/float64(s.Saturation.ConnectionPoolMax)*100))
	b.WriteString(fmt.Sprintf("| Buffer Pool | - | - | %.1f%% |\n", s.Saturation.BufferPoolUsedPercent))
	b.WriteString(fmt.Sprintf("| File Descriptors | %d | %d | %.1f%% |\n",
		s.Saturation.FileDescriptorsUsed, s.Saturation.FileDescriptorsMax,
		float64(s.Saturation.FileDescriptorsUsed)/float64(s.Saturation.FileDescriptorsMax)*100))
	b.WriteString("\n")

	// Trends
	b.WriteString("## Trends (Rate of Change)\n\n")
	b.WriteString("| Metric | Trend |\n")
	b.WriteString("|--------|-------|\n")
	b.WriteString(fmt.Sprintf("| Throughput | %+.2f |\n", s.Trends.ThroughputTrend))
	b.WriteString(fmt.Sprintf("| Latency | %+.2f |\n", s.Trends.LatencyTrend))
	b.WriteString(fmt.Sprintf("| Queue Depth | %+.2f |\n", s.Trends.QueueDepthTrend))
	b.WriteString(fmt.Sprintf("| Error Rate | %+.2f |\n", s.Trends.ErrorRateTrend))
	b.WriteString(fmt.Sprintf("| Heap Growth | %s/sec |\n", formatBytes(s.Trends.HeapGrowthBytesPerSec)))
	b.WriteString(fmt.Sprintf("| Old Gen Growth | %s/sec |\n", formatBytes(s.Trends.OldGenGrowthBytesPerSec)))
	b.WriteString("\n")

	if s.Trends.EstimatedHeapExhaustionSec > 0 {
		b.WriteString(fmt.Sprintf("**Estimated heap exhaustion:** %s\n", formatDuration(s.Trends.EstimatedHeapExhaustionSec*1000)))
	}
	if s.Trends.EstimatedOldGenExhaustionSec > 0 {
		b.WriteString(fmt.Sprintf("**Estimated old gen exhaustion:** %s\n", formatDuration(s.Trends.EstimatedOldGenExhaustionSec*1000)))
	}
	b.WriteString("\n")

	// Contention
	b.WriteString("## Contention Analysis\n\n")
	b.WriteString(fmt.Sprintf("- **Lock Contention:** %.1f%%\n", s.Contention.LockContentionPercent))
	b.WriteString(fmt.Sprintf("- **Blocked Threads:** %d\n", s.Contention.BlockedThreads))
	b.WriteString(fmt.Sprintf("- **Waiting Threads:** %d\n", s.Contention.WaitingThreads))
	b.WriteString(fmt.Sprintf("- **I/O Wait:** %.1f%%\n", s.Contention.IOWaitPercent))
	b.WriteString(fmt.Sprintf("- **Queue Blocking:** %.1f%%\n", s.Contention.QueueBlockingPercent))
	if s.Contention.DeadlockDetected {
		b.WriteString("\n🚨 **DEADLOCK DETECTED**\n")
	}
	b.WriteString("\n")

	if len(s.Contention.MostContendedLocks) > 0 {
		b.WriteString("### Most Contended Locks\n\n")
		b.WriteString("| Lock | Contention | Wait Count | Avg Wait |\n")
		b.WriteString("|------|------------|------------|----------|\n")
		for _, lock := range s.Contention.MostContendedLocks {
			b.WriteString(fmt.Sprintf("| %s | %.1f%% | %d | %.1fms |\n",
				lock.Name, lock.ContentionPercent, lock.WaitCount, lock.AvgWaitMs))
		}
		b.WriteString("\n")
	}

	// GC Analysis
	b.WriteString("## Garbage Collection\n\n")
	b.WriteString(fmt.Sprintf("- **Algorithm:** %s\n", s.GC.GCAlgorithm))
	b.WriteString(fmt.Sprintf("- **GC Overhead:** %.1f%%\n", s.GC.GCOverheadPercent))
	b.WriteString(fmt.Sprintf("- **GC CPU:** %.1f%%\n", s.GC.GCCPUPercent))
	b.WriteString(fmt.Sprintf("- **Frequency:** %.1f/min\n", s.GC.GCFrequencyPerMin))
	b.WriteString(fmt.Sprintf("- **Consecutive Full GCs:** %d\n", s.GC.ConsecutiveFullGCs))
	b.WriteString("\n")

	b.WriteString("| GC Type | Count | Total Time | Avg Pause | Max Pause |\n")
	b.WriteString("|---------|-------|------------|-----------|----------|\n")
	b.WriteString(fmt.Sprintf("| Young | %d | %dms | %.1fms | - |\n",
		s.GC.YoungGCCount, s.GC.YoungGCTimeMs, s.GC.AvgYoungGCPauseMs))
	b.WriteString(fmt.Sprintf("| Old | %d | %dms | %.1fms | %.1fms |\n",
		s.GC.OldGCCount, s.GC.OldGCTimeMs, s.GC.AvgOldGCPauseMs, s.GC.MaxGCPauseMs))
	b.WriteString("\n")

	// Pipeline Stages
	if len(s.Stages) > 0 {
		b.WriteString("## Pipeline Stages\n\n")
		b.WriteString("| Stage | Events/sec | P50 | P99 | Queue | Saturation | Status |\n")
		b.WriteString("|-------|------------|-----|-----|-------|------------|--------|\n")
		for _, stage := range s.Stages {
			status := "✓"
			if stage.IsBottleneck {
				status = "🔥 BOTTLENECK"
			} else if stage.BackpressureApplied {
				status = "⚠️ Backpressure"
			}
			b.WriteString(fmt.Sprintf("| %s | %.1f | %.1fms | %.1fms | %d | %.1f%% | %s |\n",
				stage.Name, stage.EventsPerSecond, stage.LatencyMsP50, stage.LatencyMsP99,
				stage.QueueDepth, stage.SaturationPercent, status))
		}
		b.WriteString("\n")
	}

	// Dependencies
	if len(s.Dependencies) > 0 {
		b.WriteString("## Dependencies\n\n")
		b.WriteString("| Dependency | Type | P50 | P99 | Calls/sec | Errors | Circuit | Saturation |\n")
		b.WriteString("|------------|------|-----|-----|-----------|--------|---------|------------|\n")
		for _, dep := range s.Dependencies {
			b.WriteString(fmt.Sprintf("| %s | %s | %.1fms | %.1fms | %.1f | %.2f%% | %s | %.1f%% |\n",
				dep.Name, dep.Type, dep.LatencyMsP50, dep.LatencyMsP99,
				dep.CallsPerSecond, dep.ErrorRatePercent, dep.CircuitBreakerState, dep.SaturationPercent))
		}
		b.WriteString("\n")
	}

	// Errors
	b.WriteString("## Errors\n\n")
	b.WriteString(fmt.Sprintf("- **Total Errors:** %d\n", s.Errors.TotalErrorCount))
	b.WriteString(fmt.Sprintf("- **Error Rate:** %.2f%%\n", s.Errors.ErrorRatePercent))
	b.WriteString("\n")

	if len(s.Errors.ErrorsByType) > 0 {
		b.WriteString("### Errors by Type\n\n")
		b.WriteString("| Error Type | Count |\n")
		b.WriteString("|------------|-------|\n")
		for errType, count := range s.Errors.ErrorsByType {
			b.WriteString(fmt.Sprintf("| %s | %d |\n", errType, count))
		}
		b.WriteString("\n")
	}

	// Crash Indicators
	b.WriteString("### Crash Risk Indicators\n\n")
	ci := s.Errors.CrashIndicators
	b.WriteString(fmt.Sprintf("- **OOM Risk:** %v (%.1f%%)\n", ci.OOMRisk, ci.OOMRiskPercent))
	b.WriteString(fmt.Sprintf("- **GC Thrashing:** %v\n", ci.GCThrashing))
	b.WriteString(fmt.Sprintf("- **Memory Leak Suspected:** %v\n", ci.MemoryLeakSuspected))
	b.WriteString(fmt.Sprintf("- **Thread Exhaustion Risk:** %v\n", ci.ThreadExhaustionRisk))
	b.WriteString("\n")

	// Capacity
	b.WriteString("## Capacity Analysis\n\n")
	b.WriteString(fmt.Sprintf("- **Current Throughput:** %.1f events/sec\n", s.Capacity.CurrentThroughput))
	b.WriteString(fmt.Sprintf("- **Max Observed:** %.1f events/sec\n", s.Capacity.MaxObservedThroughput))
	b.WriteString(fmt.Sprintf("- **Theoretical Max:** %.1f events/sec\n", s.Capacity.TheoreticalMaxThroughput))
	b.WriteString(fmt.Sprintf("- **Headroom:** %.1f%%\n", s.Capacity.HeadroomPercent))
	b.WriteString("\n")

	if len(s.Capacity.LimitingFactors) > 0 {
		b.WriteString("### Limiting Factors\n\n")
		b.WriteString("| Factor | Impact | Current | Threshold | Unit |\n")
		b.WriteString("|--------|--------|---------|-----------|------|\n")
		for _, lf := range s.Capacity.LimitingFactors {
			b.WriteString(fmt.Sprintf("| %s | %.1f%% | %.1f | %.1f | %s |\n",
				lf.Factor, lf.ImpactPercent, lf.CurrentValue, lf.ThresholdValue, lf.Unit))
		}
		b.WriteString("\n")
	}

	// Historical Trend
	if len(s.History) > 0 {
		b.WriteString("## Recent History\n\n")
		b.WriteString("| Time | Throughput | Avg Latency | Max Latency | Errors | Heap |\n")
		b.WriteString("|------|------------|-------------|-------------|--------|------|\n")
		for _, h := range s.History {
			t := time.UnixMilli(h.BucketStartMs).UTC().Format("15:04:05")
			b.WriteString(fmt.Sprintf("| %s | %.1f | %.1fms | %.1fms | %d | %.1f%% |\n",
				t, h.AvgThroughput, h.AvgLatencyMs, h.MaxLatencyMs, h.ErrorCount, h.HeapUsedAvgPercent))
		}
		b.WriteString("\n")
	}

	// Sampled Requests
	if len(s.SampledRequests) > 0 {
		b.WriteString("## Sampled Request Traces\n\n")
		for _, req := range s.SampledRequests {
			b.WriteString(fmt.Sprintf("### Trace: %s\n\n", req.TraceID))
			b.WriteString(fmt.Sprintf("- **Total Time:** %.1fms\n", req.TotalTimeMs))
			b.WriteString(fmt.Sprintf("- **Status:** %s\n", req.FinalStatus))
			b.WriteString(fmt.Sprintf("- **GC Pauses:** %d\n", req.GCPausesEncountered))
			b.WriteString(fmt.Sprintf("- **Retries:** %d\n", req.Retries))
			b.WriteString("\n**Time Breakdown:**\n")
			for component, ms := range req.TimeBreakdown {
				pct := (ms / req.TotalTimeMs) * 100
				b.WriteString(fmt.Sprintf("- %s: %.1fms (%.1f%%)\n", component, ms, pct))
			}
			b.WriteString("\n")
		}
	}

	_, err := w.Write([]byte(b.String()))
	return err
}

func formatDuration(ms int64) string {
	d := time.Duration(ms) * time.Millisecond
	if d < time.Minute {
		return fmt.Sprintf("%.1fs", d.Seconds())
	}
	if d < time.Hour {
		return fmt.Sprintf("%.1fm", d.Minutes())
	}
	if d < 24*time.Hour {
		return fmt.Sprintf("%.1fh", d.Hours())
	}
	return fmt.Sprintf("%.1fd", d.Hours()/24)
}

func formatBytes(b int64) string {
	const unit = 1024
	if b < unit {
		return fmt.Sprintf("%d B", b)
	}
	div, exp := int64(unit), 0
	for n := b / unit; n >= unit; n /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %cB", float64(b)/float64(div), "KMGTPE"[exp])
}

