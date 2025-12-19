# OTEL Collector with wget for health checks
FROM otel/opentelemetry-collector-contrib:0.91.0 AS collector

FROM alpine:3.19

# Install wget for health checks
RUN apk add --no-cache wget ca-certificates

# Copy the collector binary from the official image
COPY --from=collector /otelcol-contrib /otelcol-contrib

EXPOSE 4317 4318 8889 13133 55679

ENTRYPOINT ["/otelcol-contrib"]
