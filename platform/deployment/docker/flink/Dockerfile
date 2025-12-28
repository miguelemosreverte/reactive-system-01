# syntax=docker/dockerfile:1.4
# Flink Service Dockerfile - Optimized with BuildKit caching

ARG SERVICE_VERSION=1.0.0
ARG OTEL_AGENT_VERSION=1.32.0

# Stage 1: Build with Maven (cached dependencies)
# Must use Java 17 to match Flink runtime
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy the entire project structure for proper Maven resolution
COPY pom.xml pom.xml
COPY platform platform
COPY application application

# Remove Java 21+ files that won't compile with Java 17 (Flink requirement)
# These are benchmark/optional files not needed for the Flink job
RUN rm -rf platform/kafka/src/main/java/com/reactive/platform/gateway/microbatch

# Clean all target directories to remove Java 22 pre-compiled classes
# Docker build uses Java 17, but local machine may have compiled with Java 22
RUN find . -type d -name target -exec rm -rf {} + 2>/dev/null || true

# Install parent pom first
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -f pom.xml install -N -DskipTests -q

# Install all platform modules from root (ensures dependencies resolve)
# Override Java version to 17 for Flink compatibility
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -f pom.xml install -DskipTests -q -pl platform/base,platform/http-server,platform/kafka -am \
    -Djava.version=17 -Dmaven.compiler.source=17 -Dmaven.compiler.target=17

# Copy only pom.xml first for dependency caching
COPY platform/deployment/docker/flink/pom.xml flink-job/pom.xml

# Use BuildKit cache mount for Maven repository
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -f flink-job/pom.xml dependency:go-offline -B

# Copy source code
COPY platform/deployment/docker/flink/src flink-job/src

# Build the fat jar (deps already cached, source COPY above triggers rebuild)
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -f flink-job/pom.xml package -DskipTests -B

# Stage 2: Runtime (using official Flink image)
FROM flink:1.18-java17

ARG SERVICE_VERSION
ARG OTEL_AGENT_VERSION

# Copy the job jar to the Flink jobs directory
RUN mkdir -p /opt/flink/jobs
COPY --from=build /app/flink-job/target/flink-counter-job-1.0.0.jar /opt/flink/jobs/counter-job.jar

# Copy structured logging configuration for Flink runtime
COPY platform/deployment/docker/flink/src/main/resources/log4j2.properties /opt/flink/conf/log4j-console.properties

# Install curl for health checks
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Download OpenTelemetry Java agent
RUN curl -L -o /opt/flink/lib/opentelemetry-javaagent.jar \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar

# Set version and OTEL Java agent
ENV SERVICE_VERSION=${SERVICE_VERSION}
ENV FLINK_ENV_JAVA_OPTS="-javaagent:/opt/flink/lib/opentelemetry-javaagent.jar"
