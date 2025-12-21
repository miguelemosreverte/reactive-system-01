import { useState } from 'react';
import { Card, Button, InputNumber, Space, Tag, Typography, Divider, theme } from 'antd';
import { PlusOutlined, MinusOutlined, UndoOutlined } from '@ant-design/icons';

const { Title, Text } = Typography;

interface CounterProps {
  value: number;
  alert: string;
  message: string;
  onIncrement: () => void;
  onDecrement: () => void;
  onReset: () => void;
  onSetValue: (value: number) => void;
}

const alertColors: Record<string, string> = {
  CRITICAL: 'error',
  WARNING: 'warning',
  NORMAL: 'success',
  RESET: 'processing',
  NONE: 'default',
};

function Counter({
  value,
  alert,
  message,
  onIncrement,
  onDecrement,
  onReset,
  onSetValue,
}: CounterProps) {
  const [inputValue, setInputValue] = useState<number | null>(null);
  const { token } = theme.useToken();

  const handleSetValue = () => {
    if (inputValue !== null) {
      onSetValue(inputValue);
      setInputValue(null);
    }
  };

  return (
    <Card
      style={{ minWidth: 320, textAlign: 'center' }}
      styles={{ body: { padding: 24 } }}
    >
      <div
        style={{
          background: token.colorBgLayout,
          borderRadius: token.borderRadiusLG,
          padding: '32px 48px',
          marginBottom: 16,
        }}
      >
        <Title
          level={1}
          style={{
            margin: 0,
            fontSize: 64,
            fontFamily: 'monospace',
            fontWeight: 700,
          }}
        >
          {value}
        </Title>
      </div>

      <Tag
        color={alertColors[alert] || 'default'}
        style={{
          padding: '4px 16px',
          fontSize: 14,
          fontWeight: 600,
          textTransform: 'uppercase',
          letterSpacing: '0.05em',
        }}
      >
        {alert}
      </Tag>

      {message && (
        <Text
          type="secondary"
          style={{ display: 'block', marginTop: 12, marginBottom: 16 }}
        >
          {message}
        </Text>
      )}

      <Divider style={{ margin: '16px 0' }} />

      <Space size="middle">
        <Button
          type="primary"
          size="large"
          icon={<MinusOutlined />}
          onClick={onDecrement}
          style={{ minWidth: 60 }}
        />
        <Button
          type="primary"
          size="large"
          icon={<PlusOutlined />}
          onClick={onIncrement}
          style={{ minWidth: 60 }}
        />
      </Space>

      <Divider style={{ margin: '16px 0' }} />

      <Space.Compact style={{ width: '100%', marginBottom: 12 }}>
        <InputNumber
          value={inputValue}
          onChange={(val) => setInputValue(val)}
          placeholder="Enter value"
          style={{ flex: 1 }}
        />
        <Button type="primary" onClick={handleSetValue}>
          Set
        </Button>
      </Space.Compact>

      <Button
        icon={<UndoOutlined />}
        onClick={onReset}
        style={{ width: '100%' }}
      >
        Reset
      </Button>

      <Divider style={{ margin: '16px 0' }} />

      <div style={{ textAlign: 'left' }}>
        <Text type="secondary" style={{ fontSize: 12, textTransform: 'uppercase' }}>
          Alert Thresholds
        </Text>
        <div style={{ marginTop: 8 }}>
          <Text>
            <Tag color="success">NORMAL</Tag> 1-10
          </Text>
          <br />
          <Text>
            <Tag color="warning">WARNING</Tag> 11-100
          </Text>
          <br />
          <Text>
            <Tag color="error">CRITICAL</Tag> &gt;100
          </Text>
        </div>
      </div>
    </Card>
  );
}

export default Counter;
