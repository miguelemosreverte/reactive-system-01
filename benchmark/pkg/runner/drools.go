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

// DroolsRunner benchmarks the Drools service directly
type DroolsRunner struct {
	*BaseRunner
	client *http.Client
}

// NewDroolsRunner creates a new Drools benchmark runner
func NewDroolsRunner() *DroolsRunner {
	return &DroolsRunner{
		BaseRunner: NewBaseRunner(
			types.ComponentDrools,
			"Drools Benchmark",
			"Direct Drools rule evaluation via HTTP. NOTE: In CQRS architecture, Drools processes global snapshots periodically (decoupled from main event flow). This benchmark measures raw capacity, not a bottleneck.",
		),
		client: &http.Client{
			Timeout: 30 * time.Second,
			Transport: &http.Transport{
				MaxIdleConns:        200,
				MaxIdleConnsPerHost: 200,
				IdleConnTimeout:     90 * time.Second,
			},
		},
	}
}

func (d *DroolsRunner) Run(config types.Config) (*types.Result, error) {
	if !d.running.CompareAndSwap(false, true) {
		return nil, fmt.Errorf("benchmark already running")
	}
	defer d.running.Store(false)

	d.Reset()
	startTime := time.Now()

	log.Printf("Starting %s: duration=%dms, concurrency=%d", d.name, config.DurationMs, config.Concurrency)

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
			d.runWorker(workerID, config, warmupEnd)
		}(i)
	}

	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()

	isWarmup := true
	for {
		select {
		case <-d.stopCh:
			log.Println("Benchmark stopped")
			wg.Wait()
			return d.BuildResult(startTime, "stopped", ""), nil

		case <-ticker.C:
			now := time.Now()
			elapsed := now.Sub(benchmarkStart)

			if isWarmup && now.After(warmupEnd) {
				isWarmup = false
				lastOpsCount = d.GetOperationCount()
				log.Println("Warmup complete, starting measurements")
			}

			if elapsed >= duration {
				log.Println("Duration reached")
				d.Stop()
				wg.Wait()
				return d.BuildResult(startTime, "completed", ""), nil
			}

			cpuPercent, memPercent := d.SampleResources()
			if !isWarmup {
				d.RecordResourceSample(cpuPercent, memPercent)

				currentOps := d.GetOperationCount()
				throughput := currentOps - lastOpsCount
				d.RecordThroughputSample(throughput)
				lastOpsCount = currentOps

				log.Printf("Progress: ops=%d, throughput=%d/s, cpu=%.1f%%, mem=%.1f%%",
					currentOps, throughput, cpuPercent, memPercent)
			}
		}
	}
}

func (d *DroolsRunner) runWorker(workerID int, config types.Config, warmupEnd time.Time) {
	url := config.DroolsURL + "/api/evaluate"

	for d.running.Load() {
		start := time.Now()
		requestID := fmt.Sprintf("drools_%d_%d", workerID, start.UnixNano())

		payload := map[string]interface{}{
			"sessionId":    fmt.Sprintf("drools-bench-%d", workerID),
			"currentValue": 100 + workerID,
		}
		body, _ := json.Marshal(payload)

		req, err := http.NewRequest("POST", url, bytes.NewReader(body))
		if err != nil {
			d.RecordFailure()
			continue
		}
		req.Header.Set("Content-Type", "application/json")

		resp, err := d.client.Do(req)
		latencyMs := time.Since(start).Milliseconds()

		if err != nil {
			d.RecordLatency(latencyMs)
			d.RecordFailure()
			d.AddSampleEvent(types.SampleEvent{
				ID:        requestID,
				Timestamp: start.UnixMilli(),
				LatencyMs: latencyMs,
				Status:    "error",
				Error:     err.Error(),
			})
			continue
		}

		d.RecordLatency(latencyMs)

		if resp.StatusCode == 200 {
			d.RecordSuccess()

			var result struct {
				TraceID string `json:"traceId"`
			}
			json.NewDecoder(resp.Body).Decode(&result)
			resp.Body.Close()

			if time.Now().After(warmupEnd) {
				d.AddSampleEvent(types.SampleEvent{
					ID:        requestID,
					TraceID:   result.TraceID,
					Timestamp: start.UnixMilli(),
					LatencyMs: latencyMs,
					Status:    "success",
					ComponentTiming: &types.ComponentTiming{
						DroolsMs: latencyMs,
					},
				})
			}
		} else {
			resp.Body.Close()
			d.RecordFailure()
			d.AddSampleEvent(types.SampleEvent{
				ID:        requestID,
				Timestamp: start.UnixMilli(),
				LatencyMs: latencyMs,
				Status:    "error",
				Error:     fmt.Sprintf("HTTP %d", resp.StatusCode),
			})
		}
	}
}
