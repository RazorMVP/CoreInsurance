import { test, expect, type Response } from '@playwright/test';

const E2E_API = process.env.E2E_API_BASE_URL ?? 'http://localhost:8090';

/**
 * Full-stack golden path per module. Each test drives the real SPA against the
 * live cia-api (dev,e2e → Postgres) and asserts the module's screen loads with
 * EVERY backend call succeeding — which proves the whole chain end-to-end: the
 * route resolves, the lazy chunk loads, React Query fires the real `/api/v1`
 * request, the e2e mock-auth satisfies `@PreAuthorize` (no 403), the backend
 * serves it from Postgres (no 5xx), and the page renders the result.
 *
 * Selector-free by design: a heading/text assertion would be brittle (no shared
 * PageHeader element); "no failed API call on this module's screen" is both more
 * robust and a stronger integration signal.
 */

interface ModuleNav {
  path: string;
  label: string;
  /** true when the landing screen is expected to fire at least one GET /api/v1. */
  expectsListCall: boolean;
}

const MODULES: ModuleNav[] = [
  { path: '/dashboard', label: 'Dashboard', expectsListCall: false },
  { path: '/customers', label: 'Customers', expectsListCall: true },
  { path: '/quotation', label: 'Quotation', expectsListCall: true },
  { path: '/policies', label: 'Policies', expectsListCall: true },
  { path: '/claims', label: 'Claims', expectsListCall: true },
  { path: '/finance', label: 'Finance', expectsListCall: true },
  { path: '/setup', label: 'Setup', expectsListCall: false },
];

for (const mod of MODULES) {
  test(`${mod.label} module loads with all backend calls healthy`, async ({ page }) => {
    const apiCalls: Response[] = [];
    page.on('response', (res) => {
      if (res.url().includes('/api/v1/')) apiCalls.push(res);
    });

    await page.goto(mod.path);
    await page.waitForLoadState('networkidle');

    // The app rendered the shell + module content (not a blank/crashed page).
    await expect(page.locator('main').first()).toBeVisible();

    // No backend call on this screen failed — auth (no 403), routing, and
    // persistence (no 5xx) all worked through the real stack.
    const failed = apiCalls
      .filter((r) => r.status() >= 400)
      .map((r) => `${r.request().method()} ${new URL(r.url()).pathname} → ${r.status()}`);
    expect(failed, `failed API calls on ${mod.path}`).toEqual([]);

    if (mod.expectsListCall) {
      const okGets = apiCalls.filter((r) => r.request().method() === 'GET' && r.status() === 200);
      expect(okGets.length, `expected ≥1 successful GET /api/v1 on ${mod.path}`).toBeGreaterThan(0);
    }
  });
}

test('seeded master data is served to the SPA (Setup → Products)', async ({ page }) => {
  // The product seeded in globalSetup must be readable through the live API.
  const res = await page.request.get(`${E2E_API}/api/v1/setup/products`);
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  const codes = (body.data as Array<{ code: string }>).map((p) => p.code);
  expect(codes).toContain('MOTC');
});
