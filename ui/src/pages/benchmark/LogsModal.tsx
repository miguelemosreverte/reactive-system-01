import React from 'react';
import { Modal, Space, Tag, Typography, List } from 'antd';
import type { SampleEvent, LokiLogEntry } from './types';
import { getServiceTagColor } from '../../theme/tokens';

const { Text } = Typography;

interface LogsModalProps {
  open: boolean;
  onClose: () => void;
  event: SampleEvent | null;
}

const getLevelColor = (level: string): string => {
  switch (level.toUpperCase()) {
    case 'ERROR': return 'error';
    case 'WARN': return 'warning';
    case 'DEBUG': return 'default';
    default: return 'processing';
  }
};

const LogsModal: React.FC<LogsModalProps> = ({ open, onClose, event }) => {
  if (!event?.traceData?.logs || event.traceData.logs.length === 0) {
    return (
      <Modal
        title="Logs"
        open={open}
        onCancel={onClose}
        footer={null}
        width={900}
      >
        <Text type="secondary">No logs available</Text>
      </Modal>
    );
  }

  const logs = event.traceData.logs;

  const parseLogEntry = (log: LokiLogEntry) => {
    let time = '-';
    let level = 'INFO';
    let message = log.line;
    let logger = '';

    try {
      const parsed = JSON.parse(log.line);
      if (parsed.timestamp) {
        time = parsed.timestamp.split('T')[1] || parsed.timestamp;
      }
      level = (parsed.level || 'INFO').trim().toUpperCase();
      message = parsed.message || parsed.msg || log.line;
      if (parsed.logger) {
        const parts = parsed.logger.split('.');
        logger = parts[parts.length - 1];
      }
    } catch {
      if (log.timestamp) {
        try {
          const ts = parseInt(log.timestamp) / 1000000;
          time = new Date(ts).toISOString().split('T')[1].replace('Z', '');
        } catch {}
      }
    }

    return { time, level, message, logger };
  };

  return (
    <Modal
      title={
        <Space>
          <span>Logs</span>
          <Text code>{event.traceId}</Text>
          <Tag>{logs.length} entries</Tag>
        </Space>
      }
      open={open}
      onCancel={onClose}
      footer={null}
      width={1000}
      styles={{ body: { maxHeight: '70vh', overflow: 'auto' } }}
    >
      <List
        size="small"
        dataSource={logs}
        renderItem={(log: LokiLogEntry) => {
          const service = log.labels?.service || log.labels?.container || 'unknown';
          const { time, level, message, logger } = parseLogEntry(log);

          return (
            <List.Item style={{ padding: '4px 0', alignItems: 'flex-start' }}>
              <Space size="small" align="start" wrap style={{ width: '100%' }}>
                <Text type="secondary" style={{ fontSize: 11, whiteSpace: 'nowrap' }}>
                  {time}
                </Text>
                <Tag color={getLevelColor(level)} style={{ fontSize: 10 }}>
                  {level}
                </Tag>
                <Tag color={getServiceTagColor(service)} style={{ fontSize: 10 }}>
                  {service}
                </Tag>
                {logger && (
                  <Text type="secondary" style={{ fontSize: 10 }}>
                    {logger}
                  </Text>
                )}
                <Text style={{ flex: 1, fontSize: 12, wordBreak: 'break-word' }}>
                  {message}
                </Text>
              </Space>
            </List.Item>
          );
        }}
      />
    </Modal>
  );
};

export default LogsModal;
