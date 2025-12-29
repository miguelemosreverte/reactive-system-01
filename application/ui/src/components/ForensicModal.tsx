import { useState, useEffect } from 'react';
import { Modal, Descriptions, Table, Tag, Space, Typography, Spin, Alert, Button, Tabs, Timeline } from 'antd';
import { ExportOutlined, ReloadOutlined, BugOutlined, HistoryOutlined, FileTextOutlined } from '@ant-design/icons';
import { getJaegerTraceUrl } from '../utils/urls';

const { Text, Paragraph } = Typography;

interface StateTransition {
  requestId: string;
  action: string;
  value: number;
  stateBefore: Record<string, unknown> | null;
  stateAfter: Record<string, unknown> | null;
  timestamp: string;
}

interface SessionForensicResponse {
  success: boolean;
  sessionId: string;
  eventCount: number;
  transitions: StateTransition[];
  error: string | null;
}

interface ForensicModalProps {
  open: boolean;
  onClose: () => void;
  sessionId: string | null;
  traceId: string | null;
}

const getGatewayUrl = (): string => {
  const hostname = window.location.hostname;
  return `http://${hostname}:8080`;
};

export default function ForensicModal({ open, onClose, sessionId, traceId }: ForensicModalProps) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sessionData, setSessionData] = useState<SessionForensicResponse | null>(null);
  const [replayLoading, setReplayLoading] = useState(false);
  const [replayResult, setReplayResult] = useState<{ traceId: string } | null>(null);

  const fetchSessionForensics = async () => {
    if (!sessionId) return;

    setLoading(true);
    setError(null);

    try {
      const response = await fetch(`${getGatewayUrl()}/api/forensic/session/${sessionId}`);
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }
      const data: SessionForensicResponse = await response.json();
      if (!data.success) {
        throw new Error(data.error || 'Unknown error');
      }
      setSessionData(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to fetch forensic data');
    } finally {
      setLoading(false);
    }
  };

  const triggerReplay = async () => {
    if (!sessionId) return;

    setReplayLoading(true);
    setError(null);

    try {
      const response = await fetch(`${getGatewayUrl()}/api/replay/session/${sessionId}`, {
        method: 'POST',
      });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }
      const data = await response.json();
      if (data.replayTraceId) {
        setReplayResult({ traceId: data.replayTraceId });
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to trigger replay');
    } finally {
      setReplayLoading(false);
    }
  };

  useEffect(() => {
    if (open && sessionId) {
      fetchSessionForensics();
      setReplayResult(null);
    }
  }, [open, sessionId]);

  const transitionColumns = [
    {
      title: 'Action',
      dataIndex: 'action',
      key: 'action',
      render: (action: string) => (
        <Tag color={action === 'increment' ? 'green' : action === 'decrement' ? 'red' : 'blue'}>
          {action}
        </Tag>
      ),
    },
    {
      title: 'Value',
      dataIndex: 'value',
      key: 'value',
    },
    {
      title: 'Before',
      dataIndex: 'stateBefore',
      key: 'stateBefore',
      render: (state: Record<string, unknown> | null) => (
        <Text code style={{ fontSize: 11 }}>
          {state ? `counter: ${state.counter}` : '-'}
        </Text>
      ),
    },
    {
      title: 'After',
      dataIndex: 'stateAfter',
      key: 'stateAfter',
      render: (state: Record<string, unknown> | null) => (
        <Text code style={{ fontSize: 11 }}>
          {state ? `counter: ${state.counter}` : '-'}
        </Text>
      ),
    },
    {
      title: 'Timestamp',
      dataIndex: 'timestamp',
      key: 'timestamp',
      render: (ts: string) => (
        <Text type="secondary" style={{ fontSize: 11 }}>
          {new Date(ts).toLocaleTimeString()}
        </Text>
      ),
    },
  ];

  return (
    <Modal
      title={
        <Space>
          <BugOutlined />
          <span>Forensic Debug</span>
        </Space>
      }
      open={open}
      onCancel={onClose}
      width={800}
      footer={[
        <Button key="close" onClick={onClose}>
          Close
        </Button>,
        traceId && (
          <Button
            key="jaeger"
            type="default"
            icon={<ExportOutlined />}
            href={getJaegerTraceUrl(traceId)}
            target="_blank"
          >
            Try Jaeger (may not exist)
          </Button>
        ),
        <Button
          key="replay"
          type="primary"
          icon={<ReloadOutlined />}
          loading={replayLoading}
          onClick={triggerReplay}
        >
          Replay with Full Trace
        </Button>,
      ]}
    >
      {loading ? (
        <div style={{ textAlign: 'center', padding: 40 }}>
          <Spin size="large" />
          <div style={{ marginTop: 16 }}>
            <Text type="secondary">Fetching forensic data from Kafka...</Text>
          </div>
        </div>
      ) : error ? (
        <Alert
          type="error"
          message="Error"
          description={error}
          showIcon
          action={
            <Button size="small" onClick={fetchSessionForensics}>
              Retry
            </Button>
          }
        />
      ) : (
        <Tabs
          items={[
            {
              key: 'overview',
              label: (
                <Space>
                  <BugOutlined />
                  Overview
                </Space>
              ),
              children: (
                <Space direction="vertical" style={{ width: '100%' }} size="middle">
                  <Descriptions bordered size="small" column={2}>
                    <Descriptions.Item label="Session ID">
                      <Text code copyable>{sessionId}</Text>
                    </Descriptions.Item>
                    <Descriptions.Item label="Trace ID">
                      {traceId ? (
                        <Text code copyable>{traceId}</Text>
                      ) : (
                        <Text type="secondary">Not captured (sampling)</Text>
                      )}
                    </Descriptions.Item>
                    <Descriptions.Item label="Events in Session">
                      <Tag color="blue">{sessionData?.eventCount || 0}</Tag>
                    </Descriptions.Item>
                    <Descriptions.Item label="Trace Status">
                      <Tag color={traceId ? 'orange' : 'red'}>
                        {traceId ? 'May exist in Jaeger' : 'Not sampled'}
                      </Tag>
                    </Descriptions.Item>
                  </Descriptions>

                  {replayResult && (
                    <Alert
                      type="success"
                      message="Replay Complete"
                      description={
                        <Space direction="vertical">
                          <Text>Session replayed with full tracing enabled.</Text>
                          <Space>
                            <Text>Replay Trace ID:</Text>
                            <Text code copyable>{replayResult.traceId}</Text>
                            <Button
                              type="primary"
                              size="small"
                              icon={<ExportOutlined />}
                              href={getJaegerTraceUrl(replayResult.traceId)}
                              target="_blank"
                            >
                              View in Jaeger
                            </Button>
                          </Space>
                        </Space>
                      }
                      showIcon
                    />
                  )}

                  <Alert
                    type="info"
                    message="About Trace Sampling"
                    description={
                      <Paragraph style={{ margin: 0 }}>
                        Production tracing uses a low sampling rate (0.00001%) to minimize overhead.
                        Most traces are not captured in Jaeger. Use "Replay with Full Trace" to
                        re-process events with 100% trace capture for debugging.
                      </Paragraph>
                    }
                    showIcon
                  />
                </Space>
              ),
            },
            {
              key: 'history',
              label: (
                <Space>
                  <HistoryOutlined />
                  State History ({sessionData?.eventCount || 0})
                </Space>
              ),
              children: (
                <Space direction="vertical" style={{ width: '100%' }} size="middle">
                  {sessionData?.transitions && sessionData.transitions.length > 0 ? (
                    <>
                      <Table
                        dataSource={sessionData.transitions}
                        columns={transitionColumns}
                        rowKey="requestId"
                        size="small"
                        pagination={{ pageSize: 10 }}
                      />

                      <Timeline
                        mode="left"
                        items={sessionData.transitions.map((t) => ({
                          color: t.action === 'increment' ? 'green' : t.action === 'decrement' ? 'red' : 'blue',
                          label: new Date(t.timestamp).toLocaleTimeString(),
                          children: (
                            <div>
                              <Tag color={t.action === 'increment' ? 'green' : t.action === 'decrement' ? 'red' : 'blue'}>
                                {t.action}({t.value})
                              </Tag>
                              <Text type="secondary" style={{ marginLeft: 8 }}>
                                {String(t.stateBefore?.counter ?? '-')} → {String(t.stateAfter?.counter ?? '-')}
                              </Text>
                            </div>
                          ),
                        }))}
                      />
                    </>
                  ) : (
                    <Alert
                      type="warning"
                      message="No History Available"
                      description="No state transitions found for this session. The events may have expired from Kafka retention."
                      showIcon
                    />
                  )}
                </Space>
              ),
            },
            {
              key: 'raw',
              label: (
                <Space>
                  <FileTextOutlined />
                  Raw Data
                </Space>
              ),
              children: (
                <pre style={{
                  background: '#f5f5f5',
                  padding: 16,
                  borderRadius: 8,
                  maxHeight: 400,
                  overflow: 'auto',
                  fontSize: 11,
                }}>
                  {JSON.stringify(sessionData, null, 2)}
                </pre>
              ),
            },
          ]}
        />
      )}
    </Modal>
  );
}
