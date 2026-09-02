# Partner Portal — BFF + Partner Auth Foundation (Sub-project A) — Design

**Epic:** Frontend Phase 3 — Partner Portal (`apps/partner`, 0/5 builds)
**Sub-project:** A — BFF + partner auth backend foundation (blocks Sub-project B, the 5 FE builds)
**Date:** 2026-08-20
**Branch:** `feat/partner-portal-bff` (own PR)

## Goal

Give the human-facing Partner Portal a **secure, industry-best-practice** way to talk to the existing machine-to-machine `/partner/v1/**` API — where the browser holds **no secret and no token** — by introducing a token-mediating **Backend-for-Frontend (BFF)** plus a partner human-authentication model. Sub-project B (the 5 SPA builds) is built against the `/portal/**` contract this sub-project defines.

## Context & problem

- `/partner/v1/**` (7 controllers + webhooks, all shipped) authenticates via **OAuth2 client-credentials JWT** — a *confidential machine client*. `PartnerScopeFilter` enforces per-route scopes. It is deliberately **not CORS-enabled** (M2M has no browser origin).
- A human-facing browser portal is a fundamentally different consumer: a browser SPA cannot (and must not) hold the confidential `client_secret`, and cannot call `/partner/v1/**` cross-origin without CORS.
- There is **no partner human-authentication story** in the system today. Partner Apps are service-account clients created *inside a tenant realm* by that insurer's System Admin; the secret is "displayed once, never stored." One Insurtech may integrate with several insurers → several partner apps across several tenant realms.

## Architecture of record — token-handler BFF (approved)

The **token-handler / BFF pattern** (IETF "OAuth 2.0 for Browser-Based Applications" BCP; Curity/Auth0/Duende "token handler"). Core invariant: **the browser holds no access token, no refresh token, and no `client_secret` — ever.** A first-party BFF is the confidential client; the browser carries only an opaque, `HttpOnly` cookie session.

```
apps/partner SPA (browser)
   │  1. Human login: Auth Code + PKCE (driven by the BFF)  ───►  Keycloak `partner` realm
   │  2. Session = Secure; HttpOnly; SameSite=Strict cookie (opaque id) — NOT a JWT in JS
   ▼  cookie-authenticated, CORS-enabled to the SPA origin only
Partner Portal BFF  (cia-partner-portal-bff, /portal/**)
   │  • Stores the human's tokens SERVER-SIDE (Redis session)
   │  • Resolves the human → their Partner App(s) via public.partner_portal_grant
   │  • Mints a partner-app-scoped client-credentials token in the app's TENANT realm,
   │    fetching the client_secret from Keycloak just-in-time (never stored by us)
   │  • Proxies management + "try-it" calls, attaching the real Bearer token itself
   ▼  Bearer token (server-to-server, no CORS needed)
/partner/v1/**   (UNCHANGED — still M2M; the BFF is just another confidential caller)
```

## Components

### A1 · Identity & realm model

- **New `partner` Keycloak realm** — cross-tenant, mirroring the existing `platform` realm (SP1). Config `cia.partner-portal.realm` (default `partner`), `PartnerPortalRealmProperties` in `cia-auth` (parallel to `PlatformRealmProperties`).
- **Public SPA client `cia-partner-portal`** — Auth Code + PKCE(S256), realm-scoped redirect URIs / web origins (`cia.partner-portal.redirect-uris`). Provisioned idempotently by extending `KeycloakTenantProvisioner` (create-then-reconcile, direct-grants disabled), mirroring the S139 back-office client.
- **Realm role `PARTNER_DEVELOPER`** (`PartnerPortalRoles.ALL`, distinct from `BootstrapRoles`/`PlatformRoles` — never seeded into a tenant or platform realm).
- **`public.partner_portal_grant`** — new registry table in the `public` schema (cross-tenant, like `public.tenants`):

  | column | type | note |
  |---|---|---|
  | `id` | UUID PK | |
  | `partner_user_id` | UUID | the `sub` of the human in the `partner` realm |
  | `partner_user_email` | text | denormalised, for admin listing |
  | `tenant_schema` | text | which insurer's schema the app lives in (soft ref to `public.tenants`) |
  | `partner_app_id` | UUID | the Partner App in that tenant (soft cross-schema ref — no DB FK, registry-style) |
  | `role` | text | `MANAGER` (manage app: webhooks, rotate, usage) vs `VIEWER` (read-only) |
  | `created_at` / `created_by` / `deleted_at` | | standard, soft-delete |

  Unique on `(partner_user_id, tenant_schema, partner_app_id)` where `deleted_at IS NULL`.

- **Grant provisioning** — a new "Invite developer" action in the tenant's back-office **Partner Management** (`POST /api/v1/partner-apps/{id}/developers` — internal, System-Admin-gated) writes a grant row (and, when the partner realm is live, ensures the `partner`-realm user exists via Keycloak admin). *Demo-first: the endpoint + table land now; the Keycloak-user side is exercised only when the `partner` realm is provisioned.*

### A2 · BFF authentication flow (token-handler)

The **BFF is the OAuth client** (not the SPA). Endpoints under `/portal/auth`:

- `GET /portal/auth/login` — BFF initiates Auth Code + PKCE against the `partner` realm (generates + stores the PKCE verifier + state server-side), 302 to Keycloak.
- `GET /portal/auth/callback` — BFF exchanges the code for tokens **server-side**, creates a Redis session, sets `Set-Cookie: cia_portal_session=<opaque>; Secure; HttpOnly; SameSite=Strict; Path=/`, 302 back to the SPA.
- `GET /portal/auth/me` — returns the developer profile (name/email) + the apps they may manage (from `partner_portal_grant`, enriched with Keycloak client metadata). 401 if no valid session.
- `POST /portal/auth/logout` — clears the Redis session + cookie + Keycloak RP-initiated logout.

**Session store: Redis** (already in the stack for partner rate-limiting → survives the 3+ API replicas). Sessions hold the human's tokens + a short idle TTL + absolute TTL; opaque id only in the cookie. **In-memory fallback for dev** (`@ConditionalOnProperty`, mirroring the rate-limit store toggle).

**Fold-in (clears backlog `partner-ratelimit-redis-distributed`):** we are introducing a real Redis dependency here, so in the same epic the **partner rate limiter is migrated from its per-replica in-memory `ConcurrentHashMap` buckets to Redis-backed distributed buckets** (bucket4j-redis), behind the same `@ConditionalOnProperty` store toggle (in-memory stays the dev/IT default). This makes per-client rate limits correct across the 3+ API replicas — the exact gap that backlog row named — with no extra infra beyond the Redis we're already adding.

### A3 · Token minting & secret handling (Keycloak-admin JIT — approved)

To call `/partner/v1/**` as a partner app, the BFF needs a partner-app-scoped **client-credentials** token in that app's **tenant** realm.

- The BFF uses the **existing Keycloak admin client** (`cia-setup` admin infrastructure, already used for user CRUD) to **fetch the partner client's secret from Keycloak just-in-time**, then performs the client-credentials grant against the tenant realm's token endpoint. The secret is **never persisted by us and never sent to the browser** — Keycloak remains the single source of truth.
- Minted tokens are **cached server-side per (tenant, partner_app)** with a margin under `exp` (mirrors the realm-decoder caching in `TenantIssuerJwtAuthenticationManagerResolver`), so we don't re-mint on every call.
- **Authorization gate:** before minting/proxying for app *X*, the BFF asserts the session's `partner_user_id` holds an active `partner_portal_grant` for *X* (and, for mutations, `role = MANAGER`). Defense-in-depth beyond the cookie.

### A4 · The `/portal/**` BFF surface

All cookie-authenticated (session), **CORS-enabled to the portal SPA origin only** (`cia.cors.allowed-origins` gains the portal URL; a dedicated policy, since `/partner/**` stays CORS-free).

- `GET /portal/apps` — apps this human manages: client_id, scopes, rate tier, status, tenant label (grant table + Keycloak admin read). Drives the **app-context selector**.
- **Management** (per app, `MANAGER`):
  - `/portal/apps/{id}/webhooks` — proxies `/partner/v1/webhooks` CRUD with the minted token.
  - `GET /portal/apps/{id}/credentials` — client_id + scopes (never the secret); `POST .../credentials/rotate` — rotate the secret via Keycloak admin, returns the new secret **once** (BFF response, not stored).
  - `GET /portal/apps/{id}/usage` — request counts / error rates / webhook-delivery logs (existing partner usage data).
- **Try-it** (`MANAGER` or `VIEWER`): `ANY /portal/apps/{id}/try/{path:.*}` — proxies an arbitrary `/partner/v1/{path}` with the minted token; powers the P2 API Explorer. Method + body + query forwarded; response relayed verbatim (status + body). Scope errors (403 from `PartnerScopeFilter`) surface as-is so the developer learns which scope they lack.

### A5 · Module placement

New **`cia-partner-portal-bff`** Maven module — depends on `cia-common`, `cia-auth` (JWT/realm props), `cia-partner-api` (its `Partner*Response` DTO types only, for typed deserialization of proxied responses), `cia-setup` (Keycloak admin client + PartnerApp). Keeps the M2M `cia-partner-api` free of browser/session/cookie concerns. Assembled into `cia-api` like the other business modules.

**Proxy fidelity (pinned):** both the management proxy (webhooks) and the try-it proxy make a **real HTTP call to `/partner/v1/**`** with the minted Bearer token — never a direct domain-service call that would bypass `PartnerScopeFilter` + rate limiting. This makes the portal behave *identically* to a genuine external integration (same scope errors, same 429s), which is the whole point of a developer portal. The `cia-partner-api` dependency is for response DTO types, not for short-circuiting the HTTP path.

### A6 · Demo-first sequencing

Increment 1 ships: the `cia-partner-portal-bff` module + the `/portal/**` contract + the `partner_portal_grant` migration + the gated `partner`-realm bootstrap (a `PartnerPortalBootstrapRunner`, `@ConditionalOnProperty` off by default, mirroring `PlatformBootstrapRunner`) + the "invite developer" internal endpoint. The **Sub-project B SPA builds against this contract in demo mode** (`DevAuthProvider`, mocked `/portal/**` data) until the `partner` realm is provisioned — exactly the state back-office and platform run in today. Nothing here provisions a live realm or requires real Keycloak infra to build/test.

### A7 · Partner API request telemetry (built now — no deferral)

Verified: today only **webhook delivery** is persisted (`webhook_delivery_logs`, joinable per app). There is **no** per-app API request-count / error-rate telemetry anywhere (the rate limiter's buckets are ephemeral in-memory tokens). Rather than ship a half-real Usage Dashboard, we build the missing pipeline in this epic so P5 is fully real end-to-end.

- **`PartnerRequestMetricsFilter`** — a new `OncePerRequestFilter` on `/partner/v1/**`, ordered **after** authentication (so `client_id` + tenant are resolved) and around the request so the response status is known. On each request it increments per-app counters: `total`, `success` (2xx), `client_error` (4xx), `server_error` (5xx).
- **Live store: Redis rollups** (reusing the Redis introduced for sessions) — keys `partner:usage:{tenant}:{clientId}:{yyyy-MM-dd}` (hash of the four counters), atomic `HINCRBY`, TTL ~95 days. Off the DB hot path.
- **Durable history: `partner_request_daily`** (tenant schema: `partner_app_id`, `usage_date`, `total`, `success`, `client_error`, `server_error`, unique on `(partner_app_id, usage_date)`) — a lightweight **daily scheduled flush** (Temporal cron, mirroring the existing `PdfDownloadLogRetentionWorkflow` cron pattern) persists each day's Redis rollup into the table so history survives the Redis TTL.
- **`/portal/apps/{id}/usage`** composes **today's live counts (Redis) + historical daily rows (table) + webhook-delivery success/failure (existing `webhook_delivery_logs`)**. Error rate = `(client_error + server_error) / total`.
- **Dev/IT fallback:** with the in-memory store toggle, the filter increments an in-memory map and the usage endpoint reads it (same contract, no Redis needed for tests).

## Security properties

- **Secret confidentiality** — `client_secret` lives only in Keycloak; the BFF retrieves it server-side JIT and never returns it to the browser (except a freshly-rotated secret, shown once in the rotate response).
- **XSS-hardened** — no access/refresh token in JS; a script injection can at most ride the `SameSite=Strict` cookie for the live session, bounded by idle + absolute TTLs.
- **CORS integrity** — the browser only ever talks to the first-party BFF; `/partner/v1/**` keeps its deliberate no-CORS M2M posture.
- **Least privilege + accountability** — every `/portal/**` action is gated by an active `partner_portal_grant` (role-checked) and tied to an authenticated developer for audit; the minted token carries only the partner app's own scopes.
- **CSRF** — state-changing `/portal/**` use `SameSite=Strict` + a double-submit CSRF token issued at login (cookie is not sufficient alone for cross-site POST protection on older browsers).

## Scope

**In scope (Sub-project A):** the `cia-partner-portal-bff` module; `/portal/**` (auth, apps, webhooks proxy, credentials + rotate, usage, try-it proxy); the `partner` realm + `cia-partner-portal` client provisioning (idempotent, gated); `PartnerPortalRoles`; `public.partner_portal_grant` + the internal "invite developer" endpoint; Redis (+ in-memory) session store; Keycloak-admin JIT secret retrieval + per-app token cache; CORS policy for the portal origin; **partner request telemetry (A7): `PartnerRequestMetricsFilter` + Redis rollups + `partner_request_daily` table + daily flush cron**; **Redis-backed distributed rate limiting (clears `partner-ratelimit-redis-distributed`)**; ITs.

**Non-goals:** the SPA itself (Sub-project B); changing `/partner/v1/**` request/response *behavior* (the metrics filter + Redis rate-limit are transparent additions — no contract change); a live `partner` realm deployment (gated/off, provisioned at infra time like platform/back-office); billing/quota UI; partner self-signup (invite-only in v1 — a tenant admin grants access; **confirmed**).

## Backlog cleared by this sub-project

Per the standing "build complete, reduce backlog" directive, this epic folds in and removes two existing backlog rows rather than deferring:

- `partner-ratelimit-redis-distributed` (P3) — the Redis-backed distributed rate limiter (A2 fold-in).
- The would-be `partner-usage-telemetry` follow-up is **never created** — the telemetry (A7) is built here instead of logged.

## Testing strategy

- **BFF auth flow IT** — `TestWorkflowEnvironment`-style is N/A; use MockMvc + a stubbed Keycloak (or Testcontainers Keycloak via `KeycloakItSupport`) to prove: login sets an `HttpOnly` session cookie; `/portal/auth/me` returns the granted apps; no token/secret ever appears in a response body.
- **Grant authorization IT** — a session without a `partner_portal_grant` for app X gets 403 on `/portal/apps/{X}/**`; `VIEWER` blocked from mutations.
- **Token-minting unit test** — the Keycloak-admin JIT retrieval + per-app cache (mock the admin client + token endpoint); assert the secret never leaves the BFF.
- **Proxy IT** — `/portal/apps/{id}/try/products` proxies to `/partner/v1/products` and relays status + body; a scope-denied call relays 403.
- **Snapshot/guard** — the internal `/api/v1/partner-apps/{id}/developers` endpoint joins the `InternalApiSnapshotIT` surface.
- Full reactor `mvn verify` green.

## Resolved decisions (were open questions)

1. **Grant provisioning UX — resolved: invite-only in v1.** A tenant System Admin grants a developer access (grant row + Keycloak-user ensure when the realm is live). No partner self-signup in v1.
2. **Usage data source — resolved: build the telemetry now (A7).** Verified that only webhook-delivery data exists; the request-count/error-rate pipeline is built in this epic (A7) rather than deferred, so P5 is fully real end-to-end.

## Acceptance criteria

1. The browser never receives an access token, refresh token, or `client_secret` (except a rotate response's one-time new secret) — proven by the auth-flow IT.
2. `/portal/**` is cookie-authenticated + CORS-enabled to the portal origin; `/partner/v1/**` is unchanged and stays no-CORS.
3. The BFF mints a partner-app-scoped token by fetching the secret from Keycloak JIT (never persisted), gated by an active `partner_portal_grant`.
4. The `partner` realm + `cia-partner-portal` client provisioning is idempotent and gated (off by default; dev + IT suite never provision).
5. Full `mvn verify` green; the internal developer-invite endpoint is in the OpenAPI snapshot.
6. No change to `/partner/v1/**` request/response behavior or its (absent) CORS posture (the metrics filter + Redis rate-limit are transparent).
7. `/portal/apps/{id}/usage` returns **real** request counts + error rate (from `PartnerRequestMetricsFilter` → Redis/`partner_request_daily`) and **real** webhook-delivery history (`webhook_delivery_logs`) — no mocked panels.
8. The partner rate limiter is Redis-backed and correct across replicas (backlog `partner-ratelimit-redis-distributed` cleared); in-memory stays the dev/IT default.
