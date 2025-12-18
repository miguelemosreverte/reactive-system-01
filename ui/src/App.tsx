import { useState, useEffect } from 'react'
import Counter from './components/Counter'
import ConnectionStatus from './components/ConnectionStatus'
import { useWebSocket } from './hooks/useWebSocket'

function App() {
  const [counterValue, setCounterValue] = useState(0)
  const [alert, setAlert] = useState<string>('NONE')
  const [message, setMessage] = useState<string>('')

  const { isConnected, sessionId, sendMessage } = useWebSocket({
    onMessage: (msg) => {
      if (msg.type === 'counter-update') {
        setCounterValue(msg.data.currentValue)
        setAlert(msg.data.alert)
        setMessage(msg.data.message)
      }
    }
  })

  const handleIncrement = () => {
    sendMessage({
      type: 'counter-action',
      action: 'increment',
      value: 1
    })
  }

  const handleDecrement = () => {
    sendMessage({
      type: 'counter-action',
      action: 'decrement',
      value: 1
    })
  }

  const handleReset = () => {
    sendMessage({
      type: 'counter-action',
      action: 'set',
      value: 0
    })
  }

  const handleSetValue = (value: number) => {
    sendMessage({
      type: 'counter-action',
      action: 'set',
      value
    })
  }

  return (
    <div style={styles.container}>
      <header style={styles.header}>
        <h1 style={styles.title}>Reactive Counter</h1>
        <p style={styles.subtitle}>
          Powered by Kafka + Flink + Drools
        </p>
        <ConnectionStatus isConnected={isConnected} sessionId={sessionId} />
      </header>

      <main style={styles.main}>
        <Counter
          value={counterValue}
          alert={alert}
          message={message}
          onIncrement={handleIncrement}
          onDecrement={handleDecrement}
          onReset={handleReset}
          onSetValue={handleSetValue}
        />
      </main>

      <footer style={styles.footer}>
        <p style={styles.footerText}>
          Events flow: UI → Kafka → Flink → Drools → Kafka → UI
        </p>
      </footer>
    </div>
  )
}

const styles: { [key: string]: React.CSSProperties } = {
  container: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    minHeight: '100vh',
    padding: '2rem',
    color: '#fff'
  },
  header: {
    textAlign: 'center',
    marginBottom: '2rem'
  },
  title: {
    fontSize: '2.5rem',
    fontWeight: 700,
    marginBottom: '0.5rem',
    background: 'linear-gradient(135deg, #4f46e5, #7c3aed)',
    WebkitBackgroundClip: 'text',
    WebkitTextFillColor: 'transparent'
  },
  subtitle: {
    color: '#94a3b8',
    fontSize: '1rem',
    marginBottom: '1rem'
  },
  main: {
    flex: 1,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center'
  },
  footer: {
    marginTop: '2rem',
    textAlign: 'center'
  },
  footerText: {
    color: '#64748b',
    fontSize: '0.875rem'
  }
}

export default App
