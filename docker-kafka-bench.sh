#!/bin/bash
# Docker-based Kafka benchmark

DURATION=${1:-10000}
THREADS=${2:-8}

docker run --rm --network reactive-system-01_reactive-network \
  -v /Users/miguel_lemos/Desktop/reactive-system-01:/app \
  -v maven-repo:/root/.m2 \
  -w /app \
  maven:3.9-eclipse-temurin-22 sh -c "
DEPS=\$(mvn -f application/counter/pom.xml dependency:build-classpath -Dmdep.outputFile=/dev/stdout -q -Pbenchmark | tail -1) && \\
java -cp \"platform/target/classes:platform/base/target/classes:application/counter/target/classes:application/counter/target/test-classes:\$DEPS\" \\
  com.reactive.counter.benchmark.KafkaBenchmark $DURATION $THREADS http://reactive-application:3000 http://reactive-drools:8080 /app/reports/kafka true"
