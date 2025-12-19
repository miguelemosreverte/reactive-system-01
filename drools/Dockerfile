# syntax=docker/dockerfile:1.4
# Drools Service Dockerfile - Optimized with BuildKit caching

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

# Build the application (deps already cached)
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn package -DskipTests -B

# Stage 2: Runtime (minimal)
FROM eclipse-temurin:17-jre AS runtime

ARG SERVICE_VERSION
ARG OTEL_AGENT_VERSION

WORKDIR /app

# Install curl for health checks
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Download OpenTelemetry Java agent (cached in layer)
RUN curl -L -o /app/opentelemetry-javaagent.jar \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar

# Copy the built jar
COPY --from=build /app/target/*.jar app.jar

# Copy rules for hot reload
COPY src/main/resources/rules /app/rules

# Create compile script for hot reload
RUN echo '#!/bin/sh' > /app/compile-rules.sh && \
    echo 'echo "Rules are automatically reloaded on next request"' >> /app/compile-rules.sh && \
    chmod +x /app/compile-rules.sh

# Set version
ENV SERVICE_VERSION=${SERVICE_VERSION}

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

# Run the application with OpenTelemetry agent (conditionally)
COPY <<'EOF' /app/start.sh
#!/bin/sh
if [ "$OTEL_ENABLED" = "false" ]; then
    echo "OpenTelemetry disabled"
    exec java $JAVA_OPTS -jar app.jar
else
    echo "OpenTelemetry enabled"
    exec java $JAVA_OPTS -javaagent:/app/opentelemetry-javaagent.jar -jar app.jar
fi
EOF
RUN chmod +x /app/start.sh

ENTRYPOINT ["/app/start.sh"]
