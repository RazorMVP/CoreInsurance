# Slice A — Tenant Provisioning Runtime (Design)

**Date:** 2026-06-02
**Status:** Approved (brainstorm) — pending implementation plan
**Milestone:** First deployable milestone, sub-slice A of (A → C → B)
**Backlog rows touched:** elevates/replaces `keycloak-seed-admin-user` (P3); partially addresses `prod-deployability-k8s-manifests` (the runtime-provisioning half).

---

## 1. Problem

The audit (2026-06-02) established that **the application has no runtime tenant-provisioning path**, despite CLAUDE.md §5.4/§6 describing one as present:

- The app is effectively a **single-schema application running in `public`**. All 66 Flyway migrations except `V2` are schema-relative (no schema qualifier); Flyway is pinned to `schemas: public`; so every migration builds the full schema in `public`. `V1` puts the `tenants` registry there too.
- `template_` (created by `V2`) is **vestigial**: it holds ~3 tables (`audit_log`, `partner_apps`, `webhook_registrations`) that are re-created *unqualified* in `public` by `V12`/`V13`/`V16`. It is not a complete schema and cannot serve as a clone source.
- Multi-tenancy is plumbed (`MultiTenantConnectionProvider`, `TenantIdentifierResolver` → default `public`) but never operationalized: no code creates, migrates, or seeds a tenant schema, and a fresh Keycloak tenant realm seeds no login-able user.

**Consequence:** in production you cannot onboard a tenant — there is no way to create an isolated tenant schema, migrate it, seed it, or create its first admin.

This slice delivers a real, automated, idempotent tenant-provisioning runtime.

## 2. Goal (single slice goal)

> Provision and migrate isolated per-tenant schemas at application startup — schema creation, Flyway-per-schema migration, sensible-defaults seed, Keycloak realm roles + first-admin user, and registry-sweep re-migration — driven by config, idempotent, fail-fast, and gated off by default so existing ITs/dev are unaffected.

## 3. Decisions (resolved during brainstorm)

| # | Decision | Choice |
|---|---|---|
| Q1 | Provisioning entry point | **CLI/bootstrap `ApplicationRunner` only** (no REST admin API this slice) |
| Q2 | Schema migration model | **True schema-per-tenant via Flyway-per-schema**, baselined past V1; `public` stays the system/registry schema; **zero edits to existing migrations** |
| Q3 | First-admin credential | **Env-driven temporary password + `UPDATE_PASSWORD` forced reset** (no SMTP dependency at first boot) |
| Q4 | Migration-failure semantics | **Fail-fast — abort startup** |
| Q5 | Seed scope | **Sensible defaults**: Administrators access group + first-admin `users` row + NGN currency + customer/policy number-format singletons |

## 4. Architecture & component placement

```
cia-api (assembly)                          cia-setup
┌─────────────────────────────────┐         ┌──────────────────────────────┐
│ TenantBootstrapRunner           │         │ KeycloakTenantProvisioner     │
│  (ApplicationRunner, gated)     │────────▶│  (EXTENDED this slice:        │
│   reads cia.tenants.bootstrap[] │         │   + ensureRealmRoles()        │
│   + sweeps public.tenants       │         │   + ensureFirstAdminUser())   │
└──────────────┬──────────────────┘         └──────────────────────────────┘
               │ per tenant
               ▼
┌─────────────────────────────────┐
│ TenantProvisioningService       │  idempotent per step:
│  1. ensureSchema(schema)        │   → CREATE SCHEMA IF NOT EXISTS
│  2. migrate(schema)             │   → programmatic Flyway-per-schema
│  3. seed(schema)                │   → idempotent seed SQL
│  4. register(schema,…)          │   → upsert public.tenants
└─────────────────────────────────┘
```

- **`TenantBootstrapRunner`** — new, in **cia-api**, alongside `BackfillCliRunner` / `TemporalWorkerStarter`. Owns the config-list ensure + the boot-time registry sweep.
- **`TenantProvisioningService`** — new, in **cia-api**. Coordinates the data-plane steps (schema, Flyway, seed, registry); needs `DataSource` + Flyway + cross-module seed knowledge, all assembly concerns. Delegates the auth-plane to `KeycloakTenantProvisioner`.
- **`KeycloakTenantProvisioner`** — existing, in **cia-setup**, *extended* with `ensureRealmRoles` + `ensureFirstAdminUser`. Keeps all Keycloak logic in one place; no new Keycloak dependency added to cia-api.

## 5. Per-tenant provisioning sequence

For each configured tenant (idempotent — safe every boot):

1. **Keycloak** (`KeycloakTenantProvisioner`): ensure realm → ensure back-office client (existing) → **ensure realm roles** (full `{module}_create/view/update/approve` authority set) → **ensure first-admin user** (temp password from config, `temporary=true` → `UPDATE_PASSWORD` forced reset; `accessGroupId` attribute; all roles assigned).
2. **Schema**: `CREATE SCHEMA IF NOT EXISTS <schema>`.
3. **Migrate**: programmatic Flyway against `<schema>` (§6).
4. **Seed**: idempotent seed SQL into `<schema>` (§7), incl. matching `users` row + Administrators access group.
5. **Register**: upsert `public.tenants (schema_name, name, subdomain, active)`.

After the config list is processed, **sweep `public.tenants WHERE active = true`** and run step 3 against each — making "all subsequent migrations run against every schema on startup" true.

**Ordering vs Spring Boot's own Flyway:** Spring Boot auto-configures one Flyway bean that migrates `public` during context initialization, *before* any `ApplicationRunner`. `TenantBootstrapRunner` runs after the context is ready, so `public` is already current when tenant provisioning begins. No conflict.

## 6. Flyway-per-schema mechanism (load-bearing)

```java
Flyway.configure()
    .dataSource(dataSource)
    .schemas(schema).defaultSchema(schema)
    .baselineOnMigrate(true).baselineVersion("1")   // skips V1 (shared tenants registry)
    .locations("classpath:db/migration")
    .load().migrate();
```

- `baselineVersion("1")` marks **V1 as already-applied** in a fresh tenant schema → per-tenant migration starts at **V2**; V1's shared `tenants` registry is never cloned into tenant schemas.
- **V2 is a harmless no-op for tenant schemas**: it `SET search_path TO template_`, so its tables land in `template_` (idempotent), not the tenant — but those same tables are re-created *unqualified* by V12/V13/V16, which land in the tenant schema. So every tenant schema ends complete (V3…V66 all run schema-relative against it).
- Each tenant gets its own `<schema>.flyway_schema_history`.
- **No existing migration is edited.**

## 7. Seed contents (sensible defaults)

Idempotent SQL (`ON CONFLICT DO NOTHING` / `IF NOT EXISTS`) into the tenant schema after migrate:

- **Administrators access group** (full module permissions) — the first-admin's `accessGroupId`.
- **First-admin `users` row** — matches the Keycloak user; carries the access-group FK.
- **NGN currency** default.
- **`customer_number_format`** singleton (sensible default prefix/sequence) — removes the `CUSTOMER_NUMBER_FORMAT_NOT_CONFIGURED` footgun.
- **`policy_number_format`** singleton.

*Not seeded* (admin configures via Setup UI): products, classes of business, approval groups.

## 8. Configuration & gating

```yaml
cia:
  tenants:
    bootstrap:
      enabled: ${CIA_TENANT_BOOTSTRAP_ENABLED:false}   # OFF by default
      tenants:
        - schema: tenant_acme
          realm: tenant_acme            # realm name == tenant id (matches S139)
          display-name: "Acme Insurance"
          subdomain: acme
          admin-username: admin
          admin-email: admin@acme.example
          admin-temp-password: ${ACME_ADMIN_TEMP_PASSWORD}
```

- Master flag `enabled: false` by default (mirrors `cia.keycloak.admin.enabled`). Both the config-list ensure and the registry sweep are gated by it → **the 274 ITs and local dev are unaffected** (they manage their own schema).
- New env var: `CIA_TENANT_BOOTSTRAP_ENABLED` (+ per-tenant `*_ADMIN_TEMP_PASSWORD` secrets). To be added to the Environment Variables table.

## 9. Error handling & idempotency

- **Fail-fast (Q4):** any step failure for any tenant throws → `ApplicationRunner` propagates → context fails to start → k8s keeps the prior ReplicaSet, alerts fire. Postgres DDL is transactional, so a failed Flyway migration rolls back to the prior consistent version.
- **Idempotent steps:** re-running on an already-provisioned tenant is a no-op (`CREATE … IF NOT EXISTS`, Flyway version check, `ON CONFLICT`, Keycloak existence checks).

## 10. Testing strategy

- **`TenantProvisioningServiceIT`** (Testcontainers Postgres): provision fresh `tenant_test` → assert schema exists, `flyway_schema_history` present, a late-migration table (e.g. `journal_entry`) exists *in the tenant schema*, seed rows present; re-run → idempotent (no duplicates, no error).
- **Flyway-per-schema assertion**: `baselineVersion=1` skips V1 (no `tenants` table in tenant schema); a V31+ table *is* created in the tenant schema.
- **Keycloak extension IT** (existing Keycloak Testcontainers harness): realm roles created; first-admin user exists with `UPDATE_PASSWORD` action + roles assigned + `accessGroupId` attribute.
- **Gating test**: with `enabled=false`, runner is a no-op (guards the 274-IT baseline).

## 11. Scope boundaries

**In scope:** CLI/bootstrap provisioning (realm + roles + admin + schema + migrate + seed + registry) + startup registry-sweep migration; idempotent; gated.

**Out of scope (→ backlog):**
- REST admin provisioning API (Q1 option B) — needs platform-admin auth in the Keycloak master realm.
- Tenant de-provisioning / suspend / soft-disable.
- `public.tenants` allowlist gate in the auth path — already tracked as `jwt-resolver-registry-allowlist` (P2).
- k8s/Helm manifests — Slice B.
- `application-prod.yml` / Hikari tuning / observability — Slice C.

## 12. Documentation reconciliation (produced by this slice)

- Correct CLAUDE.md §5.4 (new-tenant provisioning) and §6 (multi-tenancy) to describe what is now real.
- Update the Environment Variables table (`CIA_TENANT_BOOTSTRAP_ENABLED`, per-tenant admin temp-password secrets).
- Replace the under-rated `keycloak-seed-admin-user` (P3) backlog row with the delivered provisioning; add any new follow-ups surfaced.
- New `cia-log.md` session entry; backlog reconciliation per slice discipline.
- docs-site: per the §9 docs gate — update `architecture/security.md` / tenant-provisioning guide and `guides/environment-variables.md`.

## 13. Open assumptions to validate during planning

- Exact entity/table shapes for the seed (`access_groups`, `currencies`, `customer_number_format`, `policy_number_format`, `users`) — confirm column names/constraints before writing seed SQL.
- The canonical full set of Keycloak realm roles (the `{module}_*` authority list) — derive from the existing `JwtAuthConverter` / RBAC mapping so the seeded roles exactly match what `@PreAuthorize` expects.
- Whether seeding is best done as raw idempotent SQL vs via existing services with `TenantContext` set — lean SQL for isolation, but confirm no business-rule invariants are bypassed.
