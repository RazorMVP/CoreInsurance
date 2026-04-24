import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    // Raise the inline warning threshold slightly — Tailwind tokens are fine inlined
    chunkSizeWarningLimit: 600,
    rollupOptions: {
      output: {
        manualChunks: (id) => {
          // React core — changes almost never; cache independently from app code
          if (id.includes('node_modules/react/') || id.includes('node_modules/react-dom/')) {
            return 'vendor-react';
          }
          // React Router — changes infrequently
          if (id.includes('node_modules/react-router')) {
            return 'vendor-router';
          }
          // TanStack Query + Table — separate from React so a query upgrade
          // doesn't bust the React cache
          if (
            id.includes('node_modules/@tanstack/react-query') ||
            id.includes('node_modules/@tanstack/react-table')
          ) {
            return 'vendor-tanstack';
          }
          // All Radix UI primitives in a single chunk — they change together
          if (id.includes('node_modules/@radix-ui/')) {
            return 'vendor-radix';
          }
          // Hugeicons can be large — isolate so an icon update doesn't bust
          // unrelated vendor caches
          if (
            id.includes('node_modules/@hugeicons/') ||
            id.includes('node_modules/lucide-react')
          ) {
            return 'vendor-icons';
          }
          // Form utilities
          if (
            id.includes('node_modules/react-hook-form') ||
            id.includes('node_modules/@hookform/') ||
            id.includes('node_modules/zod/')
          ) {
            return 'vendor-forms';
          }
          // Remaining node_modules go into a general vendor chunk
          if (id.includes('node_modules/')) {
            return 'vendor-misc';
          }
        },
      },
    },
  },
});
