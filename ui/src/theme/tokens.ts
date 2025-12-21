import type { ThemeConfig } from 'antd';

// Service colors for the reactive system architecture
export const serviceColors = {
  gateway: '#52c41a',
  kafka: '#faad14',
  flink: '#722ed1',
  drools: '#ff4d4f',
} as const;

// Get service color by name (supports partial matching)
export const getServiceColor = (serviceName: string): string => {
  const name = serviceName.toLowerCase();
  if (name.includes('gateway') || name.includes('counter-application')) return serviceColors.gateway;
  if (name.includes('kafka')) return serviceColors.kafka;
  if (name.includes('flink')) return serviceColors.flink;
  if (name.includes('drools')) return serviceColors.drools;
  return '#1890ff'; // default to primary blue
};

// Get Ant Design tag color name by service
export const getServiceTagColor = (serviceName: string): string => {
  const name = serviceName.toLowerCase();
  if (name.includes('gateway') || name.includes('counter-application')) return 'green';
  if (name.includes('kafka')) return 'orange';
  if (name.includes('flink')) return 'purple';
  if (name.includes('drools')) return 'red';
  return 'blue';
};

// Shared token configuration
const sharedTokens = {
  colorPrimary: '#1890ff',
  colorSuccess: '#52c41a',
  colorWarning: '#faad14',
  colorError: '#ff4d4f',
  colorInfo: '#1890ff',
  borderRadius: 6,
  fontFamily: `-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'Noto Sans', sans-serif, 'Apple Color Emoji', 'Segoe UI Emoji', 'Segoe UI Symbol', 'Noto Color Emoji'`,
};

// Light theme configuration
export const lightTheme: ThemeConfig = {
  token: {
    ...sharedTokens,
    colorBgContainer: '#ffffff',
    colorBgLayout: '#f5f5f5',
  },
};

// Dark theme configuration
export const darkTheme: ThemeConfig = {
  token: {
    ...sharedTokens,
    colorBgContainer: '#141414',
    colorBgLayout: '#000000',
  },
};

// Layout dimensions
export const layout = {
  siderWidth: 240,
  headerHeight: 64,
} as const;
