import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals:     false,
    setupFiles:  ['./src/test/setup.ts'],
    css:         false,
    include:     ['src/**/*.test.{ts,tsx}'],
    server: {
      deps: {
        inline: ['@cia/api-client', '@cia/ui', '@cia/auth'],
      },
    },
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
});
