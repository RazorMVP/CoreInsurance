import type { FullConfig } from '@playwright/test';

/**
 * Seeds the master data the golden paths depend on, via the REAL cia-api (dev,e2e
 * profile → public schema). Idempotent: each item is created only if absent, so
 * re-runs against a non-fresh DB are safe. Uses the e2e mock-auth (no Keycloak),
 * so no Authorization header is needed.
 */
const API = process.env.E2E_API_BASE_URL ?? 'http://localhost:8090';

async function waitForBackend(): Promise<void> {
  for (let i = 0; i < 60; i++) {
    try {
      const res = await fetch(`${API}/actuator/health`);
      if (res.ok) return;
    } catch {
      /* not up yet */
    }
    await new Promise((r) => setTimeout(r, 2000));
  }
  throw new Error(`Backend not healthy at ${API} after 120s`);
}

async function getJson(path: string): Promise<any> {
  const res = await fetch(`${API}${path}`);
  if (!res.ok) throw new Error(`GET ${path} → ${res.status}`);
  return res.json();
}

async function post(path: string, body: unknown): Promise<any> {
  const res = await fetch(`${API}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`POST ${path} → ${res.status}: ${await res.text()}`);
  return res.json();
}

async function put(path: string, body: unknown): Promise<void> {
  const res = await fetch(`${API}${path}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`PUT ${path} → ${res.status}: ${await res.text()}`);
}

export default async function globalSetup(_config: FullConfig): Promise<void> {
  await waitForBackend();

  // 1. Class of business (idempotent by code).
  const classes = (await getJson('/api/v1/setup/classes-of-business')).data as any[];
  let motor = classes.find((c) => c.code === 'MOT');
  if (!motor) {
    motor = (await post('/api/v1/setup/classes-of-business', {
      name: 'Motor', code: 'MOT', description: 'Motor insurance (E2E seed)',
    })).data;
  }

  // 2. Product under that class (idempotent by code).
  const products = (await getJson('/api/v1/setup/products')).data as any[];
  if (!products.find((p) => p.code === 'MOTC')) {
    await post('/api/v1/setup/products', {
      name: 'Motor Comprehensive', code: 'MOTC', classOfBusinessId: motor.id,
      type: 'SINGLE_RISK', rate: 2.5, minPremium: 5000, active: true,
    });
  }

  // 3. Customer number format singleton (PUT is idempotent) — required to onboard customers.
  await put('/api/v1/setup/customer-number-format', {
    prefix: 'CUST', includeYear: true, includeType: true, sequenceLength: 6,
  });

  // eslint-disable-next-line no-console
  console.log('[e2e] master data seeded (class=MOT, product=MOTC, customer-number-format)');
}
