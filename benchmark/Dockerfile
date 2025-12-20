FROM golang:1.23-alpine AS builder

WORKDIR /app

# Install git for go mod
RUN apk add --no-cache git

# Copy go mod files first
COPY go.mod ./
RUN go mod download || true

# Copy source
COPY . .

# Tidy and build
RUN go mod tidy && CGO_ENABLED=0 GOOS=linux go build -o /benchmark ./cmd/benchmark

# Runtime stage
FROM alpine:3.19

RUN apk add --no-cache ca-certificates curl

WORKDIR /app

COPY --from=builder /benchmark /app/benchmark

# Create reports directory
RUN mkdir -p /reports

ENV PORT=8090
ENV REPORT_DIR=/reports

EXPOSE 8090

ENTRYPOINT ["/app/benchmark"]
