# syntax=docker/dockerfile:1.4
# UI Service Dockerfile - Optimized with BuildKit caching

ARG SERVICE_VERSION=1.0.0

# Stage 1: Dependencies (heavily cached)
FROM node:20-alpine AS deps

WORKDIR /app

# Copy package files for dependency caching
COPY package.json package-lock.json* ./

# Use BuildKit cache mount for npm cache
RUN --mount=type=cache,target=/root/.npm \
    if [ -f package-lock.json ]; then npm ci; else npm install; fi

# Stage 2: Build (source changes only)
FROM deps AS build

WORKDIR /app

# Copy source code
COPY . .

# Build the application
RUN npm run build

# Stage 3: Production (nginx)
FROM nginx:alpine AS runtime

ARG SERVICE_VERSION

# Copy custom nginx config
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy built assets
COPY --from=build /app/dist /usr/share/nginx/html

# Create docs directory
RUN mkdir -p /usr/share/nginx/docs/ui /usr/share/nginx/docs/gateway /usr/share/nginx/docs/flink /usr/share/nginx/docs/drools

# Placeholder for docs
RUN echo "# Documentation" > /usr/share/nginx/docs/DOCUMENTATION.md

# Set version
ENV SERVICE_VERSION=${SERVICE_VERSION}

# Expose port
EXPOSE 80

# Health check
HEALTHCHECK --interval=10s --timeout=5s --start-period=5s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:80 || exit 1

# Start nginx
CMD ["nginx", "-g", "daemon off;"]
