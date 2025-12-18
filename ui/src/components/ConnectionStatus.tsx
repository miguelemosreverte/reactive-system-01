interface ConnectionStatusProps {
  isConnected: boolean
  sessionId: string | null
}

function ConnectionStatus({ isConnected, sessionId }: ConnectionStatusProps) {
  return (
    <div style={styles.container}>
      <div style={styles.statusRow}>
        <span
          style={{
            ...styles.indicator,
            backgroundColor: isConnected ? '#10b981' : '#ef4444'
          }}
        />
        <span style={styles.statusText}>
          {isConnected ? 'Connected' : 'Disconnected'}
        </span>
      </div>
      {sessionId && (
        <span style={styles.sessionId}>
          Session: {sessionId.substring(0, 8)}...
        </span>
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
  sessionId: {
    fontSize: '0.7rem',
    color: '#64748b',
    fontFamily: 'monospace'
  }
}

export default ConnectionStatus
