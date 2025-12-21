package observability

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"sort"
	"time"

	"github.com/reactive/benchmark/pkg/types"
)

// Fetcher fetches traces from Jaeger and logs from Loki
type Fetcher struct {
	jaegerURL string
	lokiURL   string
	client    *http.Client
}

// NewFetcher creates a new observability fetcher
func NewFetcher() *Fetcher {
	jaegerURL := os.Getenv("JAEGER_QUERY_URL")
	if jaegerURL == "" {
		jaegerURL = "http://jaeger:16686"
	}

	lokiURL := os.Getenv("LOKI_URL")
	if lokiURL == "" {
		lokiURL = "http://loki:3100"
	}

	return &Fetcher{
		jaegerURL: jaegerURL,
		lokiURL:   lokiURL,
		client: &http.Client{
			Timeout: 30 * time.Second, // Increased timeout for post-benchmark fetching
		},
	}
}

// FetchTraceByOtelID fetches a trace from Jaeger by OpenTelemetry trace ID (direct lookup).
func (f *Fetcher) FetchTraceByOtelID(otelTraceID string) (*types.JaegerTrace, error) {
	if otelTraceID == "" {
		return nil, nil
	}

	// Direct fetch by Jaeger trace ID
	fetchURL := fmt.Sprintf("%s/api/traces/%s", f.jaegerURL, otelTraceID)

	// Retry up to 5 times with exponential backoff
	var lastErr error
	for attempt := 0; attempt < 5; attempt++ {
		if attempt > 0 {
			waitTime := time.Duration(1<<uint(attempt-1)) * time.Second
			time.Sleep(waitTime)
		}

		resp, err := f.client.Get(fetchURL)
		if err != nil {
			lastErr = err
			log.Printf("Attempt %d: Failed to fetch trace %s: %v", attempt+1, otelTraceID, err)
			continue
		}

		if resp.StatusCode != 200 {
			resp.Body.Close()
			if attempt < 4 {
				log.Printf("Attempt %d: Trace %s not found (status %d), retrying...", attempt+1, otelTraceID, resp.StatusCode)
				continue
			}
			return nil, nil
		}

		var result struct {
			Data []types.JaegerTrace `json:"data"`
		}
		if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
			resp.Body.Close()
			lastErr = err
			continue
		}
		resp.Body.Close()

		if len(result.Data) == 0 {
			if attempt < 4 {
				log.Printf("Attempt %d: Trace %s has no data, retrying...", attempt+1, otelTraceID)
				continue
			}
			return nil, nil
		}

		log.Printf("Found trace by otelTraceId=%s with %d spans", otelTraceID, len(result.Data[0].Spans))
		return &result.Data[0], nil
	}

	return nil, lastErr
}

// FetchTrace fetches a trace from Jaeger by searching for our app.traceId tag (fallback).
func (f *Fetcher) FetchTrace(traceID string) (*types.JaegerTrace, error) {
	if traceID == "" {
		return nil, nil
	}

	// Search for traces by the app.traceId tag
	u, _ := url.Parse(f.jaegerURL + "/api/traces")
	q := u.Query()
	q.Set("service", "counter-application")
	q.Set("tags", fmt.Sprintf(`{"app.traceId":"%s"}`, traceID))
	q.Set("limit", "1")
	q.Set("lookback", "1h")
	u.RawQuery = q.Encode()

	var lastErr error
	for attempt := 0; attempt < 5; attempt++ {
		if attempt > 0 {
			waitTime := time.Duration(1<<uint(attempt-1)) * time.Second
			time.Sleep(waitTime)
		}

		resp, err := f.client.Get(u.String())
		if err != nil {
			lastErr = err
			log.Printf("Attempt %d: Failed to search trace %s: %v", attempt+1, traceID, err)
			continue
		}

		if resp.StatusCode != 200 {
			resp.Body.Close()
			if attempt < 4 {
				log.Printf("Attempt %d: Trace search for %s failed (status %d), retrying...", attempt+1, traceID, resp.StatusCode)
				continue
			}
			return nil, nil
		}

		var result struct {
			Data []types.JaegerTrace `json:"data"`
		}
		if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
			resp.Body.Close()
			lastErr = err
			continue
		}
		resp.Body.Close()

		if len(result.Data) == 0 {
			if attempt < 4 {
				log.Printf("Attempt %d: Trace %s has no data, retrying...", attempt+1, traceID)
				continue
			}
			return nil, nil
		}

		log.Printf("Found trace for app.traceId=%s (Jaeger traceId=%s)", traceID, result.Data[0].TraceID)
		return &result.Data[0], nil
	}

	return nil, lastErr
}

// FetchTraces fetches multiple traces by their IDs
func (f *Fetcher) FetchTraces(traceIDs []string) map[string]*types.JaegerTrace {
	traces := make(map[string]*types.JaegerTrace)

	for _, traceID := range traceIDs {
		if trace, err := f.FetchTrace(traceID); err == nil && trace != nil {
			traces[traceID] = trace
		}
	}

	return traces
}

// FetchLogs fetches logs from Loki for a requestId
// Searches across all application services (application, flink-taskmanager, drools)
func (f *Fetcher) FetchLogs(requestID string, start, end time.Time) ([]types.LokiLogEntry, error) {
	if requestID == "" {
		return nil, nil
	}

	// Extend time range slightly to catch logs that might be slightly out of sync
	startNs := start.Add(-30 * time.Second).UnixNano()
	endNs := end.Add(30 * time.Second).UnixNano()

	// Query logs from application services
	// Loki uses service label from promtail config
	query := fmt.Sprintf(`{service=~"application|flink-taskmanager|drools"} |= "%s"`, requestID)
	logs, err := f.queryLoki(query, startNs, endNs)
	if err == nil && len(logs) > 0 {
		log.Printf("Found %d logs for requestId %s", len(logs), requestID)
	}

	return logs, err
}

// FetchLogsMulti fetches logs from Loki for multiple IDs
// Uses requestId for log correlation (present in all services)
func (f *Fetcher) FetchLogsMulti(otelTraceID, requestID string, start, end time.Time) ([]types.LokiLogEntry, error) {
	startNs := start.Add(-30 * time.Second).UnixNano()
	endNs := end.Add(30 * time.Second).UnixNano()

	var allLogs []types.LokiLogEntry
	seenLines := make(map[string]bool)

	// Search by requestId (present in all service logs)
	if requestID != "" {
		query := fmt.Sprintf(`{service=~"application|flink-taskmanager|drools"} |= "%s"`, requestID)
		logs, err := f.queryLoki(query, startNs, endNs)
		if err == nil {
			for _, l := range logs {
				if !seenLines[l.Line] {
					seenLines[l.Line] = true
					allLogs = append(allLogs, l)
				}
			}
		}
	}

	// Fallback: search by otelTraceId if no logs found with requestId
	if len(allLogs) == 0 && otelTraceID != "" {
		query := fmt.Sprintf(`{service=~"application|flink-taskmanager|drools"} |= "%s"`, otelTraceID)
		logs, err := f.queryLoki(query, startNs, endNs)
		if err == nil {
			for _, l := range logs {
				if !seenLines[l.Line] {
					seenLines[l.Line] = true
					allLogs = append(allLogs, l)
				}
			}
		}
	}

	if len(allLogs) > 0 {
		log.Printf("Found %d total logs (requestId=%s, otelTraceId=%s)", len(allLogs), requestID, otelTraceID)
	}

	// Sort by timestamp
	sort.Slice(allLogs, func(i, j int) bool {
		return allLogs[i].Timestamp < allLogs[j].Timestamp
	})

	return allLogs, nil
}

func (f *Fetcher) queryLoki(query string, startNs, endNs int64) ([]types.LokiLogEntry, error) {
	u, _ := url.Parse(f.lokiURL + "/loki/api/v1/query_range")
	q := u.Query()
	q.Set("query", query)
	q.Set("start", fmt.Sprintf("%d", startNs))
	q.Set("end", fmt.Sprintf("%d", endNs))
	q.Set("limit", "100")
	u.RawQuery = q.Encode()

	resp, err := f.client.Get(u.String())
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != 200 {
		return nil, nil
	}

	var result struct {
		Status string `json:"status"`
		Data   struct {
			Result []struct {
				Stream map[string]string `json:"stream"`
				Values [][]string        `json:"values"` // [[timestamp, line], ...]
			} `json:"result"`
		} `json:"data"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, err
	}

	if result.Status != "success" {
		return nil, nil
	}

	var entries []types.LokiLogEntry
	for _, stream := range result.Data.Result {
		for _, value := range stream.Values {
			if len(value) >= 2 {
				entry := types.LokiLogEntry{
					Timestamp: value[0],
					Line:      value[1],
					Labels:    stream.Stream,
				}

				// Try to parse JSON fields
				var fields map[string]interface{}
				if err := json.Unmarshal([]byte(value[1]), &fields); err == nil {
					entry.Fields = fields
				}

				entries = append(entries, entry)
			}
		}
	}

	// Sort by timestamp
	sort.Slice(entries, func(i, j int) bool {
		return entries[i].Timestamp < entries[j].Timestamp
	})

	return entries, nil
}

// FetchTraceData fetches both trace and logs for a trace
// otelTraceID is used for direct Jaeger lookup, traceID for logs
func (f *Fetcher) FetchTraceData(otelTraceID, traceID string, start, end time.Time) *types.TraceData {
	if otelTraceID == "" && traceID == "" {
		return nil
	}

	data := &types.TraceData{}

	// Fetch trace using OTel trace ID (direct lookup - includes all services)
	if otelTraceID != "" {
		if trace, err := f.FetchTraceByOtelID(otelTraceID); err == nil && trace != nil {
			data.Trace = trace
		}
	}

	// Fallback: search by app.traceId tag if direct lookup failed
	if data.Trace == nil && traceID != "" {
		if trace, err := f.FetchTrace(traceID); err == nil && trace != nil {
			data.Trace = trace
		}
	}

	// Fetch logs using both otelTraceId (flink) and app.traceId (gateway/drools)
	if logs, err := f.FetchLogsMulti(otelTraceID, traceID, start, end); err == nil {
		data.Logs = logs
	}

	if data.Trace == nil && len(data.Logs) == 0 {
		return nil
	}

	return data
}

// EnrichSampleEvents fetches trace and log data for each sample event
func (f *Fetcher) EnrichSampleEvents(events []types.SampleEvent, start, end time.Time) {
	for i := range events {
		// Use OtelTraceID for direct Jaeger lookup, TraceID for log correlation
		if events[i].OtelTraceID != "" || events[i].TraceID != "" {
			events[i].TraceData = f.FetchTraceData(events[i].OtelTraceID, events[i].TraceID, start, end)
		}
	}
}
