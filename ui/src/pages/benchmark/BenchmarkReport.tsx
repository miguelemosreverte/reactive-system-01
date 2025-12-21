import React, { useState } from 'react';
import { Card, Row, Col, Statistic, Table, Tag, Button, Space, Typography, Badge, theme } from 'antd';
import {
  ThunderboltOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  ClockCircleOutlined,
  DesktopOutlined,
  DatabaseOutlined,
} from '@ant-design/icons';
import { Line } from '@ant-design/charts';
import type { BenchmarkResult, SampleEvent } from './types';
import TraceModal from './TraceModal';
import LogsModal from './LogsModal';

const { Title, Text } = Typography;

interface BenchmarkReportProps {
  result: BenchmarkResult;
  commit?: string;
}

const BenchmarkReport: React.FC<BenchmarkReportProps> = ({ result, commit }) => {
  const { token } = theme.useToken();
  const [traceModalOpen, setTraceModalOpen] = useState(false);
  const [logsModalOpen, setLogsModalOpen] = useState(false);
  const [selectedEvent, setSelectedEvent] = useState<SampleEvent | null>(null);

  const successRate = (result.successfulOperations / result.totalOperations) * 100;

  // Prepare chart data
  const throughputData = result.throughputTimeline.map((value, index) => ({
    time: `${index + 1}s`,
    value,
    type: 'Throughput',
  }));

  const resourceData = [
    ...result.cpuTimeline.map((value, index) => ({
      time: `${index + 1}s`,
      value,
      type: 'CPU %',
    })),
    ...result.memoryTimeline.map((value, index) => ({
      time: `${index + 1}s`,
      value,
      type: 'Memory %',
    })),
  ];

  const throughputConfig = {
    data: throughputData,
    xField: 'time',
    yField: 'value',
    smooth: true,
    color: token.colorPrimary,
    areaStyle: { fill: `${token.colorPrimary}20` },
    height: 200,
  };

  const resourceConfig = {
    data: resourceData,
    xField: 'time',
    yField: 'value',
    seriesField: 'type',
    smooth: true,
    height: 200,
    color: [token.colorWarning, '#722ed1'],
  };

  const handleViewTrace = (event: SampleEvent) => {
    setSelectedEvent(event);
    setTraceModalOpen(true);
  };

  const handleViewLogs = (event: SampleEvent) => {
    setSelectedEvent(event);
    setLogsModalOpen(true);
  };

  const eventColumns = [
    {
      title: 'ID',
      dataIndex: 'id',
      key: 'id',
      render: (id: string) => <Text code>{id || '-'}</Text>,
    },
    {
      title: 'Trace ID',
      dataIndex: 'traceId',
      key: 'traceId',
      render: (id: string) => <Text code style={{ fontSize: 11 }}>{id || '-'}</Text>,
    },
    {
      title: 'Latency',
      dataIndex: 'latencyMs',
      key: 'latencyMs',
      render: (ms: number) => `${ms}ms`,
      sorter: (a: SampleEvent, b: SampleEvent) => a.latencyMs - b.latencyMs,
    },
    {
      title: 'Status',
      dataIndex: 'status',
      key: 'status',
      render: (status: string) => (
        <Tag color={status === 'success' ? 'success' : status === 'error' ? 'error' : 'warning'}>
          {status}
        </Tag>
      ),
    },
    {
      title: 'Actions',
      key: 'actions',
      render: (_: unknown, event: SampleEvent) => {
        const hasTrace = event.traceData?.trace;
        const hasLogs = event.traceData?.logs && event.traceData.logs.length > 0;
        return (
          <Space size="small">
            <Button
              size="small"
              type="primary"
              disabled={!hasTrace}
              onClick={() => handleViewTrace(event)}
            >
              Trace {hasTrace ? `(${event.traceData!.trace!.spans.length})` : ''}
            </Button>
            <Button
              size="small"
              disabled={!hasLogs}
              onClick={() => handleViewLogs(event)}
            >
              Logs {hasLogs ? `(${event.traceData!.logs!.length})` : ''}
            </Button>
          </Space>
        );
      },
    },
  ];

  return (
    <Space direction="vertical" size="large" style={{ width: '100%', padding: 24 }}>
      <div>
        <Title level={2} style={{ margin: 0 }}>{result.name}</Title>
        <Text type="secondary">{result.description}</Text>
      </div>

      {/* Metrics Cards */}
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}>
          <Card size="small">
            <Statistic
              title="Peak Throughput"
              value={result.peakThroughput}
              suffix="ops/sec"
              prefix={<ThunderboltOutlined style={{ color: token.colorPrimary }} />}
            />
            <Text type="secondary">Avg: {result.avgThroughput} ops/sec</Text>
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card size="small">
            <Statistic
              title="Total Operations"
              value={result.totalOperations}
              prefix={<DatabaseOutlined />}
            />
            <Space>
              <Badge status="success" text={<Text type="secondary">{result.successfulOperations} success</Text>} />
              <Badge status="error" text={<Text type="secondary">{result.failedOperations} failed</Text>} />
            </Space>
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card size="small">
            <Statistic
              title="Success Rate"
              value={successRate}
              precision={1}
              suffix="%"
              valueStyle={{ color: successRate >= 99 ? token.colorSuccess : token.colorWarning }}
              prefix={successRate >= 99 ? <CheckCircleOutlined /> : <CloseCircleOutlined />}
            />
            <Text type="secondary">Duration: {result.durationMs / 1000}s</Text>
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card size="small">
            <Statistic
              title="P99 Latency"
              value={result.latency.p99}
              suffix="ms"
              prefix={<ClockCircleOutlined style={{ color: token.colorWarning }} />}
            />
            <Text type="secondary">P50: {result.latency.p50}ms | P95: {result.latency.p95}ms</Text>
          </Card>
        </Col>
      </Row>

      {/* Latency Details */}
      <Card title="Latency Distribution" size="small">
        <Row gutter={16}>
          {[
            { label: 'Min', value: result.latency.min },
            { label: 'P50', value: result.latency.p50 },
            { label: 'P95', value: result.latency.p95 },
            { label: 'P99', value: result.latency.p99 },
            { label: 'Max', value: result.latency.max },
          ].map((item) => (
            <Col key={item.label} span={4}>
              <Statistic title={item.label} value={item.value} suffix="ms" />
            </Col>
          ))}
        </Row>
      </Card>

      {/* Charts */}
      <Row gutter={16}>
        <Col xs={24} lg={12}>
          <Card title="Throughput Over Time" size="small">
            <Line {...throughputConfig} />
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title="Resource Usage" size="small">
            <Line {...resourceConfig} />
          </Card>
        </Col>
      </Row>

      {/* Resource Stats */}
      <Card title="Resource Statistics" size="small">
        <Row gutter={16}>
          <Col span={6}>
            <Statistic
              title="Peak CPU"
              value={result.peakCpu}
              precision={1}
              suffix="%"
              prefix={<DesktopOutlined />}
            />
          </Col>
          <Col span={6}>
            <Statistic title="Avg CPU" value={result.avgCpu} precision={1} suffix="%" />
          </Col>
          <Col span={6}>
            <Statistic
              title="Peak Memory"
              value={result.peakMemory}
              precision={1}
              suffix="%"
              prefix={<DatabaseOutlined />}
            />
          </Col>
          <Col span={6}>
            <Statistic title="Avg Memory" value={result.avgMemory} precision={1} suffix="%" />
          </Col>
        </Row>
      </Card>

      {/* Sample Events */}
      {result.sampleEvents && result.sampleEvents.length > 0 && (
        <Card title="Sample Events" size="small">
          <Table
            dataSource={result.sampleEvents}
            columns={eventColumns}
            rowKey={(e) => e.id || e.traceId}
            size="small"
            pagination={{ pageSize: 10 }}
          />
        </Card>
      )}

      {/* Footer */}
      <Text type="secondary" style={{ display: 'block', textAlign: 'center' }}>
        Generated: {new Date().toLocaleString()} {commit && `| Commit: ${commit}`} | Go Benchmark Tool
      </Text>

      {/* Modals */}
      <TraceModal
        open={traceModalOpen}
        onClose={() => setTraceModalOpen(false)}
        event={selectedEvent}
      />
      <LogsModal
        open={logsModalOpen}
        onClose={() => setLogsModalOpen(false)}
        event={selectedEvent}
      />
    </Space>
  );
};

export default BenchmarkReport;
