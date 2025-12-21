/**
 * Entry point for the benchmark index/dashboard page.
 * This file renders the main dashboard that shows all benchmark results.
 */
import React, { useState } from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider, theme } from 'antd';
import BenchmarkIndexView from './BenchmarkIndex';
import type { BenchmarkIndex } from './types';

// Data is embedded in the HTML by the Go tool
declare global {
  interface Window {
    __BENCHMARK_INDEX__: BenchmarkIndex;
  }
}

const App: React.FC = () => {
  const data = window.__BENCHMARK_INDEX__;
  const [selectedComponent, setSelectedComponent] = useState(
    data?.components?.[0]?.id || 'full'
  );

  if (!data) {
    return (
      <ConfigProvider theme={{ algorithm: theme.defaultAlgorithm }}>
        <div style={{ padding: 48, textAlign: 'center' }}>
          <h2>No benchmark index data found</h2>
          <p>The index data should be embedded in window.__BENCHMARK_INDEX__</p>
        </div>
      </ConfigProvider>
    );
  }

  return (
    <ConfigProvider
      theme={{
        algorithm: theme.defaultAlgorithm,
        token: {
          colorPrimary: '#1890ff',
          borderRadius: 6,
        },
      }}
    >
      <BenchmarkIndexView
        data={data}
        selectedComponent={selectedComponent}
        onSelectComponent={setSelectedComponent}
      />
    </ConfigProvider>
  );
};

const root = document.getElementById('root');
if (root) {
  ReactDOM.createRoot(root).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>
  );
}
