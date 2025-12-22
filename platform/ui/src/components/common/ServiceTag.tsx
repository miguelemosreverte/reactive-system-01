import React from 'react';
import { Tag } from 'antd';
import { getServiceTagColor } from '../../theme/tokens';

interface ServiceTagProps {
  service: string;
  children?: React.ReactNode;
}

const ServiceTag: React.FC<ServiceTagProps> = ({ service, children }) => {
  return (
    <Tag color={getServiceTagColor(service)}>
      {children || service}
    </Tag>
  );
};

export default ServiceTag;
