import React from 'react';
import { Badge, Space } from 'antd';
import type { BadgeProps } from 'antd';

type StatusType = 'connected' | 'disconnected' | 'connecting' | 'healthy' | 'unhealthy' | 'warning' | 'success' | 'error';

interface StatusBadgeProps {
  status: StatusType;
  text?: string;
  showText?: boolean;
}

const statusConfig: Record<StatusType, { status: BadgeProps['status']; text: string }> = {
  connected: { status: 'success', text: 'Connected' },
  disconnected: { status: 'error', text: 'Disconnected' },
  connecting: { status: 'processing', text: 'Connecting...' },
  healthy: { status: 'success', text: 'Healthy' },
  unhealthy: { status: 'error', text: 'Unhealthy' },
  warning: { status: 'warning', text: 'Warning' },
  success: { status: 'success', text: 'Success' },
  error: { status: 'error', text: 'Error' },
};

const StatusBadge: React.FC<StatusBadgeProps> = ({ status, text, showText = true }) => {
  const config = statusConfig[status];

  if (!showText) {
    return <Badge status={config.status} />;
  }

  return (
    <Space size="small">
      <Badge status={config.status} />
      <span>{text || config.text}</span>
    </Space>
  );
};

export default StatusBadge;
