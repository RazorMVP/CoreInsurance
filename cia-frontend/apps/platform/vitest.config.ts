import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: false,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    include: ['src/**/*.test.{ts,tsx}'],
    server: { deps: { inline: ['@cia/api-client', '@cia/ui', '@cia/auth'] } },
    coverage: {
      provider: 'v8',
      all:      true,
      include:  ['src/**/*.{ts,tsx}'],
      exclude:  ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/**/*.d.ts', 'src/main.tsx', 'src/vite-env.d.ts'],
      reporter: ['text-summary', 'json-summary', 'html'],
      // STARTING floors set just below the measured baseline (don't-regress
      // ratchet). Platform is small + reasonably covered. Measured
      // 2026-06-21: lines 37.89% · branches 55% · functions 41.86%.
      thresholds: {
        lines:      35,
        statements: 35,
        functions:  40,
        branches:   50,
      },
    },
  },
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
});
