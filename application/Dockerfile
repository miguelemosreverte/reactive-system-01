# Build stage
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Copy platform module first (dependency)
COPY platform/pom.xml platform/pom.xml
COPY platform/src platform/src

# Install platform to local repo
WORKDIR /build/platform
RUN mvn clean install -DskipTests -q

# Copy application module
WORKDIR /build
COPY application/pom.xml application/pom.xml
COPY application/src application/src

# Build application
WORKDIR /build/application
RUN mvn clean package -DskipTests -q

# Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy the jar
COPY --from=builder /build/application/target/*.jar app.jar

# OpenTelemetry Java Agent for auto-instrumentation
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v1.32.0/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar

# Environment
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxGCPauseMillis=10 -Xms256m -Xmx512m"
ENV OTEL_ENABLED="true"
ENV OTEL_SERVICE_NAME="counter-application"
ENV OTEL_EXPORTER_OTLP_ENDPOINT="http://otel-collector:4317"

EXPOSE 3000

# Start with OpenTelemetry agent
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -javaagent:/app/opentelemetry-javaagent.jar -jar app.jar"]
