---
id: tenant-provisioning
title: Tenant Provisioning
sidebar_label: Tenant Provisioning
---

## Overview

Each insurance company is a **tenant** in the CIAGB system. Provisioning a new tenant creates four isolated resources: a Keycloak realm, a PostgreSQL schema, Flyway-migrated tables, and seed configuration data.

Provisioning is done by a **platform administrator** through the admin API or (eventually) the super-admin console. The provisioning token must carry `PLATFORM_ADMIN`; this endpoint is the only authenticated path allowed to run without an existing tenant claim because it creates tenant context.

---

## What Gets Created

```text
1. Keycloak realm  →  {schemaName}
2. PostgreSQL      →  CREATE SCHEMA {schemaName}
3. Flyway          →  baseline the tenant schema at V2, then run V3+ business migrations
4. Tenant registry →  activate the `public.tenants` row after migrations pass
5. Config records  →  seeded by existing migrations and first-use setup services
```

After these five steps the tenant is live. Traffic from `{tenant}.cia.app` routes to its isolated schema.

---

## Provisioning via Admin API

```bash
POST /admin/v1/tenants
Authorization: Bearer <platform-admin-token>
Content-Type: application/json

{
  "schemaName": "tenant_acme",
  "subdomain": "acme",
  "name": "Acme Insurance Ltd"
}
```

**Response:** `201 Created` with the tenant registry record:

```json
{
  "data": {
    "id": "6fd0f5de-7e6b-4622-a274-8e42f4b69080",
    "schemaName": "tenant_acme",
    "subdomain": "acme",
    "name": "Acme Insurance Ltd",
    "active": true
  }
}
```

The API creates the tenant row as inactive, creates the schema, baselines Flyway at V2 inside that schema, runs V3+ tenant migrations, and only then marks the tenant active. Failed provisioning attempts clean up the inactive registry row and newly created schema.

---

## Provisioning Manually (Development)

For local development you can provision a tenant directly:

### 1. Create the Keycloak realm

Open Keycloak Admin Console at [http://localhost:8280](http://localhost:8280) (admin / admin).

- Create a new realm named `tenant_acme`.
- Under **Realm Settings → Keys**, verify RS256 key is present.
- Under **Clients**, create a client:
  - Client ID: `cia-frontend`
  - Client Protocol: `openid-connect`
  - Access Type: `public`
  - Valid Redirect URIs: `http://localhost:5173/*`
- Add the custom claim `tenant_id` = `tenant_acme` to the access token via a mapper on the `cia-frontend` client.

### 2. Register the tenant and create the PostgreSQL schema

```bash
docker exec -it coreinsurance-postgres-1 psql -U cia -d cia -c \
  "INSERT INTO public.tenants (schema_name, subdomain, name, active)
   VALUES ('tenant_acme', 'acme', 'Acme Insurance Ltd', false);
   CREATE SCHEMA tenant_acme;"
```

### 3. Run tenant Flyway migrations

Tenant schemas must be baselined at V2 so public registry migrations do not run inside tenant schemas. The application performs this automatically through `TenantSchemaMigrator`. For manual development, prefer the admin API or restart the Spring Boot app after inserting the tenant registry row.

On startup, `TenantMigrationRunner` applies pending V3+ migrations to every active tenant schema. During API provisioning, migrations run before the tenant is marked active.

### 4. Activate and verify

After migrations pass, activate the tenant if you provisioned manually:

```bash
docker exec -it coreinsurance-postgres-1 psql -U cia -d cia -c \
  "UPDATE public.tenants SET active = true, updated_at = NOW()
   WHERE schema_name = 'tenant_acme';"
```

Verify via:

```bash
docker exec -it coreinsurance-postgres-1 psql -U cia -d cia \
  -c "SELECT * FROM tenant_acme.currencies;"
```

---

## Tenant Isolation Guarantees

| Layer | Mechanism |
| --- | --- |
| Auth | Separate Keycloak realm per tenant; tokens not cross-realmable |
| Data | Separate PostgreSQL schema; `MultiTenantConnectionProvider` routes via ThreadLocal |
| Storage | All S3/MinIO paths prefixed with `{tenant_id}/` |
| Application | `TenantContextFilter` sets schema on every request from JWT claim |

A token issued by `tenant_acme`'s Keycloak realm cannot authenticate against `tenant_leadway`'s API because the JWKS endpoint is realm-specific.

---

## Per-Tenant Configuration

After provisioning, the tenant admin configures the following in the Setup & Administration module:

| Setting | Options |
| --- | --- |
| KYC provider | `dojah`, `prembly`; `mock` is dev/test only |
| Storage type | `s3`, `minio` |
| Email provider | `sendgrid`, `ses`, `smtp`, `log` |
| SMS provider | `termii`, `twilio`, `log` |
| AI features | enabled / disabled per feature flag |
| Policy number format | configurable prefix + sequence |
| Data retention period | days (NDPR compliance) |

These settings live in the tenant's own schema — changing them for one tenant does not affect others.
