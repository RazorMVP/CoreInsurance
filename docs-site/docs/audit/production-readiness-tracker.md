---
id: production-readiness-tracker
title: Production Readiness Fix Tracker
sidebar_label: Production Readiness Tracker
---

# Production Readiness Fix Tracker

Last updated: 2026-05-08 08:30 WAT

This tracker captures the fixes required before the Core Insurance Application can be considered ready for full testing and live deployment by insurance companies.

The purpose of this document is to support decision-making, sequencing, ownership, and verification. No item should be marked complete until the related implementation, tests, documentation, and deployment checks have passed.

## Status Legend

| Status | Meaning |
| --- | --- |
| Not started | No implementation work has begun. |
| In progress | Implementation is underway. |
| Blocked | Work cannot proceed without a decision, dependency, credential, vendor input, or architecture choice. |
| In review | Implementation is complete and awaiting review or validation. |
| Verified | Code, tests, docs, and deployment checks have passed. |

## Release Gates

These gates must pass before live deployment approval.

| Gate | Required outcome | Status | Notes |
| --- | --- | --- | --- |
| Build gate | Backend, frontend, docs, and Docker config build successfully from a clean checkout. | In progress | Phase 0 and Phase 1 working-tree baselines passed on branch `production-readiness-phase-0`; repeat from clean checkout after committing the phase. |
| Security gate | Production cannot run with dev profile, default secrets, mock providers, or unauthenticated endpoints. | In progress | Phase 1 startup guardrails are verified; endpoint authorization remains in Phase 2. |
| Authorization gate | Role and scope checks are enforced and tested for critical endpoints. | Verified | Phase 2 backend/frontend authorization fixes are implemented and full verification passed. |
| Tenant isolation gate | Tenant data isolation is proven with automated tests. | Verified | Phase 3 is closed. Tenant resolution, provisioning, migration, HTTP authorization, and two-tenant isolation checks passed against local Docker Compose PostgreSQL. |
| Data correctness gate | Reports, premium calculations, finance postings, and migrations are validated. | Verified | Phase 4 database migration/report SQL is closed and all Phase 5 insurance and finance correctness items are verified. |
| Integration gate | KYC, NAICOM, NIID, and Temporal workflows are implemented or explicitly blocked outside dev/test. | Verified | Phase 6 hard-blocks pending live KYC, NAICOM, and NIID adapters until go-live provider work is complete; Phase 7 implements and verifies Temporal approval, NAICOM, NIID, and webhook worker execution/registration. |
| PII protection gate | PII is encrypted, redacted, or excluded from logs, audit records, files, and webhook payload history. | Verified | Phase 8 is closed for the current pre-go-live scope. Audit snapshots are redacted, upload limits/type checks/scanner hooks are enforced, webhook payload history is not retained, webhook responses are sanitized, storage tenant fallback is blocked, SSRF validation is in place, API docs default private, and sensitive endpoint rate limits are enforced. |
| Frontend contract gate | Production UI screens are wired to real backend contracts or intentionally disabled. | Verified | Phase 9 is closed. Core contract fixes are implemented, users setup is disabled until backed by an endpoint, production demo auth is removed, and Playwright smoke coverage passes for core back-office routes. |
| Deployment gate | Backend deployment, migrations, health checks, readiness checks, secrets, rollback, and monitoring are documented and tested. | Verified | Phase 10 adds and verifies the backend image, migration job mode, production Compose template, health/readiness checks, CI image workflow, deployment runbook, rollback procedure, and observability pack. A clean-environment deployment rehearsal remains Phase 11. |

## Phase 0: Baseline And Tracking

Goal: establish a known-good baseline before production hardening begins.

| ID | Fix item | Status | Owner | Verification |
| --- | --- | --- | --- | --- |
| P0-001 | Create a stabilization branch from the current working state. | Verified | TBD | Branch `production-readiness-phase-0` exists and current changes are preserved. |
| P0-002 | Record baseline build commands for backend, frontend, docs, and Docker config. | Verified | TBD | Commands are listed in the Phase 0 baseline run log below. |
| P0-003 | Run backend Maven verification. | Verified | TBD | `./mvnw verify --batch-mode --no-transfer-progress` passed. |
| P0-004 | Run frontend typechecks for back-office and partner apps. | Verified | TBD | `pnpm --filter @cia/back-office typecheck` and `pnpm --filter @cia/partner typecheck` passed. |
| P0-005 | Run docs build. | Verified | TBD | `npm run build` passed in `docs-site`. |
| P0-006 | Validate Docker Compose config. | Verified | TBD | `docker-compose config` passed. |

### Phase 0 Baseline Run Log

| Field | Value |
| --- | --- |
| Baseline date | 2026-05-06 |
| Baseline branch | `production-readiness-phase-0` |
| Starting branch | `main` |
| Starting commit | `b04f7b5` |
| Worktree state | Existing local changes were preserved; the baseline was run against the current working tree. |

| Command | Directory | Result | Notes |
| --- | --- | --- | --- |
| `./mvnw verify --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Maven reactor completed all 20 modules successfully. OpenAPI/Postman generation emitted warnings about `{{baseUrl}}/partner/v1` and JSON formatting, but the build exited successfully. |
| `pnpm --filter @cia/back-office typecheck` | `cia-frontend` | Passed | TypeScript completed with `tsc --noEmit`. |
| `pnpm --filter @cia/partner typecheck` | `cia-frontend` | Passed | TypeScript completed with `tsc --noEmit`. |
| `npm run build` | `docs-site` | Passed | Docusaurus generated static files successfully. Existing warnings remain for deprecated `onBrokenMarkdownLinks` config and local update-check permissions. |
| `docker-compose config` | repository root | Passed | Compose configuration rendered successfully for local infrastructure services. |

Phase 0 closure note: the working-tree baseline is verified. The build gate should be repeated from a clean checkout after the baseline branch is committed, because the current repository intentionally contains uncommitted local changes from the earlier stabilization work.

## Phase 1: Production Safety Guardrails

Goal: prevent unsafe production startup.

| ID | Fix item | Status | Owner | Verification |
| --- | --- | --- | --- | --- |
| P1-001 | Remove `dev` as the default backend profile. | Verified | TBD | `application.yml` no longer activates `dev` implicitly; backend startup requires an explicit Spring profile. |
| P1-002 | Fail startup if a production-like environment uses the dev security profile. | Verified | TBD | `ProductionSafetyValidatorTest` rejects `dev` with production-like `CIA_ENV`. |
| P1-003 | Reject known development PII keys outside dev/test. | Verified | TBD | `ProductionSafetyValidatorTest` rejects the checked-in dev PII key outside dev/test profiles. |
| P1-004 | Fail startup if production uses mock KYC or stub NAICOM/NIID providers. | Verified | TBD | `ProductionSafetyValidatorTest` rejects mock KYC and stub NAICOM/NIID outside dev/test profiles. |
| P1-005 | Fail startup when required production JWT, database, storage, or integration config is missing. | Verified | TBD | `ProductionSafetyValidatorTest` covers production JWT, database, storage, webhook, and integration config guardrails. |
| P1-006 | Update local setup docs so developer startup remains simple after profile changes. | Verified | TBD | Local setup, environment variable, testing, and Compose docs now use explicit `SPRING_PROFILES_ACTIVE=dev`. |

### Phase 1 Run Log

| Field | Value |
| --- | --- |
| Phase date | 2026-05-06 |
| Branch | `production-readiness-phase-0` |
| Scope | Production startup safety guardrails; no auth/authorization endpoint policy changes yet. |

| Command | Directory | Result | Notes |
| --- | --- | --- | --- |
| `./mvnw test -pl cia-common` | `cia-backend` | Passed | 32 common-module tests passed, including PII and production safety validator coverage. |
| `./mvnw verify --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Maven reactor completed all 20 modules successfully. Existing OpenAPI/Postman generation warnings remain. |
| `pnpm --filter @cia/back-office typecheck` | `cia-frontend` | Passed | TypeScript completed with `tsc --noEmit`. |
| `pnpm --filter @cia/partner typecheck` | `cia-frontend` | Passed | TypeScript completed with `tsc --noEmit`. |
| `npm run build` | `docs-site` | Passed | Docusaurus generated static files successfully. Existing Docusaurus deprecation/update-check warnings remain. |
| `docker-compose config` | repository root | Passed | Compose configuration rendered successfully for local infrastructure services. |

## Phase 2: Authentication And Authorization

Goal: enforce backend access control consistently.

| ID | Fix item | Status | Owner | Verification |
| --- | --- | --- | --- | --- |
| P2-001 | Enable Spring method security for `@PreAuthorize` checks. | Verified | TBD | `MethodSecurityConfig` enables method security outside `dev`; tests prove role and authority checks allow/deny correctly. |
| P2-002 | Review all admin, setup, finance, policy, claim, customer, report, document, audit, and approval endpoints. | Verified | TBD | `authorization-matrix.md` records the endpoint authority model; `ControllerAuthorizationCoverageTest` fails the build if a new back-office handler lacks explicit `@PreAuthorize`. |
| P2-003 | Standardize Keycloak roles, backend authorities, and frontend route guards. | Verified | TBD | JWT and frontend auth normalization now bridge role-style and permission-style grants; back-office routes and navigation use the same authority model. |
| P2-004 | Add negative authorization tests for critical endpoints. | Verified | TBD | `ReportControllerAuthorizationTest` proves a real reports endpoint returns `403` for the wrong role; method-security tests cover direct role/authority denial. |
| P2-005 | Add partner scope tests for partner API endpoints. | Verified | TBD | Partner scope tests now cover missing auth, missing scope, and correctly scoped JWT behavior. |

### Phase 2 Run Log

| Field | Value |
| --- | --- |
| Phase date | 2026-05-06 |
| Branch | `production-readiness-phase-0` |
| Scope | Non-dev method security, JWT/frontend authority normalization, route guards, endpoint authorization matrix, controller coverage tests, and partner scope enforcement tests. |

| Command | Directory | Result | Notes |
| --- | --- | --- | --- |
| `./mvnw test -pl cia-auth` | `cia-backend` | Passed | Covers method security allow/deny behavior and JWT role/permission/scope conversion. |
| `./mvnw test -pl cia-auth,cia-partner-api` | `cia-backend` | Passed | Adds partner scope allow/deny behavior coverage. |
| `./mvnw test -pl cia-auth,cia-reports,cia-partner-api,cia-api -am --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Covers auth conversion, method security, partner scope enforcement, reports HTTP authorization, and all-controller `@PreAuthorize` coverage. |
| `pnpm --filter @cia/back-office typecheck` | `cia-frontend` | Passed | Verifies back-office route guard and authority normalization TypeScript. |
| `pnpm --filter @cia/partner typecheck` | `cia-frontend` | Passed | Partner app typecheck remains green. |
| `pnpm --filter @cia/back-office build` | `cia-frontend` | Passed | Vite production build succeeded. Existing large chunk warning remains for the main bundle. |
| `pnpm --filter @cia/partner build` | `cia-frontend` | Passed | Vite production build succeeded. |
| `./mvnw verify --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Maven reactor completed all 20 modules successfully. Existing OpenAPI/Postman warnings remain. |
| `npm run build` | `docs-site` | Passed | Docusaurus generated static files successfully. Existing Docusaurus deprecation/update-check warnings remain. |
| `docker-compose config` | repository root | Passed | Compose configuration rendered successfully. |

## Phase 3: Tenant Architecture And Isolation

Goal: make tenant isolation real and testable.

| ID | Fix item | Status | Owner | Verification |
| --- | --- | --- | --- | --- |
| P3-001 | Confirm final tenancy model: schema-per-tenant or single-schema `tenant_id`. | Verified | TBD | Schema-per-tenant is documented as the active production-readiness model in the architecture docs. |
| P3-002 | Correct tenant filter ordering so JWT claims are available before tenant resolution. | Verified | TBD | Tenant filter now runs after bearer token authentication; focused auth/common tests passed. |
| P3-003 | Validate tenant IDs against the tenant registry. | Verified | TBD | Tenant claims must resolve to an active `public.tenants` row; unknown or inactive claims return `403`. |
| P3-004 | Remove fallback to `public` tenant outside dev/test. | Verified | TBD | `TenantIdentifierResolverTest` proves missing tenant context throws outside `dev` and `test`. |
| P3-005 | Implement tenant provisioning. | Verified | TBD | `POST /admin/v1/tenants` creates inactive registry rows, creates schemas, migrates them, and activates only after success; Docker-backed integration tests passed. |
| P3-006 | Implement per-tenant migrations for schema-per-tenant. | Verified | TBD | `TenantSchemaMigrator` baselines tenant schemas at V2 and runs V3+ migrations on provisioning and startup; Docker-backed integration tests passed. |
| P3-007 | Add two-tenant isolation tests. | Verified | TBD | `TenantProvisioningServiceIntegrationTest` proves two tenant schemas keep customer rows isolated through schema routing and migration reruns. |
| P3-008 | Add HTTP authorization proof for platform tenant provisioning. | Verified | TBD | `TenantProvisioningControllerAuthorizationTest` proves only `PLATFORM_ADMIN` can call `POST /admin/v1/tenants` and rejected users do not invoke provisioning. |

### Phase 3 Run Log

| Field | Value |
| --- | --- |
| Phase date | 2026-05-07 |
| Branch | `production-readiness-phase-0` |
| Closure status | Closed |
| Scope | Tenant resolution ordering, tenant registry validation, fail-closed missing context outside dev/test, schema-name safety, platform tenant provisioning, per-tenant migrations, and Docker-backed tenant isolation tests. |

| Command | Directory | Result | Notes |
| --- | --- | --- | --- |
| `./mvnw test -pl cia-common,cia-auth -am --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Covers missing tenant fail-closed behavior, dev/test public fallback, tenant registry validation, tenant claim resolution, unknown tenant rejection, unsafe schema rejection, and tenant context cleanup. |
| `./mvnw verify --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Maven reactor completed all 20 modules successfully after tenant guardrail changes. Existing OpenAPI/Postman, Commons Logging, and deprecation warnings remain non-blocking. |
| `pnpm --filter @cia/back-office typecheck` | `cia-frontend` | Passed | Back-office TypeScript remains green with Phase 2 frontend authorization work still in the working tree. |
| `pnpm --filter @cia/partner typecheck` | `cia-frontend` | Passed | Partner TypeScript remains green. |
| `pnpm --filter @cia/back-office build` | `cia-frontend` | Passed | Vite production build succeeded. Existing large chunk warning remains for the main bundle. |
| `pnpm --filter @cia/partner build` | `cia-frontend` | Passed | Vite production build succeeded. |
| `npm run build` | `docs-site` | Passed | Docusaurus generated static files successfully after the multi-tenancy docs update. Existing Docusaurus deprecation/update-check warnings remain. |
| `docker-compose config` | repository root | Passed | Compose configuration rendered successfully. |
| `git diff --check` | repository root | Passed | No whitespace errors found. |
| `./mvnw test -pl cia-auth,cia-api -am -Dtest=TenantContextFilterTest,TenantProvisioningServiceIntegrationTest,ControllerAuthorizationCoverageTest -Dsurefire.failIfNoSpecifiedTests=false --batch-mode --no-transfer-progress` | `cia-backend` | Passed with skips | Auth/platform provisioning filter tests and controller coverage passed. The Testcontainers tenant isolation tests were skipped because the Java Docker client failed discovery even though Docker CLI access works. |
| `./mvnw verify --batch-mode --no-transfer-progress` | `cia-backend` | Passed with skips | Maven reactor completed all 20 modules successfully. `TenantProvisioningServiceIntegrationTest` was skipped because Testcontainers could not establish a valid Java Docker environment. |
| `docker version` / `docker info` / `docker --context desktop-linux ps` | repository root | Passed with escalation | Docker Desktop 4.69.0 is running, the daemon is reachable through the CLI, and existing CoreInsurance containers are healthy. |
| `docker run --rm postgres:16-alpine postgres --version` | repository root | Passed with escalation | Proves the local Docker daemon can create and run a short-lived PostgreSQL container. |
| `./mvnw test -pl cia-api -am -Dtest=TenantSchemaNameTest,TenantProvisioningControllerAuthorizationTest,TenantProvisioningServiceIntegrationTest,ControllerAuthorizationCoverageTest -Dsurefire.failIfNoSpecifiedTests=false --batch-mode --no-transfer-progress` | `cia-backend` | Passed with escalation | Runs tenant schema/subdomain validation, HTTP authorization, provisioning, and isolation tests against the local Docker Compose PostgreSQL on `localhost:5434`; eight API tests passed with zero skips. |
| `./mvnw verify --batch-mode --no-transfer-progress` | `cia-backend` | Passed with escalation | Maven reactor completed all 20 modules successfully with the Docker-backed tenant integration tests and platform provisioning authorization test running. |
| `npm run build` | `docs-site` | Passed | Docusaurus generated static files successfully after provisioning docs updates. Existing Docusaurus deprecation/update-check warnings remain. |
| `docker-compose config` | repository root | Passed | Compose configuration rendered successfully. |
| `git diff --check` | repository root | Passed | No whitespace errors found. |

Docker position: Docker is available locally and can create containers. The earlier skipped test path was caused by Testcontainers' Java Docker discovery/client compatibility in this local environment, so Phase 3 verification now uses the existing Docker Compose PostgreSQL service directly for JDBC-backed tenant isolation tests.

Phase 3 closure note: Phase 3 is confirmed closed as of 2026-05-07 after commits `30b80fb`, `3743237`, and `96fa2c6` were pushed to `production-readiness-phase-0`. All Phase 3 tracker items are `Verified`, the tenant isolation release gate is `Verified`, full backend verification passed, docs build passed, Compose config rendered successfully, and whitespace checks passed.

## Phase 4: Database, Migrations, And Reporting

Goal: make database-backed operations reliable.

| ID | Fix item | Status | Owner | Verification |
| --- | --- | --- | --- | --- |
| P4-001 | Align report SQL with actual migration table and column names. | Verified | TBD | `ReportQueryBuilderIntegrationTest` passed against local Docker Compose PostgreSQL with zero skips. |
| P4-002 | Review native SQL across all modules for schema drift. | Verified | TBD | Native SQL inventory is recorded below; the report SQL drift was corrected, and migration/tenant SQL paths were covered by focused tests. |
| P4-003 | Add fresh-database migration test. | Verified | TBD | `FreshDatabaseMigrationIntegrationTest` migrated an empty disposable PostgreSQL database to Flyway version `31`. |
| P4-004 | Add seeded report generation tests. | Verified | TBD | Fresh-database test executes all 55 active seeded system reports through `ReportQueryBuilder`; representative seeded row/totals tests also pass. |
| P4-005 | Review indexes for policies, claims, customers, finance, audit, and reports. | Verified | TBD | `V31__reporting_query_indexes.sql` adds missing date/grouping indexes used by report and dashboard read paths. |
| P4-006 | Define production migration and rollback procedure. | Verified | TBD | `database-migration-runbook.md` documents pre-checks, deployment sequence, rollback position, and post-deployment checks; docs build passed. |

### Phase 4 Native SQL Inventory

| Area | File | Position |
| --- | --- | --- |
| Reports | `cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportQueryBuilder.java` | Uses tenant-scoped `JdbcTemplate` SQL; corrected to use migrated table and column names for policies, claims, finance, reinsurance, customers, and endorsements. |
| Dashboard | `cia-api/src/main/java/com/nubeero/cia/dashboard/DashboardService.java` | Uses native SQL for dashboard cards, trends, renewals, search, recent activity, and RI utilisation; table/column names match the migration schema, with finance business semantics deferred to Phase 5. |
| Tenant registry | `cia-auth/src/main/java/com/nubeero/cia/auth/JdbcTenantRegistry.java` | Uses `public.tenants`; covered by Phase 3 tenant registry tests. |
| Tenant migration | `cia-api/src/main/java/com/nubeero/cia/tenant/TenantSchemaMigrator.java` | Uses schema-history and `to_regclass` checks; covered by Phase 3 tenant migration tests. |
| Tenant provisioning | `cia-api/src/main/java/com/nubeero/cia/tenant/TenantProvisioningService.java` | Uses parameterized registry queries plus validated schema identifiers; covered by Phase 3 provisioning and isolation tests. |

### Phase 4 Run Log

| Field | Value |
| --- | --- |
| Phase date | 2026-05-07 |
| Branch | `production-readiness-phase-0` |
| Scope | Report SQL schema alignment, database-backed report tests, fresh database Flyway test, reporting index migration, native SQL inventory, and migration runbook. |

| Command | Directory | Result | Notes |
| --- | --- | --- | --- |
| `docker-compose up -d postgres` | repository root | Passed with escalation | Started the local PostgreSQL service required for Phase 4 database-backed tests. |
| `docker-compose ps postgres` | repository root | Passed with escalation | PostgreSQL was healthy on `localhost:5434`. |
| `./mvnw test -pl cia-reports -am -Dtest=ReportQueryBuilderIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false --batch-mode --no-transfer-progress` | `cia-backend` | Passed with escalation | Three report SQL integration tests passed against Docker Compose PostgreSQL with zero skips. |
| `./mvnw test -pl cia-api -am -Dtest=FreshDatabaseMigrationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false --batch-mode --no-transfer-progress` | `cia-backend` | Passed with escalation | A disposable database migrated from empty to Flyway version `31`; reporting index migration was verified. |
| `./mvnw verify --batch-mode --no-transfer-progress` | `cia-backend` | Passed with escalation | Maven reactor completed all 20 modules successfully; tenant and migration tests applied tenant schemas and disposable databases to Flyway version `31`. |
| `npm run build` | `docs-site` | Passed | Docusaurus generated static files successfully after the runbook and tracker updates. Existing Docusaurus deprecation/update-check warnings remain. |
| `docker-compose config` | repository root | Passed | Compose configuration rendered successfully. |
| `git diff --check` | repository root | Passed | No whitespace errors found. |
| `./mvnw test -pl cia-api -am -Dtest=FreshDatabaseMigrationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false --batch-mode --no-transfer-progress` | `cia-backend` | Passed with escalation | Expanded fresh-migration coverage executes all 55 active seeded system reports against the migrated schema. |
| `./mvnw verify --batch-mode --no-transfer-progress` | `cia-backend` | Passed with escalation | Maven reactor completed all 20 modules successfully after expanded seeded-report coverage. |
| `npm run build` | `docs-site` | Passed | Docusaurus generated static files successfully after the Phase 4 closure update. Existing Docusaurus warnings remain. |
| `docker-compose config` | repository root | Passed | Compose configuration rendered successfully after the Phase 4 closure update. |
| `git diff --check` | repository root | Passed | No whitespace errors found after the Phase 4 closure update. |

Phase 4 closure note: the Phase 4 database, migration, and reporting scope is implementation-complete as of 2026-05-07. It proves fresh PostgreSQL migration to Flyway version `31`, tenant schema migration to version `31`, execution of all active seeded system reports, report SQL alignment with migrated table and column names, reporting indexes, and the production migration rollback runbook. Premium calculation and finance posting correctness remain in Phase 5.

## Phase 5: Insurance And Finance Correctness

Goal: prevent incorrect policy, claim, endorsement, and finance outcomes.

| ID | Fix item | Status | Owner | Verification |
| --- | --- | --- | --- | --- |
| P5-001 | Align quote and direct policy premium calculations. | Verified | TBD | `PremiumCalculatorTest`, `QuoteServiceTest`, and `PolicyServiceTest` prove percentage-rate premium calculation for quote and direct policy paths. |
| P5-002 | Prevent receipt and payment overposting unless an approved overpayment workflow exists. | Verified | TBD | Receipt and payment services reject posting above outstanding balances, and note rows are locked during posting. |
| P5-003 | Add policy issuance tests from quote and direct policy creation. | Verified | TBD | Direct-created and quote-bound policy approvals publish finance events with approved net premium, and debit-note creation uses the event as the receivable source. |
| P5-004 | Add endorsement premium adjustment tests. | Verified | TBD | Endorsement tests prove pro-rata additional/return premium adjustments, approval event payloads, and debit/credit-note routing for financial impact. |
| P5-005 | Add claim lifecycle transition tests. | Verified | TBD | Claim tests prove registered-to-settled happy path and invalid submit, approve, settle, and rejected-withdrawal transitions. |
| P5-006 | Add finance settlement and outstanding balance tests. | Verified | TBD | Debit-note and credit-note recalculation tests prove unpaid, partial, and settled statuses; receipt/payment tests prove posting and reversal recalculate the correct paid amount. |

### Phase 5 Run Log

| Field | Value |
| --- | --- |
| Phase date | 2026-05-07 |
| Branch | `production-readiness-phase-0` |
| Scope | Insurance and finance correctness, starting with quote/direct-policy premium parity. |

| Command | Directory | Result | Notes |
| --- | --- | --- | --- |
| `./mvnw test -pl cia-common,cia-quotation,cia-policy -am -Dtest=PremiumCalculatorTest,QuoteServiceTest,PolicyServiceTest -Dsurefire.failIfNoSpecifiedTests=false --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Proves shared gross premium calculation at percentage rate, quote creation premium totals, direct policy creation premium totals, and discount capping behavior. |
| `./mvnw test -pl cia-common,cia-quotation,cia-policy -am --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Affected module test suite passed after P5-001 changes. |
| `./mvnw verify --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Maven reactor completed all 20 modules successfully after P5-001 changes. Existing optional database integration tests reported skips in the non-escalated local path. |
| `./mvnw test -pl cia-finance -am -Dtest=ReceiptServiceTest,PaymentServiceTest -Dsurefire.failIfNoSpecifiedTests=false --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Proves debit-note receipt and credit-note payment overposting attempts are rejected before saving or recalculating status. |
| `./mvnw test -pl cia-common,cia-quotation,cia-policy,cia-finance -am --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Affected module suite passed after P5-001 and P5-002 changes. |
| `./mvnw verify --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Maven reactor completed all 20 modules successfully after P5-002 changes. Existing optional database integration tests reported skips in the non-escalated local path. |
| `./mvnw test -pl cia-policy,cia-finance -am -Dtest=PolicyServiceTest,DebitNoteServiceTest -Dsurefire.failIfNoSpecifiedTests=false --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Proves direct-created and quote-bound policy approvals publish finance events with the approved net premium, and debit-note creation records that receivable. |
| `./mvnw test -pl cia-common,cia-quotation,cia-policy,cia-finance -am --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Affected module suite passed after P5-003 tests were added. |
| `./mvnw verify --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Maven reactor completed all 20 modules successfully after P5-003 changes. Existing optional database integration tests reported skips in the non-escalated local path. |
| `./mvnw test -pl cia-endorsement,cia-finance -am -Dtest=EndorsementServiceTest,EndorsementApprovedEventListenerTest -Dsurefire.failIfNoSpecifiedTests=false --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Proves additional premium, return premium, zero premium, approval event, and finance debit/credit routing behavior for endorsements. |
| `./mvnw test -pl cia-common,cia-quotation,cia-policy,cia-endorsement,cia-finance -am --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Affected module suite passed after P5-004 tests were added. |
| `./mvnw verify --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Maven reactor completed all 20 modules successfully after P5-004 changes. Existing optional database integration tests reported skips in the non-escalated local path. |
| `./mvnw test -pl cia-claims -am -Dtest=ClaimServiceTest -Dsurefire.failIfNoSpecifiedTests=false --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Proves claim registered-to-settled lifecycle and rejects invalid submit, approve, settle, and rejected-withdrawal transitions. |
| `./mvnw test -pl cia-common,cia-quotation,cia-policy,cia-claims,cia-endorsement,cia-finance -am --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Affected module suite passed after P5-005 lifecycle transition changes. |
| `./mvnw verify --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Maven reactor completed all 20 modules successfully after P5-005 changes. Existing optional database integration tests reported skips in the non-escalated local path. |
| `./mvnw test -pl cia-finance -am -Dtest=DebitNoteServiceTest,CreditNoteServiceTest,ReceiptServiceTest,PaymentServiceTest -Dsurefire.failIfNoSpecifiedTests=false --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Proves debit-note and credit-note unpaid, partial, and settled status recalculation, plus receipt/payment posting and reversal paid-amount recalculation. |
| `./mvnw test -pl cia-common,cia-quotation,cia-policy,cia-claims,cia-endorsement,cia-finance -am --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Affected Phase 5 module suite passed after P5-006 settlement and outstanding-balance tests were added. |
| `./mvnw verify --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Maven reactor completed all 20 modules successfully after P5-006 changes. Existing optional database integration tests reported skips in the non-escalated local path. |
| `npm run build` | `docs-site` | Passed | Docusaurus generated static files successfully after Phase 5 tracker updates. Existing Docusaurus deprecation/update-check warnings remain. |
| `docker-compose config` | repository root | Passed | Compose configuration rendered successfully after Phase 5 changes. |
| `git diff --check` | repository root | Passed | No whitespace errors found after Phase 5 changes. |

Phase 5 closure note: Phase 5 is implementation-complete as of 2026-05-07. Quote and direct policy premiums, policy issuance finance events, receipt/payment overposting prevention, endorsement premium adjustment routing, claim lifecycle transitions, and finance settlement/outstanding balance behavior are verified.

## Phase 6: Production Integrations

Goal: replace production stubs with real integration paths or hard startup blocks.

| ID | Fix item | Status | Owner | Verification |
| --- | --- | --- | --- | --- |
| P6-001 | Implement live KYC integration or block production customer onboarding until available. | Blocked | TBD | Go-live KYC implementation is deferred by decision; Dojah/Prembly live beans now fail startup clearly until provider contract work is complete. |
| P6-002 | Implement live NAICOM integration. | Blocked | TBD | Go-live NAICOM implementation is deferred by decision; `NAICOM_MODE=live` now fails startup clearly until provider contract work is complete. |
| P6-003 | Implement live NIID integration. | Blocked | TBD | Go-live NIID implementation is deferred by decision; `NIID_MODE=live` now fails startup clearly until provider contract work is complete. |
| P6-004 | Add provider timeout, retry, and failure-state handling. | Blocked | TBD | Requires live HTTP client implementations during go-live provider work. |
| P6-005 | Redact sensitive integration payloads from logs and audit records. | Verified | TBD | Mock/stub adapter tests prove KYC ID numbers, RC numbers, policy numbers, vehicle numbers, and payload fragments are not logged. |
| P6-006 | Restrict mock and stub providers to dev/test only. | Verified | TBD | `ProductionSafetyValidatorTest` rejects mock/stub providers outside dev/test; integration startup-block tests reject pending live adapters until implementation. |

### Phase 6 Run Log

| Field | Value |
| --- | --- |
| Phase date | 2026-05-07 |
| Branch | `production-readiness-phase-0` |
| Scope | Treat live KYC, NAICOM, and NIID integrations as go-live work and hard-block unsafe production activation until those providers are implemented and contract-tested. |

| Command | Directory | Result | Notes |
| --- | --- | --- | --- |
| `./mvnw test -pl cia-integrations -am --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Proves pending live adapters fail startup and dev/test mock/stub adapters do not log sensitive identifiers or payload fragments. |
| `./mvnw test -pl cia-common,cia-integrations,cia-customer,cia-policy,cia-workflow,cia-api -am --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Affected backend modules passed after integration startup blockers and log redaction changes. Existing optional database integration tests reported skips in the non-escalated local path. |
| `./mvnw verify --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Maven reactor completed all 20 modules successfully after Phase 6 changes. Existing optional database integration tests reported skips in the non-escalated local path. |

Phase 6 closure note: Phase 6 is closed for the current pre-go-live readiness scope. KYC, NAICOM, and NIID live implementations are intentionally deferred until go-live provider onboarding; the current build must not be deployed live with these integrations enabled, and it now fails clearly instead of accepting pending adapters. P6-001 through P6-004 remain blocked go-live implementation items by decision, not unresolved current-phase defects.

## Phase 7: Temporal Workflows

Goal: make long-running operational workflows executable and observable.

| ID | Fix item | Status | Owner | Verification |
| --- | --- | --- | --- | --- |
| P7-001 | Implement approval workflow worker. | Verified | TBD | `ApprovalWorkflowImplTest` proves notify, approve signal, status query, and finalise activity execution. |
| P7-002 | Implement NAICOM upload workflow worker. | Verified | TBD | `NaicomUploadWorkflowImplTest` proves policy payload fetch, upload, certificate update, and retry-backed activity execution. |
| P7-003 | Implement NIID upload workflow worker. | Verified | TBD | `NiidUploadWorkflowImplTest` proves policy payload fetch, upload, NIID reference update, and retry-backed activity execution. |
| P7-004 | Register all workflow implementations. | Verified | TBD | Core approval/NAICOM/NIID worker registration and webhook dispatch worker registration tests pass. |
| P7-005 | Make Temporal worker health part of readiness checks. | Verified | TBD | `TemporalWorkerHealthIndicatorTest` reports DOWN until the worker manager is started and UP only while active. |
| P7-006 | Stop swallowing critical Temporal startup failures in production. | Verified | TBD | `TemporalWorkerStarterTest` fails startup outside dev/test and only tolerates Temporal unavailability in dev/test profiles. |

### Phase 7 Run Log

| Command | Directory | Result | Notes |
| --- | --- | --- | --- |
| `./mvnw test -pl cia-workflow,cia-api,cia-partner-api -am -Dtest=ApprovalWorkflowImplTest,NaicomUploadWorkflowImplTest,NiidUploadWorkflowImplTest,CoreWorkflowWorkerConfigTest,TemporalWorkerHealthIndicatorTest,TemporalWorkerStarterTest,WebhookWorkerConfigTest -Dsurefire.failIfNoSpecifiedTests=false --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Focused Phase 7 workflow, worker registration, health, and startup guardrail checks passed. |
| `./mvnw test -pl cia-workflow,cia-policy,cia-claims,cia-endorsement,cia-partner-api,cia-api -am --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Affected backend modules passed after Temporal worker implementation and wrapper changes. Existing optional database integration tests reported skips in the local path. |
| `./mvnw verify --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Full Maven reactor completed all 20 backend modules successfully. Existing optional database integration tests reported skips in the local path. |
| `npm run build` | `docs-site` | Passed | Docusaurus production build generated static files. Deprecation/update-check warnings were removed by moving markdown link handling under `markdown.hooks` and disabling update notifier during scripted builds. |
| `docker-compose config` | repository root | Passed | Local infrastructure compose configuration rendered successfully. |
| `git diff --check` | repository root | Passed | No whitespace errors were detected in the Phase 7 diff. |

Phase 7 closure note: Phase 7 is closed. Temporal now has executable approval, NAICOM upload, NIID upload, and webhook dispatch workers registered through a testable worker manager; worker health is exposed for readiness, and non-dev/test startup no longer hides critical worker startup failures.

## Phase 8: PII, Files, Webhooks, And API Hardening

Goal: protect insurer and customer data.

| ID | Fix item | Status | Owner | Verification |
| --- | --- | --- | --- | --- |
| P8-001 | Redact or encrypt PII inside audit logs. | Verified | TBD | `AuditValueSanitizerTest` proves ID numbers, addresses, contact details, document URLs, tokens, and free-text identifiers are redacted before audit snapshots are stored. |
| P8-002 | Define PII classification for customer, KYC, claim, policy, and finance records. | Verified | TBD | `pii-classification.md` documents the classification levels, domain inventory, and required handling rules. |
| P8-003 | Add explicit upload limits for claim, KYC, and document files. | Verified | TBD | `UploadSecurityPolicyTest` proves oversized KYC uploads fail safely; service code now validates KYC and claim uploads before storage. |
| P8-004 | Add file type validation and malware-scanning integration point. | Verified | TBD | `UploadSecurityPolicyTest` proves invalid extensions/types fail safely and malware scanner detections block storage. A production scanner bean remains a go-live provider decision. |
| P8-005 | Prevent file storage fallback to the `public` tenant outside dev/test. | Verified | TBD | `StorageTenantGuardTest` proves missing tenants fail and `public` storage is blocked outside dev/test while real tenants continue. |
| P8-006 | Harden webhook target URL validation against SSRF. | Verified | TBD | `WebhookTargetUrlValidatorTest` rejects non-HTTPS, userinfo, localhost, private IPs, link-local, metadata, carrier-grade NAT, benchmark, loopback, and unique-local IPv6 targets. |
| P8-007 | Cap and redact webhook delivery response bodies. | Verified | TBD | `WebhookDeliverySanitizerTest` proves webhook payloads are not stored and sensitive response/error fields are redacted and capped. |
| P8-008 | Restrict internal and partner API docs in production. | Verified | TBD | `ApiDocsAccessPolicyTest` proves docs routes are centrally identified; config defaults public docs off and enables them only in dev unless explicitly overridden. |
| P8-009 | Add rate limits for sensitive back-office and partner endpoints. | Verified | TBD | `SensitiveEndpointRateLimitPolicyTest` and `SensitiveEndpointRateLimitFilterTest` prove partner, tenant admin, audit export, customer write, claim upload, and failed-login paths are rate-limited server-side. |

### Phase 8 Run Log

| Command | Directory | Result | Notes |
| --- | --- | --- | --- |
| `./mvnw test -pl cia-partner-api -am --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Focused webhook SSRF and delivery-log redaction tests passed. |
| `./mvnw test -pl cia-storage,cia-partner-api -am --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Storage tenant guard and partner webhook hardening tests passed. |
| `./mvnw test -pl cia-auth,cia-partner-api,cia-api -am --batch-mode --no-transfer-progress` | `cia-backend` | Passed | API docs access policy, storage guard, partner webhook hardening, and assembled API tests passed. Existing optional database integration tests reported skips in the local path. |
| `./mvnw verify --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Full Maven reactor completed all 20 backend modules successfully after Phase 8 webhook, storage, API docs, and workflow payload DTO hardening. Existing optional database integration tests reported skips in the local path. |
| `npm run build` | `docs-site` | Passed | Docusaurus production build generated static files after Phase 8 tracker updates. |
| `docker-compose config` | repository root | Passed | Local infrastructure compose configuration rendered successfully. |
| `git diff --check` | repository root | Passed | No whitespace errors were detected in the Phase 8 diff. |
| `./mvnw test -pl cia-common,cia-auth,cia-customer,cia-claims,cia-partner-api,cia-api -am --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Affected Phase 8 audit-redaction, upload-validation, rate-limit, customer/claim upload, partner, and assembled API modules passed. Existing optional database integration tests reported skips in the local path. |
| `./mvnw verify --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Full Maven reactor completed all 20 backend modules successfully after closing P8-001 through P8-004 and P8-009. Existing optional database integration tests reported skips in the local path. |
| `npm run build` | `docs-site` | Passed | Docusaurus production build generated static files after the PII classification and tracker updates. |
| `docker-compose config` | repository root | Passed | Local infrastructure compose configuration rendered successfully after final Phase 8 changes. |
| `git diff --check` | repository root | Passed | No whitespace errors were detected after final Phase 8 changes. |

Phase 8 closure note: Phase 8 is closed for the current pre-go-live scope. The remaining live decision is vendor selection and registration of a production malware scanner bean; the application-level integration point and fail-safe validation behavior are now implemented and tested.

## Phase 9: Frontend And Backend Contract Alignment

Goal: make production UI screens work against real backend responses.

| ID | Fix item | Status | Owner | Verification |
| --- | --- | --- | --- | --- |
| P9-001 | Standardize API response envelope handling for paginated endpoints. | Verified | TBD | Shared page normalizer now handles Spring `Page<T>`, existing page wrappers, and arrays; customer, quote, policy, and claim list contracts typecheck and build. |
| P9-002 | Replace mock fallbacks with real loading, empty, and error states. | Verified | TBD | Customer detail and audit alerts no longer fall back to illustrative data; real empty/error/loading states are used. |
| P9-003 | Wire or disable users setup screen. | Verified | TBD | Users setup is hidden from setup routes/navigation, and approval groups no longer call the missing `/setup/users` endpoint. |
| P9-004 | Align audit alert config frontend route with backend route. | Verified | TBD | Alert config now calls `GET/PUT /api/v1/setup/audit-config`. |
| P9-005 | Wire customer policy and claim tabs to real backend endpoints. | Verified | TBD | Customer detail tabs now call `/api/v1/policies?customerId=` and `/api/v1/claims?customerId=` and unwrap paginated responses. |
| P9-006 | Align document template frontend types with backend template types. | Verified | TBD | Template UI now uses backend template types, HTML upload payloads, `/api/v1/document-templates`, and backend delete semantics. |
| P9-007 | Remove production access to demo auth mode. | Verified | TBD | Production builds fail without Keycloak config; `VITE_DEMO_MODE` no longer enables DevAuthProvider or a demo banner. |
| P9-008 | Add Playwright smoke tests for core production journeys. | Verified | TBD | Back-office Playwright smoke tests cover authenticated shell startup and dashboard, customers, quotation, policies, claims, finance, reports, and setup products routes. |

### Phase 9 Run Log

| Command | Directory | Result | Notes |
| --- | --- | --- | --- |
| `pnpm --filter @cia/back-office typecheck` | `cia-frontend` | Passed | Back-office TypeScript completed with the Phase 9 page, route, auth, approval group, and document-template contract fixes. |
| `pnpm --filter @cia/partner typecheck` | `cia-frontend` | Passed | Partner TypeScript remains green after shared API client page-normalizer changes. |
| `pnpm --filter @cia/back-office build` | `cia-frontend` | Passed | Vite production build succeeded. Existing large chunk warning remains for the main bundle. |
| `pnpm --filter @cia/back-office test:e2e` | `cia-frontend` | Passed with escalation | Nine Chromium smoke tests passed against the Vite e2e server with mocked backend API responses and local e2e auth mode. |
| `npm run build` | `docs-site` | Passed | Docusaurus production build generated static files after the Phase 9 tracker update. |
| `docker-compose config` | repository root | Passed | Local infrastructure compose configuration rendered successfully after the Phase 9 changes. |
| `git diff --check` | repository root | Passed | No whitespace errors were detected in the Phase 9 diff. |
| `rg -n "@playwright/test\|playwright" ...` | repository root | Passed | Playwright dependency, config, and smoke-test harness are present in the back-office workspace. |

Phase 9 closure note: Phase 9 is closed as of 2026-05-07. P9-001 through P9-008 are verified by frontend typecheck, production build, Playwright smoke tests, docs build, Compose config rendering, and whitespace checks.

## Phase 10: Deployment Architecture

Goal: make the full system reproducible in a live environment.

| ID | Fix item | Status | Owner | Verification |
| --- | --- | --- | --- | --- |
| P10-001 | Add backend Dockerfile. | Verified | TBD | `docker build -f cia-backend/Dockerfile -t cia-backend:phase10 cia-backend` built and exported the backend image. |
| P10-002 | Add production deployment configuration. | Verified | TBD | `docker/production/docker-compose.yml` renders with `production.env.example`; clean live-environment startup rehearsal remains Phase 11 with real secrets and provider credentials. |
| P10-003 | Add database migration job. | Verified | TBD | `cia-migrate` runs the same image with `CIA_MIGRATION_ONLY=true`; `TenantMigrationRunnerTest` proves migration-only mode closes the application after tenant migrations. |
| P10-004 | Add health and readiness checks. | Verified | TBD | Liveness/readiness probes are enabled; readiness includes database, Redis, Temporal worker, and production external dependency configuration health. |
| P10-005 | Document production environment variable and secret contract. | Verified | TBD | `production-deployment.md`, `environment-variables.md`, and `production.env.example` document required non-local values and secret-backed inputs. |
| P10-006 | Add backend CI image build and deployment workflow. | Verified | TBD | `.github/workflows/backend-image.yml` builds the backend image and publishes to GHCR outside pull requests. |
| P10-007 | Add rollback procedure. | Verified | TBD | Production deployment and database migration runbooks document backup, rollback, forward repair, and post-rollback validation. |
| P10-008 | Add observability for logs, metrics, traces, workflows, and integration failures. | Verified | TBD | Prometheus metrics are exposed, production observability requirements are documented, and `ops/observability/` provides alert rules plus a Grafana dashboard starter. |

### Phase 10 Run Log

| Field | Value |
| --- | --- |
| Phase date | 2026-05-07 |
| Branch | `production-readiness-phase-0` |
| Scope | Backend image packaging, production deployment template, migration-only job mode, health/readiness, CI image workflow, production runbook, rollback procedure, and observability assets. |

| Command | Directory | Result | Notes |
| --- | --- | --- | --- |
| `./mvnw test -pl cia-api -am -Dtest=ExternalDependenciesHealthIndicatorTest,TenantMigrationRunnerTest,TemporalWorkerHealthIndicatorTest -Dsurefire.failIfNoSpecifiedTests=false --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Seven focused API tests passed for production dependency health, migration-only shutdown behavior, and Temporal worker health. |
| `docker build -f cia-backend/Dockerfile -t cia-backend:phase10 cia-backend` | repository root | Passed with escalation | Built and exported the backend image. Earlier attempts hit transient Maven Central TLS/content-length transfer errors; the Dockerfile was simplified to one real `mvn package` step with `-U`, and the final cached retry passed. |
| `docker compose --env-file docker/production/production.env.example -f docker/production/docker-compose.yml config` | repository root | Passed | Production Compose template rendered the migration job and API service with required environment contract. |
| `docker-compose config` | repository root | Passed | Local infrastructure Compose configuration still renders successfully. |
| `./mvnw verify --batch-mode --no-transfer-progress` | `cia-backend` | Passed with skips | Maven reactor completed all 20 backend modules. Optional database integration tests reported five skips in the non-escalated local path. |
| `npm run build` | `docs-site` | Passed | Docusaurus generated static files after production deployment docs and observability references were added. |
| `node -e "JSON.parse(...)"` | repository root | Passed | Grafana dashboard JSON parsed successfully. |
| `ruby -e "require 'yaml'; YAML.load_file(...)"` | repository root | Passed | Prometheus alert rules YAML parsed successfully. |
| `git diff --check` | repository root | Passed | No whitespace errors were detected. |

Phase 10 closure note: Phase 10 is implementation-complete for deployment architecture as of 2026-05-07. The deployment gate is verified for the current repository scope: image build, migration job mode, readiness gating, production configuration contract, CI image workflow, rollback docs, and monitoring assets are in place. The first clean-environment deployment rehearsal with real vault secrets, live provider credentials, and imported monitoring assets remains Phase 11.

## Phase 11: Full Regression And Release Certification

Goal: prove the system is ready for controlled live deployment.

| ID | Fix item | Status | Owner | Verification |
| --- | --- | --- | --- | --- |
| P11-001 | Run full backend unit and integration tests. | Verified | TBD | `./mvnw verify --batch-mode --no-transfer-progress` passed across all 20 backend modules with zero failures. |
| P11-002 | Run multi-tenant isolation test suite. | Verified | TBD | Docker Compose PostgreSQL-backed tenant schema, provisioning, authorization, and isolation tests passed with zero skips. |
| P11-003 | Run auth and authorization test suite. | Verified | TBD | Method security, JWT authority conversion, tenant context, reports authorization, controller coverage, tenant provisioning authorization, and partner scope tests passed. |
| P11-004 | Run workflow and integration contract test suites. | Blocked | TBD | Temporal workflow and current stub/mock integration tests pass; live KYC, NAICOM, and NIID contract tests remain blocked until go-live provider credentials are issued. |
| P11-005 | Run frontend typecheck and end-to-end tests. | Verified | TBD | Back-office and partner typechecks/builds passed; back-office Playwright smoke suite passed in Chromium; CI now repeats partner build and Playwright smoke checks. |
| P11-006 | Run dependency and image vulnerability checks. | In review | TBD | Frontend and docs package audits now pass, secret scan only found documented placeholders, SBOM generation works, and the backend image workflow now enforces a Trivy high/critical CVE gate; final release evidence requires a successful GitHub run for the exact image. |
| P11-007 | Run clean-environment deployment rehearsal. | Blocked | TBD | Production Compose config renders and a production env preflight now validates the release environment before rehearsal; the actual rehearsal still requires real vault secrets, live provider credentials, and target monitoring/deployment access. |
| P11-008 | Produce release readiness sign-off. | In review | TBD | Release certification report is prepared for decision-maker review; controlled deployment approval remains pending. |

### Phase 11 Run Log

| Field | Value |
| --- | --- |
| Phase date | 2026-05-08 |
| Branch | `production-readiness-phase-0` |
| Scope | Full regression, security/dependency audit, production configuration verification, and release certification decision record. |

| Command | Directory | Result | Notes |
| --- | --- | --- | --- |
| `docker-compose up -d postgres` | repository root | Passed with escalation | Local PostgreSQL service was running for Docker-backed database tests. |
| `./mvnw verify --batch-mode --no-transfer-progress` | `cia-backend` | Passed with escalation | Maven reactor completed all 20 modules successfully; fresh database migration and API tests ran with zero failures. |
| `./mvnw test -pl cia-api -am -Dtest=TenantSchemaNameTest,TenantProvisioningControllerAuthorizationTest,TenantProvisioningServiceIntegrationTest,ControllerAuthorizationCoverageTest -Dsurefire.failIfNoSpecifiedTests=false --batch-mode --no-transfer-progress` | `cia-backend` | Passed with escalation | Tenant schema validation, tenant provisioning authorization, and Docker-backed tenant isolation tests passed with zero skips. |
| `./mvnw test -pl cia-auth,cia-reports,cia-partner-api,cia-api -am -Dtest=MethodSecurityConfigTest,JwtAuthConverterTest,TenantContextFilterTest,ReportControllerAuthorizationTest,ControllerAuthorizationCoverageTest,TenantProvisioningControllerAuthorizationTest,PartnerScopeFilterTest -Dsurefire.failIfNoSpecifiedTests=false --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Authorization, controller coverage, tenant context, report authorization, tenant provisioning authorization, and partner scope tests passed. |
| `./mvnw test -pl cia-integrations,cia-workflow,cia-api -am -Dtest=IntegrationStartupBlockTest,MockKycServiceTest,StubNaicomServiceTest,StubNiidServiceTest,ApprovalWorkflowImplTest,NaicomUploadWorkflowImplTest,NiidUploadWorkflowImplTest,CoreWorkflowWorkerConfigTest,TemporalWorkerStarterTest,TemporalWorkerHealthIndicatorTest,ExternalDependenciesHealthIndicatorTest -Dsurefire.failIfNoSpecifiedTests=false --batch-mode --no-transfer-progress` | `cia-backend` | Passed | Temporal worker/workflow tests and current mock/stub integration tests passed; live provider contract tests remain a go-live dependency. |
| `pnpm --filter @cia/back-office typecheck` | `cia-frontend` | Passed | Back-office TypeScript passed. |
| `pnpm --filter @cia/partner typecheck` | `cia-frontend` | Passed | Partner TypeScript passed. |
| `pnpm --filter @cia/back-office build` | `cia-frontend` | Passed | Vite production build succeeded; the existing large chunk warning remains non-blocking. |
| `pnpm --filter @cia/partner build` | `cia-frontend` | Passed | Vite production build succeeded. |
| `pnpm --filter @cia/back-office test:e2e` | `cia-frontend` | Passed with escalation | Nine Chromium Playwright smoke tests passed across the authenticated shell and core back-office routes. |
| `npm run build` | `docs-site` | Passed | Docusaurus static build passed after dependency audit remediation. |
| `pnpm audit --prod --audit-level high` | `cia-frontend` | Passed with escalation | No known production frontend vulnerabilities were reported. |
| `npm audit --omit=dev --audit-level=high` | `docs-site` | Passed with escalation | Initial high `serialize-javascript` finding was fixed by overriding to `7.0.5` and refreshing the lockfile; re-audit reports zero vulnerabilities. |
| `rg` secret-pattern scan | repository root | Passed | No tracked env files were found; scan results were limited to documented placeholders and a shortened bearer-token example in partner docs. |
| `docker-compose config` | repository root | Passed | Local Compose configuration rendered successfully. |
| `docker compose --env-file docker/production/production.env.example -f docker/production/docker-compose.yml config` | repository root | Passed | Production Compose template renders with placeholder values from the example env file. |
| `scripts/validate-production-env.sh --allow-placeholders docker/production/production.env.example` | repository root | Passed | Production env example shape passes required-variable and production-mode validation while explicitly allowing placeholders. |
| `docker image inspect cia-backend:phase10` | repository root | Passed with escalation | Phase 10 backend image exists locally as Linux arm64 image `sha256:a22a02083f37...`. |
| `docker sbom cia-backend:phase10` | repository root | Passed with escalation | Docker generated an SBOM for the Phase 10 backend image. |
| `docker scout cves cia-backend:phase10 --only-severity high,critical` | repository root | Blocked with escalation | Docker Scout requires Docker login in this environment; Trivy, Grype, Syft, and Gitleaks are not installed locally. |
| `ruby -e "require 'yaml'; YAML.load_file(...)"` | repository root | Passed | Backend image workflow YAML parses successfully after adding the Trivy CVE gate. |
| `ruby -e "require 'yaml'; YAML.load_file(...)"` | repository root | Passed | Main CI workflow YAML parses successfully after enabling docs CI, frontend production audit, partner build, Playwright smoke checks, production env example preflight, and production Compose validation. |
| `gh run view 25543540746 --log-failed` | repository root | Investigated with escalation | GitHub Backend Image workflow reached Trivy but failed because the scan digest reference used mixed-case repository naming; the workflow now derives scan refs from lowercase metadata tags. |
| `npm run build` | `docs-site` | Passed | Docusaurus static build passed after adding the Phase 11 certification and image-scan runbook updates. |

Phase 11 certification note: regression evidence supports continued controlled release preparation, but Phase 11 is not fully closed for live deployment. Live release approval remains blocked on provider contract tests for KYC, NAICOM, and NIID, a successful GitHub image CVE scan for the exact release image, a clean-environment deployment rehearsal with real secrets and target infrastructure access, and formal decision-maker sign-off.

## Immediate Next Decision

The immediate decision is whether to prepare the external go-live dependencies now or keep the system in controlled pre-live readiness until provider and infrastructure access is available.

Recommended order:

1. Secure live or pre-production KYC, NAICOM, and NIID credentials and confirm allowed test windows.
2. Enable an approved image CVE scanner for the exact release image.
3. Provision the clean rehearsal environment with vault-managed secrets and monitoring targets.
4. Rerun Phase 11 end to end and record final release sign-off.
