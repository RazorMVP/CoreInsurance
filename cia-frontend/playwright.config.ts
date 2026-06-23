import { defineConfig, devices } from '@playwright/test';

/**
 * Full-stack E2E for the NubSure back-office SPA.
 *
 * Runs the production build (DevAuthProvider via VITE_DEMO_MODE) against a REAL
 * cia-api backend started with the `dev,e2e` profiles — so the golden paths
 * exercise the live frontend → API → Postgres path. The backend + Postgres are
 * started by the caller (CI workflow / `scripts/e2e-local.sh`); Playwright owns
 * only the frontend (build + preview) and seeds master data in globalSetup.
 */
const API_BASE = process.env.E2E_API_BASE_URL ?? 'http://localhost:8090';
// Serve on 5173 — it's in the backend's default CORS allowlist
// (cia.cors.allowed-origins), so the browser's cross-origin calls to :8090 pass.
const APP_BASE = process.env.E2E_BASE_URL ?? 'http://localhost:5173';

export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  timeout: 45_000,
  expect: { timeout: 12_000 },
  // Golden paths share seeded master data + write to one DB — keep them serial.
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: APP_BASE,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    // Preview only — the bundle is built up front by the `e2e` script (the build
    // is kept out of the webServer because running it alongside the backend +
    // Postgres made readiness racy, and the build env is applied there). Served
    // on 5173 so it matches the backend's default CORS allowlist.
    command: 'pnpm --filter @cia/back-office exec vite preview --port 5173 --strictPort',
    url: APP_BASE,
    timeout: 60_000,
    reuseExistingServer: !process.env.CI,
  },
});
