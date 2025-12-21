import React from 'react';
import { Tag } from 'antd';
import { serviceColors } from '../../theme/tokens';

type ServiceType = keyof typeof serviceColors;

interface ServiceTagProps {
  service: ServiceType | string;
  children?: React.ReactNode;
}

const serviceConfig: Record<string, { color: string; label: string }> = {
  gateway: { color: serviceColors.gateway, label: 'Gateway' },
  kafka: { color: serviceColors.kafka, label: 'Kafka' },
  flink: { color: serviceColors.flink, label: 'Flink' },
  drools: { color: serviceColors.drools, label: 'Drools' },
};

const ServiceTag: React.FC<ServiceTagProps> = ({ service, children }) => {
  const normalizedService = service.toLowerCase();

  // Find matching service config
  let config = serviceConfig[normalizedService];

  // Check if service name contains one of the known services
  if (!config) {
    for (const [key, value] of Object.entries(serviceConfig)) {
      if (normalizedService.includes(key)) {
        config = value;
        break;
      }
    }
  }

  if (!config) {
    return <Tag color="default">{children || service}</Tag>;
  }

  return (
    <Tag color={config.color}>
      {children || config.label}
    </Tag>
  );
};

export default ServiceTag;
