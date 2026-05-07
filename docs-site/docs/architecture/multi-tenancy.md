---
id: multi-tenancy
title: Multi-Tenancy
sidebar_label: Multi-Tenancy
---

# Multi-Tenancy

CIA uses **schema-per-tenant** isolation in PostgreSQL. Every insurance company gets its own isolated schema (e.g., `tenant_acme`, `tenant_leadway`). This is the active tenancy model for production-readiness work.

## Tenant Resolution

```
Request arrives at /api/v1/policies
  │
  ├── BearerTokenAuthenticationFilter validates JWT
  │     Claims: sub (user_id), realm_access.roles, tenant_id
  │
  └── TenantContextFilter reads tenant_id claim after authentication
        └── public.tenants resolves tenant_id to an active schema_name
              └── TenantContext.setTenantId(schema_name)  [ThreadLocal]
              └── Hibernate CurrentTenantIdentifierResolver returns it
                    └── MultiTenantConnectionProvider routes to correct schema
```

The `tenant_id` claim is embedded in the Keycloak JWT at login time and is immutable for the session lifetime. The API does not trust the claim as a schema name directly. It must resolve to an active row in `public.tenants` by `schema_name`, `subdomain`, or `id`; otherwise the request is rejected with `403`.

Outside `dev` and `test` profiles, missing tenant context fails closed. The `public` schema fallback is available only for local and test execution where a real tenant context is not present.

## Keycloak Isolation

Each tenant gets its own **Keycloak realm**. A token from Tenant A cannot authenticate against Tenant B because:
- The JWKS endpoint is realm-specific (`/realms/{tenant}/protocol/openid-connect/certs`)
- The `tenant_id` claim must resolve to an active tenant registry entry before a tenant schema is selected
- The selected schema name must match the safe PostgreSQL schema pattern `[a-z][a-z0-9_]{0,62}`

## Schema Provisioning

New tenant setup (see [Tenant Provisioning](../guides/tenant-provisioning)):

1. Create Keycloak realm with admin user, roles, and groups
2. `CREATE SCHEMA {tenant_id}` in PostgreSQL
3. Flyway runs all migrations against the new schema
4. Seed default data (currencies, policy number format, approval groups)
5. Configure KYC provider, storage type, notification providers, AI flag

## `public` Schema

The `public` schema holds **only** the tenant registry table — no business data:

```sql
-- public.tenants
id           UUID PRIMARY KEY
schema_name  VARCHAR(63) UNIQUE
name         VARCHAR(255)
subdomain    VARCHAR(63) UNIQUE
active       BOOLEAN
created_at   TIMESTAMPTZ
updated_at   TIMESTAMPTZ
```

## Per-Tenant Configuration

Stored in the tenant schema's `tenant_config` table:

| Config Key | Examples |
|-----------|---------|
| `kyc_provider` | `dojah`, `prembly`; `mock` is dev/test only |
| `storage_type` | `minio`, `s3` |
| `notification_email_provider` | `sendgrid`, `ses`, `smtp`, `log` |
| `notification_sms_provider` | `termii`, `twilio`, `log` |
| `ai_enabled` | `true` / `false` |
| `policy_number_format` | `CIA/{year}/{seq:6}` |
| `data_retention_days` | `2555` (7 years, NDPR default) |
