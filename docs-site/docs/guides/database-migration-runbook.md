---
id: database-migration-runbook
title: Database Migration Runbook
sidebar_label: Database Migration Runbook
---

# Database Migration Runbook

This runbook defines the controlled process for applying Core Insurance Application database migrations in test, staging, and production environments.

## Scope

The application uses Flyway migrations from `cia-backend/cia-api/src/main/resources/db/migration`.

Production data is tenant-scoped using the schema-per-tenant model. Platform metadata lives in `public`; insurer business data lives in tenant schemas provisioned and migrated by the tenant schema migrator.

## Pre-Deployment Checks

1. Confirm the release artifact was built from the intended commit.
2. Confirm the target database is reachable from the deployment environment.
3. Confirm `SPRING_PROFILES_ACTIVE` is set to the intended non-dev profile.
4. Confirm `CIA_ENV` is set correctly for the target environment.
5. Confirm the configured PII key is available to the application before Flyway starts.
6. Take a database backup or storage snapshot before applying migrations.
7. Run the fresh-database migration test:

```bash
./mvnw test -pl cia-api -am -Dtest=FreshDatabaseMigrationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false --batch-mode --no-transfer-progress
```

8. Run the report SQL integration test:

```bash
./mvnw test -pl cia-reports -am -Dtest=ReportQueryBuilderIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false --batch-mode --no-transfer-progress
```

## Deployment Sequence

1. Put the environment into the approved deployment window.
2. Stop background workers that may write business data during migration.
3. Apply the application deployment with Flyway enabled.
4. Watch Flyway logs until the latest migration version is reported as successful.
5. Confirm the platform schema version:

```sql
SELECT version, success
FROM flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 1;
```

6. Confirm each active tenant schema has its tenant migration history table and expected business tables.
7. Run smoke checks for authentication, tenant resolution, customer lookup, policy lookup, claims lookup, finance lookup, and report generation.
8. Restart background workers after migration checks pass.

## Rollback Position

Flyway migrations are forward-only. A failed deployment rollback should restore the database backup or snapshot taken before migration, then redeploy the last known-good application version.

Do not manually edit `flyway_schema_history` in production. If a migration fails after partial execution, preserve logs, stop the application, and restore the pre-migration backup unless the incident lead approves a forward repair migration.

## Post-Deployment Checks

1. Confirm application readiness and health endpoints.
2. Confirm report generation works for policies, claims, finance, reinsurance, customers, and endorsements.
3. Confirm tenant provisioning still migrates a new tenant schema.
4. Confirm no unexpected Flyway validation errors appear after restart.
5. Record the deployed application commit, latest Flyway version, backup reference, and smoke-test result in the release notes.
