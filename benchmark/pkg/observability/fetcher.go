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
			Timeout: 10 * time.Second,
		},
	}
}

// FetchTrace fetches a single trace from Jaeger
func (f *Fetcher) FetchTrace(traceID string) (*types.JaegerTrace, error) {
	if traceID == "" {
		return nil, nil
	}

	url := fmt.Sprintf("%s/api/traces/%s", f.jaegerURL, traceID)
	resp, err := f.client.Get(url)
	if err != nil {
		log.Printf("Failed to fetch trace %s: %v", traceID, err)
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != 200 {
		return nil, nil
	}

	var result struct {
		Data []types.JaegerTrace `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, err
	}

	if len(result.Data) == 0 {
		return nil, nil
	}

	return &result.Data[0], nil
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

// FetchLogs fetches logs from Loki for a trace ID
func (f *Fetcher) FetchLogs(traceID string, start, end time.Time) ([]types.LokiLogEntry, error) {
	if traceID == "" {
		return nil, nil
	}

	// Convert to nanoseconds for Loki
	startNs := start.UnixNano()
	endNs := end.UnixNano()

	// Try label filter first
	query := fmt.Sprintf(`{traceId="%s"}`, traceID)
	logs, err := f.queryLoki(query, startNs, endNs)
	if err != nil {
		return nil, err
	}

	// Fallback to line filter if no results
	if len(logs) == 0 {
		query = fmt.Sprintf(`{service=~".+"} |= "traceId" |= "%s"`, traceID)
		logs, err = f.queryLoki(query, startNs, endNs)
	}

	return logs, err
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

// FetchTraceData fetches both trace and logs for a trace ID
func (f *Fetcher) FetchTraceData(traceID string, start, end time.Time) *types.TraceData {
	if traceID == "" {
		return nil
	}

	data := &types.TraceData{}

	// Fetch trace
	if trace, err := f.FetchTrace(traceID); err == nil {
		data.Trace = trace
	}

	// Fetch logs
	if logs, err := f.FetchLogs(traceID, start, end); err == nil {
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
		if events[i].TraceID != "" {
			events[i].TraceData = f.FetchTraceData(events[i].TraceID, start, end)
		}
	}
}
