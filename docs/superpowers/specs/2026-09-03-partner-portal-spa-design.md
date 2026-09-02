# Partner Portal SPA (Sub-project B) — Design

**Epic:** Frontend Phase 3 — Partner Portal (`apps/partner`)
**Sub-project:** B — the human-facing SPA, built against Sub-project A's `/portal/**` BFF contract (A shipped on `main`, PR #70)
**Date:** 2026-09-03
**Branch:** `feat/partner-portal-spa` (own PR)
**Depends on:** Sub-project A spec (`docs/superpowers/specs/2026-08-20-partner-portal-bff-auth-design.md`) — the BFF whose contract this SPA consumes.

## Goal

Build the Partner Portal single-page app (`apps/partner`, dark mode, port 5174) so an Insurtech developer can log in, manage their Partner App(s), explore the API, manage webhooks, and see real usage — driving the existing token-handler BFF (`/portal/**`) with the browser holding no secret or token. Ships **demo-first** (mock data on the public URL) until the live `partner` realm is provisioned, exactly like back-office and platform.

## Context

- Sub-project A built the BFF and the `/portal/**` surface (on `main`): `GET /portal/auth/{login,callback,me}` + `POST /portal/auth/logout`; `GET /portal/apps`; per-app `GET/POST /portal/apps/{id}/webhooks` + `DELETE .../webhooks/{whId}`; `GET /portal/apps/{id}/credentials` + `POST .../credentials/rotate`; `GET /portal/apps/{id}/usage`; and the `ANY /portal/apps/{id}/try/{*path}` proxy. All are **cookie-authenticated** (the BFF `cia_portal_session` httpOnly cookie), **not** bearer/Keycloak-JS.
- The `apps/partner` scaffold today is 3 files (`App.tsx`, `main.tsx`, `app/globals.css`) — a "coming soon" placeholder. It already depends on `@cia/ui`, `@cia/api-client`, `@tanstack/react-query`, `react-router-dom`; it does **not** depend on `@cia/auth` (correct — it uses the BFF cookie-session, not the Keycloak JS adapter).
- The live `partner` realm + `cia-partner-portal` client are **gated off** by default, so the real BFF session path can't run locally without provisioning — the SPA must therefore be built **mock-first**, with the real `/portal/**` path used when a session exists.

## Architecture

### B1 · Auth — cookie-session (not Keycloak-JS)

A new **`PortalAuthProvider`** (app-local under `apps/partner/src/app/auth/`, NOT `@cia/auth` — that package is Keycloak-JS-based and doesn't fit the token-handler cookie model):

- **Real path:** a `useSession()` hook fetches `GET /portal/auth/me` (`credentials:'include'`). A valid session returns `{ partnerUserId, email, displayName, csrfToken, apps[] }`. "Log in" does `window.location = <API_BASE>/portal/auth/login` (the BFF drives Auth Code + PKCE). "Log out" `POST /portal/auth/logout` then redirects. On `401` from `/me` the app shows a login screen.
- **Demo path:** demo mode is **flag-driven at build time** (`import.meta.env.DEV` OR `VITE_DEMO_MODE==='true'`) — a single `demoMode` constant computed once in `main.tsx`, exactly like back-office's `main.tsx` decides `AuthProvider` vs `DevAuthProvider`. When `demoMode` is true, `PortalAuthProvider` supplies a **mock session** (a fake developer + 1–2 mock apps) and the api-client routes to the mock adapter — no `/portal/**` call is made. A "Demo" banner is shown. This is the only way the app is usable until the `partner` realm is live. (No runtime "is a session reachable" probe — the flag decides real vs mock, so a page's data source is unambiguous.)
- **CSRF:** the `csrfToken` from `/me` is attached as the `X-CSRF-Token` header on every mutating `/portal/**` request (the BFF enforces double-submit).

### B2 · API client + demo/mock layer

A new **`@cia/api-client/src/modules/portal.ts`** module (shared package, consistent with `finance-closures.ts`): zod schemas for every `/portal/**` DTO + typed react-query hooks (`useApps`, `useCredentials`, `useRotateSecret`, `useWebhooks`, `useCreateWebhook`, `useDeleteWebhook`, `useUsage`, `useTryIt`). All fetches use `credentials:'include'` and attach `X-CSRF-Token` on mutations, validated through the existing `validatedGet`/`validatedList`/`validatedPost` helpers.

A **mock adapter** (`portal-mocks.ts`) returns canned, zod-valid `/portal/**` responses; the hooks route to it when the app is in demo mode. Same schemas validate real + mock, so a page can't diverge from the contract. (This mirrors how back-office was built FE-first with mocks, but the mock layer is more central here because the real path needs infra.)

### B3 · Shell (dark mode) + app-context

- A dark **`AppShell`** using the partner charcoal tokens already in `@cia/ui` (Partner background `oklch(0.15 0.012 240)`), Sidebar + Topbar, mirroring back-office's shell structure but themed dark.
- An **app-context selector** in the Topbar: the developer picks which Partner App they are managing, from `GET /portal/apps`. The selection (persisted in `localStorage`, per-viewer) scopes every per-app page (`{id}` in the endpoints). A developer with one app auto-selects it; with none, an empty state.
- Lazy-loaded routes per build; a "Demo" banner when in demo mode.

### B4 · The four build pages

| Build | Page(s) | `/portal/**` used | Notes |
|---|---|---|---|
| **P1 Authentication** | Login/session gate; **Credentials** page (client_id + granted scopes; "Rotate secret" → shows the new secret **once**, copy-to-clipboard, never re-shown); scope overview | `/portal/auth/*`, `/portal/apps/{id}/credentials`, `POST .../credentials/rotate` | The rotate response's one-time secret is the only secret the browser ever sees; render it in a dismissible reveal, never persist it. |
| **P2 API Explorer** | Interactive request builder (method + path under `/partner/v1/` + optional JSON body) → fires the try-it proxy; response viewer shows **verbatim** status + body, including a scope-`403` (with which scope is missing) and a `429` with rate-limit headers | `ANY /portal/apps/{id}/try/{*path}` | Behaves exactly like a real integration — that's the point. A curated list of common `/partner/v1` endpoints as quick-fills. |
| **P3 Webhook Management** | Register (URL + event types) / list / delete webhooks; a **delivery-log** view (success/failure per event, http status, timestamp) | `/portal/apps/{id}/webhooks*`; delivery history from `/portal/apps/{id}/usage` | Delivery logs come from the usage endpoint's `webhookDeliveries`. |
| **P5 Usage Dashboard** | Request counts, error rate, and webhook-delivery history — the fully-real telemetry A built (no mocked panels in real mode) | `GET /portal/apps/{id}/usage` | Charts via the app's charting lib; degrade gracefully with the null-tolerant formatters. |

## Sequencing & delivery

**One spec/plan**, foundation-first task order:
1. **Foundation** — `apps/partner` app config (Vite/Tailwind dark theme, router, react-query, Vitest), `PortalAuthProvider` (real + demo mock), `@cia/api-client/modules/portal.ts` + `portal-mocks.ts`, `AppShell` + app-context selector.
2. **P1** Authentication + Credentials.
3. **P2** API Explorer.
4. **P3** Webhook Management.
5. **P5** Usage Dashboard.
6. **Wire-up + Vercel** — the `apps/partner` build/deploy (a separate Vercel project, mirroring the platform-console one-time setup), demo-mode env, and CI (`vercel-deploy-partner.yml` mirroring the platform workflow).

Demo-first: the public URL runs the mock layer + demo auth until the `partner` realm is provisioned. Every mutation "succeeds locally but does not persist" in demo mode (same honest caveat as back-office).

## Scope

**In scope (Sub-project B):** the `apps/partner` SPA — dark shell + app-context; `PortalAuthProvider` (cookie-session real + demo mock); `@cia/api-client/modules/portal.ts` + mock adapter; the four build pages (P1, P2, P3, P5); the app's Vitest setup + tests for the auth provider, the api-client hooks (mock), and critical page flows; the Vercel project wiring + CI workflow.

**Non-goals:**
- **P4 Sandbox** — no backend sandbox exists; it is a separate future epic (backend sandbox subsystem + the P4 UI). Tracked as backlog `partner-portal-sandbox-epic`.
- No changes to the BFF / `/portal/**` contract (consume it as-is; if a small contract gap is found, log it, don't expand A here).
- No live `partner` realm provisioning (infra step, gated).
- The internal "invite developer" flow is back-office's surface (Sub-project A), not this SPA.

## Testing

- **Vitest** in `apps/partner` (mirror the back-office Vitest infra): unit tests for `PortalAuthProvider` (real 200/401 branches via mocked fetch; demo-mode mock session), the portal api-client hooks (against the mock adapter), and critical flows (credentials rotate reveals once; try-it renders a verbatim 403; app-context selection scopes the per-app queries).
- **CI guards:** `tsc --noEmit`, `check-api-wiring.sh` (no `console.log`, no stray mocks in module files beyond the sanctioned `portal-mocks.ts` demo adapter — add an `// allow-mock:` opt-out where needed), `check-dto-drift.mjs` if any `*Dto` maps to a backend `*Response` (portal DTOs are zod-first; map/allowlist as needed), and the app's Vitest.
- No backend changes ⇒ no `mvn verify` needed for this sub-project; the frontend CI + a green `pnpm --filter @cia/partner build` + Vitest is the gate.

## Open questions (for spec review)

1. **Charting library for P5** — back-office uses Recharts. Reuse it for the partner Usage Dashboard (consistency) unless you prefer a lighter dark-mode-first option. Default: **Recharts** (already in the monorepo).
2. **API base + cross-origin in real mode** — in production the SPA (Vercel) and the BFF (`/portal/**`, API host) are different origins; the BFF's `/portal/**` CORS (A, Task 11) allows the portal origin with credentials. Confirm the SPA reads the API base from `VITE_API_BASE_URL` (same var name as back-office) and sends `credentials:'include'`. (Demo mode never crosses origins — it's all mock.)

## Acceptance criteria

1. `apps/partner` builds (`pnpm --filter @cia/partner build`) and runs in demo mode (`import.meta.env.DEV` / `VITE_DEMO_MODE`) with a mock session + mock `/portal/**` data — every page renders, no live BFF required.
2. In real mode, the SPA authenticates via the BFF cookie-session (`/portal/auth/*`), never handles a token or the app secret except the one-time rotate reveal, and sends `X-CSRF-Token` on mutations.
3. The four builds (P1/P2/P3/P5) are wired to their `/portal/**` endpoints via zod-validated hooks; P2's try-it relays verbatim status/body (incl. 403/429); P5 shows real usage in real mode.
4. App-context selection scopes every per-app query; a single-app developer auto-selects; none → empty state.
5. Vitest + `tsc` + api-wiring + dto-drift guards green; `apps/partner` Vercel project + CI workflow added.
6. No BFF/`/portal/**` contract change; P4 Sandbox tracked as a separate epic (`partner-portal-sandbox-epic`).
