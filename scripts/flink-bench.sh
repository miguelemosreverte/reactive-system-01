#!/bin/bash
# Ultra-fast Flink throughput benchmark

DURATION=${1:-5}
PARALLELISM=${2:-16}

echo "=== Flink Quick Bench (${DURATION}s, parallelism=$PARALLELISM) ==="

# Get baseline count
BEFORE=$(docker logs reactive-system-01-flink-taskmanager-1 2>&1 | grep -c "Processed:" 2>/dev/null || echo 0)

# Use wrk or similar for load generation - fall back to kafka-producer-perf
docker exec reactive-kafka kafka-producer-perf-test.sh \
  --topic counter-events \
  --num-records 1000000 \
  --record-size 200 \
  --throughput -1 \
  --producer-props bootstrap.servers=localhost:9092 \
  2>&1 | tail -5 &
LOAD_PID=$!

sleep $DURATION
kill $LOAD_PID 2>/dev/null
sleep 2

# Get final count
AFTER=$(docker logs reactive-system-01-flink-taskmanager-1 2>&1 | grep -c "Processed:" 2>/dev/null || echo 0)

PROCESSED=$((AFTER - BEFORE))
THROUGHPUT=$((PROCESSED / DURATION))

echo ""
echo "Processed: $PROCESSED events in ${DURATION}s"
echo "Throughput: $THROUGHPUT events/sec"
