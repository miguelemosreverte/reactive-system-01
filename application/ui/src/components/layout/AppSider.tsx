import React from 'react';
import { Layout, Menu, Typography, Space, theme } from 'antd';
import {
  PlayCircleOutlined,
  DashboardOutlined,
  LineChartOutlined,
  BookOutlined,
  LinkOutlined,
  BarChartOutlined,
} from '@ant-design/icons';
import type { MenuProps } from 'antd';
import { layout } from '../../theme/tokens';
import { getJaegerUrl, getGrafanaUrl, getFlinkUrl } from '../../utils/urls';

const { Sider } = Layout;
const { Text } = Typography;

export type NavItem = 'demo' | 'status' | 'traces' | 'docs' | 'benchmark';

interface AppSiderProps {
  activeItem: NavItem;
  onNavigate: (item: NavItem) => void;
  showBenchmark?: boolean;
}

type MenuItem = Required<MenuProps>['items'][number];

const AppSider: React.FC<AppSiderProps> = ({ activeItem, onNavigate, showBenchmark = false }) => {
  const { token } = theme.useToken();

  const navItems: MenuItem[] = [
    {
      key: 'app-group',
      label: 'Application',
      type: 'group',
      children: [
        {
          key: 'demo',
          icon: <PlayCircleOutlined />,
          label: 'Live Demo',
        },
        {
          key: 'status',
          icon: <DashboardOutlined />,
          label: 'System Status',
        },
        {
          key: 'traces',
          icon: <LineChartOutlined />,
          label: 'Traces',
        },
        {
          key: 'docs',
          icon: <BookOutlined />,
          label: 'Documentation',
        },
        ...(showBenchmark ? [{
          key: 'benchmark',
          icon: <BarChartOutlined />,
          label: 'Benchmarks',
        }] : []),
      ],
    },
    {
      key: 'obs-group',
      label: 'Observability',
      type: 'group',
      children: [
        {
          key: 'jaeger',
          icon: <LinkOutlined />,
          label: (
            <a href={getJaegerUrl()} target="_blank" rel="noopener noreferrer">
              Jaeger UI
            </a>
          ),
        },
        {
          key: 'grafana',
          icon: <LinkOutlined />,
          label: (
            <a href={getGrafanaUrl()} target="_blank" rel="noopener noreferrer">
              Grafana
            </a>
          ),
        },
        {
          key: 'flink',
          icon: <LinkOutlined />,
          label: (
            <a href={getFlinkUrl()} target="_blank" rel="noopener noreferrer">
              Flink Dashboard
            </a>
          ),
        },
      ],
    },
  ];

  const handleMenuClick: MenuProps['onClick'] = (e) => {
    // Only handle internal navigation
    if (['demo', 'status', 'traces', 'docs', 'benchmark'].includes(e.key)) {
      onNavigate(e.key as NavItem);
    }
  };

  return (
    <Sider
      width={layout.siderWidth}
      style={{
        background: token.colorBgContainer,
        borderRight: `1px solid ${token.colorBorderSecondary}`,
      }}
    >
      <div
        style={{
          height: layout.headerHeight,
          display: 'flex',
          alignItems: 'center',
          padding: '0 16px',
          borderBottom: `1px solid ${token.colorBorderSecondary}`,
        }}
      >
        <Space>
          <Text style={{ fontSize: 20, color: token.colorPrimary }}>
            &#x27C1;
          </Text>
          <Text strong style={{ fontSize: 16 }}>
            Reactive System
          </Text>
        </Space>
      </div>

      <Menu
        mode="inline"
        selectedKeys={[activeItem]}
        onClick={handleMenuClick}
        items={navItems}
        style={{ border: 'none' }}
      />

      <div
        style={{
          position: 'absolute',
          bottom: 0,
          left: 0,
          right: 0,
          padding: 16,
          borderTop: `1px solid ${token.colorBorderSecondary}`,
          textAlign: 'center',
        }}
      >
        <Text type="secondary" style={{ fontSize: 12 }}>
          Event-Driven Architecture
        </Text>
        <br />
        <Text type="secondary" style={{ fontSize: 11 }}>
          Kafka &bull; Flink &bull; Drools
        </Text>
      </div>
    </Sider>
  );
};

export default AppSider;
