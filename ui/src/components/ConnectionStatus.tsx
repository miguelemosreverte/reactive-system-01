import { Badge, Space, Typography, theme } from 'antd';
import { WifiOutlined, DisconnectOutlined, LoadingOutlined } from '@ant-design/icons';

const { Text } = Typography;

interface ConnectionStatusProps {
  isConnected: boolean;
  sessionId: string | null;
  isReconnecting?: boolean;
  reconnectAttempt?: number;
}

function ConnectionStatus({
  isConnected,
  sessionId,
  isReconnecting,
  reconnectAttempt,
}: ConnectionStatusProps) {
  const { token } = theme.useToken();

  const getStatus = () => {
    if (isReconnecting) {
      return {
        status: 'processing' as const,
        text: `Reconnecting... (${reconnectAttempt})`,
        icon: <LoadingOutlined spin />,
        color: token.colorWarning,
      };
    }
    if (isConnected) {
      return {
        status: 'success' as const,
        text: 'Connected',
        icon: <WifiOutlined />,
        color: token.colorSuccess,
      };
    }
    return {
      status: 'error' as const,
      text: 'Disconnected',
      icon: <DisconnectOutlined />,
      color: token.colorError,
    };
  };

  const statusConfig = getStatus();

  return (
    <div
      style={{
        padding: '8px 16px',
        background: token.colorBgContainer,
        borderRadius: token.borderRadius,
        border: `1px solid ${token.colorBorderSecondary}`,
      }}
    >
      <Space>
        <Badge status={statusConfig.status} />
        <Text style={{ color: statusConfig.color }}>{statusConfig.icon}</Text>
        <Text>{statusConfig.text}</Text>
        {sessionId && isConnected && (
          <Text type="secondary" style={{ fontSize: 11 }}>
            ID: {sessionId.substring(0, 8)}...
          </Text>
        )}
      </Space>
    </div>
  );
}

export default ConnectionStatus;
