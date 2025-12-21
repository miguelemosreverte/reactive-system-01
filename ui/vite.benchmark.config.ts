import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'path';

/**
 * Vite configuration for building benchmark report bundles.
 * This creates two separate bundles:
 * 1. benchmark-report.js - For individual report pages
 * 2. benchmark-index.js - For the main dashboard
 */
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../reports/assets',
    emptyDir: false,
    lib: {
      entry: {
        'benchmark-report': resolve(__dirname, 'src/pages/benchmark/report-entry.tsx'),
        'benchmark-index': resolve(__dirname, 'src/pages/benchmark/index-entry.tsx'),
      },
      formats: ['iife'],
      name: 'BenchmarkReport',
    },
    rollupOptions: {
      output: {
        entryFileNames: '[name].js',
        assetFileNames: '[name].[ext]',
        // Keep all code in a single bundle (no external dependencies)
        inlineDynamicImports: false,
      },
    },
    // Don't minify for easier debugging in development
    minify: true,
    sourcemap: false,
  },
  define: {
    'process.env.NODE_ENV': '"production"',
  },
});
