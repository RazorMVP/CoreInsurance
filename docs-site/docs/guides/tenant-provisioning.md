---
id: tenant-provisioning
title: Tenant Provisioning
sidebar_label: Tenant Provisioning
---

## Overview

Each insurance company is a **tenant** in the CIAGB system. Provisioning a new tenant creates four isolated resources: a Keycloak realm, a PostgreSQL schema, Flyway-migrated tables, and seed configuration data.

Provisioning is handled by a **gated `ApplicationRunner`** (`TenantBootstrapRunner` in `cia-api`), not a REST API. It runs at Spring Boot startup when explicitly enabled via configuration.

---

## How It Works

`TenantBootstrapRunner` is `@ConditionalOnProperty(cia.tenants.bootstrap.enabled=true)` — it is **OFF by default**. This means local development and the integration-test suite never trigger provisioning unless you explicitly opt in.

The runner also requires `cia.keycloak.admin.enabled=true` and fails fast at startup if that flag is absent, preventing a half-provisioned state where schemas exist but Keycloak realms do not.

### Provisioning sequence

For each tenant declared in configuration, the runner calls `TenantProvisioningService.provision(spec)` in this exact order:

#### Step 1 — Generate the Administrators group UUID

A deterministic UUID is derived from the schema name. This is used as the `accessGroupId` for the first-admin user and seeded into the schema — both sides must agree on the same value without a round-trip.

#### Step 2 — Create the schema

`TenantSchemaMigrator.ensureSchema(schema)` runs `CREATE SCHEMA IF NOT EXISTS <schema>`. Safe to re-run on restart.

#### Step 3 — Run Flyway migrations

`TenantSchemaMigrator.migrate(schema)` runs a **programmatic Flyway instance scoped to this schema**:

- `baselineVersion=2` — skips V1 (the shared `public.tenants` registry) and V2 (a vestigial `template_`-schema script that was superseded by V12/V13).
- A `BEFORE_EACH_MIGRATE` callback pins `SET search_path TO "<schema>", public` on the connection before each migration script runs, so unqualified table references resolve into the tenant schema.
- `pgcrypto` is expected to already be installed in `public` (it is pre-installed there, which is why the search path includes `public`).
- Each tenant schema gets its own `flyway_schema_history` table — there is no cross-tenant migration state.

No existing migration file was edited to achieve this. The three tables that V2 would have created are re-created unqualified by V12 and V13 into each tenant's schema.

#### Step 4 — Seed default data

`TenantSeeder.seed(schema, adminGroupId)` inserts the minimum required rows, idempotently:

| Seeded item | Detail |
| --- | --- |
| Administrators access group | UUID matches the value from Step 1 |
| Module permissions for Administrators | Full set of `module:action` grants |
| NGN default currency | Nigerian Naira; `is_default = true` |
| Customer-number-format singleton | Empty prefix, `includeYear = true`, `sequenceLength = 8` |

What is **not** seeded: user rows (users live in Keycloak, not in the application DB), and policy-number format (that is per-product and configured by the tenant admin in Setup → Products).

#### Step 5 — Provision Keycloak

`KeycloakTenantProvisioner.provisionTenantAuth(realm, spec)` ensures, idempotently:

- The Keycloak realm exists.
- `UnmanagedAttributePolicy = ENABLED` on the realm's user-profile config (required so `accessGroupId` attributes set during user creation are not silently dropped by Keycloak 24+).
- The back-office SPA public client exists with auth-code + PKCE(S256), correct redirect URIs, and a `tenant_id` claim mapper whose value is the realm name.
- All bootstrap realm roles are present.
- A first-admin user is created with a **temporary password** — Keycloak forces `UPDATE_PASSWORD` on first login, so the plain-text `admin-temp-password` from config is never a long-lived credential. The user's `accessGroupId` attribute is set to the UUID from Step 1.

No SMTP configuration is required for provisioning. Password reset emails are a post-provisioning concern.

#### Step 6 — Register in the tenant registry

`TenantRegistry.upsert(schema, displayName, subdomain)` writes the row into `public.tenants`. This happens **last** — a tenant only appears in the registry once it is fully provisioned. If any earlier step fails, the runner throws and Spring Boot aborts startup (Kubernetes keeps the prior pod running).

### Sweep: migrate all existing tenants

After processing the configured tenant list, the runner queries `public.tenants WHERE active = true` and calls `TenantSchemaMigrator.migrate(schema)` for every row it finds. This ensures that new Flyway migrations reach all existing tenants on the next boot, without a separate migration job.

---

## Configuration

Enable the runner and declare tenants in `application.yml` (or via environment variables):

```yaml
cia:
  keycloak:
    admin:
      enabled: ${KEYCLOAK_ADMIN_ENABLED:false}
  tenants:
    bootstrap:
      enabled: ${CIA_TENANT_BOOTSTRAP_ENABLED:false}
      tenants:
        - schema: tenant_acme
          realm: tenant_acme
          display-name: "Acme Insurance"
          subdomain: acme
          admin-username: admin
          admin-email: admin@acme.example
          admin-temp-password: ${ACME_ADMIN_TEMP_PASSWORD}
```

**Adding a new tenant** = add an entry to the list and restart the application with `CIA_TENANT_BOOTSTRAP_ENABLED=true` and `KEYCLOAK_ADMIN_ENABLED=true`. The runner is idempotent — existing tenants are not re-seeded, only migrated.

---

## Tenant Isolation Guarantees

| Layer | Mechanism |
| --- | --- |
| Auth | Separate Keycloak realm per tenant; `TenantIssuerJwtAuthenticationManagerResolver` validates each JWT against its own realm's JWKS — a token from one tenant cannot authenticate against another |
| Data | Separate PostgreSQL schema; `MultiTenantConnectionProvider` routes via ThreadLocal on every request |
| Storage | All S3/MinIO paths prefixed with `{tenant_id}/` |
| Application | `TenantContextFilter` sets the schema from the validated `iss` claim on every inbound request |

---

## Per-Tenant Configuration

After provisioning, the tenant admin configures the following in the Setup & Administration module:

| Setting | Options |
| --- | --- |
| KYC provider | `dojah`, `prembly`, `nibss`, `mock` |
| Storage type | `s3`, `gcs`, `azure`, `minio`, `local` |
| Email provider | `sendgrid`, `ses`, `smtp`, `log` |
| SMS provider | `termii`, `twilio`, `log` |
| AI features | enabled / disabled per feature flag |
| Policy number format | configurable prefix + sequence (per product) |
| Data retention period | days (NDPR compliance) |

These settings live in the tenant's own schema — changing them for one tenant does not affect others.

---

## Notes

**`public` is the system/registry schema.** The `public.tenants` table is the authoritative list of provisioned tenants. All business tables live in the per-tenant schema. Flyway V1 manages the `public.tenants` table; all subsequent migrations run in tenant schemas only.

**A REST admin provisioning API is not yet implemented.** The `TenantBootstrapRunner` approach covers the current use case (bootstrap on deploy). A REST API for on-demand provisioning (e.g. a super-admin console) is deferred — it requires a platform-admin authentication layer that is not yet built.

**Known limitation — runtime pgcrypto search path (`runtime-pgcrypto-search-path`).**
`MultiTenantConnectionProvider` currently sets the per-connection `search_path` to the tenant schema only (e.g. `SET search_path TO tenant_acme`). The pgcrypto functions (`pgp_sym_encrypt` / `pgp_sym_decrypt`) live in `public`. At migration time the `BEFORE_EACH_MIGRATE` callback includes `public` in the search path, so DDL works. At runtime, however, the PII encryption `@ColumnTransformer` expressions will fail with "function pgp_sym_encrypt does not exist" unless the runtime connection provider also includes `public` in the path (e.g. `SET search_path TO tenant_acme, public`). This must be resolved before the first real multi-tenant deployment. Tracked in the backlog as `runtime-pgcrypto-search-path`.
