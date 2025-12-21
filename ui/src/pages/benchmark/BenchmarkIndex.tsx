import React, { useState } from 'react';
import { Layout, Menu, Card, Typography, Space, Progress, Tag, Row, Col, theme } from 'antd';
import {
  DashboardOutlined,
  ThunderboltOutlined,
  ApiOutlined,
  CloudServerOutlined,
  FunctionOutlined,
  FileTextOutlined,
  RocketOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import type { BenchmarkIndex as BenchmarkIndexData } from './types';

const { Sider, Content } = Layout;
const { Title, Text, Paragraph } = Typography;

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
      <Sider width={380} style={{ background: token.colorBgContainer, padding: 20, overflow: 'auto' }}>
        <Title level={4} style={{ color: token.colorPrimary, marginBottom: 8 }}>
          Benchmark Dashboard
        </Title>
        <Text type="secondary" style={{ fontSize: 12 }}>
          Generated: {data.generatedAt}
        </Text>

        {/* Architecture Diagram */}
        <Card size="small" style={{ marginTop: 20, marginBottom: 20 }}>
          <Text strong style={{ fontSize: 12, textTransform: 'uppercase', color: token.colorPrimary }}>
            System Architecture
          </Text>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8, flexWrap: 'wrap', padding: '16px 0' }}>
            {['Client', 'Gateway', 'Kafka', 'Flink', 'Drools'].map((name, idx) => (
              <React.Fragment key={name}>
                <div
                  style={{
                    border: `2px solid ${
                      name === 'Client' ? token.colorPrimary :
                      name === 'Gateway' ? token.colorSuccess :
                      name === 'Kafka' ? token.colorWarning :
                      name === 'Flink' ? '#722ed1' :
                      token.colorError
                    }`,
                    borderRadius: 8,
                    padding: '8px 12px',
                    textAlign: 'center',
                    minWidth: 60,
                  }}
                >
                  <div style={{ fontSize: 16, marginBottom: 2 }}>
                    {name === 'Client' ? '🌐' :
                     name === 'Gateway' ? '⚡' :
                     name === 'Kafka' ? '📨' :
                     name === 'Flink' ? '🔄' : '📋'}
                  </div>
                  <Text style={{ fontSize: 10, textTransform: 'uppercase' }}>{name}</Text>
                </div>
                {idx < 4 && <Text type="secondary">→</Text>}
              </React.Fragment>
            ))}
          </div>
          <Paragraph style={{ fontSize: 11, marginBottom: 0 }} type="secondary">
            <Text strong style={{ color: token.colorPrimary }}>Full Pipeline:</Text> HTTP → Gateway → Kafka → Flink → Drools → Response
          </Paragraph>
        </Card>

        {/* Throughput Ranking */}
        <Card size="small" style={{ marginBottom: 20 }}>
          <Text strong style={{ fontSize: 12, textTransform: 'uppercase', color: token.colorWarning }}>
            Throughput Ranking
          </Text>
          <Space direction="vertical" style={{ width: '100%', marginTop: 12 }} size="small">
            {data.rankings.map((item) => (
              <div key={item.id}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <Space>
                    <Text>{item.name}</Text>
                    {item.isBottleneck && (
                      <Tag color="error" icon={<WarningOutlined />} style={{ fontSize: 10 }}>
                        Bottleneck
                      </Tag>
                    )}
                  </Space>
                  <Text strong style={{ color: item.isBottleneck ? token.colorError : token.colorSuccess }}>
                    {item.peakThroughput} ops/s
                  </Text>
                </div>
                <Progress
                  percent={item.barWidth}
                  showInfo={false}
                  strokeColor={item.isBottleneck ? token.colorError : token.colorSuccess}
                  size="small"
                />
              </div>
            ))}
          </Space>
        </Card>

        {/* Component List */}
        <Text strong style={{ fontSize: 12, textTransform: 'uppercase', color: token.colorPrimary, display: 'block', marginBottom: 12 }}>
          Component Reports
        </Text>
        <Menu
          mode="inline"
          selectedKeys={[selectedComponent]}
          onClick={(e) => onSelectComponent(e.key)}
          style={{ border: 'none' }}
          items={data.components.map((comp) => ({
            key: comp.id,
            icon: componentIcons[comp.id] || <DashboardOutlined />,
            label: (
              <div>
                <Text strong>{comp.name}</Text>
                <div style={{ fontSize: 11 }}>
                  <Text type="secondary">{comp.peakThroughput} ops/s</Text>
                  <Text type="secondary" style={{ marginLeft: 12 }}>P99: {comp.latencyP99}ms</Text>
                </div>
              </div>
            ),
          }))}
        />
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
