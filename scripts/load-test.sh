#!/bin/bash
# Simple high-throughput load test using parallel curl requests

GATEWAY_URL="http://localhost:8080/api/counter"
DURATION=${1:-30}
CONCURRENCY=${2:-50}

echo "=== High-Throughput Load Test ==="
echo "Duration: ${DURATION}s"
echo "Concurrency: ${CONCURRENCY} parallel requests"
echo ""

# Get initial count
initial=$(curl -s 'http://localhost:9090/api/v1/query?query=sum(flink_taskmanager_job_task_operator_numRecordsIn)' | jq -r '.data.result[0].value[1]')
echo "Initial Flink records: $initial"
echo ""
echo "Starting load test..."

start_time=$(date +%s)
sent=0

# Run for specified duration
while true; do
    current_time=$(date +%s)
    elapsed=$((current_time - start_time))

    if [ $elapsed -ge $DURATION ]; then
        break
    fi

    # Send batch of concurrent requests
    for i in $(seq 1 $CONCURRENCY); do
        curl -s -X POST "$GATEWAY_URL" \
            -H "Content-Type: application/json" \
            -d '{"action":"increment","value":1,"sessionId":"loadtest"}' &
    done

    # Wait for batch to complete
    wait
    sent=$((sent + CONCURRENCY))

    # Brief pause to prevent overwhelming
    sleep 0.01
done

echo ""
echo "Sent approximately $sent requests in ${DURATION}s"

# Wait for processing
echo "Waiting 5s for processing..."
sleep 5

# Get final count
final=$(curl -s 'http://localhost:9090/api/v1/query?query=sum(flink_taskmanager_job_task_operator_numRecordsIn)' | jq -r '.data.result[0].value[1]')
processed=$((final - initial))

echo ""
echo "=== Results ==="
echo "Final Flink records: $final"
echo "Records processed: $processed"
echo "Throughput: $((processed / DURATION)) records/sec"
