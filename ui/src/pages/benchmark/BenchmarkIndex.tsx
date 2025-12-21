import React from 'react';
import { Layout, Menu, Typography, Progress, Tag, theme } from 'antd';
import {
  DashboardOutlined,
  ThunderboltOutlined,
  ApiOutlined,
  CloudServerOutlined,
  FunctionOutlined,
  FileTextOutlined,
  RocketOutlined,
} from '@ant-design/icons';
import type { BenchmarkIndex as BenchmarkIndexData } from './types';

const { Sider, Content } = Layout;
const { Title, Text } = Typography;

interface BenchmarkIndexProps {
  data: BenchmarkIndexData;
  onSelectComponent: (componentId: string) => void;
  selectedComponent: string;
}

const componentIcons: Record<string, React.ReactNode> = {
  http: <ThunderboltOutlined />,
  gateway: <ApiOutlined />,
  kafka: <CloudServerOutlined />,
  flink: <FunctionOutlined />,
  drools: <FileTextOutlined />,
  full: <RocketOutlined />,
};

const BenchmarkIndexView: React.FC<BenchmarkIndexProps> = ({
  data,
  onSelectComponent,
  selectedComponent,
}) => {
  const { token } = theme.useToken();

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        width={280}
        style={{
          background: token.colorBgContainer,
          borderRight: `1px solid ${token.colorBorderSecondary}`,
        }}
      >
        {/* Header */}
        <div style={{ padding: '16px 16px 12px', borderBottom: `1px solid ${token.colorBorderSecondary}` }}>
          <Title level={5} style={{ margin: 0, color: token.colorText }}>
            Benchmark Reports
          </Title>
          <Text type="secondary" style={{ fontSize: 11 }}>
            {data.generatedAt}
          </Text>
        </div>

        {/* Component Menu */}
        <Menu
          mode="inline"
          selectedKeys={[selectedComponent]}
          onClick={(e) => onSelectComponent(e.key)}
          style={{ border: 'none', padding: '8px 0' }}
          items={data.components.map((comp) => ({
            key: comp.id,
            icon: componentIcons[comp.id] || <DashboardOutlined />,
            label: comp.name,
          }))}
        />

        {/* Throughput Summary */}
        <div style={{ padding: '12px 16px', borderTop: `1px solid ${token.colorBorderSecondary}` }}>
          <Text type="secondary" style={{ fontSize: 11, textTransform: 'uppercase' }}>
            Throughput
          </Text>
          <div style={{ marginTop: 8 }}>
            {data.rankings.map((item) => (
              <div key={item.id} style={{ marginBottom: 8 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, marginBottom: 2 }}>
                  <span>
                    {item.name}
                    {item.isBottleneck && (
                      <Tag
                        style={{ fontSize: 9, marginLeft: 4, padding: '0 4px', lineHeight: '14px' }}
                      >
                        slow
                      </Tag>
                    )}
                  </span>
                  <Text type="secondary" style={{ fontSize: 11 }}>
                    {item.peakThroughput.toLocaleString()}/s
                  </Text>
                </div>
                <Progress
                  percent={item.barWidth}
                  showInfo={false}
                  strokeColor={token.colorPrimary}
                  size="small"
                  style={{ margin: 0 }}
                />
              </div>
            ))}
          </div>
        </div>
      </Sider>
      <Content style={{ background: token.colorBgLayout }}>
        <iframe
          src={`${selectedComponent}/index.html`}
          style={{ width: '100%', height: '100%', border: 'none' }}
          title="Benchmark Report"
        />
      </Content>
    </Layout>
  );
};

export default BenchmarkIndexView;
