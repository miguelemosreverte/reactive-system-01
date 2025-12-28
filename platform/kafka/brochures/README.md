# Kafka Benchmark Brochures

This directory contains benchmarks for Kafka producer throughput. **Read carefully** to understand what each number means.

## ⚠️ IMPORTANT: Pre-Run Cleanup

**Before running ANY benchmark**, ensure Docker has sufficient disk space:

```bash
docker volume prune -f    # Free unused volumes
docker system df          # Check disk usage
docker restart reactive-kafka  # Restart Kafka
```

Disk-full conditions cause **10-100x throughput degradation** and produce misleading results.

---

## Benchmark Categories

### 1. `kafka-bulk-sendrate` - Theoretical Maximum (127M msg/s)

**What it measures:** How fast the producer can call `send()` (fire-and-forget)

| Setting | Value | Meaning |
|---------|-------|---------|
| acks | 0 | ⚠️ NO acknowledgment |
| Durability | None | Messages may be LOST |
| Flush time | NOT included | Only measures send phase |

**Use for:**
- Understanding theoretical limits
- Comparing collection overhead
- Bragging rights 😉

**NOT for:**
- Production capacity planning
- Reliable data pipelines
- Any critical data

---

### 2. `kafka-bulk-sustained` - Realistic Docker Throughput (5-10M msg/s)

**What it measures:** What Kafka can actually absorb (including flush time)

| Setting | Value | Meaning |
|---------|-------|---------|
| acks | 0 | Still fire-and-forget |
| Durability | Low | Messages buffered, not persisted |
| Flush time | INCLUDED | Measures total time |

**The gap explained:**
```
Send phase:   5 seconds  @ 127M msg/s = 635M messages
Flush phase: 120 seconds (Kafka persisting)
───────────────────────────────────────────────────
Sustained:   635M / 125s = 5M msg/s
```

**Use for:**
- Realistic capacity planning (non-critical data)
- Understanding Docker Kafka limits
- Baseline for adaptive system comparison

---

### 3. `kafka-transactional` - Production Safe (200K-500K msg/s)

**What it measures:** Throughput with guaranteed durability

| Setting | Value | Meaning |
|---------|-------|---------|
| acks | all | ✅ Wait for ALL replicas |
| Durability | Guaranteed | Messages persisted before ack |
| Idempotence | Enabled | No duplicates |

**Why 20-50x slower?**
- Producer waits for broker acknowledgment
- Broker waits for replication
- fsync to disk before acknowledging

**Use for:**
- Financial transactions
- User actions
- Audit logs
- Any data where loss is unacceptable

---

## Quick Reference

| Benchmark | Throughput | acks | Durability | Use Case |
|-----------|------------|------|------------|----------|
| `kafka-bulk-sendrate` | 127M msg/s | 0 | ❌ None | Theoretical limit |
| `kafka-bulk-sustained` | 5-10M msg/s | 0 | ⚠️ Low | Docker capacity |
| `kafka-transactional` | 200-500K msg/s | all | ✅ Guaranteed | Production |

---

## Production Recommendations

### For Metrics/Telemetry (loss acceptable):
```yaml
kafkaAcks: "1"          # Leader ack only
kafkaLingerMs: 100      # Allow batching
kafkaBatchSize: 1MB     # Large batches
```
Expected: 1-5M msg/s

### For Critical Data (loss unacceptable):
```yaml
kafkaAcks: "all"              # All replicas
kafkaEnableIdempotence: true  # No duplicates
kafkaRetries: 3               # Handle transients
```
Expected: 200K-500K msg/s

### For Maximum Throughput (loss OK, logs only):
```yaml
kafkaAcks: "0"          # Fire-and-forget
kafkaLingerMs: 5        # Minimal batching delay
kafkaBatchSize: 16MB    # Maximum batches
```
Expected: 5-10M msg/s sustained (Docker)

---

## Running Benchmarks

```bash
# Run send rate benchmark (theoretical max)
mvn exec:java -Dexec.mainClass="...StandaloneBulkBenchmark" \
    -Dexec.args="localhost:9092 5 1000"

# Run with different acks settings
mvn exec:java -Dexec.mainClass="...KafkaBaselineBenchmark" \
    -Dexec.args="BULK 10 localhost:9092"
```
