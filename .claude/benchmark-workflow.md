# Benchmark Workflow & Trace Requirements

## Overview

This document defines the expected behavior for benchmark traces, logs, and reports.
Use this as a reference when iterating on observability improvements.

## Trace Requirements

### Ideal Trace Structure

Each benchmark request should produce a **distributed trace** that spans all services in the pipeline:

```
[Root Span: HTTP Request to Gateway]
  |
  +-- [Child Span: Kafka Producer - publish to counter-events]
  |     |
  |     +-- [Child Span: Kafka Consumer - Flink reads from counter-events]
  |           |
  |           +-- [Child Span: Flink Processing]
  |                 |
  |                 +-- [Child Span: Kafka Producer - publish to processed-events]
  |                       |
  |                       +-- [Child Span: Kafka Consumer - Drools reads from processed-events]
  |                             |
  |                             +-- [Child Span: Drools Rule Execution]
```

### Trace ID Propagation

1. **Gateway** receives HTTP request with trace headers (W3C TraceContext or B3)
2. **Gateway** creates root span and propagates trace context via Kafka headers
3. **Flink** extracts trace context from Kafka headers, creates child span
4. **Drools** extracts trace context from Kafka headers, creates child span
5. All services use the SAME trace ID throughout the request lifecycle

### Required Span Attributes

Each span should include:
- `service.name`: The service that created the span
- `span.kind`: SERVER, CLIENT, PRODUCER, CONSUMER
- `http.method`, `http.url`, `http.status_code` (for HTTP spans)
- `messaging.system`: "kafka" (for Kafka spans)
- `messaging.destination`: topic name
- `messaging.operation`: "publish" or "receive"

### Trace Verification Checklist

- [ ] Trace ID is consistent across all services
- [ ] Spans have parent-child relationships (not orphaned)
- [ ] Span timing shows actual component processing time
- [ ] Component timing can be derived: `gatewayMs`, `kafkaMs`, `flinkMs`, `droolsMs`

## Log Requirements

### Ideal Log Format

Each log entry should be structured JSON with:

```json
{
  "timestamp": "2025-12-25T22:44:05.692Z",
  "level": "INFO",
  "message": "Processing counter increment",
  "service": "counter-application",
  "traceId": "abc123...",
  "spanId": "def456...",
  "requestId": "000e955d0dbb52250000000000000000",
  "action": "increment",
  "value": 1
}
```

### Log Correlation

1. Logs MUST include `traceId` for correlation with traces
2. Logs MUST include `requestId` for end-to-end tracking
3. Logs from all services should be queryable by the same trace ID

### Log Verification Checklist

- [ ] Logs are in structured JSON format
- [ ] Logs include traceId field
- [ ] Logs include requestId field
- [ ] Logs from Gateway, Flink, Drools all correlate to same trace

## Benchmark Report Requirements

### Sample Events

Each sample event in the report should include:

1. **Trace Data**: Actual Jaeger spans with parent-child relationships
2. **Log Data**: Correlated Loki logs from all services
3. **Component Timing**: Breakdown of time spent in each service
4. **Status**: success/error with error details if failed

### Data Completeness Targets

| Metric | Target |
|--------|--------|
| Trace capture rate | 100% of sample events have traces |
| Log correlation rate | 100% of sample events have logs |
| Span count per trace | >= 4 spans (gateway, kafka, flink, drools) |
| Component timing | All 4 components have non-zero times |

## Diagnostic-Driven Development

### Iteration Process

1. Run benchmark: `./reactive bench <component>`
2. Check diagnostics in the generated report
3. If diagnostics show issues:
   - Read the specific issue description
   - Follow the fix steps provided
   - Run the benchmark again
4. Repeat until all diagnostics pass

### Common Issues and Fixes

#### Issue: "Empty traces"
**Cause**: Trace context not being propagated via Kafka headers
**Fix**: Ensure OTel instrumentation is enabled and Kafka producer/consumer are traced

#### Issue: "Logs not correlated"
**Cause**: traceId not included in log MDC context
**Fix**: Add traceId to MDC in OpenTelemetry logging configuration

#### Issue: "Component timing all zeros"
**Cause**: Spans not being parsed correctly from Jaeger
**Fix**: Verify Jaeger API is returning spans, check service name matching

## Service Endpoints

| Service | Prometheus | Health | Traces |
|---------|------------|--------|--------|
| Gateway | :8080/actuator/prometheus | :8080/actuator/health | via OTel |
| Drools | :8180/actuator/prometheus | :8180/actuator/health | via OTel |
| Flink TM | :8081/taskmanagers | N/A | via OTel |
| Jaeger | N/A | N/A | :16686/api/traces |
| Loki | N/A | N/A | :3100/loki/api/v1/query_range |

## CLI Commands

```bash
# Run individual component benchmarks
./reactive bench gateway
./reactive bench kafka
./reactive bench flink
./reactive bench drools
./reactive bench full

# Run diagnostics
./reactive diagnose

# View generated reports
open reports/dashboard.html
open reports/<component>/index.html
```
