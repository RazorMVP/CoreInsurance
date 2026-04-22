---
id: database-migrations
title: Database Migrations
sidebar_label: Database Migrations
---

## Flyway Basics

CIAGB uses [Flyway](https://flywaydb.org/) for all schema changes. Migrations are versioned SQL files that run in order and are never re-run once applied.

**Rule:** Never edit a migration file after it has been committed. Always create a new versioned file.

---

## Migration Files

All migration files live in `cia-api/src/main/resources/db/migration/`:

```text
db/migration/
├── V1__init_public_schema.sql        # tenant registry in public schema
├── V2__init_tenant_schema.sql        # all business tables (applied per tenant schema)
├── V3__add_partner_apps.sql          # example incremental migration
└── ...
```

### Naming Convention

```text
V{version}__{description}.sql
```

- Version: sequential integer (`V3`, `V4`, …) — never reuse a number.
- Description: `__` (double underscore) separator then snake_case description.
- Example: `V5__add_policy_naicom_uid_column.sql`

---

## Multi-Tenant Migration Execution

The application applies migrations to **every registered tenant schema** on startup via `TenantMigrationRunner`. You do not need to run migrations manually per tenant.

```text
App startup
  └── TenantMigrationRunner (ApplicationRunner)
        ├── query public.tenants → list all tenant_ids
        └── for each tenant_id:
              Flyway.configure()
                .schemas(tenant_id)
                .locations("classpath:db/migration")
                .load()
                .migrate()
```

New tenants provisioned after the app is running have migrations applied at provisioning time by `TenantProvisioningService`.

---

## Writing a Migration

### Adding a column

```sql
-- V6__add_claim_surveyor_fields.sql
ALTER TABLE claims
    ADD COLUMN surveyor_name    VARCHAR(255),
    ADD COLUMN surveyor_report  TEXT,
    ADD COLUMN surveyed_at      TIMESTAMPTZ;
```

### Adding a table

```sql
-- V7__create_reinsurance_treaties.sql
CREATE TABLE reinsurance_treaties (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    treaty_type     VARCHAR(50)  NOT NULL,
    treaty_year     INT          NOT NULL,
    effective_from  DATE         NOT NULL,
    effective_to    DATE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(255),
    deleted_at      TIMESTAMPTZ
);

CREATE INDEX idx_ri_treaties_year ON reinsurance_treaties (treaty_year);
CREATE INDEX idx_ri_treaties_type ON reinsurance_treaties (treaty_type);
```

### Adding a constraint

```sql
-- V8__add_policy_number_unique_constraint.sql
ALTER TABLE policies
    ADD CONSTRAINT uq_policies_policy_number UNIQUE (policy_number);
```

---

## Table Conventions

All tables must follow these conventions:

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `UUID` | `DEFAULT gen_random_uuid()`, primary key |
| `created_at` | `TIMESTAMPTZ` | Set by DB default, not application |
| `updated_at` | `TIMESTAMPTZ` | Updated by DB trigger or application |
| `created_by` | `VARCHAR(255)` | Username from JWT `sub` claim |
| `deleted_at` | `TIMESTAMPTZ` | `NULL` = active; non-null = soft deleted |

- Foreign keys enforced at database level — not just application level.
- Indexes on every foreign key column.
- Indexes on high-cardinality filter columns (`status`, `policy_number`, `class_of_business_id`).
- JSONB for flexible payloads: `risk_details` on policies, `old_value`/`new_value` in audit log.

---

## Running Migrations Manually

In development, migrations run automatically on `spring-boot:run`. To run manually:

```bash
cd cia-backend
./mvnw flyway:migrate -pl cia-api \
  -Dflyway.schemas=tenant_dev \
  -Dflyway.url=jdbc:postgresql://localhost:5432/cia \
  -Dflyway.user=cia \
  -Dflyway.password=cia_dev
```

To check migration status:

```bash
./mvnw flyway:info -pl cia-api \
  -Dflyway.schemas=tenant_dev \
  -Dflyway.url=jdbc:postgresql://localhost:5432/cia \
  -Dflyway.user=cia \
  -Dflyway.password=cia_dev
```

---

## Rollback Policy

Flyway Community Edition does not support automatic rollback. To undo a migration:

1. Write a new migration that reverses the change (e.g., `DROP COLUMN`, `ALTER TABLE … DROP CONSTRAINT`).
2. Apply it as the next version number.

For destructive operations in production, always take a snapshot before applying.
