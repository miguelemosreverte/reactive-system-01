package diagnostic

import (
	"fmt"
	"strings"
	"time"
)

// FormatComponentDiagnostics formats a DiagnosticSnapshot for console output
func FormatComponentDiagnostics(snapshot DiagnosticSnapshot, comp ComponentInfo) string {
	var sb strings.Builder

	// Component header
	status := getComponentStatus(snapshot, comp)
	statusIcon := getStatusIcon(status)

	sb.WriteString(fmt.Sprintf("\n%s %s (%s)\n", statusIcon, comp.DisplayName, comp.Name))
	sb.WriteString(strings.Repeat("─", 60) + "\n")

	// Status line
	if snapshot.UptimeMs > 0 {
		uptime := formatDurationDisplay(time.Duration(snapshot.UptimeMs) * time.Millisecond)
		sb.WriteString(fmt.Sprintf("  Status: %s │ Uptime: %s\n", status, uptime))
	} else {
		sb.WriteString(fmt.Sprintf("  Status: %s\n", status))
	}

	// Memory section
	sb.WriteString("\n  Memory:\n")
	if snapshot.Memory.HeapUsedBytes > 0 || snapshot.Memory.NativeMemoryUsedBytes > 0 {
		heapMB := float64(snapshot.Memory.HeapUsedBytes) / 1024 / 1024
		heapMaxMB := float64(snapshot.Memory.HeapMaxBytes) / 1024 / 1024
		containerMB := float64(snapshot.Memory.NativeMemoryUsedBytes) / 1024 / 1024

		if snapshot.Memory.HeapUsedBytes > 0 && snapshot.Memory.HeapMaxBytes > 0 {
			heapPct := snapshot.Saturation.HeapUsedPercent
			bar := pressureBarColored(int(heapPct))
			sb.WriteString(fmt.Sprintf("    Heap:      %s %.0f / %.0f MB (%.1f%%)\n", bar, heapMB, heapMaxMB, heapPct))
		}

		if containerMB > 0 {
			containerPct := containerMB / float64(comp.MemoryLimitMB) * 100
			bar := pressureBarColored(int(containerPct))
			sb.WriteString(fmt.Sprintf("    Container: %s %.0f / %d MB (%.1f%%)\n", bar, containerMB, comp.MemoryLimitMB, containerPct))
		}

		// Non-heap if available
		if snapshot.Memory.NonHeapUsedBytes > 0 {
			nonHeapMB := float64(snapshot.Memory.NonHeapUsedBytes) / 1024 / 1024
			sb.WriteString(fmt.Sprintf("    Non-Heap:  %.0f MB\n", nonHeapMB))
		}

		// Direct buffers if available
		if snapshot.Memory.DirectBufferUsedBytes > 0 {
			directMB := float64(snapshot.Memory.DirectBufferUsedBytes) / 1024 / 1024
			sb.WriteString(fmt.Sprintf("    Direct:    %.0f MB\n", directMB))
		}

		// Peak memory from pools
		for _, pool := range snapshot.Memory.MemoryPools {
			if pool.Name == "container_peak" {
				peakMB := float64(pool.UsedBytes) / 1024 / 1024
				peakBar := pressureBarColored(int(pool.UsedPercent))
				sb.WriteString(fmt.Sprintf("    Peak:      %s %.0f / %.0f MB (%.1f%%)\n",
					peakBar, peakMB, float64(pool.MaxBytes)/1024/1024, pool.UsedPercent))
			}
		}
	} else {
		sb.WriteString("    (no data available)\n")
	}

	// GC section (only for JVM)
	if snapshot.GC.YoungGCCount > 0 || snapshot.GC.OldGCCount > 0 {
		sb.WriteString("\n  GC:\n")
		if snapshot.GC.YoungGCCount > 0 {
			avgPause := float64(0)
			if snapshot.GC.YoungGCCount > 0 {
				avgPause = float64(snapshot.GC.YoungGCTimeMs) / float64(snapshot.GC.YoungGCCount)
			}
			sb.WriteString(fmt.Sprintf("    Young GC:  %d times, avg %.1fms\n", snapshot.GC.YoungGCCount, avgPause))
		}
		if snapshot.GC.OldGCCount > 0 {
			avgPause := float64(0)
			if snapshot.GC.OldGCCount > 0 {
				avgPause = float64(snapshot.GC.OldGCTimeMs) / float64(snapshot.GC.OldGCCount)
			}
			sb.WriteString(fmt.Sprintf("    Old GC:    %d times, avg %.1fms\n", snapshot.GC.OldGCCount, avgPause))
		}
		if snapshot.GC.GCFrequencyPerMin > 0 {
			sb.WriteString(fmt.Sprintf("    GC Rate:   %.1f/min\n", snapshot.GC.GCFrequencyPerMin))
		}
	}

	// Threads section
	if snapshot.Saturation.ThreadPoolActive > 0 {
		sb.WriteString("\n  Threads:\n")
		sb.WriteString(fmt.Sprintf("    Active:    %d", snapshot.Saturation.ThreadPoolActive))
		if snapshot.Saturation.ThreadPoolMax > 0 {
			sb.WriteString(fmt.Sprintf(" / %d", snapshot.Saturation.ThreadPoolMax))
		}
		sb.WriteString("\n")
	}

	// CPU section
	if snapshot.Saturation.CPUPercent > 0 {
		sb.WriteString("\n  CPU:\n")
		cpuBar := pressureBarColored(int(snapshot.Saturation.CPUPercent))
		sb.WriteString(fmt.Sprintf("    Usage:     %s %.1f%%\n", cpuBar, snapshot.Saturation.CPUPercent))
	}

	// Throughput section
	if snapshot.Throughput.EventsPerSecond > 0 || snapshot.Throughput.DroppedCount > 0 {
		sb.WriteString("\n  Throughput:\n")
		if snapshot.Throughput.EventsPerSecond > 0 {
			sb.WriteString(fmt.Sprintf("    Events:    %.0f/s\n", snapshot.Throughput.EventsPerSecond))
		}
		if snapshot.Throughput.DroppedCount > 0 {
			sb.WriteString(fmt.Sprintf("    Dropped:   %d (⚠)\n", snapshot.Throughput.DroppedCount))
		}
		if snapshot.Throughput.QueueDepth > 0 {
			sb.WriteString(fmt.Sprintf("    Queue:     %d\n", snapshot.Throughput.QueueDepth))
		}
	}

	// Crash Risk section
	if snapshot.Errors.CrashIndicators.OOMRisk ||
		snapshot.Errors.CrashIndicators.GCThrashing ||
		snapshot.Errors.CrashIndicators.MemoryLeakSuspected ||
		snapshot.Errors.CrashIndicators.ThreadExhaustionRisk {
		sb.WriteString("\n  ⚠ RISK INDICATORS:\n")
		if snapshot.Errors.CrashIndicators.OOMRisk {
			sb.WriteString(fmt.Sprintf("    • OOM Risk: %.1f%% memory used\n", snapshot.Errors.CrashIndicators.OOMRiskPercent))
		}
		if snapshot.Errors.CrashIndicators.GCThrashing {
			sb.WriteString("    • GC Thrashing detected (frequent full GCs)\n")
		}
		if snapshot.Errors.CrashIndicators.MemoryLeakSuspected {
			sb.WriteString("    • Memory leak suspected (high heap + many full GCs)\n")
		}
		if snapshot.Errors.CrashIndicators.ThreadExhaustionRisk {
			sb.WriteString("    • Thread exhaustion risk (>90% threads in use)\n")
		}
	}

	return sb.String()
}

// FormatPlatformDiagnostics formats all component diagnostics
func FormatPlatformDiagnostics(snapshots []DiagnosticSnapshot) string {
	var sb strings.Builder

	sb.WriteString("\n")
	sb.WriteString("╔══════════════════════════════════════════════════════════════════╗\n")
	sb.WriteString("║              PLATFORM COMPONENT DIAGNOSTICS                      ║\n")
	sb.WriteString("║              " + time.Now().Format("2006-01-02 15:04:05") + "                               ║\n")
	sb.WriteString("╚══════════════════════════════════════════════════════════════════╝\n")

	// Summary section
	components := PlatformComponents()
	healthy := 0
	warning := 0
	critical := 0
	crashed := 0

	for i, snap := range snapshots {
		status := getComponentStatus(snap, components[i])
		switch status {
		case "healthy":
			healthy++
		case "warning":
			warning++
		case "critical":
			critical++
		case "crashed", "oom":
			crashed++
		}
	}

	sb.WriteString(fmt.Sprintf("\nSummary: %d healthy, %d warning, %d critical, %d crashed\n",
		healthy, warning, critical, crashed))

	// Quick view table
	sb.WriteString("\n┌────────────────────┬──────────┬────────────────┬────────────────┬─────────┐\n")
	sb.WriteString("│ Component          │ Status   │ Memory         │ Peak           │ CPU     │\n")
	sb.WriteString("├────────────────────┼──────────┼────────────────┼────────────────┼─────────┤\n")

	for i, snap := range snapshots {
		comp := components[i]
		status := getComponentStatus(snap, comp)
		statusIcon := getStatusIcon(status)

		memStr := "─"
		peakStr := "─"
		cpuStr := "─"

		// Memory
		if snap.Memory.NativeMemoryUsedBytes > 0 {
			containerMB := float64(snap.Memory.NativeMemoryUsedBytes) / 1024 / 1024
			containerPct := containerMB / float64(comp.MemoryLimitMB) * 100
			memStr = fmt.Sprintf("%.0f MB (%.0f%%)", containerMB, containerPct)
		} else if snap.Saturation.HeapUsedPercent > 0 {
			memStr = fmt.Sprintf("%.0f%%", snap.Saturation.HeapUsedPercent)
		}

		// Peak
		for _, pool := range snap.Memory.MemoryPools {
			if pool.Name == "container_peak" {
				peakMB := float64(pool.UsedBytes) / 1024 / 1024
				peakStr = fmt.Sprintf("%.0f MB (%.0f%%)", peakMB, pool.UsedPercent)
			}
		}

		// CPU
		if snap.Saturation.CPUPercent > 0 {
			cpuStr = fmt.Sprintf("%.1f%%", snap.Saturation.CPUPercent)
		}

		sb.WriteString(fmt.Sprintf("│ %s %-16s │ %-8s │ %-14s │ %-14s │ %-7s │\n",
			statusIcon, truncateString(comp.DisplayName, 16), status, memStr, peakStr, cpuStr))
	}

	sb.WriteString("└────────────────────┴──────────┴────────────────┴────────────────┴─────────┘\n")

	// Detailed view for problematic components
	hasProblems := false
	for i, snap := range snapshots {
		comp := components[i]
		status := getComponentStatus(snap, comp)
		if status != "healthy" {
			if !hasProblems {
				sb.WriteString("\n" + strings.Repeat("═", 60) + "\n")
				sb.WriteString("  COMPONENTS REQUIRING ATTENTION\n")
				sb.WriteString(strings.Repeat("═", 60) + "\n")
				hasProblems = true
			}
			sb.WriteString(FormatComponentDiagnostics(snap, comp))
		}
	}

	// Recommendations section
	recommendations := generateRecommendations(snapshots, components)
	if len(recommendations) > 0 {
		sb.WriteString("\n" + strings.Repeat("═", 60) + "\n")
		sb.WriteString("  RECOMMENDATIONS\n")
		sb.WriteString(strings.Repeat("═", 60) + "\n\n")
		for i, rec := range recommendations {
			sb.WriteString(fmt.Sprintf("  %d. %s\n", i+1, rec))
		}
	}

	return sb.String()
}

// getComponentStatus determines the overall status of a component
func getComponentStatus(snap DiagnosticSnapshot, comp ComponentInfo) string {
	// Check for crash
	if snap.Errors.CrashIndicators.OOMRiskPercent >= 100 {
		return "oom"
	}

	// Check container status via uptime
	if snap.UptimeMs == 0 && snap.Memory.HeapUsedBytes == 0 && snap.Memory.NativeMemoryUsedBytes == 0 {
		if snap.Errors.CrashIndicators.OOMRisk {
			return "oom"
		}
		return "crashed"
	}

	// Check crash indicators
	if snap.Errors.CrashIndicators.OOMRisk ||
		snap.Errors.CrashIndicators.GCThrashing ||
		snap.Errors.CrashIndicators.ThreadExhaustionRisk {
		return "critical"
	}

	// Check memory pressure
	memPct := snap.Saturation.HeapUsedPercent
	if memPct == 0 && snap.Memory.NativeMemoryUsedBytes > 0 {
		memPct = float64(snap.Memory.NativeMemoryUsedBytes) / float64(comp.MemoryLimitMB*1024*1024) * 100
	}

	// Check peak memory
	for _, pool := range snap.Memory.MemoryPools {
		if pool.Name == "container_peak" && pool.UsedPercent > memPct {
			memPct = pool.UsedPercent
		}
	}

	if memPct > 90 {
		return "critical"
	} else if memPct > 75 {
		return "warning"
	}

	return "healthy"
}

// getStatusIcon returns an icon for the status
func getStatusIcon(status string) string {
	switch status {
	case "healthy":
		return "✓"
	case "warning":
		return "⚠"
	case "critical":
		return "✗"
	case "crashed", "oom":
		return "☠"
	default:
		return "?"
	}
}

// pressureBarColored returns a colored pressure bar
func pressureBarColored(pct int) string {
	width := 10
	filled := pct * width / 100
	if filled > width {
		filled = width
	}
	if filled < 0 {
		filled = 0
	}
	return "[" + strings.Repeat("▓", filled) + strings.Repeat("░", width-filled) + "]"
}

// formatDurationDisplay formats a duration for display
func formatDurationDisplay(d time.Duration) string {
	if d < time.Minute {
		return fmt.Sprintf("%.0fs", d.Seconds())
	} else if d < time.Hour {
		return fmt.Sprintf("%.0fm", d.Minutes())
	} else if d < 24*time.Hour {
		return fmt.Sprintf("%.1fh", d.Hours())
	}
	return fmt.Sprintf("%.1fd", d.Hours()/24)
}

// truncateString truncates a string to a maximum length
func truncateString(s string, max int) string {
	if len(s) <= max {
		return s
	}
	return s[:max-1] + "…"
}

// generateRecommendations creates actionable recommendations based on diagnostics
func generateRecommendations(snapshots []DiagnosticSnapshot, components []ComponentInfo) []string {
	var recs []string

	for i, snap := range snapshots {
		comp := components[i]

		// OOM detected or risk
		if snap.Errors.CrashIndicators.OOMRisk || snap.Errors.CrashIndicators.OOMRiskPercent >= 100 {
			currentMB := comp.MemoryLimitMB
			suggestedMB := int64(float64(currentMB) * 1.5)
			if suggestedMB < currentMB+128 {
				suggestedMB = currentMB + 128
			}
			recs = append(recs, fmt.Sprintf("Increase %s memory: %d MB → %d MB",
				comp.Name, currentMB, suggestedMB))
		}

		// GC Thrashing
		if snap.Errors.CrashIndicators.GCThrashing {
			recs = append(recs, fmt.Sprintf("%s: GC thrashing detected - increase heap size or investigate memory usage pattern",
				comp.Name))
		}

		// Memory leak suspected
		if snap.Errors.CrashIndicators.MemoryLeakSuspected {
			recs = append(recs, fmt.Sprintf("%s: Memory leak suspected - take heap dump for analysis (./reactive memory heap %s)",
				comp.Name, comp.Name))
		}

		// High dropped count for otel
		if snap.Throughput.DroppedCount > 0 && comp.Name == "otel-collector" {
			recs = append(recs, fmt.Sprintf("OTEL Collector is dropping spans (%d) - increase memory or reduce sampling rate",
				snap.Throughput.DroppedCount))
		}
	}

	return recs
}
