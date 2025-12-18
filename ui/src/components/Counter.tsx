import { useState } from 'react'

interface CounterProps {
  value: number
  alert: string
  message: string
  onIncrement: () => void
  onDecrement: () => void
  onReset: () => void
  onSetValue: (value: number) => void
}

function Counter({
  value,
  alert,
  message,
  onIncrement,
  onDecrement,
  onReset,
  onSetValue
}: CounterProps) {
  const [inputValue, setInputValue] = useState('')

  const getAlertColor = () => {
    switch (alert) {
      case 'CRITICAL':
        return '#ef4444'
      case 'WARNING':
        return '#f59e0b'
      case 'NORMAL':
        return '#10b981'
      case 'RESET':
        return '#6366f1'
      default:
        return '#64748b'
    }
  }

  const handleSetValue = () => {
    const num = parseInt(inputValue, 10)
    if (!isNaN(num)) {
      onSetValue(num)
      setInputValue('')
    }
  }

  return (
    <div style={styles.card}>
      <div style={styles.counterDisplay}>
        <span style={styles.counterValue}>{value}</span>
      </div>

      <div style={{ ...styles.alertBadge, backgroundColor: getAlertColor() }}>
        {alert}
      </div>

      {message && (
        <p style={styles.message}>{message}</p>
      )}

      <div style={styles.buttonGroup}>
        <button
          style={{ ...styles.button, ...styles.decrementButton }}
          onClick={onDecrement}
        >
          -
        </button>
        <button
          style={{ ...styles.button, ...styles.incrementButton }}
          onClick={onIncrement}
        >
          +
        </button>
      </div>

      <div style={styles.setValueGroup}>
        <input
          type="number"
          value={inputValue}
          onChange={(e) => setInputValue(e.target.value)}
          placeholder="Enter value"
          style={styles.input}
        />
        <button
          style={{ ...styles.button, ...styles.setButton }}
          onClick={handleSetValue}
        >
          Set
        </button>
      </div>

      <button
        style={{ ...styles.button, ...styles.resetButton }}
        onClick={onReset}
      >
        Reset
      </button>

      <div style={styles.legend}>
        <p style={styles.legendTitle}>Alert Thresholds:</p>
        <ul style={styles.legendList}>
          <li><span style={{ color: '#10b981' }}>NORMAL</span>: 1-10</li>
          <li><span style={{ color: '#f59e0b' }}>WARNING</span>: 11-100</li>
          <li><span style={{ color: '#ef4444' }}>CRITICAL</span>: &gt;100</li>
        </ul>
      </div>
    </div>
  )
}

const styles: { [key: string]: React.CSSProperties } = {
  card: {
    background: 'rgba(255, 255, 255, 0.05)',
    borderRadius: '1.5rem',
    padding: '2.5rem',
    backdropFilter: 'blur(10px)',
    border: '1px solid rgba(255, 255, 255, 0.1)',
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    gap: '1.5rem',
    minWidth: '320px'
  },
  counterDisplay: {
    background: 'rgba(0, 0, 0, 0.3)',
    borderRadius: '1rem',
    padding: '2rem 3rem',
    minWidth: '200px',
    textAlign: 'center'
  },
  counterValue: {
    fontSize: '4rem',
    fontWeight: 700,
    fontFamily: 'monospace',
    color: '#fff'
  },
  alertBadge: {
    padding: '0.5rem 1.5rem',
    borderRadius: '2rem',
    fontWeight: 600,
    fontSize: '0.875rem',
    textTransform: 'uppercase',
    letterSpacing: '0.05em'
  },
  message: {
    color: '#94a3b8',
    fontSize: '0.9rem',
    textAlign: 'center'
  },
  buttonGroup: {
    display: 'flex',
    gap: '1rem'
  },
  button: {
    padding: '0.75rem 1.5rem',
    borderRadius: '0.75rem',
    border: 'none',
    fontWeight: 600,
    fontSize: '1.25rem',
    cursor: 'pointer',
    transition: 'all 0.2s ease',
    minWidth: '60px'
  },
  incrementButton: {
    background: '#4f46e5',
    color: '#fff'
  },
  decrementButton: {
    background: '#7c3aed',
    color: '#fff'
  },
  setButton: {
    background: '#0ea5e9',
    color: '#fff',
    fontSize: '1rem',
    padding: '0.75rem 1rem'
  },
  resetButton: {
    background: 'rgba(255, 255, 255, 0.1)',
    color: '#fff',
    fontSize: '0.9rem',
    padding: '0.5rem 1rem'
  },
  setValueGroup: {
    display: 'flex',
    gap: '0.5rem',
    alignItems: 'center'
  },
  input: {
    padding: '0.75rem 1rem',
    borderRadius: '0.75rem',
    border: '1px solid rgba(255, 255, 255, 0.2)',
    background: 'rgba(0, 0, 0, 0.3)',
    color: '#fff',
    fontSize: '1rem',
    width: '120px',
    outline: 'none'
  },
  legend: {
    marginTop: '1rem',
    padding: '1rem',
    background: 'rgba(0, 0, 0, 0.2)',
    borderRadius: '0.75rem',
    width: '100%'
  },
  legendTitle: {
    fontSize: '0.75rem',
    color: '#64748b',
    marginBottom: '0.5rem',
    textTransform: 'uppercase',
    letterSpacing: '0.05em'
  },
  legendList: {
    listStyle: 'none',
    padding: 0,
    margin: 0,
    fontSize: '0.875rem',
    color: '#94a3b8'
  }
}

export default Counter
