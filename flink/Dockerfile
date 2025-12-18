# Flink Service Dockerfile
# Multi-stage build with layer optimization

# Stage 1: Build dependencies (cached layer)
FROM maven:3.9-eclipse-temurin-17 AS deps

WORKDIR /app

# Copy only pom.xml first for dependency caching
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Stage 2: Build application
FROM deps AS build

WORKDIR /app

# Copy source code
COPY src ./src

# Build the fat jar
RUN mvn package -DskipTests -B

# Stage 3: Runtime (using official Flink image)
FROM flink:1.18-java17

# Copy the job jar to the Flink jobs directory
RUN mkdir -p /opt/flink/jobs
COPY --from=build /app/target/flink-counter-job-1.0.0.jar /opt/flink/jobs/counter-job.jar

# Install curl for health checks
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*
