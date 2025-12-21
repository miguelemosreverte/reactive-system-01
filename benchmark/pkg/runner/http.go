package runner

import (
	"fmt"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/reactive/benchmark/pkg/types"
)

// HTTPRunner benchmarks raw HTTP latency
type HTTPRunner struct {
	*BaseRunner
	client *http.Client
}

// NewHTTPRunner creates a new HTTP benchmark runner
func NewHTTPRunner() *HTTPRunner {
	return &HTTPRunner{
		BaseRunner: NewBaseRunner(
			types.ComponentHTTP,
			"HTTP Benchmark",
			"Raw HTTP latency to Gateway health endpoint",
		),
		client: &http.Client{
			Timeout: 10 * time.Second,
			Transport: &http.Transport{
				MaxIdleConns:          500,
				MaxIdleConnsPerHost:   500,
				MaxConnsPerHost:       500,
				IdleConnTimeout:       90 * time.Second,
				DisableKeepAlives:     false,
				DisableCompression:    true,
				ResponseHeaderTimeout: 5 * time.Second,
				ExpectContinueTimeout: 1 * time.Second,
			},
		},
	}
}

func (h *HTTPRunner) Run(config types.Config) (*types.Result, error) {
	if !h.running.CompareAndSwap(false, true) {
		return nil, fmt.Errorf("benchmark already running")
	}
	defer h.running.Store(false)

	h.Reset()
	startTime := time.Now()

	log.Printf("Starting %s: duration=%dms, concurrency=%d", h.name, config.DurationMs, config.Concurrency)

	// Cooldown
	time.Sleep(time.Duration(config.CooldownMs) * time.Millisecond)

	benchmarkStart := time.Now()
	warmupEnd := benchmarkStart.Add(time.Duration(config.WarmupMs) * time.Millisecond)
	duration := time.Duration(config.DurationMs) * time.Millisecond

	var wg sync.WaitGroup
	var lastOpsCount int64

	// Start concurrent workers
	for i := 0; i < config.Concurrency; i++ {
		wg.Add(1)
		go func(workerID int) {
			defer wg.Done()
			h.runWorker(workerID, config, warmupEnd)
		}(i)
	}

	// Monitor progress
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()

	isWarmup := true
	for {
		select {
		case <-h.stopCh:
			log.Println("Benchmark stopped")
			wg.Wait()
			return h.BuildResult(startTime, "stopped", ""), nil

		case <-ticker.C:
			now := time.Now()
			elapsed := now.Sub(benchmarkStart)

			if isWarmup && now.After(warmupEnd) {
				isWarmup = false
				lastOpsCount = h.GetOperationCount()
				log.Println("Warmup complete, starting measurements")
			}

			if elapsed >= duration {
				log.Println("Duration reached")
				h.Stop()
				wg.Wait()
				return h.BuildResult(startTime, "completed", ""), nil
			}

			// Sample resources
			cpuPercent, memPercent := h.SampleResources()
			if !isWarmup {
				h.RecordResourceSample(cpuPercent, memPercent)

				currentOps := h.GetOperationCount()
				throughput := currentOps - lastOpsCount
				h.RecordThroughputSample(throughput)
				lastOpsCount = currentOps

				log.Printf("Progress: ops=%d, throughput=%d/s, cpu=%.1f%%, mem=%.1f%%",
					currentOps, throughput, cpuPercent, memPercent)
			}
		}
	}
}

func (h *HTTPRunner) runWorker(workerID int, config types.Config, warmupEnd time.Time) {
	url := config.GatewayURL + "/actuator/health"

	for h.running.Load() {
		start := time.Now()
		requestID := fmt.Sprintf("http_%d_%d", workerID, start.UnixNano())

		resp, err := h.client.Get(url)
		latencyMs := time.Since(start).Milliseconds()

		if err != nil {
			h.RecordLatency(latencyMs)
			h.RecordFailure()
			h.AddSampleEvent(types.SampleEvent{
				ID:        requestID,
				Timestamp: start.UnixMilli(),
				LatencyMs: latencyMs,
				Status:    "error",
				Error:     err.Error(),
			})
			continue
		}
		resp.Body.Close()

		h.RecordLatency(latencyMs)

		if resp.StatusCode == 200 {
			h.RecordSuccess()
			if time.Now().After(warmupEnd) {
				h.AddSampleEvent(types.SampleEvent{
					ID:        requestID,
					Timestamp: start.UnixMilli(),
					LatencyMs: latencyMs,
					Status:    "success",
				})
			}
		} else {
			h.RecordFailure()
			h.AddSampleEvent(types.SampleEvent{
				ID:        requestID,
				Timestamp: start.UnixMilli(),
				LatencyMs: latencyMs,
				Status:    "error",
				Error:     fmt.Sprintf("HTTP %d", resp.StatusCode),
			})
		}
	}
}
