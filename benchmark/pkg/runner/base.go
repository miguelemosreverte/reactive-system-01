package runner

import (
	"sort"
	"sync"
	"sync/atomic"
	"time"

	"github.com/reactive/benchmark/pkg/types"
	"github.com/shirou/gopsutil/v3/cpu"
	"github.com/shirou/gopsutil/v3/mem"
)

// BaseRunner provides common benchmark functionality
type BaseRunner struct {
	id          types.ComponentID
	name        string
	description string

	running atomic.Bool
	stopCh  chan struct{}
	mu      sync.Mutex

	// Tracking
	latencies         []int64
	throughputSamples []int64
	cpuSamples        []float64
	memorySamples     []float64
	successCount      atomic.Int64
	failCount         atomic.Int64
	sampleEvents      []types.SampleEvent
	sampleEventsMu    sync.Mutex
}

// NewBaseRunner creates a new base runner
func NewBaseRunner(id types.ComponentID, name, description string) *BaseRunner {
	return &BaseRunner{
		id:          id,
		name:        name,
		description: description,
		stopCh:      make(chan struct{}),
	}
}

func (b *BaseRunner) ID() types.ComponentID   { return b.id }
func (b *BaseRunner) Name() string            { return b.name }
func (b *BaseRunner) Description() string     { return b.description }
func (b *BaseRunner) IsRunning() bool         { return b.running.Load() }

func (b *BaseRunner) Stop() {
	if b.running.CompareAndSwap(true, false) {
		close(b.stopCh)
	}
}

func (b *BaseRunner) Reset() {
	b.mu.Lock()
	defer b.mu.Unlock()

	b.latencies = nil
	b.throughputSamples = nil
	b.cpuSamples = nil
	b.memorySamples = nil
	b.successCount.Store(0)
	b.failCount.Store(0)
	b.sampleEvents = nil
	b.stopCh = make(chan struct{})
}

func (b *BaseRunner) RecordLatency(latencyMs int64) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.latencies = append(b.latencies, latencyMs)
}

func (b *BaseRunner) RecordSuccess() {
	b.successCount.Add(1)
}

func (b *BaseRunner) RecordFailure() {
	b.failCount.Add(1)
}

func (b *BaseRunner) AddSampleEvent(event types.SampleEvent) {
	b.sampleEventsMu.Lock()
	defer b.sampleEventsMu.Unlock()

	// Keep only the LAST N samples (most recent) so traces are still in Jaeger when we fetch
	if event.Status == "success" {
		// Collect all success samples
		var successSamples []types.SampleEvent
		var otherSamples []types.SampleEvent
		for _, e := range b.sampleEvents {
			if e.Status == "success" {
				successSamples = append(successSamples, e)
			} else {
				otherSamples = append(otherSamples, e)
			}
		}

		// Add new success sample
		successSamples = append(successSamples, event)

		// Keep only the last 2 success samples (most recent)
		if len(successSamples) > 2 {
			successSamples = successSamples[len(successSamples)-2:]
		}

		// Rebuild sample events
		b.sampleEvents = append(otherSamples, successSamples...)
	} else {
		// Collect all error samples
		var errorSamples []types.SampleEvent
		var otherSamples []types.SampleEvent
		for _, e := range b.sampleEvents {
			if e.Status != "success" {
				errorSamples = append(errorSamples, e)
			} else {
				otherSamples = append(otherSamples, e)
			}
		}

		// Add new error sample
		errorSamples = append(errorSamples, event)

		// Keep only the last 10 error samples (most recent)
		if len(errorSamples) > 10 {
			errorSamples = errorSamples[len(errorSamples)-10:]
		}

		// Rebuild sample events
		b.sampleEvents = append(otherSamples, errorSamples...)
	}
}

func (b *BaseRunner) GetOperationCount() int64 {
	return b.successCount.Load() + b.failCount.Load()
}

func (b *BaseRunner) SampleResources() (cpuPercent, memPercent float64) {
	cpuPercentages, err := cpu.Percent(0, false)
	if err == nil && len(cpuPercentages) > 0 {
		cpuPercent = cpuPercentages[0]
	}

	memInfo, err := mem.VirtualMemory()
	if err == nil {
		memPercent = memInfo.UsedPercent
	}

	return cpuPercent, memPercent
}

func (b *BaseRunner) RecordResourceSample(cpuPercent, memPercent float64) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.cpuSamples = append(b.cpuSamples, cpuPercent)
	b.memorySamples = append(b.memorySamples, memPercent)
}

func (b *BaseRunner) RecordThroughputSample(throughput int64) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.throughputSamples = append(b.throughputSamples, throughput)
}

func (b *BaseRunner) BuildResult(startTime time.Time, status string, errorMessage string) *types.Result {
	endTime := time.Now()
	durationMs := endTime.Sub(startTime).Milliseconds()

	b.mu.Lock()
	latencies := make([]int64, len(b.latencies))
	copy(latencies, b.latencies)
	throughputSamples := make([]int64, len(b.throughputSamples))
	copy(throughputSamples, b.throughputSamples)
	cpuSamples := make([]float64, len(b.cpuSamples))
	copy(cpuSamples, b.cpuSamples)
	memorySamples := make([]float64, len(b.memorySamples))
	copy(memorySamples, b.memorySamples)
	b.mu.Unlock()

	b.sampleEventsMu.Lock()
	sampleEvents := make([]types.SampleEvent, len(b.sampleEvents))
	copy(sampleEvents, b.sampleEvents)
	b.sampleEventsMu.Unlock()

	// Calculate latency stats
	latencyStats := calculateLatencyStats(latencies)

	// Calculate throughput
	var peakThroughput, avgThroughput int64
	if len(throughputSamples) > 0 {
		for _, t := range throughputSamples {
			if t > peakThroughput {
				peakThroughput = t
			}
			avgThroughput += t
		}
		avgThroughput /= int64(len(throughputSamples))
	} else if durationMs > 0 {
		avgThroughput = (b.successCount.Load() + b.failCount.Load()) * 1000 / durationMs
	}

	// Calculate resource stats
	var peakCPU, avgCPU, peakMemory, avgMemory float64
	if len(cpuSamples) > 0 {
		for _, c := range cpuSamples {
			if c > peakCPU {
				peakCPU = c
			}
			avgCPU += c
		}
		avgCPU /= float64(len(cpuSamples))
	}
	if len(memorySamples) > 0 {
		for _, m := range memorySamples {
			if m > peakMemory {
				peakMemory = m
			}
			avgMemory += m
		}
		avgMemory /= float64(len(memorySamples))
	}

	return &types.Result{
		Component:            b.id,
		Name:                 b.name,
		Description:          b.description,
		StartTime:            startTime,
		EndTime:              endTime,
		DurationMs:           durationMs,
		TotalOperations:      b.successCount.Load() + b.failCount.Load(),
		SuccessfulOperations: b.successCount.Load(),
		FailedOperations:     b.failCount.Load(),
		PeakThroughput:       peakThroughput,
		AvgThroughput:        avgThroughput,
		ThroughputTimeline:   throughputSamples,
		Latency:              latencyStats,
		CPUTimeline:          cpuSamples,
		MemoryTimeline:       memorySamples,
		PeakCPU:              peakCPU,
		PeakMemory:           peakMemory,
		AvgCPU:               avgCPU,
		AvgMemory:            avgMemory,
		SampleEvents:         sampleEvents,
		Status:               status,
		ErrorMessage:         errorMessage,
	}
}

func calculateLatencyStats(latencies []int64) types.LatencyStats {
	if len(latencies) == 0 {
		return types.LatencyStats{}
	}

	sorted := make([]int64, len(latencies))
	copy(sorted, latencies)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i] < sorted[j] })

	var sum int64
	for _, l := range sorted {
		sum += l
	}

	return types.LatencyStats{
		Min: sorted[0],
		Max: sorted[len(sorted)-1],
		Avg: sum / int64(len(sorted)),
		P50: percentile(sorted, 50),
		P95: percentile(sorted, 95),
		P99: percentile(sorted, 99),
	}
}

func percentile(sorted []int64, p int) int64 {
	if len(sorted) == 0 {
		return 0
	}
	index := (p * len(sorted) / 100)
	if index >= len(sorted) {
		index = len(sorted) - 1
	}
	if index < 0 {
		index = 0
	}
	return sorted[index]
}
