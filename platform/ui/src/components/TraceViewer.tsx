import React, { useState, useEffect, useCallback } from 'react';
import { Card, Input, Button, Typography, Space, Spin, Alert, Tag, Row, Col, List, theme } from 'antd';
import { SearchOutlined, ExportOutlined, ClockCircleOutlined } from '@ant-design/icons';
import { getJaegerUrl, getJaegerTraceUrl } from '../utils/urls';

const { Title, Text } = Typography;

interface Trace {
  traceId: string;
  sessionId: string;
  timestamp: number;
  action: string;
}

interface JaegerTrace {
  traceID: string;
  spans: JaegerSpan[];
  processes: Record<string, JaegerProcess>;
}

interface JaegerSpan {
  traceID: string;
  spanID: string;
  operationName: string;
  startTime: number;
  duration: number;
  tags: { key: string; value: string }[];
  processID: string;
}

interface JaegerProcess {
  serviceName: string;
}

interface TraceViewerProps {
  apiKey?: string;
  initialTraceId?: string | null;
}

const getApiKey = (): string => {
  return import.meta.env.VITE_ADMIN_API_KEY || 'reactive-admin-key';
};

const TraceViewer: React.FC<TraceViewerProps> = ({ apiKey = getApiKey(), initialTraceId }) => {
  const { token } = theme.useToken();
  const [recentTraces, setRecentTraces] = useState<Trace[]>([]);
  const [selectedTrace, setSelectedTrace] = useState<JaegerTrace | null>(null);
  const [searchTraceId, setSearchTraceId] = useState(initialTraceId || '');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fetchError, setFetchError] = useState<string | null>(null);

  const fetchRecentTraces = useCallback(async () => {
    try {
      const response = await fetch('/api/admin/traces?limit=20', {
        headers: { 'x-api-key': apiKey },
      });
      if (response.ok) {
        const data = await response.json();
        setRecentTraces(data.traces || []);
        setFetchError(null);
      } else if (response.status === 401) {
        setFetchError('Unauthorized - Invalid API key');
      } else {
        setFetchError(`Failed to fetch traces (${response.status})`);
      }
    } catch (err) {
      setFetchError('Unable to connect to trace service');
    }
  }, [apiKey]);

  useEffect(() => {
    fetchRecentTraces();
    const interval = setInterval(fetchRecentTraces, 5000);
    return () => clearInterval(interval);
  }, [fetchRecentTraces]);

  useEffect(() => {
    if (initialTraceId) {
      setSearchTraceId(initialTraceId);
      fetchTraceDetails(initialTraceId);
    }
  }, [initialTraceId]);

  const fetchTraceDetails = async (traceId: string) => {
    setLoading(true);
    setError(null);
    try {
      const response = await fetch(`/api/admin/traces/${traceId}`, {
        headers: { 'x-api-key': apiKey },
      });
      if (response.ok) {
        const data = await response.json();
        if (data.data && data.data.length > 0) {
          setSelectedTrace(data.data[0]);
        } else {
          setError('Trace not found in Jaeger');
        }
      } else {
        setError('Failed to fetch trace details');
      }
    } catch (err) {
      setError('Error connecting to trace service');
    }
    setLoading(false);
  };

  const handleSearch = () => {
    if (searchTraceId.trim()) {
      fetchTraceDetails(searchTraceId.trim());
    }
  };

  const formatDuration = (microseconds: number) => {
    if (microseconds < 1000) return `${microseconds}µs`;
    if (microseconds < 1000000) return `${(microseconds / 1000).toFixed(2)}ms`;
    return `${(microseconds / 1000000).toFixed(2)}s`;
  };

  const formatTimestamp = (timestamp: number) => {
    return new Date(timestamp).toLocaleTimeString();
  };

  const getServiceColor = (serviceName: string): string => {
    const name = serviceName.toLowerCase();
    if (name.includes('gateway') || name.includes('counter-application')) return 'green';
    if (name.includes('kafka')) return 'orange';
    if (name.includes('flink')) return 'purple';
    if (name.includes('drools')) return 'red';
    return 'blue';
  };

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <div>
        <Title level={3}>Distributed Traces</Title>
        <Text type="secondary">Query and visualize traces across all services</Text>
      </div>

      {/* Search Section */}
      <Card size="small">
        <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
          <Space.Compact style={{ maxWidth: 500 }}>
            <Input
              value={searchTraceId}
              onChange={(e) => setSearchTraceId(e.target.value)}
              placeholder="Enter trace ID to search..."
              onPressEnter={handleSearch}
              style={{ width: 350 }}
            />
            <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>
              Search
            </Button>
          </Space.Compact>
          <Button
            icon={<ExportOutlined />}
            href={getJaegerUrl()}
            target="_blank"
          >
            Open Jaeger UI
          </Button>
        </Space>
      </Card>

      <Row gutter={24}>
        {/* Recent Traces */}
        <Col xs={24} lg={10}>
          <Card
            title="Recent Traces"
            size="small"
            styles={{ body: { maxHeight: 500, overflow: 'auto' } }}
          >
            {fetchError ? (
              <Alert message={fetchError} type="error" showIcon />
            ) : recentTraces.length === 0 ? (
              <Text type="secondary" style={{ display: 'block', textAlign: 'center', padding: 32 }}>
                No traces yet. Perform some actions to generate traces.
              </Text>
            ) : (
              <List
                dataSource={recentTraces}
                renderItem={(trace, idx) => (
                  <List.Item
                    key={`${trace.traceId}-${idx}`}
                    onClick={() => fetchTraceDetails(trace.traceId)}
                    style={{ cursor: 'pointer', padding: '8px 0' }}
                    actions={[
                      <Button
                        type="link"
                        size="small"
                        href={getJaegerTraceUrl(trace.traceId)}
                        target="_blank"
                        onClick={(e) => e.stopPropagation()}
                      >
                        Jaeger
                      </Button>,
                    ]}
                  >
                    <List.Item.Meta
                      title={
                        <Space>
                          <Tag color="blue">{trace.action}</Tag>
                          <Text type="secondary" style={{ fontSize: 12 }}>
                            {formatTimestamp(trace.timestamp)}
                          </Text>
                        </Space>
                      }
                      description={
                        <Space direction="vertical" size={0}>
                          <Text code style={{ fontSize: 11 }}>
                            {trace.traceId}
                          </Text>
                          <Text type="secondary" style={{ fontSize: 11 }}>
                            Session: {trace.sessionId}
                          </Text>
                        </Space>
                      }
                    />
                  </List.Item>
                )}
              />
            )}
          </Card>
        </Col>

        {/* Trace Details */}
        <Col xs={24} lg={14}>
          <Card title="Trace Details" size="small">
            {loading ? (
              <div style={{ textAlign: 'center', padding: 48 }}>
                <Spin size="large" />
                <Text type="secondary" style={{ display: 'block', marginTop: 16 }}>
                  Loading trace...
                </Text>
              </div>
            ) : error ? (
              <Alert message={error} type="error" showIcon />
            ) : selectedTrace ? (
              <Space direction="vertical" style={{ width: '100%' }} size="middle">
                <div
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    padding: 12,
                    background: token.colorBgLayout,
                    borderRadius: token.borderRadius,
                  }}
                >
                  <Text code copyable>
                    {selectedTrace.traceID}
                  </Text>
                  <Tag color="blue">{selectedTrace.spans.length} spans</Tag>
                </div>

                <List
                  dataSource={selectedTrace.spans.sort((a, b) => a.startTime - b.startTime)}
                  renderItem={(span) => {
                    const process = selectedTrace.processes[span.processID];
                    const serviceName = process?.serviceName || 'unknown';
                    return (
                      <List.Item style={{ padding: '8px 12px' }}>
                        <Space direction="vertical" style={{ width: '100%' }} size={4}>
                          <Space>
                            <Tag color={getServiceColor(serviceName)}>{serviceName}</Tag>
                            <Text strong>{span.operationName}</Text>
                            <Tag icon={<ClockCircleOutlined />} color="gold">
                              {formatDuration(span.duration)}
                            </Tag>
                          </Space>
                          <Space wrap size={[4, 4]}>
                            {span.tags.slice(0, 5).map((tag, idx) => (
                              <Tag key={idx} style={{ fontSize: 11 }}>
                                {tag.key}: {String(tag.value).substring(0, 30)}
                              </Tag>
                            ))}
                          </Space>
                        </Space>
                      </List.Item>
                    );
                  }}
                />
              </Space>
            ) : (
              <Text type="secondary" style={{ display: 'block', textAlign: 'center', padding: 48 }}>
                Select a trace from the list or search by trace ID
              </Text>
            )}
          </Card>
        </Col>
      </Row>
    </Space>
  );
};

export default TraceViewer;
