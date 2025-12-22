import { useMemo } from 'react';
import { Card, Steps, Typography, Space, Tag, Badge, theme } from 'antd';
import {
  UserOutlined,
  ApiOutlined,
  CloudServerOutlined,
  ThunderboltOutlined,
  FileTextOutlined,
  CheckCircleOutlined,
  LoadingOutlined,
  ClockCircleOutlined,
} from '@ant-design/icons';


const { Text, Paragraph } = Typography;

export type FlowStage = 'idle' | 'sending' | 'processing' | 'complete';

interface EventFlowProps {
  flowStage: FlowStage;
  lastAction: {
    type: string;
    action: string;
    value: number;
    timestamp: number;
    traceId?: string;
  } | null;
  lastResult: {
    currentValue: number;
    alert: string;
    message: string;
    timestamp: number;
    traceId?: string;
  } | null;
  elapsedTime: number | null;
}

interface FlowStep {
  id: number;
  label: string;
  detail: string;
  service: string;
  icon: React.ReactNode;
}

function EventFlow({ flowStage, lastAction, lastResult, elapsedTime }: EventFlowProps) {
  const { token } = theme.useToken();

  const steps: FlowStep[] = [
    { id: 1, label: 'UI', detail: 'User action', service: 'ui', icon: <UserOutlined /> },
    { id: 2, label: 'Gateway', detail: 'WebSocket → Kafka', service: 'gateway', icon: <ApiOutlined /> },
    { id: 3, label: 'Kafka', detail: 'counter-events', service: 'kafka', icon: <CloudServerOutlined /> },
    { id: 4, label: 'Flink', detail: 'Process state', service: 'flink', icon: <ThunderboltOutlined /> },
    { id: 5, label: 'Drools', detail: 'Evaluate rules', service: 'drools', icon: <FileTextOutlined /> },
    { id: 6, label: 'Result', detail: 'Update display', service: 'ui', icon: <CheckCircleOutlined /> },
  ];

  const currentStep = useMemo(() => {
    switch (flowStage) {
      case 'idle':
        return -1;
      case 'sending':
        return 1;
      case 'processing':
        return 3;
      case 'complete':
        return 6;
      default:
        return -1;
    }
  }, [flowStage]);

  const getStageConfig = () => {
    switch (flowStage) {
      case 'idle':
        return { status: 'default' as const, text: 'Waiting for action...', icon: <ClockCircleOutlined /> };
      case 'sending':
        return { status: 'processing' as const, text: 'Sending to Gateway...', icon: <LoadingOutlined spin /> };
      case 'processing':
        return { status: 'processing' as const, text: 'Processing in pipeline...', icon: <LoadingOutlined spin /> };
      case 'complete':
        return { status: 'success' as const, text: 'Complete!', icon: <CheckCircleOutlined /> };
    }
  };

  const stageConfig = getStageConfig();

  const formatElapsedTime = (ms: number): string => {
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(2)}s`;
  };

  return (
    <Card
      title={
        <Space>
          <ThunderboltOutlined />
          <span>Event Flow Visualization</span>
        </Space>
      }
      extra={
        elapsedTime !== null && flowStage !== 'idle' && (
          <Tag color={flowStage === 'complete' ? 'success' : 'processing'}>
            {flowStage === 'complete' ? 'Total: ' : 'Elapsed: '}
            {formatElapsedTime(elapsedTime)}
          </Tag>
        )
      }
    >
      <Space direction="vertical" style={{ width: '100%' }} size="large">
        {/* Status Badge */}
        <div
          style={{
            padding: '12px 16px',
            background: token.colorBgLayout,
            borderRadius: token.borderRadius,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
          }}
        >
          <Space>
            <Badge status={stageConfig.status} />
            <Text strong>{stageConfig.text}</Text>
          </Space>
          {stageConfig.icon}
        </div>

        {/* Flow Steps */}
        <Steps
          current={currentStep}
          size="small"
          items={steps.map((step) => ({
            title: step.label,
            description: step.detail,
            icon: step.icon,
          }))}
        />

        {/* Event Details */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
          <Card size="small" title="Last Action" type="inner">
            {lastAction ? (
              <Paragraph>
                <pre
                  style={{
                    margin: 0,
                    padding: 8,
                    background: token.colorBgLayout,
                    borderRadius: token.borderRadius,
                    fontSize: 12,
                    overflow: 'auto',
                  }}
                >
                  {JSON.stringify({ action: lastAction.action, value: lastAction.value }, null, 2)}
                </pre>
              </Paragraph>
            ) : (
              <Text type="secondary">No action yet</Text>
            )}
          </Card>

          <Card size="small" title="Last Result" type="inner">
            {lastResult ? (
              <Paragraph>
                <pre
                  style={{
                    margin: 0,
                    padding: 8,
                    background: token.colorBgLayout,
                    borderRadius: token.borderRadius,
                    fontSize: 12,
                    overflow: 'auto',
                  }}
                >
                  {JSON.stringify(
                    {
                      currentValue: lastResult.currentValue,
                      alert: lastResult.alert,
                      message: lastResult.message,
                    },
                    null,
                    2
                  )}
                </pre>
              </Paragraph>
            ) : (
              <Text type="secondary">No result yet</Text>
            )}
          </Card>
        </div>

        {/* Legend */}
        <Space split={<Text type="secondary">|</Text>}>
          <Space size="small">
            <Badge status="processing" />
            <Text type="secondary">Processing</Text>
          </Space>
          <Space size="small">
            <Badge status="success" />
            <Text type="secondary">Completed</Text>
          </Space>
          <Space size="small">
            <Badge status="default" />
            <Text type="secondary">Pending</Text>
          </Space>
        </Space>

        {/* Trace ID */}
        {lastAction?.traceId && flowStage !== 'idle' && (
          <Space style={{ width: '100%' }}>
            <Text type="secondary">Trace ID:</Text>
            <Text code copyable style={{ fontSize: 11 }}>
              {lastAction.traceId}
            </Text>
          </Space>
        )}
      </Space>
    </Card>
  );
}

export default EventFlow;
