FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy pom.xml first for dependency caching
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN mvn package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Install curl for health checks
RUN apk add --no-cache curl

# Download OpenTelemetry Java Agent
ARG OTEL_AGENT_VERSION=1.32.0
RUN curl -L -o /app/opentelemetry-javaagent.jar \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar

# Copy the built jar
COPY --from=build /app/target/*.jar app.jar

# JVM tuning for low latency
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxGCPauseMillis=10 -XX:+UseStringDeduplication -Xms256m -Xmx512m"

# OpenTelemetry configuration
ENV OTEL_SERVICE_NAME=gateway
ENV OTEL_TRACES_EXPORTER=otlp
ENV OTEL_METRICS_EXPORTER=otlp
ENV OTEL_LOGS_EXPORTER=none
ENV OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317

EXPOSE 3000

# Start with OpenTelemetry Java Agent
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -javaagent:/app/opentelemetry-javaagent.jar -jar app.jar"]
