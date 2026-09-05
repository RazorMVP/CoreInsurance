# Partner Portal SPA (Sub-project B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `apps/partner` dark-mode SPA so an Insurtech developer can log in (BFF cookie-session), pick a Partner App, view credentials + rotate the secret, explore the API via the try-it proxy, manage webhooks, and see real usage — the browser holding no token or secret except a one-time rotate reveal.

**Architecture:** A React 18 + Vite + TS SPA consuming the shipped `/portal/**` BFF (Sub-project A, on `main`). Auth is an app-local cookie-session `PortalAuthProvider` (NOT Keycloak-JS). A new `@cia/api-client/modules/portal.ts` owns a credentialed axios instance (`withCredentials`, `X-CSRF-Token`) that parses through the package's existing `apiEnvelope` zod envelope, plus a `portal-mocks.ts` adapter so the app is fully usable in demo mode. Ships demo-first (mock data on the public URL) until the `partner` realm is provisioned.

**Tech Stack:** React 18, Vite 5, TypeScript 5.6, Tailwind + `@cia/ui` dark tokens, `@tanstack/react-query` 5, `react-router-dom` 6, zod (via `@cia/api-client`), Recharts 3, Vitest 2 + `@testing-library/react`.

**Spec:** `docs/superpowers/specs/2026-09-03-partner-portal-spa-design.md` (Sub-project B). Consumes the contract in `docs/superpowers/specs/2026-08-20-partner-portal-bff-auth-design.md` (Sub-project A).

## Global Constraints

Every task's requirements implicitly include this section. Values are copied verbatim from the specs and the verified BFF contract.

- **No BFF / `/portal/**` contract change.** Consume it as-is. If a contract gap is found, log it to the `cia-log.md` backlog — do not expand Sub-project A.
- **Browser holds no token or secret**, ever, except the one-time secret in the `POST /portal/apps/{id}/credentials/rotate` response (`clientSecret`), which is shown once and never persisted.
- **Cookie-session only.** The BFF sets `cia_portal_session` (HttpOnly). The SPA never reads it. Every portal fetch uses `withCredentials: true`.
- **CSRF:** attach header `X-CSRF-Token` = the session `csrfToken` (from `/portal/auth/me`) on **every** mutating call: logout, webhook create/delete, credentials rotate, and mutating (POST/PUT/PATCH/DELETE) try-it calls.
- **Demo mode is flag-driven at build time:** `const demoMode = import.meta.env.DEV || import.meta.env.VITE_DEMO_MODE === 'true';` — computed **once** in `main.tsx` and passed into the api-client via `configurePortalClient`. No runtime "is a session reachable" probe.
- **Real mode reads the API base from `VITE_API_BASE_URL`** (same var name as back-office) and sends `withCredentials`. Demo mode never crosses origins (all mock).
- **Validation:** every `/portal/**` DTO has a zod schema; the **same schema validates real responses and mock data**. Real parsing goes through the package's exported `apiEnvelope(schema)`.
- **Field-name fidelity (verified against the BFF):** webhooks use `targetUrl` + `active` (boolean), NOT `url`/`status`; webhook create body is `{ targetUrl, secret, eventTypes }` with `secret` min length 16; no secret is returned after creation. Usage counters are `clientError`/`serverError`. Grant/app ids are `partnerAppId`. Rotate returns `clientSecret` once. `/portal/auth/me` = `{ partnerUserId, email, csrfToken, apps[] }` where `apps[]` is grant-shape (`{ id, partnerAppId, partnerUserId, email, role, createdAt }`) — **no `displayName`, no `clientId`/`scopes`**; the rich per-app data comes from `GET /portal/apps` (`PortalAppSummary`).
- **401 from `/portal/auth/me`** (body `{ errors:[{ code:"PORTAL_SESSION_REQUIRED" }] }`, no `data`) is the "not logged in" signal → show the login screen.
- **Dark theme:** `apps/partner/index.html` already sets `<html class="dark">`; `globals.css` already imports `@cia/ui/tokens.css`. Use `@cia/ui` tokens (`bg-background`, `text-foreground`, `text-muted-foreground`, `bg-card`, `text-primary`, `border-border`, `font-display`) — never hardcoded colors.
- **CI green gate:** `tsc --noEmit`, `node cia-frontend/scripts/check-dto-drift.mjs` (portal DTOs added to `dto-drift.config.json`), `pnpm --filter @cia/partner build`, and `pnpm --filter @cia/partner test`. (`check-api-wiring.sh` scans only `apps/back-office/src/modules`, so it does not touch partner — but keep partner free of `console.log`.)
- **P4 Sandbox is out of scope** — tracked as backlog `partner-portal-sandbox-epic`. No live `partner` realm provisioning.
- **Attribution:** commits end `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`; the PR body ends `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.

---

## File Structure

**`cia-frontend/packages/api-client/src/` (shared package):**
- Create `modules/portal.ts` — zod enums + DTO schemas; the credentialed `portalClient` (withCredentials + CSRF interceptor); `configurePortalClient`, `setPortalCsrfToken`, `isPortalDemoMode`; `portalGet/portalPost/portalDelete/portalTry` helpers; the react-query hooks (`useSession`, `useApps`, `useCredentials`, `useRotateSecret`, `useWebhooks`, `useCreateWebhook`, `useDeleteWebhook`, `useUsage`, `useTryIt`, `useLogout`).
- Create `modules/portal-mocks.ts` — canned, zod-valid mock data + `mockPortalApi` adapter.
- Modify `modules/index.ts` — barrel-export `./portal`.
- (No change to the Bearer `client.ts` / `validation.ts` — portal reuses only the exported `apiEnvelope`.)

**`cia-frontend/scripts/`:**
- Modify `dto-drift.config.json` — `manualMap` entries for portal DTOs.

**`cia-frontend/apps/partner/` (the SPA):**
- Modify `package.json` — add runtime deps (recharts, hugeicons) + dev/test deps (vitest, testing-library, jsdom, coverage-v8) + `test`/`test:watch` scripts.
- Create `vitest.config.ts`, `src/test/setup.ts`.
- Modify `vite.config.ts` — add a `/portal` dev proxy alongside the existing `/partner` one.
- Rewrite `src/main.tsx` — compute `demoMode`, `configurePortalClient`, wrap in `PortalAuthProvider` + `QueryClientProvider`.
- Rewrite `src/App.tsx` — `RouterProvider`.
- Create `src/app/auth/PortalAuthProvider.tsx`, `src/app/auth/LoginScreen.tsx`.
- Create `src/app/router.tsx`, `src/app/layout/AppShell.tsx`, `Sidebar.tsx`, `Topbar.tsx`, `AppContext.tsx` (selected-app context).
- Create `src/lib/format.ts` (null-tolerant formatters), `src/lib/copy.ts` (clipboard).
- Create the four build pages under `src/modules/{credentials,explorer,webhooks,usage}/`.
- Create `vercel.json`, `DEPLOY.md`.

**`.github/workflows/`:**
- Create `vercel-deploy-partner.yml` (mirrors `vercel-deploy-platform.yml`).

**Docs:** `cia-log.md` (session entry + backlog reconciliation), `CLAUDE.md` (Frontend Build Queue Phase 3 status + §10 Vercel), `.claude/skills/cia/SKILL.md` (Phase 3 progress).

---

## Task 1: Partner app dependencies, Vitest harness, dev/build config

**Files:**
- Modify: `cia-frontend/apps/partner/package.json`
- Create: `cia-frontend/apps/partner/vitest.config.ts`
- Create: `cia-frontend/apps/partner/src/test/setup.ts`
- Create: `cia-frontend/apps/partner/src/lib/format.smoke.test.ts` (temporary smoke test, replaced by real tests later — keep or delete at Task 9)
- Create: `cia-frontend/apps/partner/src/lib/format.ts`
- Modify: `cia-frontend/apps/partner/vite.config.ts`

**Interfaces:**
- Produces: `formatNaira`, `formatInt`, `formatPercent`, `formatDate`, `formatTimestamp` (all null-tolerant, render `—` for null/undefined) in `src/lib/format.ts` — consumed by Tasks 8 & 9.
- Produces: a working `pnpm --filter @cia/partner test` — consumed by every later task's tests.

- [ ] **Step 1: Add deps + test scripts to `package.json`**

Merge into `cia-frontend/apps/partner/package.json`. Add to `dependencies`:
```json
"@hugeicons/react": "^1.0.5",
"@hugeicons/core-free-icons": "^1.0.13",
"recharts": "^3.8.1"
```
Add to `devDependencies`:
```json
"@testing-library/jest-dom": "^6.6.3",
"@testing-library/react": "^16.0.1",
"@testing-library/user-event": "^14.5.2",
"@vitest/coverage-v8": "^2.1.9",
"jsdom": "^25.0.1",
"vitest": "^2.1.9"
```
Add to `scripts`:
```json
"test":       "vitest run --coverage",
"test:watch": "vitest"
```
(Match the hugeicons versions already resolved for back-office if they differ — run `pnpm --filter @cia/back-office ls @hugeicons/react @hugeicons/core-free-icons` and pin the same. Vitest is pinned `^2.1.9` because the repo is on Vite 5 — Vitest 4 requires Vite 6+, per CLAUDE.md.)

- [ ] **Step 2: Create `vitest.config.ts`** (mirror back-office; partner does not use `@cia/auth`, so inline only the two packages it imports)

```ts
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
      thresholds: { lines: 1, statements: 1, functions: 1, branches: 1 },
    },
  },
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
});
```

- [ ] **Step 3: Create `src/test/setup.ts`**

```ts
import '@testing-library/jest-dom/vitest';
```

- [ ] **Step 4: Create `src/lib/format.ts`** (null-tolerant, mirrors back-office `lib/format.ts` convention)

```ts
const DASH = '—';

export function formatNaira(v: number | null | undefined): string {
  if (v === null || v === undefined || Number.isNaN(v)) return DASH;
  return `₦${v.toLocaleString('en-NG', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

export function formatInt(v: number | null | undefined): string {
  if (v === null || v === undefined || Number.isNaN(v)) return DASH;
  return v.toLocaleString('en-NG');
}

export function formatPercent(v: number | null | undefined): string {
  if (v === null || v === undefined || Number.isNaN(v)) return DASH;
  return `${(v * 100).toFixed(1)}%`;
}

export function formatDate(v: string | null | undefined): string {
  if (!v) return DASH;
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? DASH : d.toLocaleDateString('en-NG', { year: 'numeric', month: 'short', day: '2-digit' });
}

export function formatTimestamp(v: string | null | undefined): string {
  if (!v) return DASH;
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? DASH : d.toLocaleString('en-NG', { dateStyle: 'medium', timeStyle: 'short' });
}
```

- [ ] **Step 5: Write the failing smoke test** `src/lib/format.smoke.test.ts`

```ts
import { describe, it, expect } from 'vitest';
import { formatPercent, formatInt, formatDate } from './format';

describe('format helpers', () => {
  it('renders em-dash for null', () => {
    expect(formatPercent(null)).toBe('—');
    expect(formatInt(undefined)).toBe('—');
    expect(formatDate('')).toBe('—');
  });
  it('formats a percent and an int', () => {
    expect(formatPercent(0.1234)).toBe('12.3%');
    expect(formatInt(1500)).toBe('1,500');
  });
});
```

- [ ] **Step 6: Add the `/portal` dev proxy** to `vite.config.ts` (alongside the existing `/partner` proxy — real-mode local dev convenience; demo mode never uses it)

In the `server.proxy` object add:
```ts
      '/portal': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
```

- [ ] **Step 7: Install + run**

Run: `pnpm install` (from `cia-frontend/`), then `pnpm --filter @cia/partner test`
Expected: the smoke test passes; coverage summary prints. Then `pnpm --filter @cia/partner build` → succeeds (still the stub App).

- [ ] **Step 8: Commit**

```bash
git add cia-frontend/apps/partner/package.json cia-frontend/apps/partner/vitest.config.ts cia-frontend/apps/partner/src/test cia-frontend/apps/partner/src/lib cia-frontend/apps/partner/vite.config.ts cia-frontend/pnpm-lock.yaml
git commit -m "chore(partner): vitest harness, format helpers, deps, /portal dev proxy"
```

---

## Task 2: `@cia/api-client` portal module — zod schemas + credentialed client + mock adapter

**Files:**
- Create: `cia-frontend/packages/api-client/src/modules/portal.ts`
- Create: `cia-frontend/packages/api-client/src/modules/portal-mocks.ts`
- Modify: `cia-frontend/packages/api-client/src/modules/index.ts`
- Modify: `cia-frontend/scripts/dto-drift.config.json`
- Test: `cia-frontend/packages/api-client/src/modules/portal.test.ts`

**Interfaces:**
- Consumes: `apiEnvelope` (exported from `../validation`), `zod`.
- Produces (schemas + inferred types): `GrantRoleSchema`, `PortalGrantDtoSchema`/`PortalGrantDto`, `PortalMeDtoSchema`/`PortalMeDto`, `PortalLogoutDtoSchema`/`PortalLogoutDto`, `PortalAppSummaryDtoSchema`/`PortalAppSummaryDto`, `PortalCredentialsDtoSchema`/`PortalCredentialsDto`, `PortalRotateSecretDtoSchema`/`PortalRotateSecretDto`, `PortalWebhookDtoSchema`/`PortalWebhookDto`, `RegisterWebhookInput`, `UsageDayDtoSchema`/`UsageDayDto`, `UsageHistoryEntryDtoSchema`/`UsageHistoryEntryDto`, `WebhookDeliverySummaryDtoSchema`/`WebhookDeliverySummaryDto`, `PortalUsageDtoSchema`/`PortalUsageDto`, `TryItResult`.
- Produces (client fns): `configurePortalClient({ baseURL, demoMode })`, `setPortalCsrfToken(t)`, `isPortalDemoMode()`, and internal `portalGet/portalPost/portalDelete/portalTry`.
- Produces (mock): `mockPortalApi` with one method per hook.

- [ ] **Step 1: Write the failing test** `portal.test.ts` (proves every mock parses against its schema, and the client config toggles demo mode)

```ts
import { describe, it, expect } from 'vitest';
import {
  PortalMeDtoSchema, PortalAppSummaryDtoSchema, PortalCredentialsDtoSchema,
  PortalRotateSecretDtoSchema, PortalWebhookDtoSchema, PortalUsageDtoSchema,
  isPortalDemoMode, configurePortalClient,
} from './portal';
import { mockPortalApi } from './portal-mocks';

describe('portal mock adapter is contract-valid', () => {
  it('every mock response parses against its schema', async () => {
    expect(() => PortalMeDtoSchema.parse(mockPortalApi.__me())).not.toThrow();
    (await mockPortalApi.getApps()).forEach((a) => expect(() => PortalAppSummaryDtoSchema.parse(a)).not.toThrow());
    expect(() => PortalCredentialsDtoSchema.parse(mockPortalApi.__creds())).not.toThrow();
    expect(() => PortalRotateSecretDtoSchema.parse(mockPortalApi.__rotate())).not.toThrow();
    (await mockPortalApi.getWebhooks('app-1')).forEach((w) => expect(() => PortalWebhookDtoSchema.parse(w)).not.toThrow());
    expect(() => PortalUsageDtoSchema.parse(await mockPortalApi.getUsage('app-1'))).not.toThrow();
  });
});

describe('configurePortalClient', () => {
  it('sets demo mode', () => {
    configurePortalClient({ baseURL: '', demoMode: true });
    expect(isPortalDemoMode()).toBe(true);
    configurePortalClient({ baseURL: 'http://x', demoMode: false });
    expect(isPortalDemoMode()).toBe(false);
  });
});
```

Run: `pnpm --filter @cia/api-client test src/modules/portal.test.ts` (add a `test` script mirroring back-office if the package lacks one; else run via the app). Expected: FAIL (module not found).

- [ ] **Step 2: Write `portal.ts` — header + enums + schemas** (follow the `finance-closures.ts` convention: enums first, DTOs after)

```ts
/**
 * Partner Portal BFF (`/portal/**`) client + schemas.
 *
 * The BFF is cookie-session (HttpOnly `cia_portal_session`) with a double-submit
 * CSRF token. The shared `apiClient` is a Bearer-token axios singleton with no
 * `withCredentials`, so the portal owns its OWN credentialed axios instance here.
 * Real responses are still validated through the package's `apiEnvelope` zod
 * envelope, so this module honors the "zod-validated" contract while keeping
 * cookie/CSRF concerns off the Bearer client.
 *
 * Wire shapes mirror the BFF Java records exactly (see the Sub-project A spec).
 * File ordering: enums up top (dependency-free), DTOs after.
 */
import axios, { AxiosInstance } from 'axios';
import { z } from 'zod';
import { apiEnvelope } from '../validation';

// ── Enums ──
export const GrantRoleSchema = z.enum(['MANAGER', 'VIEWER']);
export type GrantRole = z.infer<typeof GrantRoleSchema>;

// ── DTOs ──
export const PortalGrantDtoSchema = z.object({
  id: z.string(),
  partnerAppId: z.string(),
  partnerUserId: z.string(),
  email: z.string(),
  role: GrantRoleSchema,
  createdAt: z.string(),
});
export type PortalGrantDto = z.infer<typeof PortalGrantDtoSchema>;

export const PortalMeDtoSchema = z.object({
  partnerUserId: z.string(),
  email: z.string(),
  csrfToken: z.string(),
  apps: z.array(PortalGrantDtoSchema),
});
export type PortalMeDto = z.infer<typeof PortalMeDtoSchema>;

export const PortalLogoutDtoSchema = z.object({ logoutUrl: z.string() });
export type PortalLogoutDto = z.infer<typeof PortalLogoutDtoSchema>;

export const PortalAppSummaryDtoSchema = z.object({
  partnerAppId: z.string(),
  clientId: z.string(),
  tenantSchema: z.string(),
  tenantLabel: z.string(),
  scopes: z.array(z.string()),
  rateTier: z.string(),
  status: z.string(),
  role: GrantRoleSchema,
});
export type PortalAppSummaryDto = z.infer<typeof PortalAppSummaryDtoSchema>;

export const PortalCredentialsDtoSchema = z.object({
  clientId: z.string(),
  scopes: z.array(z.string()),
});
export type PortalCredentialsDto = z.infer<typeof PortalCredentialsDtoSchema>;

export const PortalRotateSecretDtoSchema = z.object({
  clientId: z.string(),
  clientSecret: z.string(),
});
export type PortalRotateSecretDto = z.infer<typeof PortalRotateSecretDtoSchema>;

export const PortalWebhookDtoSchema = z.object({
  id: z.string(),
  targetUrl: z.string(),
  eventTypes: z.array(z.string()),
  active: z.boolean(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type PortalWebhookDto = z.infer<typeof PortalWebhookDtoSchema>;

export interface RegisterWebhookInput {
  targetUrl: string;
  secret: string;      // min length 16 — enforced upstream
  eventTypes: string[];
}

export const UsageDayDtoSchema = z.object({
  total: z.number(),
  success: z.number(),
  clientError: z.number(),
  serverError: z.number(),
});
export type UsageDayDto = z.infer<typeof UsageDayDtoSchema>;

export const UsageHistoryEntryDtoSchema = z.object({
  date: z.string(),
  total: z.number(),
  success: z.number(),
  clientError: z.number(),
  serverError: z.number(),
});
export type UsageHistoryEntryDto = z.infer<typeof UsageHistoryEntryDtoSchema>;

export const WebhookDeliverySummaryDtoSchema = z.object({
  registrations: z.number(),
  activeRegistrations: z.number(),
  totalDeliveries: z.number(),
  successfulDeliveries: z.number(),
  failedDeliveries: z.number(),
  lastDeliveryAt: z.string().nullable(),
});
export type WebhookDeliverySummaryDto = z.infer<typeof WebhookDeliverySummaryDtoSchema>;

export const PortalUsageDtoSchema = z.object({
  today: UsageDayDtoSchema,
  history: z.array(UsageHistoryEntryDtoSchema),
  webhookDeliveries: WebhookDeliverySummaryDtoSchema,
  errorRate: z.number(),
});
export type PortalUsageDto = z.infer<typeof PortalUsageDtoSchema>;

export interface TryItResult {
  status: number;
  body: unknown;
}
```

- [ ] **Step 3: Write `portal.ts` — the credentialed client + helpers** (append)

```ts
let portalClient: AxiosInstance = axios.create({ withCredentials: true });
let demoMode = false;
let csrfToken: string | null = null;

export function configurePortalClient(opts: { baseURL: string; demoMode: boolean }) {
  demoMode = opts.demoMode;
  portalClient = axios.create({ baseURL: opts.baseURL, withCredentials: true });
  portalClient.interceptors.request.use((config) => {
    const method = (config.method ?? 'get').toUpperCase();
    if (method !== 'GET' && method !== 'HEAD' && csrfToken) {
      config.headers = config.headers ?? {};
      (config.headers as Record<string, string>)['X-CSRF-Token'] = csrfToken;
    }
    return config;
  });
  portalClient.interceptors.response.use(
    (res) => res,
    (error) => {
      if (error.response?.status === 401) window.dispatchEvent(new CustomEvent('portal:unauthorized'));
      return Promise.reject(error);
    },
  );
}

export function setPortalCsrfToken(token: string | null) { csrfToken = token; }
export function isPortalDemoMode() { return demoMode; }

async function portalGet<T extends z.ZodTypeAny>(url: string, schema: T): Promise<z.infer<T>> {
  const res = await portalClient.get(url);
  return apiEnvelope(schema).parse(res.data).data;
}
async function portalList<T extends z.ZodTypeAny>(url: string, item: T): Promise<z.infer<T>[]> {
  const res = await portalClient.get(url);
  return apiEnvelope(z.array(item)).parse(res.data).data;
}
async function portalPost<T extends z.ZodTypeAny>(url: string, body: unknown, schema: T): Promise<z.infer<T>> {
  const res = await portalClient.post(url, body);
  return apiEnvelope(schema).parse(res.data).data;
}
async function portalDelete(url: string): Promise<void> {
  await portalClient.delete(url);
}
// Try-it: relay status + body VERBATIM (never through apiEnvelope — 403/429 bodies must survive).
async function portalTry(appId: string, method: string, path: string, body?: unknown): Promise<TryItResult> {
  const clean = path.replace(/^\/+/, '');
  const res = await portalClient.request({
    url: `/portal/apps/${appId}/try/${clean}`,
    method: method.toLowerCase(),
    data: ['GET', 'HEAD'].includes(method.toUpperCase()) ? undefined : body,
    validateStatus: () => true,
  });
  return { status: res.status, body: res.data };
}
```

- [ ] **Step 4: Write `portal-mocks.ts`** (canned, zod-valid; the `__x()` sync accessors exist for the schema test)

```ts
import type {
  PortalMeDto, PortalAppSummaryDto, PortalCredentialsDto, PortalRotateSecretDto,
  PortalWebhookDto, PortalUsageDto, TryItResult, RegisterWebhookInput,
} from './portal';

const ME: PortalMeDto = {
  partnerUserId: 'dev-user-1',
  email: 'dev@insurtech.example',
  csrfToken: 'demo-csrf',
  apps: [
    { id: 'grant-1', partnerAppId: 'app-1', partnerUserId: 'dev-user-1', email: 'dev@insurtech.example', role: 'MANAGER', createdAt: '2026-08-01T09:00:00Z' },
    { id: 'grant-2', partnerAppId: 'app-2', partnerUserId: 'dev-user-1', email: 'dev@insurtech.example', role: 'VIEWER',  createdAt: '2026-08-02T09:00:00Z' },
  ],
};
const APPS: PortalAppSummaryDto[] = [
  { partnerAppId: 'app-1', clientId: 'insurtech-aggregator', tenantSchema: 'tenant_acme', tenantLabel: 'Acme Insurance', scopes: ['products:read', 'quotes:create', 'policies:read', 'webhooks:manage'], rateTier: 'GROWTH',  status: 'ACTIVE', role: 'MANAGER' },
  { partnerAppId: 'app-2', clientId: 'embedded-checkout',   tenantSchema: 'tenant_leadway', tenantLabel: 'Leadway',      scopes: ['products:read', 'policies:read'], rateTier: 'STARTER', status: 'ACTIVE', role: 'VIEWER' },
];
const CREDS: PortalCredentialsDto = { clientId: 'insurtech-aggregator', scopes: APPS[0].scopes };
let WEBHOOKS: PortalWebhookDto[] = [
  { id: 'wh-1', targetUrl: 'https://insurtech.example/hooks/cia', eventTypes: ['policy.bound', 'claim.approved'], active: true, createdAt: '2026-08-10T10:00:00Z', updatedAt: '2026-08-10T10:00:00Z' },
];
const USAGE: PortalUsageDto = {
  today: { total: 412, success: 388, clientError: 20, serverError: 4 },
  history: Array.from({ length: 14 }, (_, i) => {
    const d = new Date(Date.UTC(2026, 7, 20 - i));
    const total = 300 + ((i * 37) % 220);
    const clientError = (i * 5) % 18;
    const serverError = i % 4;
    return { date: d.toISOString().slice(0, 10), total, success: total - clientError - serverError, clientError, serverError };
  }),
  webhookDeliveries: { registrations: 1, activeRegistrations: 1, totalDeliveries: 240, successfulDeliveries: 231, failedDeliveries: 9, lastDeliveryAt: '2026-08-20T14:03:00Z' },
  errorRate: (20 + 4) / 412,
};

const delay = <T>(v: T) => new Promise<T>((r) => setTimeout(() => r(v), 120));

export const mockPortalApi = {
  __me: () => ME,
  __creds: () => CREDS,
  __rotate: (): PortalRotateSecretDto => ({ clientId: 'insurtech-aggregator', clientSecret: 'demo-secret-' + Math.random().toString(36).slice(2, 14) }),
  getMe: () => delay(ME),
  getApps: () => delay(APPS),
  getCredentials: (_appId: string) => delay(CREDS),
  rotateSecret: (_appId: string) => delay(mockPortalApi.__rotate()),
  getWebhooks: (_appId: string) => delay(WEBHOOKS),
  createWebhook: (_appId: string, input: RegisterWebhookInput) => {
    const wh: PortalWebhookDto = { id: 'wh-' + Math.random().toString(36).slice(2, 8), targetUrl: input.targetUrl, eventTypes: input.eventTypes, active: true, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() };
    WEBHOOKS = [wh, ...WEBHOOKS];
    return delay(wh);
  },
  deleteWebhook: (_appId: string, id: string) => { WEBHOOKS = WEBHOOKS.filter((w) => w.id !== id); return delay(undefined); },
  getUsage: (_appId: string) => delay(USAGE),
  logout: () => delay({ logoutUrl: '/' }),
  tryIt: (_appId: string, method: string, path: string): Promise<TryItResult> =>
    delay(method === 'GET' && path.replace(/^\/+/, '').startsWith('products')
      ? { status: 200, body: { data: [{ id: 'prod-1', name: 'Motor Comprehensive' }], meta: { total: 1 } } }
      : { status: 403, body: { errors: [{ code: 'INSUFFICIENT_SCOPE', message: 'Token is missing scope: quotes:create' }] } }),
};
```

- [ ] **Step 5: Export from the barrel** — in `modules/index.ts` add `export * from './portal';`

- [ ] **Step 6: Update `dto-drift.config.json`** — the drift script maps `XDto → XResponse.java` by default; portal DTOs whose Java counterpart is not named `*Response`, or is a pure view-model, need `manualMap` entries. Read the file, then set `manualMap` to include (preserving any existing keys):

```json
{
  "PortalGrantDto": "PartnerDeveloperGrantResponse",
  "PortalAppSummaryDto": "PortalAppSummary",
  "PortalCredentialsDto": "PortalAppCredentialsResponse",
  "PortalRotateSecretDto": "PortalAppCredentialsRotateResponse",
  "PortalWebhookDto": "PartnerWebhookResponse",
  "PortalMeDto": "PortalMeResponse",
  "PortalUsageDto": "PortalUsageResponse",
  "UsageDayDto": "UsageDayResponse",
  "UsageHistoryEntryDto": "UsageHistoryEntryResponse",
  "WebhookDeliverySummaryDto": "WebhookDeliverySummaryResponse",
  "PortalLogoutDto": "PortalLogoutResponse"
}
```

`RegisterWebhookInput` and `TryItResult` are not `*Dto` types, so the drift script ignores them. If the drift script flags a field mismatch on any mapped pair, reconcile the zod schema to the Java record's field set (the schemas above were written against the verified BFF records) — do not silence with an allowList entry unless the asymmetry is intentional and documented.

- [ ] **Step 7: Run the schema test + drift guard**

Run: `pnpm --filter @cia/partner test` (the app's vitest picks up the inlined `@cia/api-client` — or run the api-client package's own vitest if present). Then from `cia-frontend/`: `node scripts/check-dto-drift.mjs`.
Expected: schema test PASSES; drift guard exits 0.

- [ ] **Step 8: Commit**

```bash
git add cia-frontend/packages/api-client/src/modules/portal.ts cia-frontend/packages/api-client/src/modules/portal-mocks.ts cia-frontend/packages/api-client/src/modules/index.ts cia-frontend/packages/api-client/src/modules/portal.test.ts cia-frontend/scripts/dto-drift.config.json
git commit -m "feat(api-client): portal /portal module — zod schemas, credentialed client, mock adapter"
```

---

## Task 3: Portal react-query hooks

**Files:**
- Modify: `cia-frontend/packages/api-client/src/modules/portal.ts` (append the hooks section)
- Test: `cia-frontend/packages/api-client/src/modules/portal.hooks.test.tsx`

**Interfaces:**
- Consumes: the schemas + client helpers from Task 2; `@tanstack/react-query`.
- Produces: `useSession()`, `useApps()`, `useCredentials(appId)`, `useRotateSecret(appId)`, `useWebhooks(appId)`, `useCreateWebhook(appId)`, `useDeleteWebhook(appId)`, `useUsage(appId)`, `useTryIt(appId)`, `useLogout()`. Each read hook returns a react-query result; mutations invalidate the right keys. **Every hook routes to `mockPortalApi` when `isPortalDemoMode()`**, else the real `portal*` helper.

- [ ] **Step 1: Write the failing test** `portal.hooks.test.tsx` (demo mode: hooks resolve mock data; create/delete invalidate)

```tsx
import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import React from 'react';
import { configurePortalClient, useApps, useUsage, useWebhooks, useCreateWebhook } from './portal';

function wrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: React.ReactNode }) => <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

beforeEach(() => configurePortalClient({ baseURL: '', demoMode: true }));

describe('portal hooks in demo mode', () => {
  it('useApps returns mock apps', async () => {
    const { result } = renderHook(() => useApps(), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.[0].clientId).toBe('insurtech-aggregator');
  });
  it('useUsage returns a real-shaped usage object', async () => {
    const { result } = renderHook(() => useUsage('app-1'), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.today.total).toBeGreaterThan(0);
    expect(result.current.data?.history.length).toBeGreaterThan(0);
  });
  it('useCreateWebhook adds a webhook', async () => {
    const w = wrapper();
    const { result: list } = renderHook(() => useWebhooks('app-1'), { wrapper: w });
    await waitFor(() => expect(list.current.isSuccess).toBe(true));
    const before = list.current.data?.length ?? 0;
    const { result: create } = renderHook(() => useCreateWebhook('app-1'), { wrapper: w });
    await create.current.mutateAsync({ targetUrl: 'https://x.example/h', secret: 'sixteen-char-secret!', eventTypes: ['policy.bound'] });
    expect(before).toBeGreaterThanOrEqual(1);
  });
});
```

Run: `pnpm --filter @cia/partner test` → FAIL (hooks not exported).

- [ ] **Step 2: Append the hooks section to `portal.ts`**

```ts
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { mockPortalApi } from './portal-mocks';

const k = {
  session: ['portal', 'session'] as const,
  apps: ['portal', 'apps'] as const,
  credentials: (a: string) => ['portal', 'credentials', a] as const,
  webhooks: (a: string) => ['portal', 'webhooks', a] as const,
  usage: (a: string) => ['portal', 'usage', a] as const,
};

export function useSession() {
  return useQuery({
    queryKey: k.session,
    queryFn: () => (isPortalDemoMode() ? mockPortalApi.getMe() : portalGet('/portal/auth/me', PortalMeDtoSchema)),
    retry: false,
  });
}
export function useApps() {
  return useQuery({
    queryKey: k.apps,
    queryFn: () => (isPortalDemoMode() ? mockPortalApi.getApps() : portalList('/portal/apps', PortalAppSummaryDtoSchema)),
  });
}
export function useCredentials(appId: string) {
  return useQuery({
    queryKey: k.credentials(appId),
    queryFn: () => (isPortalDemoMode() ? mockPortalApi.getCredentials(appId) : portalGet(`/portal/apps/${appId}/credentials`, PortalCredentialsDtoSchema)),
    enabled: !!appId,
  });
}
export function useRotateSecret(appId: string) {
  return useMutation({
    mutationFn: () => (isPortalDemoMode() ? mockPortalApi.rotateSecret(appId) : portalPost(`/portal/apps/${appId}/credentials/rotate`, {}, PortalRotateSecretDtoSchema)),
  });
}
export function useWebhooks(appId: string) {
  return useQuery({
    queryKey: k.webhooks(appId),
    queryFn: () => (isPortalDemoMode() ? mockPortalApi.getWebhooks(appId) : portalList(`/portal/apps/${appId}/webhooks`, PortalWebhookDtoSchema)),
    enabled: !!appId,
  });
}
export function useCreateWebhook(appId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: RegisterWebhookInput) => (isPortalDemoMode() ? mockPortalApi.createWebhook(appId, input) : portalPost(`/portal/apps/${appId}/webhooks`, input, PortalWebhookDtoSchema)),
    onSuccess: () => qc.invalidateQueries({ queryKey: k.webhooks(appId) }),
  });
}
export function useDeleteWebhook(appId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => (isPortalDemoMode() ? mockPortalApi.deleteWebhook(appId, id) : portalDelete(`/portal/apps/${appId}/webhooks/${id}`)),
    onSuccess: () => qc.invalidateQueries({ queryKey: k.webhooks(appId) }),
  });
}
export function useUsage(appId: string) {
  return useQuery({
    queryKey: k.usage(appId),
    queryFn: () => (isPortalDemoMode() ? mockPortalApi.getUsage(appId) : portalGet(`/portal/apps/${appId}/usage`, PortalUsageDtoSchema)),
    enabled: !!appId,
    staleTime: 15_000,
  });
}
export function useTryIt(appId: string) {
  return useMutation({
    mutationFn: (args: { method: string; path: string; body?: unknown }) =>
      (isPortalDemoMode() ? mockPortalApi.tryIt(appId, args.method, args.path) : portalTry(appId, args.method, args.path, args.body)),
  });
}
export function useLogout() {
  return useMutation({
    mutationFn: () => (isPortalDemoMode() ? mockPortalApi.logout() : portalPost('/portal/auth/logout', {}, PortalLogoutDtoSchema)),
  });
}
```

- [ ] **Step 3: Run tests**

Run: `pnpm --filter @cia/partner test`
Expected: all three hook tests PASS.

- [ ] **Step 4: Commit**

```bash
git add cia-frontend/packages/api-client/src/modules/portal.ts cia-frontend/packages/api-client/src/modules/portal.hooks.test.tsx
git commit -m "feat(api-client): portal react-query hooks (demo + real routing)"
```

---

## Task 4: PortalAuthProvider (real + demo) + main.tsx + App.tsx

**Files:**
- Create: `cia-frontend/apps/partner/src/app/auth/PortalAuthProvider.tsx`
- Create: `cia-frontend/apps/partner/src/app/auth/LoginScreen.tsx`
- Rewrite: `cia-frontend/apps/partner/src/main.tsx`
- Rewrite: `cia-frontend/apps/partner/src/App.tsx`
- Create: `cia-frontend/apps/partner/src/vite-env.d.ts` (Vite env typings)
- Test: `cia-frontend/apps/partner/src/app/auth/PortalAuthProvider.test.tsx`

**Interfaces:**
- Consumes: `useSession`, `setPortalCsrfToken`, `useLogout`, `isPortalDemoMode` from `@cia/api-client`.
- Produces: `PortalAuthProvider` (context), `usePortalAuth()` → `{ session: PortalMeDto, demoMode: boolean, logout(): void }`. Provided above the router; on 401/no-session it renders `LoginScreen` instead of children. Consumed by AppShell (Task 5) and pages.

- [ ] **Step 1: Write the failing test** `PortalAuthProvider.test.tsx`

```tsx
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { configurePortalClient } from '@cia/api-client';
import { PortalAuthProvider } from './PortalAuthProvider';

function renderWithProviders() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <PortalAuthProvider><div>secured content</div></PortalAuthProvider>
    </QueryClientProvider>,
  );
}

describe('PortalAuthProvider', () => {
  beforeEach(() => configurePortalClient({ baseURL: '', demoMode: true }));
  it('renders children with a mock session in demo mode', async () => {
    renderWithProviders();
    await waitFor(() => expect(screen.getByText('secured content')).toBeInTheDocument());
  });
});
```

Run → FAIL.

- [ ] **Step 2: Create `LoginScreen.tsx`**

```tsx
export function LoginScreen({ apiBase }: { apiBase: string }) {
  return (
    <div className="flex min-h-screen items-center justify-center bg-background">
      <div className="w-full max-w-sm rounded-xl border border-border bg-card p-8 text-center">
        <h1 className="font-display text-2xl font-bold text-foreground">CIA Partner Portal</h1>
        <p className="mt-2 text-sm text-muted-foreground">Sign in to manage your Insurtech integration.</p>
        <a
          href={`${apiBase}/portal/auth/login`}
          className="mt-6 inline-flex w-full items-center justify-center rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90"
        >
          Sign in
        </a>
      </div>
    </div>
  );
}
```

- [ ] **Step 3: Create `PortalAuthProvider.tsx`**

```tsx
import { createContext, useContext, useEffect } from 'react';
import { useSession, useLogout, setPortalCsrfToken, isPortalDemoMode, type PortalMeDto } from '@cia/api-client';
import { LoginScreen } from './LoginScreen';

interface PortalAuthValue { session: PortalMeDto; demoMode: boolean; logout: () => void; }
const Ctx = createContext<PortalAuthValue | null>(null);

const API_BASE = (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? '';

export function PortalAuthProvider({ children }: { children: React.ReactNode }) {
  const sessionQuery = useSession();
  const logoutMutation = useLogout();

  // Keep the CSRF token in the api-client for mutating requests.
  useEffect(() => {
    setPortalCsrfToken(sessionQuery.data?.csrfToken ?? null);
  }, [sessionQuery.data?.csrfToken]);

  // A live 401 (session expired mid-app) forces the login screen.
  useEffect(() => {
    const onUnauth = () => sessionQuery.refetch();
    window.addEventListener('portal:unauthorized', onUnauth);
    return () => window.removeEventListener('portal:unauthorized', onUnauth);
  }, [sessionQuery]);

  if (sessionQuery.isLoading) {
    return <div className="flex min-h-screen items-center justify-center bg-background text-sm text-muted-foreground">Loading…</div>;
  }
  if (sessionQuery.isError || !sessionQuery.data) {
    return <LoginScreen apiBase={API_BASE} />;
  }

  const logout = () => {
    logoutMutation.mutate(undefined, {
      onSuccess: (res) => { window.location.href = isPortalDemoMode() ? '/' : res.logoutUrl; },
    });
  };

  return <Ctx.Provider value={{ session: sessionQuery.data, demoMode: isPortalDemoMode(), logout }}>{children}</Ctx.Provider>;
}

export function usePortalAuth(): PortalAuthValue {
  const v = useContext(Ctx);
  if (!v) throw new Error('usePortalAuth must be used within PortalAuthProvider');
  return v;
}
```

- [ ] **Step 4: Create `src/vite-env.d.ts`**

```ts
/// <reference types="vite/client" />
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_DEMO_MODE?: string;
}
interface ImportMeta { readonly env: ImportMetaEnv; }
```

- [ ] **Step 5: Rewrite `main.tsx`** (compute demoMode once, configure the portal client, wire providers)

```tsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { configurePortalClient } from '@cia/api-client';
import App from './App';
import { PortalAuthProvider } from './app/auth/PortalAuthProvider';
import './app/globals.css';

const demoMode = import.meta.env.DEV || import.meta.env.VITE_DEMO_MODE === 'true';
const apiBase = (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? '';

configurePortalClient({ baseURL: apiBase, demoMode });

const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: 30_000, retry: 1 } } });

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <PortalAuthProvider>
        <App />
      </PortalAuthProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);
```

- [ ] **Step 6: Rewrite `App.tsx`**

```tsx
import { RouterProvider } from 'react-router-dom';
import { router } from './app/router';

export default function App() {
  return <RouterProvider router={router} />;
}
```

(`./app/router` is created in Task 5. To keep this task independently green, temporarily point `App.tsx` at a placeholder `<div>` and switch to the router in Task 5 — OR sequence Task 5 immediately after and let the task reviewer see them together. Prefer: create a minimal `src/app/router.tsx` stub here that renders a single `<div>authenticated</div>` route, replaced in Task 5.)

Minimal stub `src/app/router.tsx` for this task:
```tsx
import { createBrowserRouter } from 'react-router-dom';
export const router = createBrowserRouter([{ path: '/', element: <div className="p-6 text-foreground">authenticated</div> }]);
```

- [ ] **Step 7: Run test + build**

Run: `pnpm --filter @cia/partner test` (auth provider test passes) and `pnpm --filter @cia/partner build` (typechecks + builds).
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add cia-frontend/apps/partner/src
git commit -m "feat(partner): PortalAuthProvider (cookie-session real + demo), main/App wiring"
```

---

## Task 5: Dark AppShell + Sidebar + Topbar + app-context selector + router

**Files:**
- Create: `cia-frontend/apps/partner/src/app/layout/AppShell.tsx`
- Create: `cia-frontend/apps/partner/src/app/layout/Sidebar.tsx`
- Create: `cia-frontend/apps/partner/src/app/layout/Topbar.tsx`
- Create: `cia-frontend/apps/partner/src/app/AppContext.tsx`
- Rewrite: `cia-frontend/apps/partner/src/app/router.tsx`
- Create page stubs: `src/modules/{credentials,explorer,webhooks,usage}/{Credentials,Explorer,Webhooks,Usage}Page.tsx` (each a one-line placeholder returning the page title — filled in Tasks 6–9)
- Test: `cia-frontend/apps/partner/src/app/AppContext.test.tsx`

**Interfaces:**
- Consumes: `usePortalAuth`, `useApps`.
- Produces: `AppContextProvider` + `useSelectedApp()` → `{ apps: PortalAppSummaryDto[], selectedAppId: string | null, selectedApp: PortalAppSummaryDto | undefined, setSelectedAppId(id): void, isLoading, isEmpty }`. Selection persists to `localStorage['cia.portal.selectedAppId']`; a single-app developer auto-selects; none → `isEmpty`. Consumed by every page (Tasks 6–9).
- Produces: `router` mounting `AppShell` with lazy routes `/usage` (index), `/credentials`, `/explorer`, `/webhooks`.

- [ ] **Step 1: Write the failing test** `AppContext.test.tsx` (auto-select single app; persist + restore; empty state)

```tsx
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { configurePortalClient } from '@cia/api-client';
import React from 'react';
import { AppContextProvider, useSelectedApp } from './AppContext';

function wrap() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={qc}><AppContextProvider>{children}</AppContextProvider></QueryClientProvider>
  );
}

beforeEach(() => { localStorage.clear(); configurePortalClient({ baseURL: '', demoMode: true }); });

describe('useSelectedApp', () => {
  it('lists apps and persists a selection', async () => {
    const { result } = renderHook(() => useSelectedApp(), { wrapper: wrap() });
    await waitFor(() => expect(result.current.apps.length).toBe(2));
    act(() => result.current.setSelectedAppId('app-2'));
    expect(result.current.selectedAppId).toBe('app-2');
    expect(localStorage.getItem('cia.portal.selectedAppId')).toBe('app-2');
  });
});
```

Run → FAIL.

- [ ] **Step 2: Create `AppContext.tsx`**

```tsx
import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import { useApps, type PortalAppSummaryDto } from '@cia/api-client';

const LS_KEY = 'cia.portal.selectedAppId';

interface SelectedAppValue {
  apps: PortalAppSummaryDto[];
  selectedAppId: string | null;
  selectedApp: PortalAppSummaryDto | undefined;
  setSelectedAppId: (id: string) => void;
  isLoading: boolean;
  isEmpty: boolean;
}
const Ctx = createContext<SelectedAppValue | null>(null);

function readStored(): string | null {
  try { return localStorage.getItem(LS_KEY); } catch { return null; }
}

export function AppContextProvider({ children }: { children: React.ReactNode }) {
  const appsQuery = useApps();
  const apps = useMemo(() => appsQuery.data ?? [], [appsQuery.data]);
  const [selectedAppId, setSelected] = useState<string | null>(readStored);

  // Reconcile once apps load: keep a valid stored id, else auto-select a lone app.
  useEffect(() => {
    if (apps.length === 0) return;
    const stillValid = selectedAppId && apps.some((a) => a.partnerAppId === selectedAppId);
    if (!stillValid) {
      const next = apps.length === 1 ? apps[0].partnerAppId : null;
      setSelected(next);
      try { if (next) localStorage.setItem(LS_KEY, next); } catch { /* ignore */ }
    }
  }, [apps, selectedAppId]);

  const setSelectedAppId = (id: string) => {
    setSelected(id);
    try { localStorage.setItem(LS_KEY, id); } catch { /* ignore */ }
  };

  const value: SelectedAppValue = {
    apps,
    selectedAppId,
    selectedApp: apps.find((a) => a.partnerAppId === selectedAppId),
    setSelectedAppId,
    isLoading: appsQuery.isLoading,
    isEmpty: !appsQuery.isLoading && apps.length === 0,
  };
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useSelectedApp(): SelectedAppValue {
  const v = useContext(Ctx);
  if (!v) throw new Error('useSelectedApp must be used within AppContextProvider');
  return v;
}
```

- [ ] **Step 3: Create `Sidebar.tsx`** (fixed 256, dark; nav = Usage / Credentials / API Explorer / Webhooks)

```tsx
import { NavLink } from 'react-router-dom';
import { HugeiconsIcon } from '@hugeicons/react';
import { Analytics01Icon, KeyIcon, CodeIcon, Notification01Icon } from '@hugeicons/core-free-icons';
import { usePortalAuth } from '../auth/PortalAuthProvider';

const NAV = [
  { label: 'Usage',        path: '/usage',       icon: Analytics01Icon },
  { label: 'Credentials',  path: '/credentials', icon: KeyIcon },
  { label: 'API Explorer', path: '/explorer',    icon: CodeIcon },
  { label: 'Webhooks',     path: '/webhooks',    icon: Notification01Icon },
];

export function Sidebar() {
  const { session, logout } = usePortalAuth();
  return (
    <div className="flex h-full flex-col border-r border-border bg-card">
      <div className="flex items-center gap-2 px-4 py-4">
        <span className="font-display text-lg font-bold text-foreground">Partner Portal</span>
      </div>
      <nav className="flex-1 px-2">
        <ul className="space-y-1">
          {NAV.map((item) => (
            <li key={item.path}>
              <NavLink
                to={item.path}
                className={({ isActive }) =>
                  `flex items-center gap-3 rounded-md px-3 py-2 text-sm ${isActive ? 'bg-primary/15 text-primary' : 'text-muted-foreground hover:bg-muted/40 hover:text-foreground'}`
                }
              >
                <HugeiconsIcon icon={item.icon} size={18} />
                {item.label}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>
      <div className="border-t border-border p-4">
        <p className="truncate text-sm text-foreground">{session.email}</p>
        <button onClick={logout} className="mt-2 text-xs text-muted-foreground hover:text-foreground">Sign out</button>
      </div>
    </div>
  );
}
```

(Verify the four hugeicons names resolve in `@hugeicons/core-free-icons`; if a name differs, pick the nearest valid icon — the icon choice is not load-bearing.)

- [ ] **Step 4: Create `Topbar.tsx`** (app-context selector + demo badge)

```tsx
import { usePortalAuth } from '../auth/PortalAuthProvider';
import { useSelectedApp } from '../AppContext';

export function Topbar() {
  const { demoMode } = usePortalAuth();
  const { apps, selectedAppId, setSelectedAppId, isEmpty } = useSelectedApp();
  return (
    <header className="flex h-14 shrink-0 items-center gap-3 border-b border-border bg-card px-4">
      <div className="flex-1">
        {!isEmpty && (
          <label className="flex items-center gap-2 text-sm text-muted-foreground">
            App:
            <select
              value={selectedAppId ?? ''}
              onChange={(e) => setSelectedAppId(e.target.value)}
              className="rounded-md border border-border bg-background px-2 py-1 text-sm text-foreground"
            >
              {selectedAppId === null && <option value="" disabled>Select an app…</option>}
              {apps.map((a) => (
                <option key={a.partnerAppId} value={a.partnerAppId}>{a.tenantLabel} · {a.clientId}</option>
              ))}
            </select>
          </label>
        )}
      </div>
      {demoMode && (
        <span className="rounded-full bg-amber-500/20 px-2 py-0.5 text-xs font-medium text-amber-400">Demo</span>
      )}
    </header>
  );
}
```

- [ ] **Step 5: Create `AppShell.tsx`**

```tsx
import { Outlet } from 'react-router-dom';
import { AppContextProvider, useSelectedApp } from '../AppContext';
import { Sidebar } from './Sidebar';
import { Topbar } from './Topbar';

function EmptyOrOutlet() {
  const { isEmpty } = useSelectedApp();
  if (isEmpty) {
    return (
      <div className="p-10 text-center text-sm text-muted-foreground">
        No Partner Apps are granted to your account yet. Ask the insurer’s admin to invite you.
      </div>
    );
  }
  return <Outlet />;
}

export function AppShell() {
  return (
    <AppContextProvider>
      <div className="flex h-screen overflow-hidden bg-background">
        <aside style={{ width: 256, flexShrink: 0 }}><Sidebar /></aside>
        <div className="flex flex-1 flex-col overflow-hidden">
          <Topbar />
          <main className="flex-1 overflow-y-auto"><div className="p-6"><EmptyOrOutlet /></div></main>
        </div>
      </div>
    </AppContextProvider>
  );
}
```

- [ ] **Step 6: Create the four page stubs** (each replaced in its own task)

e.g. `src/modules/usage/UsagePage.tsx`:
```tsx
export default function UsagePage() { return <h1 className="text-xl font-semibold text-foreground">Usage</h1>; }
```
Repeat for `credentials/CredentialsPage.tsx`, `explorer/ExplorerPage.tsx`, `webhooks/WebhooksPage.tsx`.

- [ ] **Step 7: Rewrite `router.tsx`** (lazy + Suspense skeleton, index → /usage)

```tsx
import { lazy, Suspense } from 'react';
import { createBrowserRouter, Navigate } from 'react-router-dom';
import { AppShell } from './layout/AppShell';

const UsagePage = lazy(() => import('../modules/usage/UsagePage'));
const CredentialsPage = lazy(() => import('../modules/credentials/CredentialsPage'));
const ExplorerPage = lazy(() => import('../modules/explorer/ExplorerPage'));
const WebhooksPage = lazy(() => import('../modules/webhooks/WebhooksPage'));

function PageSkeleton() {
  return (
    <div className="flex flex-col gap-4 animate-pulse">
      <div className="h-8 w-48 rounded bg-muted" />
      <div className="h-4 w-96 rounded bg-muted" />
      <div className="h-64 rounded bg-muted" />
    </div>
  );
}
const D = ({ children }: { children: React.ReactNode }) => <Suspense fallback={<PageSkeleton />}>{children}</Suspense>;

export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true, element: <Navigate to="/usage" replace /> },
      { path: 'usage', element: <D><UsagePage /></D> },
      { path: 'credentials', element: <D><CredentialsPage /></D> },
      { path: 'explorer', element: <D><ExplorerPage /></D> },
      { path: 'webhooks', element: <D><WebhooksPage /></D> },
    ],
  },
]);
```

- [ ] **Step 8: Run test + build**

Run: `pnpm --filter @cia/partner test` (AppContext test passes) + `pnpm --filter @cia/partner build`.
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add cia-frontend/apps/partner/src
git commit -m "feat(partner): dark AppShell, sidebar/topbar, app-context selector, lazy router"
```

---

## Task 6: P1 — Credentials page (rotate secret once)

**Files:**
- Rewrite: `cia-frontend/apps/partner/src/modules/credentials/CredentialsPage.tsx`
- Create: `cia-frontend/apps/partner/src/lib/copy.ts`
- Test: `cia-frontend/apps/partner/src/modules/credentials/CredentialsPage.test.tsx`

**Interfaces:**
- Consumes: `useSelectedApp`, `useCredentials`, `useRotateSecret`.
- Produces: a page rendering `clientId` + granted `scopes`; a "Rotate secret" action that reveals the new `clientSecret` **once** in a dismissible panel with copy-to-clipboard, never re-shown or persisted.

- [ ] **Step 1: Write the failing test** (rotate reveals the secret once; it clears on dismiss)

```tsx
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { configurePortalClient } from '@cia/api-client';
import CredentialsPage from './CredentialsPage';
import { AppContextProvider } from '../../app/AppContext';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}><AppContextProvider><CredentialsPage /></AppContextProvider></QueryClientProvider>,
  );
}
beforeEach(() => { localStorage.clear(); localStorage.setItem('cia.portal.selectedAppId', 'app-1'); configurePortalClient({ baseURL: '', demoMode: true }); });

describe('CredentialsPage', () => {
  it('shows client id + scopes and reveals a rotated secret once', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('insurtech-aggregator')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: /rotate secret/i }));
    await waitFor(() => expect(screen.getByText(/demo-secret-/)).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: /dismiss/i }));
    expect(screen.queryByText(/demo-secret-/)).not.toBeInTheDocument();
  });
});
```

Run → FAIL.

- [ ] **Step 2: Create `src/lib/copy.ts`**

```ts
export async function copyToClipboard(text: string): Promise<boolean> {
  try { await navigator.clipboard.writeText(text); return true; } catch { return false; }
}
```

- [ ] **Step 3: Implement `CredentialsPage.tsx`**

```tsx
import { useState } from 'react';
import { useSelectedApp, useCredentials, useRotateSecret } from '@cia/api-client';
import { copyToClipboard } from '../../lib/copy';

export default function CredentialsPage() {
  const { selectedAppId, selectedApp } = useSelectedApp();
  const appId = selectedAppId ?? '';
  const credsQuery = useCredentials(appId);
  const rotate = useRotateSecret(appId);
  const [revealed, setRevealed] = useState<string | null>(null);
  const canRotate = selectedApp?.role === 'MANAGER';

  const onRotate = () => rotate.mutate(undefined, { onSuccess: (r) => setRevealed(r.clientSecret) });

  return (
    <div className="max-w-2xl space-y-6">
      <h1 className="text-xl font-semibold text-foreground">Credentials</h1>
      {credsQuery.isLoading && <p className="text-sm text-muted-foreground">Loading…</p>}
      {credsQuery.data && (
        <div className="rounded-lg border border-border bg-card p-5">
          <dl className="space-y-3 text-sm">
            <div>
              <dt className="text-muted-foreground">Client ID</dt>
              <dd className="mt-1 font-mono text-foreground">{credsQuery.data.clientId}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground">Granted scopes</dt>
              <dd className="mt-1 flex flex-wrap gap-1">
                {credsQuery.data.scopes.map((s) => (
                  <span key={s} className="rounded bg-muted px-2 py-0.5 font-mono text-xs text-foreground">{s}</span>
                ))}
              </dd>
            </div>
          </dl>
        </div>
      )}

      {revealed ? (
        <div className="rounded-lg border border-amber-500/40 bg-amber-500/10 p-5">
          <p className="text-sm font-medium text-amber-300">New client secret — shown once</p>
          <p className="mt-2 break-all font-mono text-sm text-foreground">{revealed}</p>
          <div className="mt-3 flex gap-2">
            <button onClick={() => copyToClipboard(revealed)} className="rounded-md bg-primary px-3 py-1.5 text-sm text-primary-foreground">Copy</button>
            <button onClick={() => setRevealed(null)} className="rounded-md border border-border px-3 py-1.5 text-sm text-foreground">Dismiss</button>
          </div>
        </div>
      ) : (
        <button
          onClick={onRotate}
          disabled={!canRotate || rotate.isPending}
          title={canRotate ? undefined : 'Only a MANAGER can rotate the secret'}
          className="rounded-md bg-primary px-4 py-2 text-sm text-primary-foreground disabled:opacity-50"
        >
          {rotate.isPending ? 'Rotating…' : 'Rotate secret'}
        </button>
      )}
      {rotate.isError && <p className="text-sm text-red-400">Could not rotate the secret. Try again.</p>}
    </div>
  );
}
```

- [ ] **Step 4: Run test + build** → PASS.

- [ ] **Step 5: Commit**

```bash
git add cia-frontend/apps/partner/src/modules/credentials cia-frontend/apps/partner/src/lib/copy.ts
git commit -m "feat(partner): P1 Credentials page — client id, scopes, rotate-secret-once"
```

---

## Task 7: P2 — API Explorer (try-it proxy, verbatim relay)

**Files:**
- Rewrite: `cia-frontend/apps/partner/src/modules/explorer/ExplorerPage.tsx`
- Test: `cia-frontend/apps/partner/src/modules/explorer/ExplorerPage.test.tsx`

**Interfaces:**
- Consumes: `useSelectedApp`, `useTryIt`.
- Produces: a request builder (method select + path input under `/partner/v1/` + optional JSON body) → fires the proxy; a response viewer showing the **verbatim** status + pretty-printed body (including a scope `403` and a `429`). Quick-fill buttons for common `/partner/v1` endpoints.

- [ ] **Step 1: Write the failing test** (a mutating call returns a verbatim 403 body)

```tsx
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { configurePortalClient } from '@cia/api-client';
import ExplorerPage from './ExplorerPage';
import { AppContextProvider } from '../../app/AppContext';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}><AppContextProvider><ExplorerPage /></AppContextProvider></QueryClientProvider>);
}
beforeEach(() => { localStorage.clear(); localStorage.setItem('cia.portal.selectedAppId', 'app-1'); configurePortalClient({ baseURL: '', demoMode: true }); });

describe('ExplorerPage', () => {
  it('relays a 200 for GET products and a verbatim 403 for a scoped write', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByRole('button', { name: /send/i })).toBeEnabled());
    // default GET products → 200
    await userEvent.click(screen.getByRole('button', { name: /send/i }));
    await waitFor(() => expect(screen.getByText(/200/)).toBeInTheDocument());
    // switch to POST quotes → 403 verbatim
    await userEvent.selectOptions(screen.getByLabelText(/method/i), 'POST');
    await userEvent.clear(screen.getByLabelText(/path/i));
    await userEvent.type(screen.getByLabelText(/path/i), 'quotes');
    await userEvent.click(screen.getByRole('button', { name: /send/i }));
    await waitFor(() => expect(screen.getByText(/403/)).toBeInTheDocument());
    expect(screen.getByText(/INSUFFICIENT_SCOPE/)).toBeInTheDocument();
  });
});
```

Run → FAIL.

- [ ] **Step 2: Implement `ExplorerPage.tsx`**

```tsx
import { useState } from 'react';
import { useSelectedApp, useTryIt, type TryItResult } from '@cia/api-client';

const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];
const QUICK = [
  { label: 'GET products', method: 'GET', path: 'products' },
  { label: 'GET policies', method: 'GET', path: 'policies' },
  { label: 'POST quotes', method: 'POST', path: 'quotes' },
];

function statusClass(s: number) {
  if (s >= 200 && s < 300) return 'text-emerald-400';
  if (s === 429) return 'text-amber-400';
  return 'text-red-400';
}

export default function ExplorerPage() {
  const { selectedAppId } = useSelectedApp();
  const appId = selectedAppId ?? '';
  const tryIt = useTryIt(appId);
  const [method, setMethod] = useState('GET');
  const [path, setPath] = useState('products');
  const [body, setBody] = useState('');
  const [result, setResult] = useState<TryItResult | null>(null);
  const [bodyError, setBodyError] = useState<string | null>(null);

  const send = () => {
    let parsed: unknown;
    if (method !== 'GET' && body.trim()) {
      try { parsed = JSON.parse(body); setBodyError(null); }
      catch { setBodyError('Body is not valid JSON'); return; }
    }
    tryIt.mutate({ method, path, body: parsed }, { onSuccess: setResult });
  };

  return (
    <div className="max-w-3xl space-y-5">
      <h1 className="text-xl font-semibold text-foreground">API Explorer</h1>
      <p className="text-sm text-muted-foreground">Calls run against <code className="font-mono">/partner/v1/</code> exactly as a real integration — scope and rate-limit errors are shown verbatim.</p>

      <div className="flex flex-wrap gap-2">
        {QUICK.map((q) => (
          <button key={q.label} onClick={() => { setMethod(q.method); setPath(q.path); }} className="rounded-md border border-border px-2 py-1 text-xs text-muted-foreground hover:text-foreground">{q.label}</button>
        ))}
      </div>

      <div className="rounded-lg border border-border bg-card p-4">
        <div className="flex items-center gap-2">
          <label className="sr-only" htmlFor="method">Method</label>
          <select id="method" value={method} onChange={(e) => setMethod(e.target.value)} className="rounded-md border border-border bg-background px-2 py-2 text-sm text-foreground">
            {METHODS.map((m) => <option key={m} value={m}>{m}</option>)}
          </select>
          <span className="font-mono text-sm text-muted-foreground">/partner/v1/</span>
          <label className="sr-only" htmlFor="path">Path</label>
          <input id="path" value={path} onChange={(e) => setPath(e.target.value)} className="flex-1 rounded-md border border-border bg-background px-2 py-2 font-mono text-sm text-foreground" placeholder="products" />
          <button onClick={send} disabled={!appId || tryIt.isPending} className="rounded-md bg-primary px-4 py-2 text-sm text-primary-foreground disabled:opacity-50">
            {tryIt.isPending ? 'Sending…' : 'Send'}
          </button>
        </div>
        {method !== 'GET' && (
          <textarea value={body} onChange={(e) => setBody(e.target.value)} rows={5} className="mt-3 w-full rounded-md border border-border bg-background p-2 font-mono text-xs text-foreground" placeholder='{ "productId": "…" }' />
        )}
        {bodyError && <p className="mt-1 text-xs text-red-400">{bodyError}</p>}
      </div>

      {result && (
        <div className="rounded-lg border border-border bg-card p-4">
          <p className="text-sm">Status: <span className={`font-mono font-semibold ${statusClass(result.status)}`}>{result.status}</span></p>
          <pre className="mt-2 max-h-96 overflow-auto rounded bg-background p-3 text-xs text-foreground">{JSON.stringify(result.body, null, 2)}</pre>
        </div>
      )}
      {tryIt.isError && <p className="text-sm text-red-400">The portal could not reach the API. Try again.</p>}
    </div>
  );
}
```

- [ ] **Step 3: Run test + build** → PASS.

- [ ] **Step 4: Commit**

```bash
git add cia-frontend/apps/partner/src/modules/explorer
git commit -m "feat(partner): P2 API Explorer — try-it proxy with verbatim status/body relay"
```

---

## Task 8: P3 — Webhook Management (+ delivery log from usage)

**Files:**
- Rewrite: `cia-frontend/apps/partner/src/modules/webhooks/WebhooksPage.tsx`
- Test: `cia-frontend/apps/partner/src/modules/webhooks/WebhooksPage.test.tsx`

**Interfaces:**
- Consumes: `useSelectedApp`, `useWebhooks`, `useCreateWebhook`, `useDeleteWebhook`, `useUsage` (for the delivery summary), `formatTimestamp`, `formatInt`.
- Produces: register form (`targetUrl` + `secret` [min 16, client-guarded] + `eventTypes[]` multi-select), a list showing `targetUrl` + `active` + event types with delete, and a delivery-summary panel from `usage.webhookDeliveries`.

- [ ] **Step 1: Write the failing test** (create is blocked until secret ≥ 16 chars; created row shows targetUrl)

```tsx
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { configurePortalClient } from '@cia/api-client';
import WebhooksPage from './WebhooksPage';
import { AppContextProvider } from '../../app/AppContext';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}><AppContextProvider><WebhooksPage /></AppContextProvider></QueryClientProvider>);
}
beforeEach(() => { localStorage.clear(); localStorage.setItem('cia.portal.selectedAppId', 'app-1'); configurePortalClient({ baseURL: '', demoMode: true }); });

describe('WebhooksPage', () => {
  it('lists existing webhooks by targetUrl and validates the secret length', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('https://insurtech.example/hooks/cia')).toBeInTheDocument());
    await userEvent.type(screen.getByLabelText(/target url/i), 'https://x.example/h');
    await userEvent.type(screen.getByLabelText(/signing secret/i), 'short');
    await userEvent.click(screen.getByLabelText(/policy.bound/i));
    await userEvent.click(screen.getByRole('button', { name: /register/i }));
    expect(screen.getByText(/at least 16 characters/i)).toBeInTheDocument();
  });
});
```

Run → FAIL.

- [ ] **Step 2: Implement `WebhooksPage.tsx`**

```tsx
import { useState } from 'react';
import { useSelectedApp, useWebhooks, useCreateWebhook, useDeleteWebhook, useUsage } from '@cia/api-client';
import { formatInt, formatTimestamp } from '../../lib/format';

const EVENTS = ['policy.bound', 'policy.endorsed', 'policy.cancelled', 'claim.registered', 'claim.approved', 'claim.settled', 'quote.created', 'quote.expired', 'kyc.completed', 'renewal.due'];

export default function WebhooksPage() {
  const { selectedAppId, selectedApp } = useSelectedApp();
  const appId = selectedAppId ?? '';
  const webhooksQuery = useWebhooks(appId);
  const usageQuery = useUsage(appId);
  const create = useCreateWebhook(appId);
  const del = useDeleteWebhook(appId);
  const canManage = selectedApp?.role === 'MANAGER';

  const [targetUrl, setTargetUrl] = useState('');
  const [secret, setSecret] = useState('');
  const [selected, setSelected] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);

  const toggle = (ev: string) => setSelected((s) => (s.includes(ev) ? s.filter((e) => e !== ev) : [...s, ev]));

  const submit = () => {
    if (!targetUrl.trim()) { setError('Target URL is required'); return; }
    if (secret.length < 16) { setError('Signing secret must be at least 16 characters'); return; }
    if (selected.length === 0) { setError('Select at least one event type'); return; }
    setError(null);
    create.mutate({ targetUrl, secret, eventTypes: selected }, {
      onSuccess: () => { setTargetUrl(''); setSecret(''); setSelected([]); },
    });
  };

  const wd = usageQuery.data?.webhookDeliveries;

  return (
    <div className="max-w-3xl space-y-6">
      <h1 className="text-xl font-semibold text-foreground">Webhooks</h1>

      {canManage && (
        <div className="rounded-lg border border-border bg-card p-5 space-y-3">
          <div>
            <label htmlFor="wh-url" className="text-sm text-muted-foreground">Target URL</label>
            <input id="wh-url" value={targetUrl} onChange={(e) => setTargetUrl(e.target.value)} className="mt-1 w-full rounded-md border border-border bg-background px-2 py-2 text-sm text-foreground" placeholder="https://…" />
          </div>
          <div>
            <label htmlFor="wh-secret" className="text-sm text-muted-foreground">Signing secret (min 16 chars)</label>
            <input id="wh-secret" type="password" value={secret} onChange={(e) => setSecret(e.target.value)} className="mt-1 w-full rounded-md border border-border bg-background px-2 py-2 font-mono text-sm text-foreground" />
          </div>
          <fieldset className="flex flex-wrap gap-2">
            {EVENTS.map((ev) => (
              <label key={ev} className="flex items-center gap-1 rounded border border-border px-2 py-1 text-xs text-foreground">
                <input type="checkbox" aria-label={ev} checked={selected.includes(ev)} onChange={() => toggle(ev)} />
                {ev}
              </label>
            ))}
          </fieldset>
          {error && <p className="text-sm text-red-400">{error}</p>}
          <button onClick={submit} disabled={create.isPending} className="rounded-md bg-primary px-4 py-2 text-sm text-primary-foreground disabled:opacity-50">
            {create.isPending ? 'Registering…' : 'Register webhook'}
          </button>
        </div>
      )}

      <div className="rounded-lg border border-border bg-card">
        {webhooksQuery.isLoading && <p className="p-4 text-sm text-muted-foreground">Loading…</p>}
        {webhooksQuery.data?.length === 0 && <p className="p-4 text-sm text-muted-foreground">No webhooks registered.</p>}
        <ul className="divide-y divide-border">
          {webhooksQuery.data?.map((w) => (
            <li key={w.id} className="flex items-center justify-between p-4">
              <div>
                <p className="font-mono text-sm text-foreground">{w.targetUrl}</p>
                <p className="mt-1 flex flex-wrap gap-1">
                  {w.eventTypes.map((e) => <span key={e} className="rounded bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">{e}</span>)}
                </p>
              </div>
              <div className="flex items-center gap-3">
                <span className={`text-xs ${w.active ? 'text-emerald-400' : 'text-muted-foreground'}`}>{w.active ? 'Active' : 'Inactive'}</span>
                {canManage && <button onClick={() => del.mutate(w.id)} className="text-xs text-red-400 hover:underline">Delete</button>}
              </div>
            </li>
          ))}
        </ul>
      </div>

      <div className="rounded-lg border border-border bg-card p-5">
        <h2 className="text-sm font-medium text-foreground">Delivery summary</h2>
        <dl className="mt-3 grid grid-cols-2 gap-3 text-sm sm:grid-cols-4">
          <div><dt className="text-muted-foreground">Total</dt><dd className="text-foreground">{formatInt(wd?.totalDeliveries)}</dd></div>
          <div><dt className="text-muted-foreground">Succeeded</dt><dd className="text-emerald-400">{formatInt(wd?.successfulDeliveries)}</dd></div>
          <div><dt className="text-muted-foreground">Failed</dt><dd className="text-red-400">{formatInt(wd?.failedDeliveries)}</dd></div>
          <div><dt className="text-muted-foreground">Last delivery</dt><dd className="text-foreground">{formatTimestamp(wd?.lastDeliveryAt)}</dd></div>
        </dl>
      </div>
    </div>
  );
}
```

- [ ] **Step 3: Run test + build** → PASS.

- [ ] **Step 4: Commit**

```bash
git add cia-frontend/apps/partner/src/modules/webhooks
git commit -m "feat(partner): P3 Webhook Management — register/list/delete + delivery summary"
```

---

## Task 9: P5 — Usage Dashboard (real telemetry + Recharts)

**Files:**
- Rewrite: `cia-frontend/apps/partner/src/modules/usage/UsagePage.tsx`
- Create: `cia-frontend/apps/partner/src/modules/usage/UsageChart.tsx`
- Test: `cia-frontend/apps/partner/src/modules/usage/UsagePage.test.tsx`
- Delete: `cia-frontend/apps/partner/src/lib/format.smoke.test.ts` (superseded — real tests now cover format)

**Interfaces:**
- Consumes: `useSelectedApp`, `useUsage`, `formatInt`, `formatPercent`, Recharts.
- Produces: StatCards (today total, error rate, success), a daily-history bar/line chart (`history[]`), and a webhook-delivery summary. Null-tolerant via `format.ts`.

- [ ] **Step 1: Write the failing test** (renders today's total + an error-rate percentage)

```tsx
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { configurePortalClient } from '@cia/api-client';
import UsagePage from './UsagePage';
import { AppContextProvider } from '../../app/AppContext';

// Recharts needs a sized container in jsdom; stub ResponsiveContainer.
vi.mock('recharts', async (orig) => {
  const actual = await orig<typeof import('recharts')>();
  return { ...actual, ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div style={{ width: 800, height: 300 }}>{children}</div> };
});

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}><AppContextProvider><UsagePage /></AppContextProvider></QueryClientProvider>);
}
beforeEach(() => { localStorage.clear(); localStorage.setItem('cia.portal.selectedAppId', 'app-1'); configurePortalClient({ baseURL: '', demoMode: true }); });

describe('UsagePage', () => {
  it('shows today total and an error-rate percent', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText(/requests today/i)).toBeInTheDocument());
    expect(screen.getByText('412')).toBeInTheDocument();
    expect(screen.getByText(/%$/)).toBeInTheDocument();
  });
});
```

Run → FAIL.

- [ ] **Step 2: Create `UsageChart.tsx`** (mirror `LossRatioSparkline` dark pattern; colors off `var(--…)`)

```tsx
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import type { UsageHistoryEntryDto } from '@cia/api-client';

export function UsageChart({ history }: { history: UsageHistoryEntryDto[] }) {
  const data = [...history].reverse(); // oldest→newest for the x-axis
  return (
    <div className="h-72 w-full rounded-lg border border-border bg-card p-4">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} margin={{ top: 8, right: 8, bottom: 8, left: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
          <XAxis dataKey="date" tick={{ fontSize: 10, fill: 'var(--muted-foreground)' }} />
          <YAxis tick={{ fontSize: 10, fill: 'var(--muted-foreground)' }} />
          <Tooltip contentStyle={{ background: 'var(--card)', border: '1px solid var(--border)', color: 'var(--foreground)' }} />
          <Bar dataKey="success" stackId="a" fill="var(--primary)" />
          <Bar dataKey="clientError" stackId="a" fill="oklch(0.7 0.15 60)" />
          <Bar dataKey="serverError" stackId="a" fill="oklch(0.6 0.2 25)" />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
```

- [ ] **Step 3: Implement `UsagePage.tsx`**

```tsx
import { useSelectedApp, useUsage } from '@cia/api-client';
import { formatInt, formatPercent } from '../../lib/format';
import { UsageChart } from './UsageChart';

function Stat({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <p className="text-xs uppercase tracking-wide text-muted-foreground">{label}</p>
      <p className={`mt-1 text-2xl font-semibold ${tone ?? 'text-foreground'}`}>{value}</p>
    </div>
  );
}

export default function UsagePage() {
  const { selectedAppId } = useSelectedApp();
  const usageQuery = useUsage(selectedAppId ?? '');
  const u = usageQuery.data;

  return (
    <div className="space-y-6">
      <h1 className="text-xl font-semibold text-foreground">Usage</h1>
      {usageQuery.isLoading && <p className="text-sm text-muted-foreground">Loading…</p>}
      {u && (
        <>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <Stat label="Requests today" value={formatInt(u.today.total)} />
            <Stat label="Success today" value={formatInt(u.today.success)} tone="text-emerald-400" />
            <Stat label="Error rate today" value={formatPercent(u.errorRate)} tone={u.errorRate > 0.1 ? 'text-red-400' : 'text-foreground'} />
          </div>
          <UsageChart history={u.history} />
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <Stat label="Webhook deliveries" value={formatInt(u.webhookDeliveries.totalDeliveries)} />
            <Stat label="Delivered" value={formatInt(u.webhookDeliveries.successfulDeliveries)} tone="text-emerald-400" />
            <Stat label="Failed" value={formatInt(u.webhookDeliveries.failedDeliveries)} tone="text-red-400" />
            <Stat label="Active hooks" value={formatInt(u.webhookDeliveries.activeRegistrations)} />
          </div>
        </>
      )}
    </div>
  );
}
```

- [ ] **Step 4: Delete the smoke test** (`src/lib/format.smoke.test.ts`) and confirm coverage still green.

- [ ] **Step 5: Run test + build** → PASS.

- [ ] **Step 6: Commit**

```bash
git add cia-frontend/apps/partner/src/modules/usage
git rm cia-frontend/apps/partner/src/lib/format.smoke.test.ts
git commit -m "feat(partner): P5 Usage Dashboard — real telemetry StatCards + Recharts history"
```

---

## Task 10: Vercel project + CI workflow + docs

**Files:**
- Create: `cia-frontend/apps/partner/vercel.json`
- Create: `cia-frontend/apps/partner/DEPLOY.md`
- Create: `.github/workflows/vercel-deploy-partner.yml`
- Modify: `cia-log.md` (session entry + backlog reconciliation)
- Modify: `CLAUDE.md` (Frontend Build Queue Phase 3 status + §10 Frontend deployment)
- Modify: `.claude/skills/cia/SKILL.md` (Phase 3 progress note)

**Interfaces:**
- Consumes: the built `apps/partner` app.
- Produces: a deployable Vercel project config + a preview/prod CI workflow mirroring `vercel-deploy-platform.yml` (required secret `VERCEL_PARTNER_PROJECT_ID`; `VERCEL_TOKEN`/`VERCEL_ORG_ID` shared).

- [ ] **Step 1: Read `cia-frontend/apps/platform/vercel.json` and `.github/workflows/vercel-deploy-platform.yml`** as the exact templates.

- [ ] **Step 2: Create `apps/partner/vercel.json`** (mirror platform; partner build command + output + SPA rewrite)

```json
{
  "buildCommand": "pnpm --filter @cia/partner build",
  "outputDirectory": "apps/partner/dist",
  "rewrites": [{ "source": "/(.*)", "destination": "/index.html" }],
  "headers": [
    { "source": "/assets/(.*)", "headers": [{ "key": "Cache-Control", "value": "public, max-age=31536000, immutable" }] }
  ]
}
```

- [ ] **Step 3: Create `.github/workflows/vercel-deploy-partner.yml`** — copy `vercel-deploy-platform.yml` verbatim, changing only: the job/workflow name, `VERCEL_PROJECT_ID: ${{ secrets.VERCEL_PARTNER_PROJECT_ID }}`, and the path filter still `cia-frontend/**`. (Preview on PR, prod on push to `main`.)

- [ ] **Step 4: Create `apps/partner/DEPLOY.md`** — the one-time setup runbook mirroring `apps/platform/DEPLOY.md`: create the Vercel project, set Root Directory to `cia-frontend/`, add env vars (`VITE_API_BASE_URL`, `VITE_DEMO_MODE=true` on the public preview only), add the `VERCEL_PARTNER_PROJECT_ID` secret, dedupe git auto-deploy, verify. Note: partner is **cookie-session**, so real mode additionally requires the BFF's `cia.partner-portal.allowed-origins` to include the partner Vercel origin (a Sub-project A / infra config, not code here).

- [ ] **Step 5: Update `CLAUDE.md`** — Frontend Build Queue Phase 3 table: mark P1/P2/P3/P5 `[x]` with a one-line note; update the Build Progress Summary (Phase 3 now 4/5, P4 out as `partner-portal-sandbox-epic`); add a `apps/partner` bullet to §10 Frontend deployment mirroring the platform bullet (separate Vercel project, `vercel-deploy-partner.yml`, secret `VERCEL_PARTNER_PROJECT_ID`, cookie-session note).

- [ ] **Step 6: Update `.claude/skills/cia/SKILL.md`** — note Phase 3 Partner Portal SPA shipped (P1/P2/P3/P5) consuming the `/portal/**` BFF; P4 Sandbox tracked separately.

- [ ] **Step 7: Update `cia-log.md`** — a `2026-09-03` session entry: files created/modified, the two spec-reconciliation decisions (own credentialed axios client; `/me` grant-shape vs `/portal/apps` rich data), the demo-first mock layer, the Vercel/CI wire-up. **Backlog reconciliation:** no new rows required; confirm `partner-portal-sandbox-epic` (P4) + `partner-portal-realm-eq-schema` remain; "no other backlog change." Note whether the Postman collection needs regeneration (it does **not** — no `/partner/v1/**` change).

- [ ] **Step 8: Full gate**

Run (from `cia-frontend/`):
```bash
pnpm --filter @cia/partner build
pnpm --filter @cia/partner test
pnpm --filter @cia/api-client build
node scripts/check-dto-drift.mjs
```
Expected: all green.

- [ ] **Step 9: Commit**

```bash
git add cia-frontend/apps/partner/vercel.json cia-frontend/apps/partner/DEPLOY.md .github/workflows/vercel-deploy-partner.yml CLAUDE.md .claude/skills/cia/SKILL.md cia-log.md
git commit -m "chore(partner): Vercel project + CI workflow + docs (Phase 3 P1/P2/P3/P5 shipped)"
```

---

## Self-Review

**1. Spec coverage** (Sub-project B design → task):
- B1 Auth (cookie-session provider, demo flag, CSRF) → Tasks 3, 4 (+ Global Constraints).
- B2 API client + mock adapter → Tasks 2, 3.
- B3 Dark shell + app-context selector → Task 5.
- B4 P1/P2/P3/P5 pages → Tasks 6/7/8/9.
- Sequencing (foundation→P1→P2→P3→P5→Vercel) → Tasks 1–2–3–4–5 (foundation), 6, 7, 8, 9, 10.
- Testing (Vitest for provider, hooks, critical flows) → Tasks 1 (harness), 2, 3, 4, 5, 6, 7, 8, 9.
- Vercel project + CI → Task 10.
- Non-goals honored: no BFF change; P4 out; no live realm (Global Constraints + Task 10 log).

**2. Placeholder scan:** page stubs in Task 5 are explicitly temporary and each is rewritten in its owning task (6–9); the smoke test in Task 1 is explicitly deleted in Task 9. No "TBD"/"add error handling"/"write tests for the above" — every test and implementation is spelled out with real field names.

**3. Type consistency:** hook names, schema names, and field names (`targetUrl`/`active`/`clientError`/`serverError`/`partnerAppId`/`clientSecret`/`csrfToken`) are consistent across Tasks 2→3→6→7→8→9 and match the verified BFF contract. `useSelectedApp()` shape is defined in Task 5 and consumed identically in 6–9. `configurePortalClient`/`setPortalCsrfToken`/`isPortalDemoMode` defined in Task 2, used in 3 and 4.

**Known contract reconciliations (carried as Global Constraints, not defects):**
- Portal uses its own credentialed axios (the shared `apiClient` is Bearer-only, no `withCredentials`) but reuses the exported `apiEnvelope` — faithful to spec B2's zod-validation intent.
- `/portal/auth/me` carries grant-shape apps + no `displayName`; the rich selector uses `GET /portal/apps`. `displayName` falls back to `email`.
