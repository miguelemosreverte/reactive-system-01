/**
 * URL utilities for building external service URLs that work in any environment.
 * Uses current hostname to avoid hardcoded localhost issues in production.
 */

export const getJaegerUrl = (): string => {
  const hostname = window.location.hostname
  return import.meta.env.VITE_JAEGER_URL || `http://${hostname}:16686`
}

export const getGrafanaUrl = (): string => {
  const hostname = window.location.hostname
  return import.meta.env.VITE_GRAFANA_URL || `http://${hostname}:3001`
}

export const getFlinkUrl = (): string => {
  const hostname = window.location.hostname
  return import.meta.env.VITE_FLINK_URL || `http://${hostname}:8081`
}

/**
 * Build a Jaeger trace URL for a specific trace ID
 */
export const getJaegerTraceUrl = (traceId: string): string => {
  return `${getJaegerUrl()}/trace/${traceId}`
}
