import React from 'react';
import { Modal, Space, Tag, Typography, List, theme } from 'antd';
import { ClockCircleOutlined } from '@ant-design/icons';
import type { SampleEvent, JaegerSpan } from './types';

const { Text } = Typography;

interface TraceModalProps {
  open: boolean;
  onClose: () => void;
  event: SampleEvent | null;
}

const getServiceColor = (serviceName: string): string => {
  const name = serviceName.toLowerCase();
  if (name.includes('gateway') || name.includes('counter-application')) return 'green';
  if (name.includes('kafka')) return 'orange';
  if (name.includes('flink')) return 'purple';
  if (name.includes('drools')) return 'red';
  return 'blue';
};

const formatDuration = (microseconds: number): string => {
  if (microseconds < 1000) return `${microseconds}µs`;
  if (microseconds < 1000000) return `${(microseconds / 1000).toFixed(2)}ms`;
  return `${(microseconds / 1000000).toFixed(2)}s`;
};

const TraceModal: React.FC<TraceModalProps> = ({ open, onClose, event }) => {
  const { token } = theme.useToken();

  if (!event?.traceData?.trace) {
    return (
      <Modal
        title="Trace Viewer"
        open={open}
        onCancel={onClose}
        footer={null}
        width={900}
      >
        <Text type="secondary">No trace data available</Text>
      </Modal>
    );
  }

  const trace = event.traceData.trace;
  const spans = [...trace.spans].sort((a, b) => a.startTime - b.startTime);

  // Calculate timeline bounds
  const minTime = spans.length > 0 ? spans[0].startTime : 0;
  const maxTime = spans.length > 0 ? Math.max(...spans.map(s => s.startTime + s.duration)) : 0;
  const totalDuration = maxTime - minTime;

  return (
    <Modal
      title={
        <Space>
          <span>Trace Viewer</span>
          <Text code copyable>{event.traceId}</Text>
        </Space>
      }
      open={open}
      onCancel={onClose}
      footer={null}
      width={1000}
    >
      <List
        dataSource={spans}
        renderItem={(span: JaegerSpan) => {
          const serviceName = trace.processes[span.processID]?.serviceName || 'unknown';
          const offsetPct = totalDuration > 0 ? ((span.startTime - minTime) / totalDuration) * 100 : 0;
          const widthPct = totalDuration > 0 ? Math.max((span.duration / totalDuration) * 100, 1) : 100;

          return (
            <List.Item style={{ padding: '8px 0' }}>
              <div style={{ width: '100%' }}>
                <Space style={{ marginBottom: 8 }}>
                  <Tag color={getServiceColor(serviceName)}>{serviceName}</Tag>
                  <Text strong>{span.operationName}</Text>
                  <Tag icon={<ClockCircleOutlined />} color="gold">
                    {formatDuration(span.duration)}
                  </Tag>
                </Space>
                <div
                  style={{
                    height: 20,
                    background: token.colorBgLayout,
                    borderRadius: 4,
                    position: 'relative',
                    marginTop: 4,
                  }}
                >
                  <div
                    style={{
                      position: 'absolute',
                      left: `${offsetPct}%`,
                      width: `${widthPct}%`,
                      height: '100%',
                      borderRadius: 4,
                      background: getServiceColor(serviceName) === 'green' ? token.colorSuccess :
                                  getServiceColor(serviceName) === 'orange' ? token.colorWarning :
                                  getServiceColor(serviceName) === 'purple' ? '#722ed1' :
                                  getServiceColor(serviceName) === 'red' ? token.colorError :
                                  token.colorPrimary,
                      minWidth: 4,
                    }}
                  />
                </div>
              </div>
            </List.Item>
          );
        }}
      />
    </Modal>
  );
};

export default TraceModal;
