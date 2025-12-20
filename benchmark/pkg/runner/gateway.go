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

// GatewayRunner benchmarks the Gateway service
type GatewayRunner struct {
	*BaseRunner
	client *http.Client
}

// NewGatewayRunner creates a new gateway benchmark runner
func NewGatewayRunner() *GatewayRunner {
	return &GatewayRunner{
		BaseRunner: NewBaseRunner(
			types.ComponentGateway,
			"Gateway Benchmark",
			"Gateway HTTP handling with delivery guarantees (waits for Kafka ack)",
		),
		client: &http.Client{
			Timeout: 60 * time.Second,
			Transport: &http.Transport{
				MaxIdleConns:        500,
				MaxIdleConnsPerHost: 500,
				MaxConnsPerHost:     500,
				IdleConnTimeout:     90 * time.Second,
				DisableKeepAlives:   false,
			},
		},
	}
}

func (g *GatewayRunner) Run(config types.Config) (*types.Result, error) {
	if !g.running.CompareAndSwap(false, true) {
		return nil, fmt.Errorf("benchmark already running")
	}
	defer g.running.Store(false)

	g.Reset()
	startTime := time.Now()

	log.Printf("Starting %s: duration=%dms, concurrency=%d", g.name, config.DurationMs, config.Concurrency)

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
			g.runWorker(workerID, config, warmupEnd)
		}(i)
	}

	// Monitor progress
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()

	isWarmup := true
	for {
		select {
		case <-g.stopCh:
			log.Println("Benchmark stopped")
			wg.Wait()
			return g.BuildResult(startTime, "stopped", ""), nil

		case <-ticker.C:
			now := time.Now()
			elapsed := now.Sub(benchmarkStart)

			if isWarmup && now.After(warmupEnd) {
				isWarmup = false
				lastOpsCount = g.GetOperationCount()
				log.Println("Warmup complete, starting measurements")
			}

			if elapsed >= duration {
				log.Println("Duration reached")
				g.Stop()
				wg.Wait()
				return g.BuildResult(startTime, "completed", ""), nil
			}

			// Sample resources
			cpuPercent, memPercent := g.SampleResources()
			if !isWarmup {
				g.RecordResourceSample(cpuPercent, memPercent)

				// Calculate throughput
				currentOps := g.GetOperationCount()
				throughput := currentOps - lastOpsCount
				g.RecordThroughputSample(throughput)
				lastOpsCount = currentOps

				log.Printf("Progress: ops=%d, throughput=%d/s, cpu=%.1f%%, mem=%.1f%%",
					currentOps, throughput, cpuPercent, memPercent)
			}
		}
	}
}

func (g *GatewayRunner) runWorker(workerID int, config types.Config, warmupEnd time.Time) {
	// Use regular counter endpoint with delivery guarantees (waits for full processing)
	url := config.GatewayURL + "/api/counter"

	for g.running.Load() {
		start := time.Now()
		requestID := fmt.Sprintf("gateway_%d_%d", workerID, start.UnixNano())

		payload := map[string]interface{}{
			"action":    "INCREMENT",
			"value":     1,
			"sessionId": fmt.Sprintf("gateway-bench-%d", workerID),
		}
		body, _ := json.Marshal(payload)

		req, err := http.NewRequest("POST", url, bytes.NewReader(body))
		if err != nil {
			g.RecordFailure()
			continue
		}
		req.Header.Set("Content-Type", "application/json")

		resp, err := g.client.Do(req)
		latencyMs := time.Since(start).Milliseconds()

		if err != nil {
			g.RecordLatency(latencyMs)
			g.RecordFailure()
			g.AddSampleEvent(types.SampleEvent{
				ID:        requestID,
				Timestamp: start.UnixMilli(),
				LatencyMs: latencyMs,
				Status:    "error",
				Error:     err.Error(),
			})
			continue
		}

		g.RecordLatency(latencyMs)

		if resp.StatusCode == 200 {
			g.RecordSuccess()

			var result struct {
				TraceID string `json:"traceId"`
				Result  struct {
					ProcessingTimeMs int64 `json:"processingTimeMs"`
				} `json:"result"`
			}
			json.NewDecoder(resp.Body).Decode(&result)
			resp.Body.Close()

			// Sample successful events after warmup
			if time.Now().After(warmupEnd) {
				g.AddSampleEvent(types.SampleEvent{
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
			g.RecordFailure()
			g.AddSampleEvent(types.SampleEvent{
				ID:        requestID,
				Timestamp: start.UnixMilli(),
				LatencyMs: latencyMs,
				Status:    "error",
				Error:     fmt.Sprintf("HTTP %d", resp.StatusCode),
			})
		}
	}
}
