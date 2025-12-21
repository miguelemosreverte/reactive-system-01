import { useState, useCallback, useEffect, useRef } from 'react';
import { useNavigate, useLocation, useSearchParams } from 'react-router-dom';
import { Typography, Space, Button, Row, Col, Card, Steps, theme } from 'antd';
import { ExportOutlined } from '@ant-design/icons';
import Counter from './components/Counter';
import ConnectionStatus from './components/ConnectionStatus';
import SystemStatus from './components/SystemStatus';
import EventFlow, { FlowStage } from './components/EventFlow';
import Documentation from './components/Documentation';
import TraceViewer from './components/TraceViewer';
import { AppLayout, NavItem } from './components/layout';
import { useWebSocket } from './hooks/useWebSocket';
import { getJaegerTraceUrl } from './utils/urls';

const { Title, Text, Paragraph } = Typography;

const pathToNav: Record<string, NavItem> = {
  '/': 'demo',
  '/status': 'status',
  '/traces': 'traces',
  '/docs': 'docs',
};

const navToPath: Record<NavItem, string> = {
  demo: '/',
  status: '/status',
  traces: '/traces',
  docs: '/docs',
  benchmark: '/benchmark',
};

function App() {
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const { token } = theme.useToken();

  const [counterValue, setCounterValue] = useState(0);
  const [alert, setAlert] = useState<string>('NONE');
  const [message, setMessage] = useState<string>('');
  const [lastTraceId, setLastTraceId] = useState<string | null>(null);

  const activeNav = (pathToNav[location.pathname] || 'demo') as NavItem;
  const traceIdFromUrl = searchParams.get('traceId');

  const [flowStage, setFlowStage] = useState<FlowStage>('idle');
  const [actionSentTime, setActionSentTime] = useState<number | null>(null);
  const [elapsedTime, setElapsedTime] = useState<number | null>(null);
  const elapsedTimerRef = useRef<number | null>(null);

  const [lastAction, setLastAction] = useState<{
    type: string;
    action: string;
    value: number;
    timestamp: number;
    traceId?: string;
  } | null>(null);

  const [lastResult, setLastResult] = useState<{
    currentValue: number;
    alert: string;
    message: string;
    timestamp: number;
    traceId?: string;
  } | null>(null);

  useEffect(() => {
    if (flowStage === 'sending' || flowStage === 'processing') {
      elapsedTimerRef.current = window.setInterval(() => {
        if (actionSentTime) {
          setElapsedTime(Date.now() - actionSentTime);
        }
      }, 10);
    } else {
      if (elapsedTimerRef.current) {
        clearInterval(elapsedTimerRef.current);
        elapsedTimerRef.current = null;
      }
    }
    return () => {
      if (elapsedTimerRef.current) {
        clearInterval(elapsedTimerRef.current);
      }
    };
  }, [flowStage, actionSentTime]);

  const { isConnected, sessionId, sendMessage, isReconnecting, reconnectAttempt } = useWebSocket({
    onMessage: useCallback(
      (msg: {
        type: string;
        traceId?: string;
        data?: { currentValue: number; alert: string; message: string; traceId?: string };
      }) => {
        if (msg.type === 'counter-update' && msg.data) {
          setCounterValue(msg.data.currentValue);
          setAlert(msg.data.alert);
          setMessage(msg.data.message);
          if (msg.data.traceId) {
            setLastTraceId(msg.data.traceId);
          }
          setLastResult({
            currentValue: msg.data.currentValue,
            alert: msg.data.alert,
            message: msg.data.message,
            timestamp: Date.now(),
            traceId: msg.data.traceId,
          });

          setFlowStage('complete');
          if (actionSentTime) {
            setElapsedTime(Date.now() - actionSentTime);
          }
        } else if (msg.type === 'action-ack' && msg.traceId) {
          setLastTraceId(msg.traceId);
          setFlowStage('processing');
          setLastAction((prev) => (prev ? { ...prev, traceId: msg.traceId } : null));
        }
      },
      [actionSentTime]
    ),
  });

  const sendAction = (action: string, value: number) => {
    const now = Date.now();
    const actionData = {
      type: 'counter-action',
      action,
      value,
    };

    setFlowStage('sending');
    setActionSentTime(now);
    setElapsedTime(0);

    sendMessage(actionData);
    setLastAction({
      ...actionData,
      timestamp: now,
    });
  };

  const handleIncrement = () => sendAction('increment', 1);
  const handleDecrement = () => sendAction('decrement', 1);
  const handleReset = () => sendAction('set', 0);
  const handleSetValue = (value: number) => sendAction('set', value);

  const renderContent = () => {
    switch (activeNav) {
      case 'demo':
        return (
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            <div>
              <Title level={3}>Live Demo</Title>
              <Text type="secondary">Interact with the reactive system in real-time</Text>
            </div>

            {lastTraceId && (
              <Card size="small">
                <Space>
                  <Text type="secondary">Last Trace ID:</Text>
                  <Text code copyable>
                    {lastTraceId}
                  </Text>
                  <Button
                    type="link"
                    icon={<ExportOutlined />}
                    href={getJaegerTraceUrl(lastTraceId)}
                    target="_blank"
                    size="small"
                  >
                    View in Jaeger
                  </Button>
                  <Button
                    type="link"
                    size="small"
                    onClick={() => navigate(`/traces?traceId=${lastTraceId}`)}
                  >
                    Trace Viewer
                  </Button>
                </Space>
              </Card>
            )}

            <Row gutter={[24, 24]}>
              <Col xs={24} lg={12}>
                <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                  <ConnectionStatus
                    isConnected={isConnected}
                    sessionId={sessionId}
                    isReconnecting={isReconnecting}
                    reconnectAttempt={reconnectAttempt}
                  />
                  <div style={{ display: 'flex', justifyContent: 'center' }}>
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
                </Space>
              </Col>
              <Col xs={24} lg={12}>
                <EventFlow
                  flowStage={flowStage}
                  lastAction={lastAction}
                  lastResult={lastResult}
                  elapsedTime={elapsedTime}
                />
              </Col>
            </Row>

            <Card title="How It Works">
              <Steps
                current={-1}
                items={[
                  { title: 'User Action', description: 'Click buttons in UI' },
                  { title: 'Gateway', description: 'WebSocket → Kafka' },
                  { title: 'Flink', description: 'Stream processing' },
                  { title: 'Drools', description: 'Business rules' },
                  { title: 'Result', description: 'Real-time update' },
                ]}
              />
            </Card>
          </Space>
        );

      case 'status':
        return (
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            <div>
              <Title level={3}>System Status</Title>
              <Text type="secondary">Monitor the health and status of all services</Text>
            </div>
            <SystemStatus isConnected={isConnected} />
          </Space>
        );

      case 'traces':
        return <TraceViewer initialTraceId={traceIdFromUrl} />;

      case 'docs':
        return (
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            <Documentation />
          </Space>
        );

      default:
        return null;
    }
  };

  return (
    <AppLayout
      activeNav={activeNav}
      onNavigate={(item) => navigate(navToPath[item])}
      isConnected={isConnected}
    >
      {renderContent()}
    </AppLayout>
  );
}

export default App;
