import React from 'react';
import { Layout, theme } from 'antd';
import AppHeader from './AppHeader';
import AppSider, { NavItem } from './AppSider';

const { Content } = Layout;

interface AppLayoutProps {
  children: React.ReactNode;
  activeNav: NavItem;
  onNavigate: (item: NavItem) => void;
  isConnected?: boolean;
  showBenchmark?: boolean;
}

const AppLayout: React.FC<AppLayoutProps> = ({
  children,
  activeNav,
  onNavigate,
  isConnected,
  showBenchmark,
}) => {
  const { token } = theme.useToken();

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <AppSider
        activeItem={activeNav}
        onNavigate={onNavigate}
        showBenchmark={showBenchmark}
      />
      <Layout>
        <AppHeader isConnected={isConnected} />
        <Content
          style={{
            padding: 24,
            background: token.colorBgLayout,
            overflow: 'auto',
          }}
        >
          {children}
        </Content>
      </Layout>
    </Layout>
  );
};

export default AppLayout;
