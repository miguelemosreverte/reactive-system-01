# Adaptive Microbatch Calibration

The system learns optimal batching configurations for each load level through continuous experimentation.

## Pressure Level Buckets

| Bucket | Activates At | Target Latency | Use Case | Description |
|--------|--------------|----------------|----------|-------------|
| **IDLE** | < 10 req/s | 1ms | Development/testing | Minimal batching for fast response |
| **LOW** | 10-100 req/s | 2ms | Light traffic | Small batches, low latency |
| **MEDIUM** | 100-1K req/s | 5ms | Normal load | Balanced throughput/latency |
| **HIGH** | 1K-10K req/s | 10ms | High traffic | Larger batches for efficiency |
| **EXTREME** | 10K-100K req/s | 20ms | Peak load | Aggressive batching |
| **OVERLOAD** | 100K-1M req/s | 50ms | Stress test | Maximum batching |
| **MEGA** | 1M-10M req/s | 100ms | Benchmark | Throughput-optimized |
| **HTTP_30S** | 10M-100M req/s | 1s | Max throughput | Uses HTTP timeout headroom |
| **HTTP_60S** | > 100M req/s | 5s | Theoretical max | Extreme batching |

## How It Works

```
Request Rate → Bucket Selection → Config Application → Performance Measurement → Learning
     ↑                                                                              │
     └──────────────────────────────────────────────────────────────────────────────┘
```

1. **Pressure Detection**: System monitors request rate over 10-second windows
2. **Bucket Selection**: Selects appropriate bucket based on observed load
3. **Config Application**: Applies learned batch size and flush interval
4. **Performance Measurement**: Records throughput and latency
5. **Continuous Learning**: Updates optimal configs through experimentation

## Running the Calibration Benchmark

### Quick Status Check
```bash
# Show current calibration state
./reactive bench calibration --status
```

### Run Calibration for Specific Buckets
```bash
# Benchmark specific buckets
./reactive bench calibration EXTREME MEGA HTTP_30S

# Or use Java directly
mvn exec:java -Dexec.mainClass="com.reactive.platform.kafka.benchmark.CalibrationBenchmark" \
    -Dexec.args="localhost:9092 EXTREME MEGA HTTP_30S --rounds 5 --duration 30"
```

### Using Brochure System
```bash
# Run the calibration brochure
./reactive bench brochure run calibration-buckets
```

## Understanding the Output

```
╔══════════════════════════════════════════════════════════════════════════════╗
║                      ADAPTIVE MICROBATCH CALIBRATION                         ║
╚══════════════════════════════════════════════════════════════════════════════╝

Bucket     │ Activates At    │ Latency  │ Batch  │ Interval │ Throughput │ Status    │ Trend
───────────────────────────────────────────────────────────────────────────────────────────────
EXTREME    │ 10K-100K req/s  │ 20ms     │ 256    │ 8.0ms    │ 7.7M/s     │ ✓ learned │ → stable
MEGA       │ 1M-10M req/s    │ 100ms    │ 1126   │ 35.2ms   │ 9.8M/s     │ ✓ learned │ ↑ +5%
HTTP_30S   │ 10M-100M req/s  │ 1s       │ 2048   │ 64.0ms   │ 9.1M/s     │ ✓ learned │ → stable
```

### Column Descriptions

- **Bucket**: Pressure level name
- **Activates At**: Request rate range that triggers this bucket
- **Latency**: Target end-to-end latency users should expect
- **Batch**: Number of events batched together before sending
- **Interval**: Maximum time to wait before flushing a partial batch
- **Throughput**: Best observed throughput with learned config
- **Status**: `✓ learned` (from benchmarks) or `○ default` (bootstrap)
- **Trend**: Performance change indicator
  - `→ stable`: Within 10% of expected
  - `↑ +X%`: Improvement over expected
  - `⚠ -X%`: Regression detected

## Regression Detection

The system tracks expected throughput using an Exponential Moving Average (EMA):

```
expected = 0.3 * current + 0.7 * previous_expected
```

A **regression** is flagged when:
- Current throughput drops more than 10% below expected
- Trend shows `⚠ REGRESSION`

## Learned Configurations (Current)

Based on latest benchmark runs:

| Bucket | Batch Size | Flush Interval | Throughput | Notes |
|--------|------------|----------------|------------|-------|
| EXTREME | 256 | 8ms | 7.7M/s | Peak production load |
| MEGA | 1,126 | 35.2ms | 9.8M/s | **Best throughput** |
| HTTP_30S | 2,048 | 64ms | 9.1M/s | Uses latency headroom |

**Key Insight**: MEGA (batch=1,126) achieves the highest throughput, not HTTP_30S with larger batches. The system discovered that moderate batching outperforms aggressive batching on this hardware.

## Configuration Storage

Calibration data is stored in SQLite:
- Default location: `~/.reactive/calibration.db`
- Contains: `observations` table (raw data) and `best_config` table (per-bucket best)

### Viewing Raw Data
```bash
sqlite3 ~/.reactive/calibration.db "SELECT * FROM best_config"
```

## Brochure Configuration

The `brochure.yaml` defines:

```yaml
name: "Calibration - All Buckets"
component: calibration
duration: 30000  # 30 seconds per bucket

config:
  buckets:
    - EXTREME
    - MEGA
    - HTTP_30S
  learningRounds: 5
  regressionThreshold: 0.10  # Alert if throughput drops 10%+

baseline:
  MEGA:
    throughput: 9000000  # Expected baseline for regression detection
```

## Best Practices

1. **Run calibration after hardware changes** - Optimal configs are hardware-specific
2. **Monitor for regressions** - Use `--status` to check trends
3. **Allow sufficient warmup** - First few rounds explore, later rounds refine
4. **Match production load** - Benchmark the buckets you expect to use
