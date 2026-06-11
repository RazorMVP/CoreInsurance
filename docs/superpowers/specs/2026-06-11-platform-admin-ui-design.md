# Platform-Admin UI + Backend Extensions (SP2) — Design

> **Status:** Approved (brainstorm complete 2026-06-11). Next: writing-plans.
> **Epic:** Tenant-Onboarding API + Platform-Admin UI. This is **sub-project 2 of 2**. SP1 (merged `7d56f8b`) shipped the `/api/v1/platform/**` backend + the `platform` Keycloak realm + `SUPER_ADMIN`. SP2 builds the `apps/platform` SPA that consumes it, **plus four backend extensions** the UI needs. Aligns with the planned Module 12 "Phase 6 — cross-tenant platform admin view."

## Goal

Ship the **NubSure Platform** admin console (`apps/platform`, a new React SPA on the `platform` Keycloak realm, gated entirely on `SUPER_ADMIN`) and the backend it needs: a **Dashboard**, **Tenants** (paginated list + onboard + a full **tenant-detail route**), an **Audit log** (paginated + per-tenant filter), and **Super-admins** (list + invite + revoke). Deployed to its own Vercel project (demo mode for now, mirroring back-office).

## Why this scope (a deliberately larger slice)

The user explicitly chose to fold four formerly-deferred backlog items into SP2 in one pass — *"It is worth the work and it does not unnecessarily keep too many items in the deferred list."* The four:

| Item | Was | Now |
|---|---|---|
| `platform-admin-ui-vercel-deploy` | P2 backlog | **In scope** — §6 Deploy |
| Dashboard landing | P3 backlog | **In scope** — §5 DashboardPage |
| Server-side pagination / audit-at-scale | P3 backlog | **In scope** — §3 Backend (b) |
| `platform-invite-super-admin` | P3 backlog (existing row) | **In scope** — §3 Backend (c) + §5 SuperAdminsPage |

This is bigger than a typical slice but one cohesive sub-project (the platform-admin plane's surface + the backend it needs). The implementation **plan is phased** (§8) so it executes in reviewable chunks.

## Architecture

```
┌─ apps/platform SPA (NEW — port 5175, dark) ──────────────────────────────────┐
│  @cia/auth     configureKeycloak({ realm: 'platform', clientId: 'cia-platform'})│
│   • AuthProvider parses realm_access.roles → hasRole('SUPER_ADMIN')            │
│   • SuperAdminGate: not SUPER_ADMIN → "Not authorized" screen (mirrors backend │
│                     assertPlatformRealm — defense in depth, not the only gate) │
│  @cia/api-client  createApiClient(VITE_API_BASE_URL) + setTokenGetter(token)   │
│   • packages/api-client/src/modules/platform.ts  (zod + React Query hooks)     │
│  AppShell sidebar:  Dashboard · Tenants · Audit · Super-admins                  │
│     Dashboard   Tenants(list)   Onboard(Sheet)   Tenant detail(route)          │
│     Audit(list)   Super-admins(list + invite Sheet + revoke)                    │
└──────────────────────────────────│────────────────────────────────────────────┘
                                    │  Authorization: Bearer <platform JWT>
                                    ▼
┌─ Spring Boot API — /api/v1/platform/** (SP1 + SP2 extensions) ────────────────┐
│  PlatformTenantController          @PreAuthorize hasRole('SUPER_ADMIN')         │
│   GET  /tenants?page&size          + per-method assertPlatformRealm(jwt)        │
│   GET  /tenants/{schema}  ── (C) now → TenantDetailResponse{tenant,recentAudit} │
│   POST /tenants  · POST /tenants/{schema}/{suspend,activate}                    │
│   GET  /audit?page&size&targetSchema                                            │
│  PlatformSuperAdminController (NEW)                                             │
│   GET    /super-admins   ·  POST /super-admins   ·  DELETE /super-admins/{user} │
│        │                 │                              │                       │
│        ▼                 ▼                              ▼                       │
│  PlatformTenantService   PlatformSuperAdminService (NEW) ──▶ KeycloakTenant-    │
│   • detail(schema)        • list / invite / revoke           Provisioner        │
│   • paged list            • self + last-admin guards         (reuses ensure-    │
│  PlatformAuditService     • audit INVITE/REVOKE             FirstAdminUser,     │
│   • recent(page,size,targetSchema?)  • recentForSchema      ensurePlatformRoles)│
│   • count(targetSchema?)                                                        │
└──────────────────────────────│──────────────────────────────│──────────────────┘
        schema-qualified JDBC   │                              │ Keycloak admin client
                 ▼              ▼                              ▼
   ┌─ public schema ──────────────────────────┐      ┌─ platform realm ───────────┐
   │ tenants (registry) · platform_audit_log  │      │ SUPER_ADMIN role            │
   │   V68: NULLABLE target_schema +           │      │ super-admin user(s)         │
   │        drop dead per-tenant copies        │      │                             │
   └──────────────────────────────────────────┘      └─────────────────────────────┘
```

**Reading it:** the SPA authenticates against the `platform` realm, attaches the JWT, and calls `/api/v1/platform/**`. Every endpoint is double-gated (`SUPER_ADMIN` role **and** `assertPlatformRealm`). Tenant lifecycle goes through the SP1 `PlatformTenantService`; super-admin lifecycle goes through a **new** `PlatformSuperAdminService` that drives the platform realm via the existing `KeycloakTenantProvisioner` (which already knows how to create a platform user + assign `SUPER_ADMIN`). Pagination is page/size + `ApiMeta`, matching the Session-137 convention. The UI never persists a temp password — invite and onboard both reveal it once through a shared copy-gated component.

## Decisions (brainstorm Q&A)

| # | Decision | Choice |
|---|---|---|
| Q1 | Screen surface | Dashboard + Tenants(list) + Onboard + **tenant-detail route** + Audit + Super-admins. |
| Q2 | Visual style | **Dark** ("elevated cross-tenant console"; same Nubeero teal + fonts as back-office). |
| Q3 | Onboard form shape | **Single Sheet** whose content swaps to a one-time credential reveal on success. |
| Q4 | Tenant detail | **Full route** `/tenants/:schema` (not a Sheet), fed by the consolidated detail endpoint. |
| Q5 | Detail data fetch | **Approach C** — backend returns `TenantDetailResponse{tenant, recentAudit}` in one call. |
| Q6 | List/audit scale | **Server-side pagination** (page/size + `ApiMeta`) on `/tenants` and `/audit`; `/audit` also gains a `targetSchema` filter. |
| Q7 | Super-admins | **List + invite + revoke.** Revoke guarded: cannot revoke self; cannot revoke the last remaining super-admin. |
| Q8 | Deploy | **New Vercel project + CI workflow** for `apps/platform` (demo mode public URL, mirrors back-office). |
| Q9 | One-time password UX | Backend generates + returns once; FE never persists (no localStorage); "shown once" warning; **"Done" gated on Copy**. |

---

## 3. Backend extensions (`cia-api` + `cia-setup` + Flyway)

All five endpoints stay under `PlatformTenantController` (`/api/v1/platform`) except the super-admin trio, which gets its own `PlatformSuperAdminController` (same base path, separate file — keeps tenant-lifecycle and identity-lifecycle responsibilities cleanly split, mirroring the project's "flat list controller per aggregate" convention).

### (a) Consolidated tenant detail — Approach C

- **New DTO** `TenantDetailResponse(TenantSummary tenant, List<PlatformAuditService.PlatformAuditEntry> recentAudit)` in `cia-api/.../platform/dto/`.
- **`PlatformAuditService.recentForSchema(String targetSchema, int limit)`** — `WHERE target_schema = ? ORDER BY at DESC LIMIT ?`.
- **`PlatformTenantService.detail(String schema)`** → `Optional<TenantDetailResponse>`: `registry.find(schema)` + `audit.recentForSchema(schema, 20)`.
- **`GET /tenants/{schema}`** changes its return type from `ApiResponse<TenantSummary>` to `ApiResponse<TenantDetailResponse>` (404 `TENANT_NOT_FOUND` when absent). **Breaking change to the SP1 endpoint**, but SP2's UI is its only consumer (no other caller exists), so this is a clean swap — the SP1 `PlatformTenantControllerIT.get` test is updated to assert the new shape.

### (b) Server-side pagination + audit filter + dashboard stats

- **`TenantRegistry`**: add `findAll(int page, int size)` (`ORDER BY created_at LIMIT ? OFFSET ?`), `countAll()`, and `countActive()` (`WHERE active = TRUE`). Keep the existing no-arg `findAll()` (still used internally) and `findActiveSchemas()` untouched.
- **Dashboard stats endpoint** — `GET /api/v1/platform/stats` → `ApiResponse<TenantStats>` where `TenantStats(long total, long active, long suspended)` (`suspended = total − active`, computed from two cheap `COUNT`s). Mounted at `/stats` (top-level, **not** under `/tenants/`) to avoid shadowing `GET /tenants/{schema}`. This backs the three StatCards exactly, with no full-list fetch — so counts stay correct as the tenant set grows.
- **`PlatformAuditService`**: add `recent(int page, int size, String targetSchemaOrNull)` and `count(String targetSchemaOrNull)`. `targetSchema == null` → unfiltered; non-null → `WHERE target_schema = ?`. Keep the existing `recent(int limit)` for any direct caller, or migrate it — implementer's discretion, but the controller must use the paged form.
- **`PlatformTenantService.list(int page, int size)`** → returns a small `Page`-like carrier `PagedResult<TenantSummary>(List<TenantSummary> items, long total, int page, int size)` (a plain record in `dto/`; we don't pull in Spring `Page` for a JDBC source).
- **Controller**:
  - `GET /tenants` gains `@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="50") int size` → `ApiResponse.success(result.items(), ApiMeta.builder().total(result.total()).page(result.page()).size(result.size()).build())`. Return type `ApiResponse<List<TenantSummary>>`.
  - `GET /audit` gains `page`/`size` (default `0`/`50`) + `@RequestParam(required=false) String targetSchema`. The old `limit` param is removed (no external consumer; the UI is new). `MAX_AUDIT_LIMIT` becomes a `MAX_PAGE_SIZE` clamp on `size`.

### (c) Super-admin invite + revoke

- **New `PlatformSuperAdminController`** (`/api/v1/platform/super-admins`), same class-level `@PreAuthorize("hasRole('SUPER_ADMIN')")` + per-method `assertPlatformRealm(jwt)` (extract the assertion into a small shared helper or duplicate the 5-line method — implementer's discretion; duplication is acceptable given it reads the same `PlatformRealmProperties`).
  - `GET /super-admins` → `ApiResponse<List<SuperAdminSummary>>` — `SuperAdminSummary(String username, String email, boolean enabled)`.
  - `POST /super-admins` (`@Valid InviteSuperAdminRequest{ @NotBlank username, @Email email }`) → `201` `ApiResponse<InviteSuperAdminResponse>` where `InviteSuperAdminResponse(String username, String email, String temporaryPassword)`. Temp password generated server-side (reuse the same generator as onboarding — extract `PlatformTenantService.generateTempPassword()` into a small shared `PlatformPasswords` util so both call it), returned once, never logged.
  - `DELETE /super-admins/{username}` → `200` `ApiResponse<Void>`. Removes the `SUPER_ADMIN` realm-role mapping from that platform user (does **not** delete the user account — least surprise; a stripped user simply loses platform access).
- **New `PlatformSuperAdminService`** (`cia-api/.../platform/`):
  - Depends on `KeycloakTenantProvisioner` (already a bean when `cia.keycloak.admin.enabled=true`) + `PlatformRealmProperties` (realm name) + `PlatformAuditService`.
  - **Admin-disabled guard:** when the Keycloak admin client is unavailable (dev/IT with `cia.keycloak.admin.enabled=false`), the service throws a `CiaException` → the controller surfaces **503** with `{errorCode: "KEYCLOAK_ADMIN_DISABLED"}`, mirroring the Module-1 `UserController` pattern. (The simplest wiring: the three new `KeycloakTenantProvisioner` methods below check `keycloak.getIfAvailable()` and throw `IllegalStateException`; the service maps that to the 503 `CiaException`.)
  - `invite(username, email, actor, actorRealm, ip)`: reject if a user with that username already exists in the platform realm → `409 SUPER_ADMIN_ALREADY_EXISTS`; else create + assign `SUPER_ADMIN` + temp password; audit `INVITE_SUPER_ADMIN`.
  - `revoke(username, actor, actorRealm, ip)`: **guards first** — `username.equals(actor)` → `409 CANNOT_REVOKE_SELF`; if the user is the only member of `SUPER_ADMIN` → `409 CANNOT_REVOKE_LAST_SUPER_ADMIN`; user not found → `404 SUPER_ADMIN_NOT_FOUND`. Else remove the role mapping; audit `REVOKE_SUPER_ADMIN`.
- **New `KeycloakTenantProvisioner` methods** (extend the existing platform-realm section):
  - `List<SuperAdminView> listSuperAdmins(String realm)` — `realm.roles().get("SUPER_ADMIN").getUserMembers()` → map to a small `record SuperAdminView(String username, String email, boolean enabled)` (defined in the provisioner or `cia-setup`; the controller DTO `SuperAdminSummary` maps from it). Returns `username/email/enabled`.
  - `void createSuperAdmin(String realm, String username, String email, String tempPassword)` — create the user (enabled, `UPDATE_PASSWORD` required action, temp password) + assign `SUPER_ADMIN`. Throws `SuperAdminExistsException`-style signal if the username is taken (the *service* converts to the 409; the provisioner can return a boolean or throw — implementer's discretion). No `accessGroupId` attribute (platform realm has no access groups).
  - `void removeSuperAdminRole(String realm, String username)` — find the user, remove the `SUPER_ADMIN` realm-role mapping. `boolean isOnlySuperAdmin(String realm, String username)` (or expose member-count to the service) backs the last-admin guard.
  - These mirror the existing `ensureFirstAdminUser` / `ensurePlatformRoles` shape; all Keycloak admin-client types stay encapsulated inside the provisioner (the project's hard rule).

### (d) Flyway V68 — schema-aware: relax `target_schema` **and** clean up the tenant-copy pollution

Two facts converge here:

1. `platform_audit_log.target_schema` is `NOT NULL` and semantically a tenant schema; super-admin actions (invite/revoke) have no schema, so the column must allow `NULL`.
2. `platform_audit_log` is a **public-only** table, but V67 introduced it as an *unqualified* `CREATE TABLE` **above the tenant baseline** (`baselineVersion=2`). Two Flyway runs cover `db/migration`: the **main** Spring Flyway (`schemas: public`) created the real `public.platform_audit_log`; the **per-tenant** `TenantSchemaMigrator` (search_path pinned to `"<tenant>", public`) cloned a dead copy into every tenant schema. The dead copies are never read or written (`PlatformAuditService` always qualifies `public.platform_audit_log`).

A naïve unqualified `ALTER` would compound this (it would alter every dead copy too). Instead **V68 is schema-aware**, branching on `current_schema()` so each run does the right thing — relax the real table in the public run, drop the dead copy in each tenant run. This **fixes** the `platform-audit-log-tenant-schema-pollution` backlog item rather than feeding it.

```sql
-- V68: platform_audit_log is public-only. V67 was unqualified + above the tenant baseline,
-- so it cloned a dead copy into every tenant schema. Drop those copies; and on the canonical
-- public table only, relax target_schema to NULL so user-targeted platform actions
-- (super-admin invite/revoke) can be audited without a tenant schema.
DO $$
BEGIN
  IF current_schema() = 'public' THEN
    ALTER TABLE public.platform_audit_log ALTER COLUMN target_schema DROP NOT NULL;
  ELSE
    -- Explicitly schema-qualified: can NEVER resolve to public.platform_audit_log.
    EXECUTE format('DROP TABLE IF EXISTS %I.platform_audit_log', current_schema());
  END IF;
END
$$;
COMMENT ON COLUMN public.platform_audit_log.action IS
  'ONBOARD | SUSPEND | ACTIVATE | INVITE_SUPER_ADMIN | REVOKE_SUPER_ADMIN';
```

Why this is safe: the `%I` qualification on the `DROP` is load-bearing — an *unqualified* drop in a tenant run could fall through search_path to `public` and drop the real table. `current_schema()` cleanly separates the runs (main = `public`; the tenant callback guarantees `<tenant>` is first; a tenant can never be named `public`). Newly-provisioned tenants after V68 briefly create the copy (V67) then drop it (V68) — forward-only, since V67 cannot be edited.

**Go-forward convention** (documented in `CLAUDE.md` Data Architecture): any future public-only table must use `CREATE TABLE IF NOT EXISTS public.<name>` so the tenant sweep no-ops instead of cloning. The heavier structural fix (splitting `db/migration` into `common`/`public`/`tenant` locations) is deliberately **not** done — it touches the baseline model and all existing migrations for no behavioral gain over schema-awareness + the convention.

Super-admin audit rows write `target_schema = NULL` and put the affected username/email in `detail` JSONB (`{"username":"...","email":"..."}`). `recentForSchema(schema)` naturally excludes them (null schema never matches). `PlatformAuditEntry.targetSchema` is already a plain `String` and maps `NULL` fine.

**IT note:** any IT that asserts a tenant schema contains `platform_audit_log` must be updated (none is known to; the migration-target ITs only migrate to head). Add a positive IT asserting (a) `public.platform_audit_log` survives with `target_schema` nullable and (b) a freshly-migrated tenant schema has **no** `platform_audit_log`.

### Backend tests (failsafe ITs, following SP1's harness)

- `PlatformTenantServiceIT` — extend: `detail()` returns tenant + recentAudit; paged `list()` total/page/size; `recent(page,size,targetSchema)` filter.
- `PlatformTenantControllerIT` — update `get` to the new `TenantDetailResponse` shape; add paged `list`/`audit` assertions (`jwt()` postprocessor, as SP1).
- `PlatformSuperAdminControllerIT` — `jwt()`-driven web-layer: 401/403 gates, 409 self/last/duplicate, 404 not-found, 503 admin-disabled.
- `PlatformSuperAdminE2EIT` — **real Keycloak Testcontainer** (mirrors `PlatformOnboardingE2EIT`): invite a super-admin → it appears in `listSuperAdmins` → can authenticate → revoke → role gone; last-admin guard blocks revoking the bootstrap admin.
- `V68` is exercised by the existing migration-target ITs (they Flyway-migrate to head).

---

## 4. Frontend foundation (`cia-frontend/apps/platform` + `packages/api-client`)

### App scaffold
- New workspace app `@cia/platform`, **port 5175**, dark theme. Structure mirrors `apps/back-office`: `src/{app/{layout,router.tsx,globals.css}, modules/, main.tsx, App.tsx}`, `tailwind.config.ts`, `vite.config.ts`, `index.html`, `package.json`. Deps: `@cia/{ui,api-client,auth}`, `@tanstack/react-query`, `react-router-dom`, `react`, `react-dom`. Dark tokens: reuse the partner app's dark surface values over the shared Nubeero teal accent.
- `turbo.json` already globs apps via `^build`; no pipeline change needed beyond the new package being picked up. Add a `test` script (Vitest) mirroring back-office.

### Auth bootstrap (`main.tsx`)
Mirror back-office's pattern exactly, swapping the realm/client:
- `import.meta.env.DEV` → `DevAuthProvider` (mock user **with `SUPER_ADMIN`** role so the gate passes in dev).
- Prod: `configureKeycloak({ url: VITE_PLATFORM_KEYCLOAK_URL, realm: VITE_PLATFORM_KEYCLOAK_REALM ?? 'platform', clientId: VITE_PLATFORM_KEYCLOAK_CLIENT_ID ?? 'cia-platform' })` + `setTokenGetter(() => keycloak.token)` + `initKeycloak({ onLoad: 'login-required' })` + `AuthProvider`.
- `VITE_DEMO_MODE === 'true'` → allow `DevAuthProvider` in a prod build (public stakeholder preview) with an amber "Demo" banner; otherwise fail loud if Keycloak config is absent (same invariant as back-office).
- **`SuperAdminGate`** wraps the routed app: `const { hasRole } = useAuth(); if (!hasRole('SUPER_ADMIN')) return <NotAuthorized />;`. This is defense-in-depth UX — the backend `assertPlatformRealm` + `@PreAuthorize` remain the real gate.

### API client module — `packages/api-client/src/modules/platform.ts`
Single source of zod truth (house pattern from `finance-closures.ts`: enums hoisted, DTOs below, `validatedGet`/`validatedPost`). Schemas + hooks:
- **Schemas:** `TenantSummarySchema`, `PlatformAuditEntrySchema`, `TenantDetailSchema{tenant, recentAudit}`, `OnboardTenantRequest/Response` (+ nested `FirstAdmin`), `SuperAdminSummarySchema`, `InviteSuperAdminRequest/Response`. Paginated reads parse `{ data: T[], meta: { total, page, size } }`.
- **Query hooks:** `useTenants(page, size)`, `useTenantDetail(schema)`, `useAudit(page, size, targetSchema?)`, `useSuperAdmins()`.
- **Mutation hooks:** `useOnboardTenant()`, `useSuspendTenant()`, `useActivateTenant()`, `useInviteSuperAdmin()`, `useRevokeSuperAdmin()`. Each invalidates the relevant query keys on success.
- **DTO drift:** add a `manualMap`/`allowList` entry in `dto-drift.config.json` only if the CI guard flags the new `*Dto`↔`*Response` pairs (the platform DTOs are records; the guard may need a mapping — resolve during implementation, don't pre-emptively suppress).

---

## 5. Screens (`apps/platform/src/modules/`)

A shared **`CredentialReveal`** component (in the app, or `@cia/ui` if a second consumer makes it worth promoting) renders a one-time secret: monospace value + Copy button + "shown once / never stored" amber warning + a **"Done" button disabled until Copy is clicked**. Used by both Onboard and Invite.

| Screen | Route | Contents |
|---|---|---|
| **DashboardPage** | `/` | 3 StatCards (Total / Active / Suspended — derived from `useTenants(0, …)` `meta.total` + a count, or a tiny derived count over the first page; if exactness at scale matters, read counts off the paged meta of an active-filtered call — implementer's discretion, documented). "Recent activity" = first audit page (`useAudit(0, 8)`), read-only rows. Quick-action buttons: **Onboard tenant** (opens the Sheet) and **Invite super-admin** (navigates to Super-admins + opens its Sheet). |
| **TenantsListPage** | `/tenants` | StatCards (same counts) + **paginated** `DataTable` (schema · display name · subdomain · status badge · created) with a `ServerPaginationFooter` (page/size from `meta`). Row actions: **View** → `/tenants/:schema`; **Suspend**/**Activate** (status-conditional) → `ConfirmDialog` → mutation. Toolbar: **+ Onboard tenant** → `OnboardTenantSheet`. |
| **OnboardTenantSheet** | (Sheet) | 6-field form (schema, display name, subdomain, admin username, admin email; realm defaults to schema). On submit → `useOnboardTenant()`. Success swaps the Sheet body to `CredentialReveal` (admin username/email + one-time temp password). Inline errors: `409 TENANT_ALREADY_EXISTS`, `422 REALM_SCHEMA_MISMATCH`. |
| **TenantDetailPage** | `/tenants/:schema` | `useTenantDetail(schema)`. Header (display name · schema · subdomain · status badge) + Suspend/Activate action (status-conditional, `ConfirmDialog`) + a "Recent activity" table from `recentAudit`. 404 → "tenant not found" empty state. |
| **AuditLogPage** | `/audit` | **Paginated** `DataTable` (action badge · target · actor · realm · time) + a `targetSchema` filter input (server-side via `useAudit(page,size,targetSchema)`) + `ServerPaginationFooter`. |
| **SuperAdminsPage** | `/super-admins` | `useSuperAdmins()` table (username · email · enabled). Toolbar **+ Invite super-admin** → `InviteSuperAdminSheet` (username + email → `CredentialReveal`). Row action **Revoke** → `ConfirmDialog` → `useRevokeSuperAdmin()`; the self-row and (UI-side hint) the last-remaining row disable Revoke, and the backend `409 CANNOT_REVOKE_SELF` / `CANNOT_REVOKE_LAST_SUPER_ADMIN` surface as a destructive toast if attempted. 503 `KEYCLOAK_ADMIN_DISABLED` → an informational empty state ("super-admin management needs Keycloak admin enabled"). |

**AppShell** (`app/layout/`): dark sidebar (logo "◈ NubSure Platform" + nav: Dashboard, Tenants, Audit, Super-admins + user/logout row) + topbar (page title). Lazy-loaded routes with `Suspense` skeletons, matching back-office.

---

## 6. Deploy (`apps/platform` → Vercel)

- **New workflow** `.github/workflows/vercel-deploy-platform.yml` — a copy of `vercel-deploy.yml` with `working-directory: cia-frontend/apps/platform`, path filter `cia-frontend/**` (shared packages affect it too — keep the broad filter, matching back-office), and secrets `VERCEL_TOKEN` (shared) + **`VERCEL_PLATFORM_PROJECT_ID`** (new) + `VERCEL_ORG_ID` (shared). Preview on PR, production on push to `main`.
- **Vercel project** (one-time, dashboard/CLI, documented in the spec/runbook, not code): a second project on the same monorepo root building `apps/platform`. Its `.vercel/project.json` + a `vercel.json` (build `pnpm --filter @cia/platform build`, output `apps/platform/dist`, SPA rewrite) live alongside the app. Env vars per environment: `VITE_API_BASE_URL`, `VITE_PLATFORM_KEYCLOAK_URL`, `VITE_PLATFORM_KEYCLOAK_REALM`, `VITE_PLATFORM_KEYCLOAK_CLIENT_ID`, and `VITE_DEMO_MODE=true` on the public preview URL only.
- **Demo-mode reality:** like back-office today, the public Vercel URL is a frontend-only demo until real platform Keycloak + backend infra are deployed — mutations succeed locally but do not persist. The amber Demo banner makes this explicit.
- **Docs:** add the new env vars + the deploy steps to `CLAUDE.md` (Environment Variables + Frontend deployment) and a short runbook note.

---

## 7. Error handling + testing

**Error handling (FE):** `401` → existing `cia:unauthorized` event (re-auth); non-`SUPER_ADMIN` token → `SuperAdminGate` "Not authorized" screen; structured `errorCode` from the envelope → friendly copy per code (`TENANT_ALREADY_EXISTS`, `REALM_SCHEMA_MISMATCH`, `SUPER_ADMIN_ALREADY_EXISTS`, `CANNOT_REVOKE_SELF`, `CANNOT_REVOKE_LAST_SUPER_ADMIN`, `SUPER_ADMIN_NOT_FOUND`, `KEYCLOAK_ADMIN_DISABLED`). The temp password lives only in component state and is gone when the Sheet closes — never localStorage, never a query cache.

**Testing:**
- **Backend:** the failsafe ITs in §3 (service + controller + two real-Keycloak E2E) run under `mvn verify`, keeping the reactor green.
- **Frontend (Vitest — the back-office harness extended to this app):** `CredentialReveal` (Copy gates Done; value not persisted), `SuperAdminGate` (role present/absent), the confirm flows (suspend/activate/revoke fire the right mutation), pagination footer (page math off `meta`), and the onboard/invite success→reveal swap. `vi.mock('@cia/api-client')` for fetchers; `QueryClientProvider` wrapper.

---

## 8. Implementation phasing (for the plan)

The plan (writing-plans) will sequence SP2 as four phases so each lands reviewable:

1. **Backend** — (a) consolidated detail + (b) pagination/audit filter + (c) super-admin invite/revoke + (d) V68 + all backend ITs. Reactor green before any FE work.
2. **FE foundation** — app scaffold + dark theme + auth bootstrap + `SuperAdminGate` + `packages/api-client/src/modules/platform.ts` + AppShell + empty routes.
3. **FE screens** — Dashboard, Tenants(list) + Onboard Sheet + `CredentialReveal`, Tenant detail route, Audit, Super-admins (+ Vitest per screen).
4. **Deploy** — `vercel-deploy-platform.yml` + `vercel.json`/project wiring + `CLAUDE.md` env/deploy docs + runbook note.

## Out of scope → backlog

- **Hard tenant delete** — regulated data; suspend-only stands (`platform-hard-delete-tenant`, P3).
- **Cursor pagination + a shared `useServerPagination`/`ServerPaginationFooter` in `@cia/ui`** — SP2 uses page/size + a local footer; the cross-app shared component is the existing `list-endpoints-true-pagination` item (P3). If SP2's footer is the second hand-rolled instance, note the rule-of-three.
- **Structural migration split** (`db/migration` → `common`/`public`/`tenant` locations) — the general cure for public-vs-tenant DDL ambiguity; not warranted now (§3(d) handles the one known case via schema-awareness + the qualify-`public.` convention). Log only if a third public-only table forces the issue.
- **Live platform Keycloak + backend deploy** (real, non-demo platform URL) — go-live infra step, not this slice.

## Backlog reconciliation

- **Drains** the existing `platform-invite-super-admin` (P3) row — delivered here.
- **Drains** `platform-audit-log-tenant-schema-pollution` (P3) — fixed by the schema-aware V68 (§3(d)), not deferred.
- The other three folded-in items (`platform-admin-ui-vercel-deploy`, Dashboard landing, server-side pagination/audit-at-scale) were prospective backlog rows; they are delivered here and never become rows.
- **Drains** `platform-admin-ui-sp2` (this *is* SP2).
