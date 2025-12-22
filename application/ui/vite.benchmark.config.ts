import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'path';

// Get which bundle to build from environment variable
const bundle = process.env.BENCHMARK_BUNDLE || 'report';

const entries: Record<string, { entry: string; name: string }> = {
  report: {
    entry: resolve(__dirname, 'src/pages/benchmark/report-entry.tsx'),
    name: 'BenchmarkReport',
  },
  index: {
    entry: resolve(__dirname, 'src/pages/benchmark/index-entry.tsx'),
    name: 'BenchmarkIndex',
  },
};

const config = entries[bundle];

/**
 * Vite configuration for building benchmark report bundles.
 * Run with BENCHMARK_BUNDLE=report or BENCHMARK_BUNDLE=index
 */
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../reports/assets',
    emptyDir: false,
    lib: {
      entry: config.entry,
      formats: ['iife'],
      name: config.name,
      fileName: () => `benchmark-${bundle}.js`,
    },
    rollupOptions: {
      output: {
        assetFileNames: `benchmark-${bundle}.[ext]`,
      },
    },
    minify: true,
    sourcemap: false,
  },
  define: {
    'process.env.NODE_ENV': '"production"',
  },
});
