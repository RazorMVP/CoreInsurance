---
id: multi-tenancy
title: Multi-Tenancy
sidebar_label: Multi-Tenancy
---

# Multi-Tenancy

CIA uses **schema-per-tenant** isolation in PostgreSQL. Every insurance company gets its own isolated schema (e.g., `tenant_acme`, `tenant_leadway`).

## Tenant Resolution

```
Request arrives at /api/v1/policies
  │
  ├── JwtAuthenticationFilter validates JWT
  │     Claims: sub (user_id), realm_access.roles, tenant_id
  │
  └── TenantContextFilter reads tenant_id claim
        └── TenantContext.setTenantId(tenantId)  [ThreadLocal]
              └── Hibernate CurrentTenantIdentifierResolver returns it
                    └── MultiTenantConnectionProvider routes to correct schema
```

The `tenant_id` claim is embedded in the Keycloak JWT at login time and is immutable for the session lifetime.

## Keycloak Isolation

Each tenant gets its own **Keycloak realm**. A token from Tenant A cannot authenticate against Tenant B because:
- The JWKS endpoint is realm-specific (`/realms/{tenant}/protocol/openid-connect/certs`)
- The `TenantContextFilter` validates that the `tenant_id` claim in the JWT matches the expected realm

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
id          UUID PRIMARY KEY
slug        TEXT UNIQUE    -- used as schema name and subdomain
name        TEXT
created_at  TIMESTAMPTZ
active      BOOLEAN
```

## Per-Tenant Configuration

Stored in the tenant schema's `tenant_config` table:

| Config Key | Examples |
|-----------|---------|
| `kyc_provider` | `dojah`, `prembly`, `nibss`, `mock` |
| `storage_type` | `minio`, `s3`, `gcs`, `azure` |
| `notification_email_provider` | `sendgrid`, `ses`, `smtp`, `log` |
| `notification_sms_provider` | `termii`, `twilio`, `log` |
| `ai_enabled` | `true` / `false` |
| `policy_number_format` | `CIA/{year}/{seq:6}` |
| `data_retention_days` | `2555` (7 years, NDPR default) |
