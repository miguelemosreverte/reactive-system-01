import type { ThemeConfig } from 'antd';

// Service colors for the reactive system architecture
export const serviceColors = {
  gateway: '#52c41a',
  kafka: '#faad14',
  flink: '#722ed1',
  drools: '#ff4d4f',
} as const;

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
