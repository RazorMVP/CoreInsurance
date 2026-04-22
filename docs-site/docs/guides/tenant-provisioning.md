---
id: tenant-provisioning
title: Tenant Provisioning
sidebar_label: Tenant Provisioning
---

## Overview

Each insurance company is a **tenant** in the CIAGB system. Provisioning a new tenant creates four isolated resources: a Keycloak realm, a PostgreSQL schema, Flyway-migrated tables, and seed configuration data.

Provisioning is done by a **super-admin** through the admin API or (eventually) the super-admin console.

---

## What Gets Created

```text
1. Keycloak realm  →  {tenant_id}
2. PostgreSQL      →  CREATE SCHEMA {tenant_id}
3. Flyway          →  run all migrations against {tenant_id} schema
4. Seed data       →  currencies, policy number format, approval groups, default roles
5. Config record   →  KYC provider, storage type, email/SMS providers, AI feature flag
```

After these five steps the tenant is live. Traffic from `{tenant}.cia.app` routes to its isolated schema.

---

## Provisioning via Admin API

```bash
POST /admin/v1/tenants
Authorization: Bearer <super-admin-token>
Content-Type: application/json

{
  "tenantId": "tenant_acme",
  "displayName": "Acme Insurance Ltd",
  "adminEmail": "admin@acme.com",
  "adminPassword": "••••••••",
  "kycProvider": "dojah",
  "storageType": "s3",
  "emailProvider": "sendgrid",
  "smsProvider": "termii",
  "aiEnabled": false
}
```

**Response:** `201 Created` with the tenant record and Keycloak admin credentials.

---

## Provisioning Manually (Development)

For local development you can provision a tenant directly:

### 1. Create the Keycloak realm

Open Keycloak Admin Console at [http://localhost:8180](http://localhost:8180) (admin / admin).

- Create a new realm named `tenant_acme`.
- Under **Realm Settings → Keys**, verify RS256 key is present.
- Under **Clients**, create a client:
  - Client ID: `cia-frontend`
  - Client Protocol: `openid-connect`
  - Access Type: `public`
  - Valid Redirect URIs: `http://localhost:5173/*`
- Add the custom claim `tenant_id` = `tenant_acme` to the access token via a mapper on the `cia-frontend` client.

### 2. Create the PostgreSQL schema

```bash
docker exec -it coreinsurance-postgres-1 psql -U cia -d cia -c "CREATE SCHEMA tenant_acme;"
```

### 3. Run Flyway migrations

Point Flyway at the new schema and run:

```bash
cd cia-backend
./mvnw flyway:migrate -pl cia-api \
  -Dflyway.schemas=tenant_acme \
  -Dflyway.url=jdbc:postgresql://localhost:5432/cia \
  -Dflyway.user=cia \
  -Dflyway.password=cia_dev
```

Or restart the Spring Boot app — on startup, `TenantMigrationRunner` applies all pending migrations to every registered tenant schema automatically.

### 4. Seed default data

Default currencies, policy number sequences, and approval group skeletons are inserted by `TenantSeeder` on first startup for each schema. Verify via:

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
| KYC provider | `dojah`, `prembly`, `nibss`, `mock` |
| Storage type | `s3`, `gcs`, `azure`, `minio`, `local` |
| Email provider | `sendgrid`, `ses`, `smtp`, `log` |
| SMS provider | `termii`, `twilio`, `log` |
| AI features | enabled / disabled per feature flag |
| Policy number format | configurable prefix + sequence |
| Data retention period | days (NDPR compliance) |

These settings live in the tenant's own schema — changing them for one tenant does not affect others.
