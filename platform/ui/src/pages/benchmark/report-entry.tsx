/**
 * Entry point for static benchmark report pages.
 * This file is loaded by the static HTML and renders the benchmark report
 * using data from window.__BENCHMARK_DATA__.
 */
import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider, theme } from 'antd';
import BenchmarkReport from './BenchmarkReport';
import type { BenchmarkResult } from './types';

// Data is embedded in the HTML by the Go tool
declare global {
  interface Window {
    __BENCHMARK_DATA__: BenchmarkResult;
    __BENCHMARK_COMMIT__?: string;
  }
}

const App: React.FC = () => {
  const data = window.__BENCHMARK_DATA__;
  const commit = window.__BENCHMARK_COMMIT__;

  if (!data) {
    return (
      <ConfigProvider theme={{ algorithm: theme.defaultAlgorithm }}>
        <div style={{ padding: 48, textAlign: 'center' }}>
          <h2>No benchmark data found</h2>
          <p>The benchmark data should be embedded in window.__BENCHMARK_DATA__</p>
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
      <BenchmarkReport result={data} commit={commit} />
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
