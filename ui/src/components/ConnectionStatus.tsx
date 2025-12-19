interface ConnectionStatusProps {
  isConnected: boolean
  sessionId: string | null
  isReconnecting?: boolean
  reconnectAttempt?: number
}

function ConnectionStatus({ isConnected, sessionId, isReconnecting, reconnectAttempt }: ConnectionStatusProps) {
  const getStatusText = () => {
    if (isConnected) return 'Connected'
    if (isReconnecting) return `Reconnecting${reconnectAttempt ? ` (${reconnectAttempt})` : ''}...`
    return 'Disconnected'
  }

  const getIndicatorColor = () => {
    if (isConnected) return '#10b981'
    if (isReconnecting) return '#f59e0b'
    return '#ef4444'
  }

  return (
    <div style={styles.container}>
      <div style={styles.statusRow}>
        <span
          style={{
            ...styles.indicator,
            backgroundColor: getIndicatorColor(),
            animation: isReconnecting ? 'pulse 1s infinite' : 'pulse 2s infinite'
          }}
        />
        <span style={styles.statusText}>
          {getStatusText()}
        </span>
      </div>
      {sessionId && (
        <div style={styles.sessionContainer}>
          <span style={styles.sessionLabel}>Session:</span>
          <code style={styles.sessionId}>{sessionId}</code>
        </div>
      )}
    </div>
  )
}

const styles: { [key: string]: React.CSSProperties } = {
  container: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    gap: '0.25rem'
  },
  statusRow: {
    display: 'flex',
    alignItems: 'center',
    gap: '0.5rem'
  },
  indicator: {
    width: '8px',
    height: '8px',
    borderRadius: '50%',
    animation: 'pulse 2s infinite'
  },
  statusText: {
    fontSize: '0.75rem',
    color: '#94a3b8',
    textTransform: 'uppercase',
    letterSpacing: '0.05em'
  },
  sessionContainer: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    gap: '0.15rem',
    maxWidth: '100%',
  },
  sessionLabel: {
    fontSize: '0.6rem',
    color: '#64748b',
    textTransform: 'uppercase',
    letterSpacing: '0.05em',
  },
  sessionId: {
    fontSize: '0.6rem',
    color: '#94a3b8',
    fontFamily: 'monospace',
    background: 'rgba(0, 0, 0, 0.3)',
    padding: '0.2rem 0.4rem',
    borderRadius: '0.25rem',
    wordBreak: 'break-all',
    textAlign: 'center',
    maxWidth: '200px',
  }
}

export default ConnectionStatus
