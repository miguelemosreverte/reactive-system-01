/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_JAEGER_URL?: string
  readonly VITE_GRAFANA_URL?: string
  readonly VITE_FLINK_URL?: string
  readonly VITE_ADMIN_API_KEY?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
