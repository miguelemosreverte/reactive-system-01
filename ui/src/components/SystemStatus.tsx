import { useState, useEffect, useCallback } from 'react';
import { Card, Button, Badge, Typography, Space, Row, Col, Alert, theme } from 'antd';
import { ReloadOutlined, CheckCircleOutlined, CloseCircleOutlined } from '@ant-design/icons';

const { Text, Title } = Typography;

interface ServiceHealth {
  name: string;
  status: 'healthy' | 'unhealthy' | 'checking';
  detail: string;
  port: number;
  category: 'infrastructure' | 'observability' | 'service' | 'application';
}

interface SystemStatusProps {
  isConnected: boolean;
}

function SystemStatus({ isConnected }: SystemStatusProps) {
  const { token } = theme.useToken();

  const [services, setServices] = useState<ServiceHealth[]>([
    { name: 'Zookeeper', status: 'checking', detail: 'TCP 2181', port: 2181, category: 'infrastructure' },
    { name: 'Kafka', status: 'checking', detail: 'TCP 9092', port: 9092, category: 'infrastructure' },
    { name: 'OTEL Collector', status: 'checking', detail: 'Traces & Metrics', port: 4318, category: 'observability' },
    { name: 'Jaeger', status: 'checking', detail: 'Trace Storage', port: 16686, category: 'observability' },
    { name: 'Grafana', status: 'checking', detail: 'Dashboards', port: 3001, category: 'observability' },
    { name: 'Drools', status: 'checking', detail: 'Rules Engine', port: 8180, category: 'service' },
    { name: 'Flink', status: 'checking', detail: 'Stream Processor', port: 8081, category: 'service' },
    { name: 'Gateway', status: 'checking', detail: 'WebSocket Bridge', port: 8080, category: 'service' },
    { name: 'UI', status: 'checking', detail: 'React Frontend', port: 3000, category: 'service' },
    { name: 'Flink Job', status: 'checking', detail: 'Counter Processing', port: 8081, category: 'application' },
    { name: 'WebSocket', status: isConnected ? 'healthy' : 'unhealthy', detail: isConnected ? 'Connected' : 'Disconnected', port: 0, category: 'application' },
  ]);

  const [lastCheck, setLastCheck] = useState<Date | null>(null);
  const [loading, setLoading] = useState(false);

  const checkHealth = useCallback(async () => {
    setLoading(true);
    const newServices = [...services];

    try {
      const gatewayRes = await fetch('/api/health', { method: 'GET' });
      newServices[7].status = gatewayRes.ok ? 'healthy' : 'unhealthy';
      newServices[7].detail = gatewayRes.ok ? 'WebSocket Bridge' : 'Unreachable';

      if (gatewayRes.ok) {
        newServices[0].status = 'healthy';
        newServices[0].detail = 'TCP 2181';
        newServices[1].status = 'healthy';
        newServices[1].detail = 'TCP 9092';
      }
    } catch {
      newServices[7].status = 'unhealthy';
      newServices[7].detail = 'Unreachable';
    }

    try {
      const otelRes = await fetch('/api/otel-health', { method: 'GET' });
      newServices[2].status = otelRes.ok ? 'healthy' : 'unhealthy';
      newServices[2].detail = otelRes.ok ? 'Traces & Metrics' : 'Unreachable';
    } catch {
      newServices[2].status = 'unhealthy';
      newServices[2].detail = 'Unreachable';
    }

    try {
      const jaegerRes = await fetch('/api/jaeger-health', { method: 'GET' });
      newServices[3].status = jaegerRes.ok ? 'healthy' : 'unhealthy';
      newServices[3].detail = jaegerRes.ok ? 'Trace Storage' : 'Unreachable';
    } catch {
      newServices[3].status = 'unhealthy';
      newServices[3].detail = 'Unreachable';
    }

    try {
      const grafanaRes = await fetch('/api/grafana-health', { method: 'GET' });
      newServices[4].status = grafanaRes.ok ? 'healthy' : 'unhealthy';
      newServices[4].detail = grafanaRes.ok ? 'Dashboards' : 'Unreachable';
    } catch {
      newServices[4].status = 'unhealthy';
      newServices[4].detail = 'Unreachable';
    }

    try {
      const droolsRes = await fetch('/api/drools-health', { method: 'GET' });
      newServices[5].status = droolsRes.ok ? 'healthy' : 'unhealthy';
      newServices[5].detail = droolsRes.ok ? 'Rules Engine' : 'Unreachable';
    } catch {
      newServices[5].status = 'unhealthy';
      newServices[5].detail = 'Unreachable';
    }

    try {
      const flinkRes = await fetch('/api/flink-health', { method: 'GET' });
      if (flinkRes.ok) {
        newServices[6].status = 'healthy';
        newServices[6].detail = 'Stream Processor';

        const jobsRes = await fetch('/api/flink-jobs', { method: 'GET' });
        if (jobsRes.ok) {
          const jobsData = await jobsRes.json();
          const runningJobs = jobsData.jobs?.filter((j: { state: string }) => j.state === 'RUNNING').length || 0;
          newServices[9].status = runningJobs > 0 ? 'healthy' : 'unhealthy';
          newServices[9].detail = runningJobs > 0 ? `${runningJobs} job(s) running` : 'No jobs running';
        }
      } else {
        newServices[6].status = 'unhealthy';
        newServices[6].detail = 'Unreachable';
        newServices[9].status = 'unhealthy';
        newServices[9].detail = 'Cannot check';
      }
    } catch {
      newServices[6].status = 'unhealthy';
      newServices[6].detail = 'Unreachable';
      newServices[9].status = 'unhealthy';
      newServices[9].detail = 'Cannot check';
    }

    newServices[8].status = 'healthy';
    newServices[8].detail = 'React Frontend';

    newServices[10].status = isConnected ? 'healthy' : 'unhealthy';
    newServices[10].detail = isConnected ? 'Connected' : 'Disconnected';

    setServices(newServices);
    setLastCheck(new Date());
    setLoading(false);
  }, [isConnected, services]);

  useEffect(() => {
    checkHealth();
    const interval = setInterval(checkHealth, 10000);
    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    setServices(prev => {
      const newServices = [...prev];
      newServices[10].status = isConnected ? 'healthy' : 'unhealthy';
      newServices[10].detail = isConnected ? 'Connected' : 'Disconnected';
      return newServices;
    });
  }, [isConnected]);

  const categories = [
    { key: 'infrastructure', label: 'Infrastructure' },
    { key: 'observability', label: 'Observability' },
    { key: 'service', label: 'Services' },
    { key: 'application', label: 'Application' },
  ];

  const allHealthy = services.every(s => s.status === 'healthy');
  const healthyCount = services.filter(s => s.status === 'healthy').length;

  return (
    <Card
      title={
        <Space>
          <Title level={5} style={{ margin: 0 }}>System Health</Title>
          <Badge
            count={`${healthyCount}/${services.length}`}
            style={{ backgroundColor: allHealthy ? token.colorSuccess : token.colorWarning }}
          />
        </Space>
      }
      extra={
        <Button
          icon={<ReloadOutlined spin={loading} />}
          onClick={checkHealth}
          loading={loading}
        >
          Refresh
        </Button>
      }
    >
      <Space direction="vertical" style={{ width: '100%' }} size="middle">
        {allHealthy ? (
          <Alert
            message="All services healthy"
            type="success"
            showIcon
            icon={<CheckCircleOutlined />}
          />
        ) : (
          <Alert
            message="Some services are unhealthy"
            type="warning"
            showIcon
            icon={<CloseCircleOutlined />}
          />
        )}

        <Row gutter={[16, 16]}>
          {categories.map(cat => (
            <Col xs={24} sm={12} key={cat.key}>
              <Card size="small" title={cat.label} type="inner">
                <Space direction="vertical" style={{ width: '100%' }} size="small">
                  {services
                    .filter(s => s.category === cat.key)
                    .map(service => (
                      <div
                        key={service.name}
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'space-between',
                        }}
                      >
                        <Space>
                          <Badge
                            status={
                              service.status === 'healthy'
                                ? 'success'
                                : service.status === 'unhealthy'
                                ? 'error'
                                : 'processing'
                            }
                          />
                          <Text strong style={{ minWidth: 100 }}>{service.name}</Text>
                        </Space>
                        <Space>
                          <Text type="secondary" style={{ fontSize: 12 }}>
                            {service.detail}
                          </Text>
                          {service.port > 0 && (
                            <Text type="secondary" code style={{ fontSize: 11 }}>
                              :{service.port}
                            </Text>
                          )}
                        </Space>
                      </div>
                    ))}
                </Space>
              </Card>
            </Col>
          ))}
        </Row>

        {lastCheck && (
          <Text type="secondary" style={{ display: 'block', textAlign: 'center', fontSize: 12 }}>
            Last checked: {lastCheck.toLocaleTimeString()}
          </Text>
        )}
      </Space>
    </Card>
  );
}

export default SystemStatus;
