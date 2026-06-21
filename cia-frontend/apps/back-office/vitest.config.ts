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
    coverage: {
      provider:  'v8',
      // `all: true` counts every src file, not just imported ones — so the
      // percentages reflect the whole app honestly (the baseline is low
      // because most modules are not yet tested).
      all:       true,
      include:   ['src/**/*.{ts,tsx}'],
      exclude:   ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/**/*.d.ts', 'src/main.tsx', 'src/vite-env.d.ts'],
      reporter:  ['text-summary', 'json-summary', 'html'],
      // STARTING floors set just below the measured baseline (a "don't
      // regress" ratchet, not a target). Back-office is a large, mostly
      // untested app — raise these as tests land. Measured 2026-06-21:
      // lines 1.41% · branches 27.03% · functions 10.55%.
      thresholds: {
        lines:      1,
        statements: 1,
        functions:  8,
        branches:   25,
      },
    },
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
});
