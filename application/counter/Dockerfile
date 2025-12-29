# syntax=docker/dockerfile:1.4
# Build stage - copy entire project structure for Maven resolution
FROM maven:3.9-eclipse-temurin-22 AS build

WORKDIR /app

# Copy the entire project structure for proper Maven resolution
COPY pom.xml pom.xml
COPY platform platform
COPY application application

# Clean all target directories to remove pre-compiled classes
RUN find . -type d -name target -exec rm -rf {} + 2>/dev/null || true

# Install parent pom first
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -f pom.xml install -N -DskipTests -q

# Install all platform modules from root (ensures dependencies resolve)
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -f pom.xml install -DskipTests -q -pl platform/base,platform/http-server,platform/kafka,platform -am

# Build the counter application with Spring Boot repackage to create fat jar
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -f pom.xml package spring-boot:repackage -DskipTests -q -pl application/counter

# Runtime stage
FROM eclipse-temurin:22-jre

WORKDIR /app

# Copy the jar
COPY --from=build /app/application/counter/target/*.jar app.jar

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
