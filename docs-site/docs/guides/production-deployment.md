---
id: production-deployment
title: Production Deployment Runbook
sidebar_label: Production Deployment
---

# Production Deployment Runbook

This runbook describes the deployable backend artifact, migration job, health
checks, rollback position, and observability requirements for controlled
production releases.

## Deployment Artifacts

| Artifact | Source | Purpose |
| --- | --- | --- |
| Backend image | `cia-backend/Dockerfile` | Builds the executable `cia-api` Spring Boot image. |
| Production Compose template | `docker/production/docker-compose.yml` | Documents the required API and migration services for a container runtime. |
| Production env example | `docker/production/production.env.example` | Lists required non-local environment variables without committing real secrets. |
| Backend image workflow | `.github/workflows/backend-image.yml` | Builds the backend image, scans it for high and critical CVEs, and publishes to GHCR outside pull requests. |
| Observability pack | `ops/observability/` | Provides Prometheus alert rules and a Grafana dashboard starter. |

The backend image runs as a non-root user, exposes `8090`, and includes a
container liveness healthcheck against `/actuator/health/liveness`.

## Required Deployment Sequence

1. Build, scan, and publish a backend image from the intended commit.
2. Take a database backup or storage snapshot.
3. Validate the target environment file with
   `scripts/validate-production-env.sh`.
4. Render the production Compose configuration with the same environment file.
5. Run the migration service with `CIA_MIGRATION_ONLY=true`.
6. Confirm the migration service exits successfully.
7. Start or roll the `cia-api` service.
8. Wait for `/actuator/health/readiness` to return `UP`.
9. Run smoke checks for authentication, tenant resolution, customers, quotes,
   policies, claims, finance, reports, setup, and audit.
10. Record the commit, image digest, CVE scan result, Flyway version, backup reference, and smoke
   result in the release notes.

Do not serve traffic to a new application image until the migration service has
completed successfully.

The backend image workflow enforces the vulnerability gate with Trivy. The
release image must have no unresolved high or critical CVEs before production
approval, and the SARIF report should remain available in GitHub code scanning
for audit review.

## Migration Job

The same backend image is used for both the migration job and the API service.
Set:

```bash
CIA_MIGRATION_ONLY=true
```

Spring Boot applies public Flyway migrations first, then the tenant migration
runner migrates all active tenant schemas. In migration-only mode, the
application context closes after tenant migrations complete, allowing the
container job to exit without starting a long-running API process.

## Health And Readiness

| Endpoint | Purpose |
| --- | --- |
| `/actuator/health/liveness` | Container liveness probe. |
| `/actuator/health/readiness` | Traffic readiness gate. |
| `/actuator/metrics` | JVM, HTTP, datasource, and application metrics. |
| `/actuator/prometheus` | Prometheus scrape endpoint. |

The readiness health group includes:

- Spring readiness state
- Database health
- Redis health
- Temporal worker health
- External dependency configuration health

Readiness is allowed to be relaxed in dev/test, but production-like profiles
require non-local Keycloak, storage, KYC, NAICOM, and NIID configuration. Phase
6 still applies: live KYC, NAICOM, and NIID provider adapters are go-live work,
so production deployment must not proceed until those adapters are implemented
and contract-tested.

## Environment And Secrets

Use a vault or platform secret manager for all secrets. Never commit real
values to `.env`, Compose files, CI YAML, or docs.

Minimum required secret-backed values:

- `DB_PASSWORD`
- `PII_ENCRYPTION_KEY`
- `WEBHOOK_SIGNING_SECRET`
- `STORAGE_ACCESS_KEY`
- `STORAGE_SECRET_KEY`
- `SMTP_USERNAME`
- `SMTP_PASSWORD`
- Live KYC, NAICOM, and NIID credentials when provider work is complete

`PII_ENCRYPTION_KEY` is data-critical. Losing it makes encrypted customer PII
unrecoverable; rotating it requires a controlled re-encryption procedure.

Before a clean-environment rehearsal, copy
`docker/production/production.env.example` to the target environment and replace
all placeholders with secret-manager values. Then run:

```bash
scripts/validate-production-env.sh .env.production
docker compose --env-file .env.production -f docker/production/docker-compose.yml config
```

The preflight intentionally fails when it sees placeholder values, local
endpoints, `latest` image tags, dev/test Spring profiles, mock/stub providers,
disabled rate limiting, or short PII/webhook secrets. Use
`--allow-placeholders` only to validate the checked-in example file shape.

## Rollback

Application rollback is safe only if the database did not migrate or if the
rollback application version is compatible with the migrated schema.

If a migration has already run:

1. Stop the new API service.
2. Preserve logs and the failed image digest.
3. Restore the database backup or snapshot taken before migration, unless the
   incident lead approves a forward repair migration.
4. Redeploy the last known-good image.
5. Confirm readiness and run smoke checks.

Do not manually edit `flyway_schema_history` in production.

## Observability

Production must collect:

- Application logs with tenant id, request id, and workflow id where available
- `/actuator/prometheus` metrics
- Distributed traces through the deployment platform's OpenTelemetry collector
- JVM memory, GC, thread, and HTTP latency metrics
- Datasource pool usage and database error rates
- Temporal worker readiness and workflow failure counts
- Integration failure counts for KYC, NAICOM, NIID, email, SMS, and webhooks
- Rate-limit rejection counts for partner and sensitive endpoints

Minimum alert set:

| Signal | Alert condition |
| --- | --- |
| Readiness | `/actuator/health/readiness` is not `UP` for 2 minutes. |
| Database | Connection pool exhaustion or repeated connection failures. |
| Temporal | `temporalWorker` health is `DOWN`. |
| Migrations | Migration job exits non-zero. |
| Webhooks | Delivery failure rate exceeds the agreed threshold. |
| Security | Sensitive endpoint rate-limit rejections spike. |
| Storage | Upload or download failures exceed the agreed threshold. |

Import `ops/observability/prometheus-alerts.yml` into Prometheus and
`ops/observability/grafana-dashboard-coreinsurance.json` into Grafana before
the first controlled live deployment. The default selectors expect
`job="cia-api"` for the backend scrape target and `job="cia-readiness"` for the
readiness probe.

## Validation Commands

```bash
docker build -f cia-backend/Dockerfile -t cia-backend:phase10 cia-backend
scripts/validate-production-env.sh --allow-placeholders docker/production/production.env.example
docker-compose --env-file docker/production/production.env.example -f docker/production/docker-compose.yml config
./mvnw test -pl cia-api -am -Dtest=ExternalDependenciesHealthIndicatorTest,TenantMigrationRunnerTest,TemporalWorkerHealthIndicatorTest -Dsurefire.failIfNoSpecifiedTests=false --batch-mode --no-transfer-progress
```
