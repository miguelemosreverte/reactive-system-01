# Drools Service Dockerfile
# Multi-stage build with layer optimization

# Stage 1: Build dependencies (cached layer)
FROM maven:3.9-eclipse-temurin-17-alpine AS deps

WORKDIR /app

# Copy only pom.xml first for dependency caching
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Stage 2: Build application
FROM deps AS build

WORKDIR /app

# Copy source code
COPY src ./src

# Build the application
RUN mvn package -DskipTests -B

# Stage 3: Runtime
FROM eclipse-temurin:17-jre-alpine AS runtime

WORKDIR /app

# Install curl for health checks
RUN apk add --no-cache curl

# Copy the built jar
COPY --from=build /app/target/*.jar app.jar

# Copy rules for hot reload
COPY src/main/resources/rules /app/rules

# Create compile script for hot reload
RUN echo '#!/bin/sh' > /app/compile-rules.sh && \
    echo 'echo "Rules are automatically reloaded on next request"' >> /app/compile-rules.sh && \
    chmod +x /app/compile-rules.sh

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
