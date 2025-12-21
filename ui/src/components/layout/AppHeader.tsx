import React from 'react';
import { Layout, Space, Typography } from 'antd';
import { ThemeToggle, StatusBadge } from '../common';
import { layout } from '../../theme/tokens';

const { Header } = Layout;
const { Text } = Typography;

interface AppHeaderProps {
  isConnected?: boolean;
  title?: string;
}

const AppHeader: React.FC<AppHeaderProps> = ({ isConnected, title }) => {
  return (
    <Header
      style={{
        height: layout.headerHeight,
        padding: '0 24px',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        borderBottom: '1px solid rgba(0, 0, 0, 0.06)',
      }}
    >
      <Space>
        {title && (
          <Text strong style={{ fontSize: 16 }}>
            {title}
          </Text>
        )}
      </Space>

      <Space size="middle">
        {isConnected !== undefined && (
          <StatusBadge status={isConnected ? 'connected' : 'disconnected'} />
        )}
        <ThemeToggle />
      </Space>
    </Header>
  );
};

export default AppHeader;
