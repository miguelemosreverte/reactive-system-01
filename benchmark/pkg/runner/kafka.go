package runner

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"sync"
	"time"

	"github.com/reactive/benchmark/pkg/types"
	"github.com/segmentio/kafka-go"
)

// KafkaRunner benchmarks Kafka produce/consume round-trip
type KafkaRunner struct {
	*BaseRunner
}

// NewKafkaRunner creates a new Kafka benchmark runner
func NewKafkaRunner() *KafkaRunner {
	return &KafkaRunner{
		BaseRunner: NewBaseRunner(
			types.ComponentKafka,
			"Kafka Benchmark",
			"Kafka produce/consume round-trip latency",
		),
	}
}

func (k *KafkaRunner) Run(config types.Config) (*types.Result, error) {
	if !k.running.CompareAndSwap(false, true) {
		return nil, fmt.Errorf("benchmark already running")
	}
	defer k.running.Store(false)

	k.Reset()
	startTime := time.Now()

	brokers := config.KafkaBrokers
	if brokers == "" {
		brokers = "kafka:29092"
	}

	log.Printf("Starting %s: duration=%dms, concurrency=%d, brokers=%s",
		k.name, config.DurationMs, config.Concurrency, brokers)

	// Create unique benchmark topic for this run (avoids stale offset issues)
	benchTopic := fmt.Sprintf("benchmark-kafka-%d", time.Now().UnixNano())

	// Create writer
	writer := &kafka.Writer{
		Addr:         kafka.TCP(brokers),
		Topic:        benchTopic,
		Balancer:     &kafka.LeastBytes{},
		BatchSize:    1,
		BatchTimeout: 1 * time.Millisecond,
		RequiredAcks: kafka.RequireOne,
		Async:        false,
	}
	defer writer.Close()

	// Create reader
	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers:        []string{brokers},
		Topic:          benchTopic,
		GroupID:        fmt.Sprintf("benchmark-consumer-%d", time.Now().UnixNano()),
		MinBytes:       1,
		MaxBytes:       10e6,
		MaxWait:        100 * time.Millisecond,
		CommitInterval: 0, // Commit synchronously
	})
	defer reader.Close()

	// Create topic if it doesn't exist
	conn, err := kafka.Dial("tcp", brokers)
	if err != nil {
		return nil, fmt.Errorf("failed to connect to Kafka: %w", err)
	}

	controller, err := conn.Controller()
	if err == nil {
		controllerConn, err := kafka.Dial("tcp", fmt.Sprintf("%s:%d", controller.Host, controller.Port))
		if err == nil {
			controllerConn.CreateTopics(kafka.TopicConfig{
				Topic:             benchTopic,
				NumPartitions:     1,
				ReplicationFactor: 1,
			})
			controllerConn.Close()
		}
	}
	conn.Close()

	// Wait for topic to be ready
	time.Sleep(500 * time.Millisecond)

	// Cooldown
	time.Sleep(time.Duration(config.CooldownMs) * time.Millisecond)

	benchmarkStart := time.Now()
	warmupEnd := benchmarkStart.Add(time.Duration(config.WarmupMs) * time.Millisecond)
	duration := time.Duration(config.DurationMs) * time.Millisecond

	var wg sync.WaitGroup
	var lastOpsCount int64

	// Message correlation map
	messageMap := sync.Map{}

	// Start consumer goroutine
	wg.Add(1)
	go func() {
		defer wg.Done()
		msgCount := 0
		correlatedCount := 0
		for k.running.Load() {
			ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
			msg, err := reader.FetchMessage(ctx)
			cancel()

			if err != nil {
				continue
			}

			msgCount++
			if msgCount == 1 || msgCount == 100 {
				log.Printf("Consumer: received %d messages", msgCount)
			}

			// Extract message ID and calculate round-trip time
			var payload struct {
				ID        string `json:"id"`
				Timestamp int64  `json:"timestamp"`
			}
			if err := json.Unmarshal(msg.Value, &payload); err == nil {
				if startTime, ok := messageMap.LoadAndDelete(payload.ID); ok {
					latencyMs := time.Since(startTime.(time.Time)).Milliseconds()
					k.RecordLatency(latencyMs)
					k.RecordSuccess()
					correlatedCount++

					// Sample events after warmup
					if time.Now().After(warmupEnd) {
						k.AddSampleEvent(types.SampleEvent{
							ID:        payload.ID,
							Timestamp: payload.Timestamp,
							LatencyMs: latencyMs,
							Status:    "success",
							ComponentTiming: &types.ComponentTiming{
								KafkaMs: latencyMs,
							},
						})
					}
				}
			}

			reader.CommitMessages(context.Background(), msg)
		}
		log.Printf("Consumer exiting: received %d messages, %d correlated", msgCount, correlatedCount)
	}()

	// Start producer workers
	for i := 0; i < config.Concurrency; i++ {
		wg.Add(1)
		go func(workerID int) {
			defer wg.Done()
			k.runProducer(workerID, writer, &messageMap, warmupEnd)
		}(i)
	}

	// Monitor progress
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()

	isWarmup := true
	for {
		select {
		case <-k.stopCh:
			log.Println("Benchmark stopped")
			wg.Wait()
			return k.BuildResult(startTime, "stopped", ""), nil

		case <-ticker.C:
			now := time.Now()
			elapsed := now.Sub(benchmarkStart)

			if isWarmup && now.After(warmupEnd) {
				isWarmup = false
				lastOpsCount = k.GetOperationCount()
				log.Println("Warmup complete, starting measurements")
			}

			if elapsed >= duration {
				log.Println("Duration reached")
				k.Stop()
				wg.Wait()
				return k.BuildResult(startTime, "completed", ""), nil
			}

			// Sample resources
			cpuPercent, memPercent := k.SampleResources()
			if !isWarmup {
				k.RecordResourceSample(cpuPercent, memPercent)

				// Calculate throughput
				currentOps := k.GetOperationCount()
				throughput := currentOps - lastOpsCount
				k.RecordThroughputSample(throughput)
				lastOpsCount = currentOps

				log.Printf("Progress: ops=%d, throughput=%d/s, cpu=%.1f%%, mem=%.1f%%",
					currentOps, throughput, cpuPercent, memPercent)
			}
		}
	}
}

func (k *KafkaRunner) runProducer(workerID int, writer *kafka.Writer, messageMap *sync.Map, warmupEnd time.Time) {
	msgCount := 0
	errCount := 0
	for k.running.Load() {
		start := time.Now()
		msgID := fmt.Sprintf("kafka_%d_%d", workerID, start.UnixNano())

		payload := map[string]interface{}{
			"id":        msgID,
			"timestamp": start.UnixMilli(),
			"workerId":  workerID,
		}
		body, _ := json.Marshal(payload)

		// Store send time for correlation
		messageMap.Store(msgID, start)

		err := writer.WriteMessages(context.Background(),
			kafka.Message{
				Key:   []byte(fmt.Sprintf("worker-%d", workerID)),
				Value: body,
			},
		)

		if err != nil {
			messageMap.Delete(msgID)
			k.RecordFailure()
			errCount++
			if errCount <= 3 {
				log.Printf("Producer %d error: %v", workerID, err)
			}

			if time.Now().After(warmupEnd) {
				k.AddSampleEvent(types.SampleEvent{
					ID:        msgID,
					Timestamp: start.UnixMilli(),
					LatencyMs: time.Since(start).Milliseconds(),
					Status:    "error",
					Error:     err.Error(),
				})
			}
		} else {
			msgCount++
			if msgCount == 1 || msgCount == 100 {
				log.Printf("Producer %d: sent %d messages", workerID, msgCount)
			}
		}
	}
	log.Printf("Producer %d exiting: sent %d messages, %d errors", workerID, msgCount, errCount)
}
