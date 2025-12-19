import { useMemo } from 'react'

/**
 * Flow stages based on REAL WebSocket events:
 * - idle: No activity
 * - sending: User clicked, action being sent to gateway
 * - processing: Gateway acknowledged (action-ack), event is in Kafka/Flink/Drools pipeline
 * - complete: Result received (counter-update), full pipeline finished
 */
export type FlowStage = 'idle' | 'sending' | 'processing' | 'complete'

interface EventFlowProps {
  flowStage: FlowStage
  lastAction: {
    type: string
    action: string
    value: number
    timestamp: number
    traceId?: string
  } | null
  lastResult: {
    currentValue: number
    alert: string
    message: string
    timestamp: number
    traceId?: string
  } | null
  /** Time elapsed since action was sent (ms) */
  elapsedTime: number | null
}

interface FlowStep {
  id: number
  label: string
  detail: string
  active: boolean
  completed: boolean
}

function EventFlow({ flowStage, lastAction, lastResult, elapsedTime }: EventFlowProps) {
  // Calculate step states based on the REAL flow stage
  const steps = useMemo((): FlowStep[] => {
    const baseSteps = [
      { id: 1, label: 'UI', detail: 'User action' },
      { id: 2, label: 'Gateway', detail: 'WebSocket → Kafka' },
      { id: 3, label: 'Kafka', detail: 'counter-events topic' },
      { id: 4, label: 'Flink', detail: 'Process & update state' },
      { id: 5, label: 'Drools', detail: 'Evaluate rules' },
      { id: 6, label: 'Kafka', detail: 'counter-results topic' },
      { id: 7, label: 'Gateway', detail: 'Kafka → WebSocket' },
      { id: 8, label: 'UI', detail: 'Update display' },
    ]

    return baseSteps.map((step, idx) => {
      let active = false
      let completed = false

      switch (flowStage) {
        case 'idle':
          // All steps idle
          break
        case 'sending':
          // Steps 1-2 are active (UI sent, Gateway receiving)
          if (idx === 0) completed = true
          if (idx === 1) active = true
          break
        case 'processing':
          // Steps 1-2 complete, steps 3-6 are active (in Kafka/Flink/Drools pipeline)
          if (idx <= 1) completed = true
          if (idx >= 2 && idx <= 5) active = true
          break
        case 'complete':
          // All steps complete
          completed = true
          break
      }

      return { ...step, active, completed }
    })
  }, [flowStage])

  const getStepStyle = (step: FlowStep): React.CSSProperties => {
    if (step.active) {
      return { ...styles.step, ...styles.stepActive }
    }
    if (step.completed) {
      return { ...styles.step, ...styles.stepCompleted }
    }
    return styles.step
  }

  const getStageLabel = (): string => {
    switch (flowStage) {
      case 'idle':
        return 'Waiting for action...'
      case 'sending':
        return 'Sending to Gateway...'
      case 'processing':
        return 'Processing in pipeline...'
      case 'complete':
        return 'Complete!'
    }
  }

  const formatElapsedTime = (ms: number): string => {
    if (ms < 1000) return `${ms}ms`
    return `${(ms / 1000).toFixed(2)}s`
  }

  return (
    <div style={styles.container}>
      <h3 style={styles.title}>Event Flow Visualization</h3>
      <p style={styles.subtitle}>Real-time tracking of events through the system</p>

      {/* Status bar showing current stage and timing */}
      <div style={{
        ...styles.statusBar,
        borderColor: flowStage === 'complete' ? '#22c55e' :
                     flowStage === 'processing' ? '#4f46e5' :
                     flowStage === 'sending' ? '#f59e0b' : 'rgba(255,255,255,0.1)'
      }}>
        <span style={styles.stageLabel}>{getStageLabel()}</span>
        {elapsedTime !== null && flowStage !== 'idle' && (
          <span style={styles.elapsedTime}>
            {flowStage === 'complete' ? 'Total: ' : 'Elapsed: '}
            {formatElapsedTime(elapsedTime)}
          </span>
        )}
      </div>

      <div style={styles.flowContainer}>
        {steps.map((step, idx) => (
          <div key={step.id} style={styles.stepWrapper}>
            <div style={getStepStyle(step)}>
              <span style={styles.stepLabel}>{step.label}</span>
              <span style={styles.stepDetail}>{step.detail}</span>
            </div>
            {idx < steps.length - 1 && (
              <div style={{
                ...styles.arrow,
                opacity: steps[idx].completed || steps[idx].active ? 1 : 0.3
              }}>
                →
              </div>
            )}
          </div>
        ))}
      </div>

      <div style={styles.eventDetails}>
        <div style={styles.eventBox}>
          <h4 style={styles.eventTitle}>Last Action</h4>
          {lastAction ? (
            <div style={styles.eventContent}>
              <code style={styles.code}>
                {JSON.stringify({
                  action: lastAction.action,
                  value: lastAction.value
                }, null, 2)}
              </code>
            </div>
          ) : (
            <p style={styles.noEvent}>No action yet</p>
          )}
        </div>

        <div style={styles.eventBox}>
          <h4 style={styles.eventTitle}>Last Result</h4>
          {lastResult ? (
            <div style={styles.eventContent}>
              <code style={styles.code}>
                {JSON.stringify({
                  currentValue: lastResult.currentValue,
                  alert: lastResult.alert,
                  message: lastResult.message
                }, null, 2)}
              </code>
            </div>
          ) : (
            <p style={styles.noEvent}>No result yet</p>
          )}
        </div>
      </div>

      <div style={styles.legend}>
        <span style={styles.legendItem}>
          <span style={{ ...styles.legendDot, background: '#4f46e5' }}></span>
          Processing
        </span>
        <span style={styles.legendItem}>
          <span style={{ ...styles.legendDot, background: '#22c55e' }}></span>
          Completed
        </span>
        <span style={styles.legendItem}>
          <span style={{ ...styles.legendDot, background: '#374151' }}></span>
          Pending
        </span>
      </div>

      {/* Trace ID display */}
      {lastAction?.traceId && flowStage !== 'idle' && (
        <div style={styles.traceInfo}>
          <span style={styles.traceLabel}>Trace ID:</span>
          <code style={styles.traceCode}>{lastAction.traceId}</code>
        </div>
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
  title: {
    margin: '0 0 0.25rem 0',
    fontSize: '1rem',
    fontWeight: 600,
    color: '#fff',
  },
  subtitle: {
    margin: '0 0 1rem 0',
    fontSize: '0.8rem',
    color: '#64748b',
  },
  statusBar: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: '0.75rem 1rem',
    background: 'rgba(0, 0, 0, 0.3)',
    borderRadius: '0.5rem',
    marginBottom: '1rem',
    border: '1px solid',
    transition: 'border-color 0.3s ease',
  },
  stageLabel: {
    fontSize: '0.85rem',
    fontWeight: 500,
    color: '#fff',
  },
  elapsedTime: {
    fontSize: '0.8rem',
    fontFamily: 'monospace',
    color: '#fbbf24',
    background: 'rgba(251, 191, 36, 0.1)',
    padding: '0.25rem 0.5rem',
    borderRadius: '0.25rem',
  },
  traceInfo: {
    display: 'flex',
    alignItems: 'center',
    gap: '0.5rem',
    marginTop: '1rem',
    padding: '0.5rem 0.75rem',
    background: 'rgba(79, 70, 229, 0.1)',
    borderRadius: '0.5rem',
    border: '1px solid rgba(79, 70, 229, 0.3)',
  },
  traceLabel: {
    fontSize: '0.7rem',
    color: '#94a3b8',
  },
  traceCode: {
    fontSize: '0.65rem',
    color: '#818cf8',
    fontFamily: 'monospace',
    wordBreak: 'break-all',
  },
  flowContainer: {
    display: 'flex',
    flexWrap: 'wrap',
    justifyContent: 'center',
    alignItems: 'center',
    gap: '0.25rem',
    marginBottom: '1.5rem',
  },
  stepWrapper: {
    display: 'flex',
    alignItems: 'center',
    gap: '0.25rem',
  },
  step: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    padding: '0.5rem 0.75rem',
    borderRadius: '0.5rem',
    background: 'rgba(55, 65, 81, 0.5)',
    border: '1px solid rgba(255, 255, 255, 0.1)',
    minWidth: '70px',
    transition: 'all 0.2s ease',
  },
  stepActive: {
    background: 'rgba(79, 70, 229, 0.3)',
    borderColor: '#4f46e5',
    boxShadow: '0 0 15px rgba(79, 70, 229, 0.4)',
    transform: 'scale(1.05)',
  },
  stepCompleted: {
    background: 'rgba(34, 197, 94, 0.2)',
    borderColor: '#22c55e',
  },
  stepLabel: {
    fontSize: '0.7rem',
    fontWeight: 600,
    color: '#fff',
  },
  stepDetail: {
    fontSize: '0.6rem',
    color: '#94a3b8',
    textAlign: 'center',
  },
  arrow: {
    color: '#64748b',
    fontSize: '0.8rem',
    transition: 'opacity 0.2s ease',
  },
  eventDetails: {
    display: 'grid',
    gridTemplateColumns: '1fr 1fr',
    gap: '1rem',
    marginBottom: '1rem',
  },
  eventBox: {
    background: 'rgba(0, 0, 0, 0.2)',
    borderRadius: '0.5rem',
    padding: '0.75rem',
  },
  eventTitle: {
    margin: '0 0 0.5rem 0',
    fontSize: '0.75rem',
    fontWeight: 500,
    color: '#94a3b8',
    textTransform: 'uppercase',
    letterSpacing: '0.05em',
  },
  eventContent: {
    fontSize: '0.75rem',
  },
  code: {
    display: 'block',
    background: 'rgba(0, 0, 0, 0.3)',
    padding: '0.5rem',
    borderRadius: '0.25rem',
    color: '#a5b4fc',
    fontSize: '0.7rem',
    fontFamily: 'monospace',
    whiteSpace: 'pre',
    overflow: 'auto',
  },
  noEvent: {
    margin: 0,
    fontSize: '0.75rem',
    color: '#475569',
    fontStyle: 'italic',
  },
  legend: {
    display: 'flex',
    justifyContent: 'center',
    gap: '1.5rem',
    fontSize: '0.7rem',
    color: '#64748b',
  },
  legendItem: {
    display: 'flex',
    alignItems: 'center',
    gap: '0.35rem',
  },
  legendDot: {
    width: '8px',
    height: '8px',
    borderRadius: '50%',
  },
}

export default EventFlow
