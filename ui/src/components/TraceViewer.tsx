import React, { useState, useEffect, useCallback } from 'react'
import { getJaegerUrl, getJaegerTraceUrl } from '../utils/urls'

interface Trace {
  traceId: string
  sessionId: string
  timestamp: number
  action: string
}

interface JaegerTrace {
  traceID: string
  spans: JaegerSpan[]
  processes: Record<string, JaegerProcess>
}

interface JaegerSpan {
  traceID: string
  spanID: string
  operationName: string
  startTime: number
  duration: number
  tags: { key: string; value: string }[]
  processID: string
}

interface JaegerProcess {
  serviceName: string
}

interface TraceViewerProps {
  apiKey?: string
  initialTraceId?: string | null
}

// API key is configured via environment variable for production deployments
// In a real production system, consider using session-based auth or OAuth instead
const getApiKey = (): string => {
  return import.meta.env.VITE_ADMIN_API_KEY || 'reactive-admin-key'
}

const TraceViewer: React.FC<TraceViewerProps> = ({ apiKey = getApiKey(), initialTraceId }) => {
  const [recentTraces, setRecentTraces] = useState<Trace[]>([])
  const [selectedTrace, setSelectedTrace] = useState<JaegerTrace | null>(null)
  const [searchTraceId, setSearchTraceId] = useState(initialTraceId || '')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fetchError, setFetchError] = useState<string | null>(null)

  const fetchRecentTraces = useCallback(async () => {
    try {
      const response = await fetch('/api/admin/traces?limit=20', {
        headers: { 'x-api-key': apiKey }
      })
      if (response.ok) {
        const data = await response.json()
        setRecentTraces(data.traces || [])
        setFetchError(null)
      } else if (response.status === 401) {
        setFetchError('Unauthorized - Invalid API key')
      } else {
        setFetchError(`Failed to fetch traces (${response.status})`)
      }
    } catch (err) {
      setFetchError('Unable to connect to trace service')
      console.error('Failed to fetch recent traces:', err)
    }
  }, [apiKey])

  useEffect(() => {
    fetchRecentTraces()
    const interval = setInterval(fetchRecentTraces, 5000)
    return () => clearInterval(interval)
  }, [fetchRecentTraces])

  // Auto-fetch trace details when initialTraceId changes
  useEffect(() => {
    if (initialTraceId) {
      setSearchTraceId(initialTraceId)
      fetchTraceDetails(initialTraceId)
    }
  }, [initialTraceId])

  const fetchTraceDetails = async (traceId: string) => {
    setLoading(true)
    setError(null)
    try {
      const response = await fetch(`/api/admin/traces/${traceId}`, {
        headers: { 'x-api-key': apiKey }
      })
      if (response.ok) {
        const data = await response.json()
        if (data.data && data.data.length > 0) {
          setSelectedTrace(data.data[0])
        } else {
          setError('Trace not found in Jaeger')
        }
      } else {
        setError('Failed to fetch trace details')
      }
    } catch (err) {
      setError('Error connecting to trace service')
    }
    setLoading(false)
  }

  const handleSearch = () => {
    if (searchTraceId.trim()) {
      fetchTraceDetails(searchTraceId.trim())
    }
  }

  const formatDuration = (microseconds: number) => {
    if (microseconds < 1000) return `${microseconds}µs`
    if (microseconds < 1000000) return `${(microseconds / 1000).toFixed(2)}ms`
    return `${(microseconds / 1000000).toFixed(2)}s`
  }

  const formatTimestamp = (timestamp: number) => {
    return new Date(timestamp).toLocaleTimeString()
  }

  return (
    <div style={styles.container}>
      <div style={styles.header}>
        <h2 style={styles.title}>Distributed Traces</h2>
        <p style={styles.subtitle}>
          Query and visualize traces across all services
        </p>
      </div>

      {/* Search Section */}
      <div style={styles.searchSection}>
        <div style={styles.searchBox}>
          <input
            type="text"
            value={searchTraceId}
            onChange={(e) => setSearchTraceId(e.target.value)}
            placeholder="Enter trace ID to search..."
            style={styles.searchInput}
            onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
          />
          <button onClick={handleSearch} style={styles.searchButton}>
            Search
          </button>
        </div>
        <a
          href={getJaegerUrl()}
          target="_blank"
          rel="noopener noreferrer"
          style={styles.jaegerLink}
        >
          Open Jaeger UI ↗
        </a>
      </div>

      <div style={styles.content}>
        {/* Recent Traces */}
        <div style={styles.recentTraces}>
          <h3 style={styles.sectionTitle}>Recent Traces</h3>
          <div style={styles.traceList}>
            {fetchError ? (
              <div style={styles.error}>{fetchError}</div>
            ) : recentTraces.length === 0 ? (
              <div style={styles.emptyState}>
                No traces yet. Perform some actions in the demo to generate traces.
              </div>
            ) : (
              recentTraces.map((trace, idx) => (
                <div
                  key={`${trace.traceId}-${idx}`}
                  style={styles.traceItem}
                  onClick={() => fetchTraceDetails(trace.traceId)}
                >
                  <div style={styles.traceHeader}>
                    <span style={styles.traceAction}>{trace.action}</span>
                    <span style={styles.traceTime}>{formatTimestamp(trace.timestamp)}</span>
                  </div>
                  <div style={styles.traceId}>
                    <span style={styles.traceIdLabel}>Trace:</span>
                    <code style={styles.traceIdValue}>{trace.traceId}</code>
                  </div>
                  <div style={styles.traceSession}>
                    Session: {trace.sessionId}
                  </div>
                  <a
                    href={getJaegerTraceUrl(trace.traceId)}
                    target="_blank"
                    rel="noopener noreferrer"
                    style={styles.jaegerTraceLink}
                    onClick={(e) => e.stopPropagation()}
                  >
                    Open in Jaeger
                  </a>
                </div>
              ))
            )}
          </div>
        </div>

        {/* Trace Details */}
        <div style={styles.traceDetails}>
          <h3 style={styles.sectionTitle}>Trace Details</h3>
          {loading && <div style={styles.loading}>Loading trace...</div>}
          {error && <div style={styles.error}>{error}</div>}
          {selectedTrace && !loading && (
            <div style={styles.traceView}>
              <div style={styles.traceViewHeader}>
                <code style={styles.fullTraceId}>{selectedTrace.traceID}</code>
                <span style={styles.spanCount}>
                  {selectedTrace.spans.length} spans
                </span>
              </div>
              <div style={styles.spanList}>
                {selectedTrace.spans
                  .sort((a, b) => a.startTime - b.startTime)
                  .map((span) => {
                    const process = selectedTrace.processes[span.processID]
                    return (
                      <div key={span.spanID} style={styles.spanItem}>
                        <div style={styles.spanHeader}>
                          <span style={styles.serviceName}>
                            {process?.serviceName || 'unknown'}
                          </span>
                          <span style={styles.operationName}>
                            {span.operationName}
                          </span>
                          <span style={styles.duration}>
                            {formatDuration(span.duration)}
                          </span>
                        </div>
                        <div style={styles.spanTags}>
                          {span.tags.slice(0, 5).map((tag, idx) => (
                            <span key={idx} style={styles.tag}>
                              {tag.key}: {String(tag.value).substring(0, 30)}
                            </span>
                          ))}
                        </div>
                      </div>
                    )
                  })}
              </div>
            </div>
          )}
          {!selectedTrace && !loading && !error && (
            <div style={styles.emptyState}>
              Select a trace from the list or search by trace ID
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

const styles: { [key: string]: React.CSSProperties } = {
  container: {
    padding: '1.5rem'
  },
  header: {
    marginBottom: '1.5rem'
  },
  title: {
    fontSize: '1.5rem',
    fontWeight: 600,
    color: '#fff',
    margin: 0
  },
  subtitle: {
    fontSize: '0.875rem',
    color: '#94a3b8',
    margin: '0.5rem 0 0'
  },
  searchSection: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: '1.5rem',
    gap: '1rem',
    flexWrap: 'wrap'
  },
  searchBox: {
    display: 'flex',
    gap: '0.5rem',
    flex: 1,
    maxWidth: '500px'
  },
  searchInput: {
    flex: 1,
    padding: '0.75rem 1rem',
    background: 'rgba(0, 0, 0, 0.3)',
    border: '1px solid rgba(255, 255, 255, 0.1)',
    borderRadius: '0.5rem',
    color: '#fff',
    fontSize: '0.875rem'
  },
  searchButton: {
    padding: '0.75rem 1.5rem',
    background: '#4f46e5',
    border: 'none',
    borderRadius: '0.5rem',
    color: '#fff',
    fontSize: '0.875rem',
    cursor: 'pointer'
  },
  jaegerLink: {
    padding: '0.75rem 1rem',
    background: 'rgba(255, 255, 255, 0.05)',
    border: '1px solid rgba(255, 255, 255, 0.1)',
    borderRadius: '0.5rem',
    color: '#818cf8',
    textDecoration: 'none',
    fontSize: '0.875rem'
  },
  content: {
    display: 'grid',
    gridTemplateColumns: '350px 1fr',
    gap: '1.5rem'
  },
  recentTraces: {
    background: 'rgba(0, 0, 0, 0.2)',
    borderRadius: '1rem',
    padding: '1rem',
    border: '1px solid rgba(255, 255, 255, 0.1)'
  },
  traceDetails: {
    background: 'rgba(0, 0, 0, 0.2)',
    borderRadius: '1rem',
    padding: '1rem',
    border: '1px solid rgba(255, 255, 255, 0.1)'
  },
  sectionTitle: {
    fontSize: '0.875rem',
    fontWeight: 600,
    color: '#fff',
    margin: '0 0 1rem',
    paddingBottom: '0.5rem',
    borderBottom: '1px solid rgba(255, 255, 255, 0.1)'
  },
  traceList: {
    display: 'flex',
    flexDirection: 'column',
    gap: '0.5rem',
    maxHeight: '500px',
    overflowY: 'auto'
  },
  traceItem: {
    padding: '0.75rem',
    background: 'rgba(255, 255, 255, 0.05)',
    borderRadius: '0.5rem',
    cursor: 'pointer',
    transition: 'background 0.2s ease'
  },
  traceHeader: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: '0.25rem'
  },
  traceAction: {
    fontSize: '0.8rem',
    fontWeight: 600,
    color: '#818cf8',
    textTransform: 'capitalize'
  },
  traceTime: {
    fontSize: '0.7rem',
    color: '#64748b'
  },
  traceId: {
    display: 'flex',
    alignItems: 'center',
    gap: '0.5rem',
    marginBottom: '0.25rem'
  },
  traceIdLabel: {
    fontSize: '0.7rem',
    color: '#64748b'
  },
  traceIdValue: {
    fontSize: '0.6rem',
    color: '#94a3b8',
    background: 'rgba(0, 0, 0, 0.3)',
    padding: '0.15rem 0.4rem',
    borderRadius: '0.25rem',
    wordBreak: 'break-all',
    fontFamily: 'monospace',
  },
  traceSession: {
    fontSize: '0.65rem',
    color: '#64748b',
    wordBreak: 'break-all',
  },
  jaegerTraceLink: {
    display: 'inline-block',
    marginTop: '0.5rem',
    fontSize: '0.65rem',
    color: '#818cf8',
    textDecoration: 'none',
    padding: '0.25rem 0.5rem',
    background: 'rgba(79, 70, 229, 0.2)',
    borderRadius: '0.25rem',
    border: '1px solid rgba(79, 70, 229, 0.3)',
  },
  emptyState: {
    padding: '2rem',
    textAlign: 'center',
    color: '#64748b',
    fontSize: '0.875rem'
  },
  loading: {
    padding: '2rem',
    textAlign: 'center',
    color: '#818cf8'
  },
  error: {
    padding: '1rem',
    background: 'rgba(239, 68, 68, 0.1)',
    border: '1px solid rgba(239, 68, 68, 0.3)',
    borderRadius: '0.5rem',
    color: '#f87171',
    fontSize: '0.875rem'
  },
  traceView: {
    display: 'flex',
    flexDirection: 'column',
    gap: '1rem'
  },
  traceViewHeader: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: '0.75rem',
    background: 'rgba(79, 70, 229, 0.1)',
    borderRadius: '0.5rem',
    border: '1px solid rgba(79, 70, 229, 0.3)'
  },
  fullTraceId: {
    fontSize: '0.75rem',
    color: '#818cf8'
  },
  spanCount: {
    fontSize: '0.75rem',
    color: '#94a3b8'
  },
  spanList: {
    display: 'flex',
    flexDirection: 'column',
    gap: '0.5rem'
  },
  spanItem: {
    padding: '0.75rem',
    background: 'rgba(255, 255, 255, 0.03)',
    borderRadius: '0.5rem',
    borderLeft: '3px solid #4f46e5'
  },
  spanHeader: {
    display: 'flex',
    alignItems: 'center',
    gap: '0.75rem',
    marginBottom: '0.5rem'
  },
  serviceName: {
    fontSize: '0.75rem',
    fontWeight: 600,
    color: '#10b981',
    background: 'rgba(16, 185, 129, 0.1)',
    padding: '0.2rem 0.5rem',
    borderRadius: '0.25rem'
  },
  operationName: {
    fontSize: '0.8rem',
    color: '#fff'
  },
  duration: {
    marginLeft: 'auto',
    fontSize: '0.75rem',
    color: '#fbbf24',
    fontFamily: 'monospace'
  },
  spanTags: {
    display: 'flex',
    flexWrap: 'wrap',
    gap: '0.5rem'
  },
  tag: {
    fontSize: '0.65rem',
    color: '#94a3b8',
    background: 'rgba(0, 0, 0, 0.3)',
    padding: '0.15rem 0.5rem',
    borderRadius: '0.25rem'
  }
}

export default TraceViewer
