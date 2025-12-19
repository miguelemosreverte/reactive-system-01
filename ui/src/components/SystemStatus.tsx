import { useState, useEffect, useCallback } from 'react'

interface ServiceHealth {
  name: string
  status: 'healthy' | 'unhealthy' | 'checking'
  detail: string
  port: number
  category: 'infrastructure' | 'observability' | 'service' | 'application'
}

interface SystemStatusProps {
  isConnected: boolean
}

function SystemStatus({ isConnected }: SystemStatusProps) {
  const [services, setServices] = useState<ServiceHealth[]>([
    { name: 'Zookeeper', status: 'checking', detail: 'TCP 2181', port: 2181, category: 'infrastructure' },
    { name: 'Kafka', status: 'checking', detail: 'TCP 9092', port: 9092, category: 'infrastructure' },
    { name: 'OTEL Collector', status: 'checking', detail: 'Traces & Metrics', port: 4318, category: 'observability' },
    { name: 'Jaeger', status: 'checking', detail: 'Trace Storage', port: 16686, category: 'observability' },
    { name: 'Grafana', status: 'checking', detail: 'Dashboards', port: 3001, category: 'observability' },
    { name: 'Drools', status: 'checking', detail: 'Rules Engine', port: 8180, category: 'service' },
    { name: 'Flink', status: 'checking', detail: 'Stream Processor', port: 8081, category: 'service' },
    { name: 'Gateway', status: 'checking', detail: 'WebSocket Bridge', port: 8080, category: 'service' },
    { name: 'UI', status: 'checking', detail: 'React Frontend', port: 3000, category: 'service' },
    { name: 'Flink Job', status: 'checking', detail: 'Counter Processing', port: 8081, category: 'application' },
    { name: 'WebSocket', status: isConnected ? 'healthy' : 'unhealthy', detail: isConnected ? 'Connected' : 'Disconnected', port: 0, category: 'application' },
  ])

  const [lastCheck, setLastCheck] = useState<Date | null>(null)

  const checkHealth = useCallback(async () => {
    const newServices = [...services]

    // Service indices:
    // 0: Zookeeper, 1: Kafka, 2: OTEL Collector, 3: Jaeger, 4: Grafana
    // 5: Drools, 6: Flink, 7: Gateway, 8: UI
    // 9: Flink Job, 10: WebSocket

    // Check Gateway health (which confirms Kafka connectivity)
    try {
      const gatewayRes = await fetch('/api/health', { method: 'GET' })
      newServices[7].status = gatewayRes.ok ? 'healthy' : 'unhealthy'
      newServices[7].detail = gatewayRes.ok ? 'WebSocket Bridge' : 'Unreachable'

      // If gateway is healthy, assume Zookeeper and Kafka are too
      if (gatewayRes.ok) {
        newServices[0].status = 'healthy'
        newServices[0].detail = 'TCP 2181'
        newServices[1].status = 'healthy'
        newServices[1].detail = 'TCP 9092'
      }
    } catch {
      newServices[7].status = 'unhealthy'
      newServices[7].detail = 'Unreachable'
    }

    // Check OTEL Collector
    try {
      const otelRes = await fetch('/api/otel-health', { method: 'GET' })
      newServices[2].status = otelRes.ok ? 'healthy' : 'unhealthy'
      newServices[2].detail = otelRes.ok ? 'Traces & Metrics' : 'Unreachable'
    } catch {
      newServices[2].status = 'unhealthy'
      newServices[2].detail = 'Unreachable'
    }

    // Check Jaeger
    try {
      const jaegerRes = await fetch('/api/jaeger-health', { method: 'GET' })
      newServices[3].status = jaegerRes.ok ? 'healthy' : 'unhealthy'
      newServices[3].detail = jaegerRes.ok ? 'Trace Storage' : 'Unreachable'
    } catch {
      newServices[3].status = 'unhealthy'
      newServices[3].detail = 'Unreachable'
    }

    // Check Grafana
    try {
      const grafanaRes = await fetch('/api/grafana-health', { method: 'GET' })
      newServices[4].status = grafanaRes.ok ? 'healthy' : 'unhealthy'
      newServices[4].detail = grafanaRes.ok ? 'Dashboards' : 'Unreachable'
    } catch {
      newServices[4].status = 'unhealthy'
      newServices[4].detail = 'Unreachable'
    }

    // Check Drools
    try {
      const droolsRes = await fetch('/api/drools-health', { method: 'GET' })
      newServices[5].status = droolsRes.ok ? 'healthy' : 'unhealthy'
      newServices[5].detail = droolsRes.ok ? 'Rules Engine' : 'Unreachable'
    } catch {
      newServices[5].status = 'unhealthy'
      newServices[5].detail = 'Unreachable'
    }

    // Check Flink and Job
    try {
      const flinkRes = await fetch('/api/flink-health', { method: 'GET' })
      if (flinkRes.ok) {
        newServices[6].status = 'healthy'
        newServices[6].detail = 'Stream Processor'

        // Check for running jobs
        const jobsRes = await fetch('/api/flink-jobs', { method: 'GET' })
        if (jobsRes.ok) {
          const jobsData = await jobsRes.json()
          const runningJobs = jobsData.jobs?.filter((j: { state: string }) => j.state === 'RUNNING').length || 0
          newServices[9].status = runningJobs > 0 ? 'healthy' : 'unhealthy'
          newServices[9].detail = runningJobs > 0 ? `${runningJobs} job(s) running` : 'No jobs running'
        }
      } else {
        newServices[6].status = 'unhealthy'
        newServices[6].detail = 'Unreachable'
        newServices[9].status = 'unhealthy'
        newServices[9].detail = 'Cannot check'
      }
    } catch {
      newServices[6].status = 'unhealthy'
      newServices[6].detail = 'Unreachable'
      newServices[9].status = 'unhealthy'
      newServices[9].detail = 'Cannot check'
    }

    // UI is always healthy if we're rendering this
    newServices[8].status = 'healthy'
    newServices[8].detail = 'React Frontend'

    // WebSocket status from props
    newServices[10].status = isConnected ? 'healthy' : 'unhealthy'
    newServices[10].detail = isConnected ? 'Connected' : 'Disconnected'

    setServices(newServices)
    setLastCheck(new Date())
  }, [isConnected])

  useEffect(() => {
    checkHealth()
    const interval = setInterval(checkHealth, 10000) // Check every 10s
    return () => clearInterval(interval)
  }, [checkHealth])

  const getStatusIcon = (status: string) => {
    switch (status) {
      case 'healthy':
        return '✓'
      case 'unhealthy':
        return '✗'
      default:
        return '○'
    }
  }

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'healthy':
        return '#22c55e'
      case 'unhealthy':
        return '#ef4444'
      default:
        return '#94a3b8'
    }
  }

  const categories = [
    { key: 'infrastructure', label: 'Infrastructure' },
    { key: 'observability', label: 'Observability' },
    { key: 'service', label: 'Services' },
    { key: 'application', label: 'Application' },
  ]

  const allHealthy = services.every(s => s.status === 'healthy')

  return (
    <div style={styles.container}>
      <div style={styles.header}>
        <h3 style={styles.title}>System Health</h3>
        <button onClick={checkHealth} style={styles.refreshButton}>
          Refresh
        </button>
      </div>

      {categories.map(cat => (
        <div key={cat.key} style={styles.category}>
          <h4 style={styles.categoryTitle}>{cat.label}</h4>
          {services
            .filter(s => s.category === cat.key)
            .map(service => (
              <div key={service.name} style={styles.serviceRow}>
                <span style={{ ...styles.statusIcon, color: getStatusColor(service.status) }}>
                  {getStatusIcon(service.status)}
                </span>
                <span style={styles.serviceName}>{service.name}</span>
                <span style={styles.serviceDetail}>{service.detail}</span>
                {service.port > 0 && (
                  <span style={styles.servicePort}>:{service.port}</span>
                )}
              </div>
            ))}
        </div>
      ))}

      <div style={{ ...styles.summary, backgroundColor: allHealthy ? 'rgba(34, 197, 94, 0.1)' : 'rgba(239, 68, 68, 0.1)' }}>
        <span style={{ color: allHealthy ? '#22c55e' : '#ef4444' }}>
          {allHealthy ? '✓ All services healthy' : '! Some services unhealthy'}
        </span>
      </div>

      {lastCheck && (
        <p style={styles.lastCheck}>
          Last checked: {lastCheck.toLocaleTimeString()}
        </p>
      )}
    </div>
  )
}

const styles: { [key: string]: React.CSSProperties } = {
  container: {
    background: 'rgba(255, 255, 255, 0.05)',
    borderRadius: '1rem',
    padding: '1.5rem',
    backdropFilter: 'blur(10px)',
    border: '1px solid rgba(255, 255, 255, 0.1)',
  },
  header: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: '1rem',
  },
  title: {
    margin: 0,
    fontSize: '1rem',
    fontWeight: 600,
    color: '#fff',
  },
  refreshButton: {
    padding: '0.25rem 0.75rem',
    borderRadius: '0.5rem',
    border: '1px solid rgba(255, 255, 255, 0.2)',
    background: 'transparent',
    color: '#94a3b8',
    fontSize: '0.75rem',
    cursor: 'pointer',
  },
  category: {
    marginBottom: '1rem',
  },
  categoryTitle: {
    margin: '0 0 0.5rem 0',
    fontSize: '0.7rem',
    fontWeight: 500,
    color: '#64748b',
    textTransform: 'uppercase',
    letterSpacing: '0.05em',
  },
  serviceRow: {
    display: 'flex',
    alignItems: 'center',
    gap: '0.5rem',
    padding: '0.35rem 0',
    fontSize: '0.85rem',
  },
  statusIcon: {
    fontWeight: 'bold',
    width: '1rem',
  },
  serviceName: {
    color: '#fff',
    fontWeight: 500,
    minWidth: '80px',
  },
  serviceDetail: {
    color: '#64748b',
    fontSize: '0.75rem',
    flex: 1,
  },
  servicePort: {
    color: '#475569',
    fontSize: '0.7rem',
    fontFamily: 'monospace',
  },
  summary: {
    padding: '0.75rem',
    borderRadius: '0.5rem',
    textAlign: 'center',
    fontSize: '0.85rem',
    fontWeight: 500,
    marginTop: '0.5rem',
  },
  lastCheck: {
    margin: '0.75rem 0 0 0',
    fontSize: '0.7rem',
    color: '#475569',
    textAlign: 'center',
  },
}

export default SystemStatus
