# syntax=docker/dockerfile:1.4
# Gateway Service Dockerfile - Optimized with BuildKit caching

ARG SERVICE_VERSION=1.0.0
ARG BUILD_TIME

# Stage 1: Dependencies (heavily cached)
FROM node:20-alpine AS deps

WORKDIR /app

# Copy package files for dependency caching
COPY package.json package-lock.json* ./

# Use BuildKit cache mount for npm cache
RUN --mount=type=cache,target=/root/.npm \
    if [ -f package-lock.json ]; then npm ci --only=production; else npm install --only=production; fi

# Stage 2: Build (source changes only)
FROM node:20-alpine AS build

WORKDIR /app

COPY package.json package-lock.json* ./

# Use BuildKit cache mount for npm cache
RUN --mount=type=cache,target=/root/.npm \
    if [ -f package-lock.json ]; then npm ci; else npm install; fi

# Copy source code
COPY tsconfig.json ./
COPY src ./src

# Build TypeScript
RUN npm run build

# Stage 3: Runtime (minimal)
FROM node:20-alpine AS runtime

ARG SERVICE_VERSION
ARG BUILD_TIME

WORKDIR /app

# Install curl for health checks
RUN apk add --no-cache curl

# Set version info
ENV SERVICE_VERSION=${SERVICE_VERSION}
ENV BUILD_TIME=${BUILD_TIME}

# Copy production dependencies from deps stage
COPY --from=deps /app/node_modules ./node_modules

# Copy built files from build stage
COPY --from=build /app/dist ./dist
COPY --from=build /app/package.json ./

# Create non-root user
RUN addgroup -g 1001 -S nodejs && \
    adduser -S nodejs -u 1001

USER nodejs

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=10s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

# Start the server
CMD ["node", "dist/index.js"]
