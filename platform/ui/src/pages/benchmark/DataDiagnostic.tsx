import React, { useMemo, useState } from 'react';
import { Card, Alert, Badge, Tag, Space, Typography, Button, Collapse, Row, Col, Progress } from 'antd';
import {
  CloseCircleOutlined,
  WarningOutlined,
  InfoCircleOutlined,
  BugOutlined,
  CopyOutlined,
  CheckCircleOutlined,
} from '@ant-design/icons';
import { runDiagnostic, generateTaskList } from './diagnostic-types';
import type { BenchmarkResult } from './types';

const { Text, Paragraph } = Typography;

interface DataDiagnosticProps {
  data: BenchmarkResult | null;
}

const DataDiagnostic: React.FC<DataDiagnosticProps> = ({ data }) => {
  const [showDetails, setShowDetails] = useState(false);
  const [copiedCommand, setCopiedCommand] = useState<string | null>(null);

  const diagnostic = useMemo(() => {
    return runDiagnostic(data);
  }, [data]);

  const taskList = useMemo(() => {
    return generateTaskList(diagnostic);
  }, [diagnostic]);

  const { criticalCount, warningCount, infoCount } = diagnostic.summary;
  const overallStatus = diagnostic.overallStatus === 'failing' ? 'error' : diagnostic.overallStatus === 'degraded' ? 'warning' : 'success';
  const completeness = diagnostic.completenessScore;

  const getSeverityIcon = (severity: string) => {
    switch (severity) {
      case 'critical': return <CloseCircleOutlined style={{ color: '#ff4d4f' }} />;
      case 'warning': return <WarningOutlined style={{ color: '#faad14' }} />;
      case 'info': return <InfoCircleOutlined style={{ color: '#1890ff' }} />;
      default: return <InfoCircleOutlined />;
    }
  };

  const copyTaskList = () => {
    navigator.clipboard.writeText(taskList);
  };

  if (!data) {
    return (
      <Alert
        type="error"
        icon={<BugOutlined />}
        message="No benchmark data loaded"
        description="The benchmark data is missing. Check that window.__BENCHMARK_DATA__ is properly set."
        showIcon
      />
    );
  }

  return (
    <Card
      size="small"
      title={
        <Space>
          <BugOutlined />
          <span>Data Diagnostic</span>
          <Tag color={overallStatus}>
            {overallStatus === 'success' ? 'All Good' : overallStatus === 'warning' ? 'Some Issues' : 'Problems Detected'}
          </Tag>
        </Space>
      }
      extra={
        <Button size="small" onClick={() => setShowDetails(!showDetails)}>
          {showDetails ? 'Hide Details' : 'Show Details'}
        </Button>
      }
      style={{ marginBottom: 16 }}
    >
      {/* Summary Row */}
      <Row gutter={16} align="middle" style={{ marginBottom: showDetails ? 16 : 0 }}>
        <Col span={6}>
          <div style={{ textAlign: 'center' }}>
            <Progress
              type="circle"
              percent={completeness}
              size={60}
              status={completeness === 100 ? 'success' : completeness >= 70 ? 'normal' : 'exception'}
              format={p => `${p?.toFixed(0)}%`}
            />
            <div style={{ marginTop: 4 }}>
              <Text type="secondary" style={{ fontSize: 11 }}>Data Completeness</Text>
            </div>
          </div>
        </Col>
        <Col span={18}>
          <Space size={16}>
            <Badge count={criticalCount} style={{ backgroundColor: '#ff4d4f' }} showZero>
              <Tag color="error">Critical</Tag>
            </Badge>
            <Badge count={warningCount} style={{ backgroundColor: '#faad14' }} showZero>
              <Tag color="warning">Warnings</Tag>
            </Badge>
            <Badge count={infoCount} style={{ backgroundColor: '#1890ff' }} showZero>
              <Tag color="processing">Info</Tag>
            </Badge>
          </Space>

          {/* Quick summary of what data exists */}
          <div style={{ marginTop: 8 }}>
            <Space size={4} wrap>
              <Tag color={data.throughputTimeline?.length > 0 ? 'success' : 'error'}>
                Throughput: {data.throughputTimeline?.length || 0} samples
              </Tag>
              <Tag color={data.cpuTimeline?.length > 0 ? 'success' : 'error'}>
                CPU: {data.cpuTimeline?.length || 0} samples
              </Tag>
              <Tag color={data.memoryTimeline?.length > 0 ? 'success' : 'error'}>
                Memory: {data.memoryTimeline?.length || 0} samples
              </Tag>
              <Tag color={data.sampleEvents?.length > 0 ? 'success' : 'default'}>
                Events: {data.sampleEvents?.length || 0}
              </Tag>
              <Tag color={data.sampleEvents?.some(e => (e.traceData?.logs?.length ?? 0) > 0) ? 'success' : 'default'}>
                Logs: {data.sampleEvents?.reduce((sum, e) => sum + (e.traceData?.logs?.length ?? 0), 0) || 0}
              </Tag>
              <Tag color={data.sampleEvents?.some(e => (e.traceData?.trace?.spans?.length ?? 0) > 0) ? 'success' : 'default'}>
                Traces: {data.sampleEvents?.filter(e => (e.traceData?.trace?.spans?.length ?? 0) > 0).length || 0}
              </Tag>
            </Space>
          </div>
        </Col>
      </Row>

      {/* Details Section */}
      {showDetails && (
        <div style={{ marginTop: 16 }}>
          <Collapse ghost defaultActiveKey={criticalCount > 0 ? ['issues', 'tasklist'] : []}>
            <Collapse.Panel
              header={<Text strong>Detected Issues ({diagnostic.issues.length})</Text>}
              key="issues"
            >
              {diagnostic.issues.length === 0 ? (
                <Alert
                  type="success"
                  message="No issues detected"
                  description="All diagnostic checks passed."
                  icon={<CheckCircleOutlined />}
                  showIcon
                />
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                  {diagnostic.issues.map((issue) => (
                    <Card
                      key={issue.id}
                      size="small"
                      style={{
                        borderLeft: `4px solid ${
                          issue.severity === 'critical' ? '#ff4d4f' :
                          issue.severity === 'warning' ? '#faad14' : '#1890ff'
                        }`
                      }}
                    >
                      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
                        {getSeverityIcon(issue.severity)}
                        <div style={{ flex: 1 }}>
                          <div style={{ fontWeight: 600, marginBottom: 4 }}>{issue.title}</div>
                          <div style={{ fontSize: 12, color: '#666', marginBottom: 8 }}>{issue.description}</div>
                          <div style={{ fontSize: 11, marginBottom: 8 }}>
                            <Text code style={{ fontSize: 10 }}>{issue.evidence}</Text>
                          </div>

                          {/* Fix Steps */}
                          <div style={{ marginBottom: 8 }}>
                            <Text strong style={{ fontSize: 11 }}>Fix Steps:</Text>
                            <ul style={{ margin: '4px 0', paddingLeft: 20, fontSize: 11 }}>
                              {issue.fixSteps.map((step, j) => (
                                <li key={j}>{step}</li>
                              ))}
                            </ul>
                          </div>

                          {/* Commands */}
                          {issue.commands && issue.commands.length > 0 && (
                            <div style={{ marginTop: 8 }}>
                              <Text strong style={{ fontSize: 11 }}>Commands:</Text>
                              <div style={{
                                background: '#1a1a1a',
                                padding: 8,
                                borderRadius: 4,
                                marginTop: 4,
                                fontSize: 11,
                                fontFamily: 'monospace'
                              }}>
                                {issue.commands.map((cmd, j) => (
                                  <div
                                    key={j}
                                    style={{
                                      display: 'flex',
                                      alignItems: 'center',
                                      justifyContent: 'space-between',
                                      padding: '4px 0',
                                      borderBottom: j < issue.commands!.length - 1 ? '1px solid #333' : 'none'
                                    }}
                                  >
                                    <code style={{ color: '#50fa7b' }}>{cmd}</code>
                                    <Button
                                      size="small"
                                      type="text"
                                      icon={<CopyOutlined style={{ color: copiedCommand === cmd ? '#52c41a' : '#888' }} />}
                                      onClick={() => {
                                        navigator.clipboard.writeText(cmd);
                                        setCopiedCommand(cmd);
                                        setTimeout(() => setCopiedCommand(null), 2000);
                                      }}
                                    />
                                  </div>
                                ))}
                              </div>
                            </div>
                          )}

                          {/* Related Docs */}
                          {issue.relatedDocs && (
                            <div style={{ marginTop: 8, fontSize: 11 }}>
                              <Text type="secondary">{issue.relatedDocs}</Text>
                            </div>
                          )}
                        </div>
                      </div>
                    </Card>
                  ))}
                </div>
              )}
            </Collapse.Panel>

            <Collapse.Panel
              header={
                <Space>
                  <Text strong>Full Task List (Markdown)</Text>
                  <Button size="small" icon={<CopyOutlined />} onClick={(e) => { e.stopPropagation(); copyTaskList(); }}>
                    Copy
                  </Button>
                </Space>
              }
              key="tasklist"
            >
              <Paragraph>
                <pre style={{
                  background: '#f5f5f5',
                  padding: 16,
                  borderRadius: 8,
                  fontSize: 12,
                  maxHeight: 400,
                  overflow: 'auto',
                  whiteSpace: 'pre-wrap',
                }}>
                  {taskList}
                </pre>
              </Paragraph>
            </Collapse.Panel>

            <Collapse.Panel
              header={<Text strong>Data Summary</Text>}
              key="raw"
            >
              <Row gutter={16}>
                <Col span={12}>
                  <div style={{ fontSize: 11 }}>
                    <div><strong>Component:</strong> {data.component}</div>
                    <div><strong>Total Operations:</strong> {data.totalOperations?.toLocaleString()}</div>
                    <div><strong>Successful:</strong> <Text type="success">{data.successfulOperations?.toLocaleString()}</Text></div>
                    <div><strong>Failed:</strong> <Text type="danger">{data.failedOperations?.toLocaleString()}</Text></div>
                    <div><strong>Peak Throughput:</strong> {data.peakThroughput?.toLocaleString()}/s</div>
                  </div>
                </Col>
                <Col span={12}>
                  <div style={{ fontSize: 11 }}>
                    <div><strong>Data Fields:</strong></div>
                    <Space size={4} wrap style={{ marginTop: 4 }}>
                      {Object.entries(diagnostic.summary.dataFields).map(([field, present]) => (
                        <Tag key={field} color={present ? 'success' : 'default'} style={{ fontSize: 10 }}>
                          {present ? '✓' : '✗'} {field}
                        </Tag>
                      ))}
                    </Space>
                  </div>
                </Col>
              </Row>
            </Collapse.Panel>
          </Collapse>
        </div>
      )}
    </Card>
  );
};

export default DataDiagnostic;
