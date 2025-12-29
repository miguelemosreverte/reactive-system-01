#!/bin/bash
# Quick Kafka throughput benchmark

DURATION=${1:-5}
THREADS=${2:-8}

echo "=== Quick Kafka Benchmark (${DURATION}s, ${THREADS} threads) ==="
echo ""

DEPS=$(mvn -f application/counter/pom.xml dependency:build-classpath -Dmdep.outputFile=/dev/stdout -q -Pbenchmark 2>/dev/null | tail -1)

java -cp "platform/target/classes:platform/base/target/classes:application/counter/target/classes:application/counter/target/test-classes:$DEPS" \
  com.reactive.counter.benchmark.KafkaBenchmark $((DURATION * 1000)) $THREADS
