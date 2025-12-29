#!/bin/bash
# Quick Flink throughput benchmark - 10 second test with real-time metrics

DURATION=${1:-10}
echo "=== Quick Flink Benchmark (${DURATION}s) ==="

# Clear previous logs
docker logs reactive-system-01-flink-taskmanager-1 --since 1s > /dev/null 2>&1

# Get initial count
START_COUNT=$(docker logs reactive-system-01-flink-taskmanager-1 2>&1 | grep -c "Processed:" || echo 0)
START_TIME=$(date +%s.%N)

# Send burst of events to Kafka via gateway
echo "Sending events..."
docker run --rm --network reactive-system-01_reactive-network curlimages/curl:latest \
  sh -c "for i in \$(seq 1 100000); do curl -s -X POST http://gateway:3000/counter/quick-test/increment > /dev/null & done; wait" &
CURL_PID=$!

# Wait for test duration
sleep $DURATION
kill $CURL_PID 2>/dev/null

# Get final count
END_COUNT=$(docker logs reactive-system-01-flink-taskmanager-1 2>&1 | grep -c "Processed:")
END_TIME=$(date +%s.%N)

# Calculate throughput
EVENTS=$((END_COUNT - START_COUNT))
DURATION_ACTUAL=$(echo "$END_TIME - $START_TIME" | bc)
THROUGHPUT=$(echo "scale=0; $EVENTS / $DURATION_ACTUAL" | bc)

echo ""
echo "=== Results ==="
echo "Events processed: $EVENTS"
echo "Duration: ${DURATION_ACTUAL}s"
echo "Throughput: $THROUGHPUT events/sec"
echo ""
