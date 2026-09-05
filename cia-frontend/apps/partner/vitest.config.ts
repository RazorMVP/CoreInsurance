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
    server: { deps: { inline: ['@cia/api-client', '@cia/ui'] } },
    coverage: {
      provider: 'v8',
      all: true,
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/**/*.d.ts', 'src/main.tsx', 'src/vite-env.d.ts'],
      reporter: ['text-summary', 'json-summary', 'html'],
      // Measured (2026-09-05, final-review fix wave M6): lines/statements 69.01%,
      // functions 69.38%, branches 77.63%. Set to the achieved values rounded DOWN to the
      // nearest 5 — meaningful floor without being flaky. Raise as more tests land.
      thresholds: { lines: 65, statements: 65, functions: 65, branches: 75 },
    },
  },
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
});
