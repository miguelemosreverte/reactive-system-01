# syntax=docker/dockerfile:1.4
# Flink Service Dockerfile - Optimized with BuildKit caching

ARG SERVICE_VERSION=1.0.0
ARG OTEL_AGENT_VERSION=1.32.0

# Stage 1: Build with Maven (cached dependencies)
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy only pom.xml first for dependency caching
COPY pom.xml .

# Use BuildKit cache mount for Maven repository
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the fat jar (deps already cached)
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn package -DskipTests -B

# Stage 2: Runtime (using official Flink image)
FROM flink:1.18-java17

ARG SERVICE_VERSION
ARG OTEL_AGENT_VERSION

# Copy the job jar to the Flink jobs directory
RUN mkdir -p /opt/flink/jobs
COPY --from=build /app/target/flink-counter-job-1.0.0.jar /opt/flink/jobs/counter-job.jar

# Install curl for health checks
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Download OpenTelemetry Java agent
RUN curl -L -o /opt/flink/lib/opentelemetry-javaagent.jar \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar

# Set version and OTEL Java agent
ENV SERVICE_VERSION=${SERVICE_VERSION}
ENV FLINK_ENV_JAVA_OPTS="-javaagent:/opt/flink/lib/opentelemetry-javaagent.jar"
