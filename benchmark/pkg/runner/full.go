package runner

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/reactive/benchmark/pkg/types"
)

// FullRunner benchmarks the complete pipeline: HTTP -> Kafka -> Flink -> Drools
type FullRunner struct {
	*BaseRunner
	client *http.Client
}

// NewFullRunner creates a new full E2E benchmark runner
func NewFullRunner() *FullRunner {
	return &FullRunner{
		BaseRunner: NewBaseRunner(
			types.ComponentFull,
			"Full End-to-End Benchmark",
			"Complete pipeline: HTTP → Kafka → Flink → Drools",
		),
		client: &http.Client{
			Timeout: 60 * time.Second,
			Transport: &http.Transport{
				MaxIdleConns:        200,
				MaxIdleConnsPerHost: 200,
				IdleConnTimeout:     90 * time.Second,
			},
		},
	}
}

func (f *FullRunner) Run(config types.Config) (*types.Result, error) {
	if !f.running.CompareAndSwap(false, true) {
		return nil, fmt.Errorf("benchmark already running")
	}
	defer f.running.Store(false)

	f.Reset()
	startTime := time.Now()

	log.Printf("Starting %s: duration=%dms, concurrency=%d", f.name, config.DurationMs, config.Concurrency)

	// Cooldown
	time.Sleep(time.Duration(config.CooldownMs) * time.Millisecond)

	benchmarkStart := time.Now()
	warmupEnd := benchmarkStart.Add(time.Duration(config.WarmupMs) * time.Millisecond)
	duration := time.Duration(config.DurationMs) * time.Millisecond

	var wg sync.WaitGroup
	var lastOpsCount int64

	for i := 0; i < config.Concurrency; i++ {
		wg.Add(1)
		go func(workerID int) {
			defer wg.Done()
			f.runWorker(workerID, config, warmupEnd)
		}(i)
	}

	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()

	isWarmup := true
	for {
		select {
		case <-f.stopCh:
			log.Println("Benchmark stopped")
			wg.Wait()
			return f.BuildResult(startTime, "stopped", ""), nil

		case <-ticker.C:
			now := time.Now()
			elapsed := now.Sub(benchmarkStart)

			if isWarmup && now.After(warmupEnd) {
				isWarmup = false
				lastOpsCount = f.GetOperationCount()
				log.Println("Warmup complete, starting measurements")
			}

			if elapsed >= duration {
				log.Println("Duration reached")
				f.Stop()
				wg.Wait()
				return f.BuildResult(startTime, "completed", ""), nil
			}

			cpuPercent, memPercent := f.SampleResources()
			if !isWarmup {
				f.RecordResourceSample(cpuPercent, memPercent)

				currentOps := f.GetOperationCount()
				throughput := currentOps - lastOpsCount
				f.RecordThroughputSample(throughput)
				lastOpsCount = currentOps

				log.Printf("Progress: ops=%d, throughput=%d/s, cpu=%.1f%%, mem=%.1f%%",
					currentOps, throughput, cpuPercent, memPercent)
			}
		}
	}
}

func (f *FullRunner) runWorker(workerID int, config types.Config, warmupEnd time.Time) {
	// Use the regular counter endpoint that waits for full processing
	url := config.GatewayURL + "/api/counter"

	for f.running.Load() {
		start := time.Now()
		requestID := fmt.Sprintf("full_%d_%d", workerID, start.UnixNano())

		payload := map[string]interface{}{
			"action":    "INCREMENT",
			"value":     1,
			"sessionId": fmt.Sprintf("full-bench-%d", workerID),
		}
		body, _ := json.Marshal(payload)

		req, err := http.NewRequest("POST", url, bytes.NewReader(body))
		if err != nil {
			f.RecordFailure()
			continue
		}
		req.Header.Set("Content-Type", "application/json")

		resp, err := f.client.Do(req)
		latencyMs := time.Since(start).Milliseconds()

		if err != nil {
			f.RecordLatency(latencyMs)
			f.RecordFailure()
			f.AddSampleEvent(types.SampleEvent{
				ID:        requestID,
				Timestamp: start.UnixMilli(),
				LatencyMs: latencyMs,
				Status:    "error",
				Error:     err.Error(),
			})
			continue
		}

		f.RecordLatency(latencyMs)

		if resp.StatusCode == 200 {
			f.RecordSuccess()

			var result struct {
				TraceID string `json:"traceId"`
				Result  struct {
					ProcessingTimeMs int64 `json:"processingTimeMs"`
				} `json:"result"`
			}
			json.NewDecoder(resp.Body).Decode(&result)
			resp.Body.Close()

			if time.Now().After(warmupEnd) {
				f.AddSampleEvent(types.SampleEvent{
					ID:        requestID,
					TraceID:   result.TraceID,
					Timestamp: start.UnixMilli(),
					LatencyMs: latencyMs,
					Status:    "success",
					ComponentTiming: &types.ComponentTiming{
						GatewayMs: latencyMs - result.Result.ProcessingTimeMs,
						FlinkMs:   result.Result.ProcessingTimeMs,
					},
				})
			}
		} else {
			resp.Body.Close()
			f.RecordFailure()
			f.AddSampleEvent(types.SampleEvent{
				ID:        requestID,
				Timestamp: start.UnixMilli(),
				LatencyMs: latencyMs,
				Status:    "error",
				Error:     fmt.Sprintf("HTTP %d", resp.StatusCode),
			})
		}
	}
}
