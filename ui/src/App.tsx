import { useState, useCallback, useEffect, useRef } from 'react'
import { useNavigate, useLocation, useSearchParams } from 'react-router-dom'
import Counter from './components/Counter'
import ConnectionStatus from './components/ConnectionStatus'
import SystemStatus from './components/SystemStatus'
import EventFlow, { FlowStage } from './components/EventFlow'
import Documentation from './components/Documentation'
import Sidebar, { NavItem } from './components/Sidebar'
import TraceViewer from './components/TraceViewer'
import { useWebSocket } from './hooks/useWebSocket'
import { getJaegerTraceUrl } from './utils/urls'

// Map paths to nav items
const pathToNav: Record<string, NavItem> = {
  '/': 'demo',
  '/status': 'status',
  '/traces': 'traces',
  '/docs': 'docs'
}

const navToPath: Record<NavItem, string> = {
  'demo': '/',
  'status': '/status',
  'traces': '/traces',
  'docs': '/docs'
}

function App() {
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams] = useSearchParams()

  const [counterValue, setCounterValue] = useState(0)
  const [alert, setAlert] = useState<string>('NONE')
  const [message, setMessage] = useState<string>('')
  const [lastTraceId, setLastTraceId] = useState<string | null>(null)

  // Get active nav from current path
  const activeNav = pathToNav[location.pathname] || 'demo'

  // Get trace ID from URL query params
  const traceIdFromUrl = searchParams.get('traceId')

  // Flow tracking state
  const [flowStage, setFlowStage] = useState<FlowStage>('idle')
  const [actionSentTime, setActionSentTime] = useState<number | null>(null)
  const [elapsedTime, setElapsedTime] = useState<number | null>(null)
  const elapsedTimerRef = useRef<number | null>(null)

  const [lastAction, setLastAction] = useState<{
    type: string
    action: string
    value: number
    timestamp: number
    traceId?: string
  } | null>(null)
  const [lastResult, setLastResult] = useState<{
    currentValue: number
    alert: string
    message: string
    timestamp: number
    traceId?: string
  } | null>(null)

  // Update elapsed time while processing
  useEffect(() => {
    if (flowStage === 'sending' || flowStage === 'processing') {
      elapsedTimerRef.current = window.setInterval(() => {
        if (actionSentTime) {
          setElapsedTime(Date.now() - actionSentTime)
        }
      }, 10) // Update every 10ms for smooth display
    } else {
      if (elapsedTimerRef.current) {
        clearInterval(elapsedTimerRef.current)
        elapsedTimerRef.current = null
      }
    }
    return () => {
      if (elapsedTimerRef.current) {
        clearInterval(elapsedTimerRef.current)
      }
    }
  }, [flowStage, actionSentTime])

  const { isConnected, sessionId, sendMessage, isReconnecting, reconnectAttempt } = useWebSocket({
    onMessage: useCallback((msg: { type: string; traceId?: string; data?: { currentValue: number; alert: string; message: string; traceId?: string } }) => {
      if (msg.type === 'counter-update' && msg.data) {
        // Result received - flow is complete!
        setCounterValue(msg.data.currentValue)
        setAlert(msg.data.alert)
        setMessage(msg.data.message)
        if (msg.data.traceId) {
          setLastTraceId(msg.data.traceId)
        }
        setLastResult({
          currentValue: msg.data.currentValue,
          alert: msg.data.alert,
          message: msg.data.message,
          timestamp: Date.now(),
          traceId: msg.data.traceId
        })

        // Mark flow as complete and capture final elapsed time
        setFlowStage('complete')
        if (actionSentTime) {
          setElapsedTime(Date.now() - actionSentTime)
        }
      } else if (msg.type === 'action-ack' && msg.traceId) {
        // Gateway acknowledged - event is now in Kafka pipeline
        setLastTraceId(msg.traceId)
        setFlowStage('processing')

        // Update lastAction with the traceId
        setLastAction(prev => prev ? { ...prev, traceId: msg.traceId } : null)
      }
    }, [actionSentTime])
  })

  const sendAction = (action: string, value: number) => {
    const now = Date.now()
    const actionData = {
      type: 'counter-action',
      action,
      value
    }

    // Start flow tracking
    setFlowStage('sending')
    setActionSentTime(now)
    setElapsedTime(0)

    sendMessage(actionData)
    setLastAction({
      ...actionData,
      timestamp: now
    })
  }

  const handleIncrement = () => sendAction('increment', 1)
  const handleDecrement = () => sendAction('decrement', 1)
  const handleReset = () => sendAction('set', 0)
  const handleSetValue = (value: number) => sendAction('set', value)

  const renderContent = () => {
    switch (activeNav) {
      case 'demo':
        return (
          <div style={styles.demoContent}>
            <div style={styles.demoHeader}>
              <h1 style={styles.pageTitle}>Live Demo</h1>
              <p style={styles.pageSubtitle}>
                Interact with the reactive system in real-time
              </p>
              {lastTraceId && (
                <div style={styles.traceInfo}>
                  <span style={styles.traceLabel}>Last Trace ID:</span>
                  <code style={styles.traceCode}>{lastTraceId}</code>
                  <a
                    href={getJaegerTraceUrl(lastTraceId)}
                    target="_blank"
                    rel="noopener noreferrer"
                    style={styles.viewTraceButton}
                  >
                    View in Jaeger
                  </a>
                  <button
                    onClick={() => navigate(`/traces?traceId=${lastTraceId}`)}
                    style={styles.viewTraceButton}
                  >
                    Trace Viewer
                  </button>
                </div>
              )}
            </div>

            <div style={styles.demoGrid}>
              {/* Left Column - Counter */}
              <div style={styles.counterSection}>
                <ConnectionStatus
                  isConnected={isConnected}
                  sessionId={sessionId}
                  isReconnecting={isReconnecting}
                  reconnectAttempt={reconnectAttempt}
                />
                <Counter
                  value={counterValue}
                  alert={alert}
                  message={message}
                  onIncrement={handleIncrement}
                  onDecrement={handleDecrement}
                  onReset={handleReset}
                  onSetValue={handleSetValue}
                />
              </div>

              {/* Right Column - Event Flow */}
              <div style={styles.infoSection}>
                <EventFlow
                  flowStage={flowStage}
                  lastAction={lastAction}
                  lastResult={lastResult}
                  elapsedTime={elapsedTime}
                />
              </div>
            </div>

            {/* Architecture Description */}
            <div style={styles.architectureSection}>
              <h3 style={styles.sectionTitle}>How It Works</h3>
              <div style={styles.flowDiagram}>
                <div style={styles.flowStep}>
                  <div style={styles.flowIcon}>1</div>
                  <div style={styles.flowContent}>
                    <strong>User Action</strong>
                    <span>Click buttons in UI</span>
                  </div>
                </div>
                <div style={styles.flowArrow}>→</div>
                <div style={styles.flowStep}>
                  <div style={styles.flowIcon}>2</div>
                  <div style={styles.flowContent}>
                    <strong>Gateway</strong>
                    <span>WebSocket → Kafka</span>
                  </div>
                </div>
                <div style={styles.flowArrow}>→</div>
                <div style={styles.flowStep}>
                  <div style={styles.flowIcon}>3</div>
                  <div style={styles.flowContent}>
                    <strong>Flink</strong>
                    <span>Stream processing</span>
                  </div>
                </div>
                <div style={styles.flowArrow}>→</div>
                <div style={styles.flowStep}>
                  <div style={styles.flowIcon}>4</div>
                  <div style={styles.flowContent}>
                    <strong>Drools</strong>
                    <span>Business rules</span>
                  </div>
                </div>
                <div style={styles.flowArrow}>→</div>
                <div style={styles.flowStep}>
                  <div style={styles.flowIcon}>5</div>
                  <div style={styles.flowContent}>
                    <strong>Result</strong>
                    <span>Real-time update</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        )
      case 'status':
        return (
          <div style={styles.statusContent}>
            <div style={styles.demoHeader}>
              <h1 style={styles.pageTitle}>System Status</h1>
              <p style={styles.pageSubtitle}>
                Monitor the health and status of all services
              </p>
            </div>
            <SystemStatus isConnected={isConnected} />
          </div>
        )
      case 'traces':
        return <TraceViewer initialTraceId={traceIdFromUrl} />
      case 'docs':
        return (
          <div style={styles.docsContent}>
            <Documentation />
          </div>
        )
      default:
        return null
    }
  }

  return (
    <div style={styles.container}>
      <Sidebar
        activeItem={activeNav}
        onNavigate={(item) => navigate(navToPath[item])}
        isConnected={isConnected}
      />
      <main style={styles.main}>
        {renderContent()}
      </main>
    </div>
  )
}

const styles: { [key: string]: React.CSSProperties } = {
  container: {
    display: 'flex',
    minHeight: '100vh',
    color: '#fff'
  },
  main: {
    flex: 1,
    overflow: 'auto'
  },
  demoContent: {
    padding: '1.5rem',
    display: 'flex',
    flexDirection: 'column',
    gap: '1.5rem'
  },
  statusContent: {
    padding: '1.5rem'
  },
  docsContent: {
    padding: '1.5rem'
  },
  demoHeader: {
    marginBottom: '0.5rem'
  },
  pageTitle: {
    fontSize: '1.5rem',
    fontWeight: 600,
    color: '#fff',
    margin: 0
  },
  pageSubtitle: {
    fontSize: '0.875rem',
    color: '#94a3b8',
    margin: '0.5rem 0 0'
  },
  traceInfo: {
    display: 'flex',
    alignItems: 'center',
    gap: '0.75rem',
    marginTop: '1rem',
    padding: '0.75rem 1rem',
    background: 'rgba(79, 70, 229, 0.1)',
    borderRadius: '0.5rem',
    border: '1px solid rgba(79, 70, 229, 0.3)'
  },
  traceLabel: {
    fontSize: '0.75rem',
    color: '#94a3b8'
  },
  traceCode: {
    fontSize: '0.7rem',
    color: '#818cf8',
    background: 'rgba(0, 0, 0, 0.3)',
    padding: '0.25rem 0.5rem',
    borderRadius: '0.25rem',
    fontFamily: 'monospace',
    wordBreak: 'break-all',
    flex: 1,
  },
  viewTraceButton: {
    padding: '0.4rem 0.75rem',
    background: 'rgba(79, 70, 229, 0.3)',
    border: '1px solid rgba(79, 70, 229, 0.5)',
    borderRadius: '0.25rem',
    color: '#fff',
    fontSize: '0.7rem',
    cursor: 'pointer',
    textDecoration: 'none',
    whiteSpace: 'nowrap',
  },
  demoGrid: {
    display: 'grid',
    gridTemplateColumns: '1fr 1fr',
    gap: '1.5rem',
    alignItems: 'start'
  },
  counterSection: {
    display: 'flex',
    flexDirection: 'column',
    gap: '1rem',
    alignItems: 'center'
  },
  infoSection: {
    display: 'flex',
    flexDirection: 'column',
    gap: '1rem'
  },
  architectureSection: {
    background: 'rgba(255, 255, 255, 0.05)',
    borderRadius: '1rem',
    padding: '1.5rem',
    backdropFilter: 'blur(10px)',
    border: '1px solid rgba(255, 255, 255, 0.1)'
  },
  sectionTitle: {
    margin: '0 0 1rem 0',
    fontSize: '1rem',
    fontWeight: 600,
    color: '#fff',
    textAlign: 'center'
  },
  flowDiagram: {
    display: 'flex',
    justifyContent: 'center',
    alignItems: 'center',
    flexWrap: 'wrap',
    gap: '0.5rem'
  },
  flowStep: {
    display: 'flex',
    alignItems: 'center',
    gap: '0.5rem',
    padding: '0.5rem 0.75rem',
    background: 'rgba(79, 70, 229, 0.1)',
    borderRadius: '0.5rem',
    border: '1px solid rgba(79, 70, 229, 0.3)'
  },
  flowIcon: {
    width: '24px',
    height: '24px',
    borderRadius: '50%',
    background: '#4f46e5',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontSize: '0.75rem',
    fontWeight: 600
  },
  flowContent: {
    display: 'flex',
    flexDirection: 'column',
    fontSize: '0.75rem'
  },
  flowArrow: {
    color: '#64748b',
    fontSize: '1rem'
  }
}

export default App
