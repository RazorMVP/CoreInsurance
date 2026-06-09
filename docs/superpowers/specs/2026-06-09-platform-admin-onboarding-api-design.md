# Platform-Admin Auth + Tenant-Onboarding API (SP1) — Design

> **Status:** Approved (brainstorm complete 2026-06-09). Next: writing-plans.
> **Epic:** Tenant-Onboarding API + Platform-Admin UI. This is **sub-project 1 of 2** (backend); SP2 is the `apps/platform` UI that consumes this API. Aligns with the planned Module 12 "Phase 6 — cross-tenant platform admin view."

## Goal

Introduce a cross-tenant **Platform-Admin plane**: a dedicated `platform` Keycloak realm + a `SUPER_ADMIN` identity that lives *above* the tenants, and a `/api/v1/platform/**` REST API to onboard and manage tenants at runtime (no restart) by wrapping the existing `TenantProvisioningService.provision()`. Backend only. **No destructive tenant delete.**

## Why this is its own slice

The provisioning pipeline (Slice A) and the eventual UI (SP2) are the easy parts. The load-bearing risk is the **auth boundary**: this introduces the system's first identity that operates *outside* the realm-per-tenant isolation model. Getting it wrong either blocks the super-admin or punches a hole in the tenant isolation the compliance story rests on. That risk gets a focused spec.

## Architecture

Two planes. The **platform plane** (the super-admin, *above* all tenants) drives the **tenant plane** (the insurers) through the Slice-A provisioning pipeline. The request pipeline keeps them isolated: a platform-realm token is scoped to `public` and gated by `SUPER_ADMIN`; a tenant-realm token must clear the activation allowlist gate.

```
┌─ PLATFORM PLANE (above all tenants) ─────────────────────────────────────────┐
│  Keycloak `platform` realm             apps/platform SPA  (SP2 — next slice)  │
│   • SUPER_ADMIN role                    • cia-platform client (auth-code+PKCE)│
│   • super-admin user(s)                                                       │
│        ▲ bootstrapped (gated, on boot)             │ Authorization: Bearer JWT│
│        └─ PlatformBootstrapRunner ──reuses──▶ KeycloakTenantProvisioner       │
└────────────────────────────────────────────────────│─────────────────────────┘
                                                      ▼
┌─ Spring Boot API — request pipeline ──────────────────────────────────────────┐
│  JwtAuthMgrResolver  ─▶  TenantContextFilter         ─▶  @PreAuthorize          │
│  (base-URL trust;        • platform realm → ctx=public   hasRole('SUPER_ADMIN') │
│   platform trusted)      • tenant realm → allowlist gate         │             │
│                             (TenantActivationLookup:             ▼             │
│                              cached · evict-on-suspend)  PlatformTenantController│
│                                                          /api/v1/platform/      │
│                                                              {tenants, audit}   │
│                                                                  │              │
│                                                                  ▼              │
│                                                          PlatformTenantService  │
│                                                           • uniqueness → 409     │
│                                                           • gen 1-time password  │
│                                                           • suspend / activate    │
│                                                           • audit (log + table)   │
│                                                           • provision(spec) ──┐  │
└───────────────────────────────────────────────────────────────────────────┼──┘
        schema-qualified JDBC (reads/writes)                                  │
                 │                                                            │
                 ▼                                                            ▼
   ┌─ public schema ─────────────┐                ┌─ TENANT PLANE (Slice A) ────────────┐
   │  tenants         (registry) │◀── upsert ─────│ TenantProvisioningService:          │
   │  platform_audit_log  (V67)  │    (last step) │  ensureSchema → migrate(Flyway) →   │
   └─────────────────────────────┘                │  seed → Keycloak tenant realm/role/ │
                 ▲                                 │  first-admin → registry.upsert      │
                 └── TenantActivationLookup reads  │     └─▶ tenant_<x> schema +          │
                     `active` (allowlist gate)     │         <x> realm + tenants row      │
                                                   └──────────────────────────────────────┘
```

**Reading it:** the super-admin (platform realm) calls `/api/v1/platform/*`; the resolver trusts the platform realm, `TenantContextFilter` scopes the request to `public` (not a `"platform"` schema), and `SUPER_ADMIN` authorizes it. `PlatformTenantService` checks uniqueness against the registry, calls the **Slice-A** `provision(spec)` to stand up the tenant's schema + realm + first-admin, and records the action in `public.platform_audit_log`. Independently, on **every tenant request**, the allowlist gate reads `public.tenants.active` (cached) so a suspended tenant is rejected at 401 — the platform realm is exempt.

## Decisions (brainstorm Q&A)

| # | Decision | Choice |
|---|---|---|
| Q1 | Where the super-admin identity lives | **Dedicated `platform` Keycloak realm** (alongside tenant realms; separate from Keycloak's `master`). |
| Q2 | Bootstrap of the platform realm + first super-admin | **Gated `PlatformBootstrapRunner`** (`@ConditionalOnProperty(cia.platform.bootstrap.enabled=true)`, off by default), reusing `KeycloakTenantProvisioner`. |
| Q3 | Cross-tenant role name | **`SUPER_ADMIN`** (new, distinct from the existing tenant-scoped `PLATFORM_ADMIN`). |
| Q4 | Onboarding execution model | **Synchronous REST** (`provision()` inline; 201 with one-time creds). |
| Q5 | Lifecycle scope | **Onboard + list/get + suspend/activate + the allowlist gate.** No hard delete. |
| Q6 | First-admin credential handoff | **Server-generated temp password, returned once** (forced `UPDATE_PASSWORD`). |
| §6 | Audit | **Both**: structured-log audit **and** a queryable `public.platform_audit_log` table + `GET /platform/audit`. |
| (b) | Allowlist gate on the auth hot path | **Include it**, cached, with **explicit eviction on suspend/activate** + short TTL backstop. |

## Ground truth (verified against `main`, 2026-06-09)

- `TenantProvisioningService.provision(TenantSpec)` (cia-api) is idempotent, fail-fast, and **self-contained** — operates on explicit schemas + schema-qualified `public.tenants`; does **not** read `TenantContext`. Steps: `migrator.ensureSchema` → `migrator.migrate` → `seeder.seed` → `keycloak.provisionTenantAuth` → `registry.upsert` (registry **last**).
- `KeycloakTenantProvisioner` (cia-setup) has `provisionTenantRealm` + `ensureRealm`/`ensureBackOfficeClient`/`ensureUnmanagedAttributePolicy` — reusable to provision the `platform` realm + a `cia-platform` client.
- `TenantIssuerJwtAuthenticationManagerResolver` (cia-auth): base-URL trust — any `{KEYCLOAK_URL}/realms/{realm}` issuer is trusted; per-realm decoders cached lazily. The `platform` realm is therefore already trusted; no trust-model change.
- `TenantContextFilter` (cia-auth): derives the tenant from the validated `iss` realm (fallback `tenant_id` claim); sets `TenantContext` + MDC; clears in `finally`.
- `SecurityConfig` (cia-auth): `.anyRequest().authenticated()`; permitAll for `/actuator/health/**`, `/actuator/info`, `/api/v1/auth/login/failed`. `JwtAuthConverter` maps `realm_access.roles` → `ROLE_<UPPERCASE>`.
- `TenantRegistry` (cia-api): schema-qualified JDBC against `public.tenants` (`schema_name, display_name(name), subdomain, active, created_at`). No `TenantContext` reliance.
- **Flyway:** main flyway (`application.yml`) runs `classpath:db/migration` V1..Vn against `schemas: public`; the per-schema migrator runs the same set against each tenant schema baselined past V1/V2. Net: V3+ tables already exist in **both** `public` and every tenant schema. A standard `V67` migration for `platform_audit_log` follows this convention (used in `public`, stray-but-harmless in tenant schemas — exactly like every existing table).
- **`PLATFORM_ADMIN` already exists** as a *tenant-realm* role (Module 12 `BackfillAdminPage`). The new cross-tenant role MUST be distinct (`SUPER_ADMIN`) so a tenant user can never satisfy a platform-endpoint authority check.

## Components

### 1. Auth foundation (cia-auth + cia-setup)

- **`platform` realm** — name from `cia.platform.realm` (default `platform`). Holds super-admin users, a `SUPER_ADMIN` realm role, and a `cia-platform` SPA public client (auth-code + PKCE(S256), redirect URIs from config) for SP2.
- **Trust** — unchanged; the resolver already trusts the platform realm (base-URL).
- **Non-tenant-scoping** — `TenantContextFilter`: when the validated `iss` realm equals `cia.platform.realm`, set `TenantContext` to `public` (NOT the literal `"platform"`), so any incidental JPA on a platform request resolves against the registry schema, never a nonexistent `"platform"` schema. The platform realm is recognized via injected config, not hardcoded.
- **Authz** — `/api/v1/platform/**` requires `hasRole('SUPER_ADMIN')`. Because no tenant realm mints `SUPER_ADMIN`, the role implies the platform realm. Defense-in-depth: the platform controllers additionally assert the authenticated `iss` realm == `cia.platform.realm` (a small `@PreAuthorize`-adjacent check or a method in the service), rejecting otherwise with 403.
- **Allowlist gate (`TenantActivationLookup`)** — drains backlog `jwt-resolver-registry-allowlist`:
  - SPI `TenantActivationLookup` with `boolean isActive(String realm)`, declared where `cia-auth` can reach it (cia-auth package or cia-common); JDBC impl in `cia-api` over `public.tenants` (`SELECT active FROM public.tenants WHERE schema_name = ?`).
  - **Gated by `cia.platform.tenant-allowlist.enabled` (default `false`)** — so dev + the IT suite (which don't populate `public.tenants`) are unaffected; set `true` in prod. When enabled, consulted in the tenant-resolution path (in/after `TenantContextFilter`): for a **tenant-realm** token, require a `public.tenants` row with `active = true`, else **401** (a 401 short-circuit / `InvalidBearerTokenException`). The **`platform` realm is always exempt** (not a tenant row — allowed whenever it equals the configured platform realm, gate on or off).
  - **Caching:** results cached (e.g., Caffeine/`ConcurrentHashMap` + short TTL, default 60s). `PlatformTenantService.suspend/activate` **explicitly evicts** the affected realm's cache entry so suspension/reactivation takes effect immediately; the TTL is a backstop for out-of-band registry changes.

### 2. Platform bootstrap (cia-api + cia-setup)

`PlatformBootstrapRunner` — `@ConditionalOnProperty(cia.platform.bootstrap.enabled=true)`, **off by default**; requires `cia.keycloak.admin.enabled=true` (fail-fast otherwise, mirroring `TenantBootstrapRunner`). On boot, idempotently ensures: the `platform` realm, the `SUPER_ADMIN` realm role, the `cia-platform` SPA client, and the first super-admin user (`cia.platform.bootstrap.{admin-username, admin-email, admin-temp-password}` → temp password with forced `UPDATE_PASSWORD`; `@ToString.Exclude` on the password). Reuses/extends `KeycloakTenantProvisioner` with a `provisionPlatformRealm(...)` method. `PlatformBootstrapProperties` (`@ConfigurationProperties("cia.platform")`). Adding a super-admin = config + restart; a runtime "invite super-admin" API is out of scope.

### 3. Tenant-onboarding + lifecycle API (cia-api, `com.nubeero.cia.api.platform`)

`PlatformTenantController` — base `/api/v1/platform/tenants`, class-level `@PreAuthorize("hasRole('SUPER_ADMIN')")`.

| Method | Path | Behavior |
|---|---|---|
| POST | `/tenants` | Body `OnboardTenantRequest { schema, realm?, displayName, subdomain, adminUsername, adminEmail }` (`realm` defaults to `schema`). Validate `schema` via `TenantSchemas.validate`; reject if `schema` or `subdomain` already in `public.tenants` (**409** `TENANT_ALREADY_EXISTS`). Generate a strong temp password (`SecureRandom`, ≥16 chars, charset satisfying Keycloak policy + `PiiKeyValidator`-style safety). Call `provision(spec)` **synchronously**. **201** `OnboardTenantResponse { tenant: TenantSummary, firstAdmin: { username, email, temporaryPassword } }` (password present exactly once; never persisted/logged). |
| GET | `/tenants` | List `TenantSummary[]` from `public.tenants` (`schema, displayName, subdomain, active, createdAt`). |
| GET | `/tenants/{schema}` | One `TenantSummary` (**404** if absent). |
| POST | `/tenants/{schema}/suspend` | `public.tenants.active=false`; evict the realm's allowlist cache entry; audit. (**404** if absent; idempotent if already suspended.) |
| POST | `/tenants/{schema}/activate` | `public.tenants.active=true`; evict cache; audit. |
| GET | `/audit` | `PlatformAuditEntry[]` (newest first, paged) from `public.platform_audit_log`. |

- **`PlatformTenantService`** wraps: uniqueness check, temp-password generation, `provision(spec)`, registry suspend/activate (schema-qualified JDBC), cache eviction, and audit writes. Maps a provision failure to a 5xx with a clear message (provision is fail-fast + idempotent; the registry upsert is last, so a pre-registry failure leaves no row → safe re-onboard).
- **`OnboardTenantRequest` → `TenantBootstrapProperties.TenantSpec`** mapping: the API builds a `TenantSpec` from the request + the generated password (decoupling the REST contract from the config-properties class; a shared `TenantSpec`-like record may be extracted if cleaner).
- **Error envelope:** standard `ApiResponse` errors — 400 (invalid schema), 409 (duplicate), 403 (not SUPER_ADMIN / wrong realm), 401 (untrusted/suspended), 404, 5xx (provision failure).

### 4. Audit (§6 — both forms)

Every platform action (onboard / suspend / activate) is:
1. **Structured-log** line: actor username + actor realm + action + target schema + source IP.
2. **Persisted** to `public.platform_audit_log` via **schema-qualified JDBC** (mirroring `TenantRegistry`, independent of `TenantContext`). Columns: `id UUID, action VARCHAR, target_schema VARCHAR, actor_username VARCHAR, actor_realm VARCHAR, at TIMESTAMPTZ, detail JSONB, source_ip VARCHAR`. Created by `V67__platform_audit_log.sql` (standard migration).

`GET /api/v1/platform/audit` exposes the trail for SP2 + compliance.

## Data flow (onboard)

```
SUPER_ADMIN (platform realm JWT)
  │  POST /api/v1/platform/tenants {schema, subdomain, adminEmail, ...}
  ▼
TenantIssuerJwtAuthenticationManagerResolver  → validates against platform realm JWKS (trusted)
TenantContextFilter  → realm == platform realm → TenantContext = "public"
[allowlist gate]     → platform realm exempt → allowed
@PreAuthorize hasRole('SUPER_ADMIN')  (+ iss==platform assertion)
  ▼
PlatformTenantController → PlatformTenantService
  ├── TenantSchemas.validate(schema); registry uniqueness check → 409 if dup
  ├── generate one-time temp password (SecureRandom)
  ├── TenantProvisioningService.provision(spec)   [schema+Flyway+seed+Keycloak realm/role/admin+registry]
  ├── platform audit (log + public.platform_audit_log)
  └── 201 { tenant, firstAdmin{username,email,temporaryPassword} }
```

## Testing posture

Testcontainers Postgres + Keycloak ITs (reuse `KeycloakItSupport`):
1. `PlatformBootstrapRunner` provisions the platform realm + `SUPER_ADMIN` role + first super-admin (gated-on test config).
2. **Onboard via API** → schema created + migrated, Keycloak realm + first-admin provisioned, `public.tenants` row added, response carries the one-time temp password.
3. Onboard duplicate schema/subdomain → **409**.
4. **Suspend → a token from that tenant's realm is rejected 401** (allowlist gate, immediate via eviction); **activate → token works again**.
5. Platform realm is **exempt** from the allowlist gate (super-admin keeps working).
6. A request with no `SUPER_ADMIN` → **403**; a **tenant `PLATFORM_ADMIN`** token → **403** on platform endpoints (no cross-tenant escalation).
7. Audit: onboard/suspend/activate each write exactly one `public.platform_audit_log` row; `GET /platform/audit` returns them.
8. `TenantContextFilter` unit test: platform realm → `TenantContext == "public"` (not `"platform"`).

Full-reactor `mvn verify` stays green.

## Out of scope (this slice)

- The **SP2 `apps/platform` UI** (next sub-project).
- **Hard tenant delete** (drop schema + realm) — a separate NDPR/data-retention workflow.
- Runtime **"invite super-admin"** API (super-admins added via config + restart for now).
- Tenant **re-migrate / config-edit** endpoints.
- Per-partner / non-onboarding platform features.

## Docs & backlog

- CLAUDE.md: §6 (multi-tenancy) + §8 (security) note the platform realm + `SUPER_ADMIN` + the allowlist gate; new env vars (`CIA_PLATFORM_BOOTSTRAP_*`, `CIA_PLATFORM_REALM`, `CIA_PLATFORM_TENANT_ALLOWLIST_ENABLED`); Module 1/12 note the platform-admin onboarding surface.
- `cia-log.md`: backlog reconciliation — **drain** `jwt-resolver-registry-allowlist` (P2, implemented as the gate); **add** any surfaced rows (e.g., hard-delete workflow, invite-super-admin). Session entry.
