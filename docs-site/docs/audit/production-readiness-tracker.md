---
id: production-readiness-tracker
title: Production Readiness Fix Tracker
sidebar_label: Production Readiness Tracker
---

# Production Readiness Fix Tracker

Last updated: 2026-05-07 02:31 Africa/Lagos

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
| Tenant isolation gate | Tenant data isolation is proven with automated tests. | Verified | Phase 3 tenant resolution, provisioning, migration, and two-tenant isolation checks passed against local Docker Compose PostgreSQL. |
| Data correctness gate | Reports, premium calculations, finance postings, and migrations are validated. | Not started | Blocks deployment. |
| Integration gate | KYC, NAICOM, NIID, and Temporal workflows are implemented or explicitly blocked outside dev/test. | Not started | Blocks deployment. |
| PII protection gate | PII is encrypted, redacted, or excluded from logs, audit records, files, and webhook payload history. | Not started | Blocks deployment. |
| Frontend contract gate | Production UI screens are wired to real backend contracts or intentionally disabled. | Not started | Blocks deployment. |
| Deployment gate | Backend deployment, migrations, health checks, readiness checks, secrets, rollback, and monitoring are documented and tested. | Not started | Blocks deployment. |

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

## Phase 4: Database, Migrations, And Reporting

Goal: make database-backed operations reliable.

| ID | Fix item | Status | Owner | Verification |
| --- | --- | --- | --- | --- |
| P4-001 | Align report SQL with actual migration table and column names. | Not started | TBD | Database-backed report tests pass. |
| P4-002 | Review native SQL across all modules for schema drift. | Not started | TBD | Native SQL inventory is complete. |
| P4-003 | Add fresh-database migration test. | Not started | TBD | Empty database migrates to latest version successfully. |
| P4-004 | Add seeded report generation tests. | Not started | TBD | Reports return expected rows and totals. |
| P4-005 | Review indexes for policies, claims, customers, finance, audit, and reports. | Not started | TBD | Query paths are documented and indexed where needed. |
| P4-006 | Define production migration and rollback procedure. | Not started | TBD | Procedure is documented and reviewed. |

## Phase 5: Insurance And Finance Correctness

Goal: prevent incorrect policy, claim, endorsement, and finance outcomes.

| ID | Fix item | Status | Owner | Verification |
| --- | --- | --- | --- | --- |
| P5-001 | Align quote and direct policy premium calculations. | Not started | TBD | Premium calculation tests pass for quote and direct policy paths. |
| P5-002 | Prevent receipt and payment overposting unless an approved overpayment workflow exists. | Not started | TBD | Posting above outstanding balance is rejected. |
| P5-003 | Add policy issuance tests from quote and direct policy creation. | Not started | TBD | Financial records are consistent across both paths. |
| P5-004 | Add endorsement premium adjustment tests. | Not started | TBD | Endorsements produce expected financial impact. |
| P5-005 | Add claim lifecycle transition tests. | Not started | TBD | Invalid transitions are rejected. |
| P5-006 | Add finance settlement and outstanding balance tests. | Not started | TBD | Paid, partial, and unpaid statuses are correct. |

## Phase 6: Production Integrations

Goal: replace production stubs with real integration paths or hard startup blocks.

| ID | Fix item | Status | Owner | Verification |
| --- | --- | --- | --- | --- |
| P6-001 | Implement live KYC integration or block production customer onboarding until available. | Not started | TBD | Provider contract tests pass or production startup fails clearly. |
| P6-002 | Implement live NAICOM integration. | Not started | TBD | NAICOM contract tests pass. |
| P6-003 | Implement live NIID integration. | Not started | TBD | NIID contract tests pass. |
| P6-004 | Add provider timeout, retry, and failure-state handling. | Not started | TBD | Timeout and retry tests pass. |
| P6-005 | Redact sensitive integration payloads from logs and audit records. | Not started | TBD | Log redaction tests pass. |
| P6-006 | Restrict mock and stub providers to dev/test only. | Not started | TBD | Production config validation rejects mock/stub providers. |

## Phase 7: Temporal Workflows

Goal: make long-running operational workflows executable and observable.

| ID | Fix item | Status | Owner | Verification |
| --- | --- | --- | --- | --- |
| P7-001 | Implement approval workflow worker. | Not started | TBD | Quote, policy, claim, and endorsement approval workflow tests pass. |
| P7-002 | Implement NAICOM upload workflow worker. | Not started | TBD | Workflow test confirms status updates and retries. |
| P7-003 | Implement NIID upload workflow worker. | Not started | TBD | Workflow test confirms status updates and retries. |
| P7-004 | Register all workflow implementations. | Not started | TBD | Worker startup test confirms registrations. |
| P7-005 | Make Temporal worker health part of readiness checks. | Not started | TBD | Readiness fails when required workers are unavailable. |
| P7-006 | Stop swallowing critical Temporal startup failures in production. | Not started | TBD | Production startup/readiness test fails clearly. |

## Phase 8: PII, Files, Webhooks, And API Hardening

Goal: protect insurer and customer data.

| ID | Fix item | Status | Owner | Verification |
| --- | --- | --- | --- | --- |
| P8-001 | Redact or encrypt PII inside audit logs. | Not started | TBD | Audit tests prove ID numbers, addresses, and documents are not stored in plain text. |
| P8-002 | Define PII classification for customer, KYC, claim, policy, and finance records. | Not started | TBD | Classification is documented and reviewed. |
| P8-003 | Add explicit upload limits for claim, KYC, and document files. | Not started | TBD | Oversized upload tests fail safely. |
| P8-004 | Add file type validation and malware-scanning integration point. | Not started | TBD | Invalid file type tests fail safely. |
| P8-005 | Prevent file storage fallback to the `public` tenant outside dev/test. | Not started | TBD | Missing tenant storage test fails safely. |
| P8-006 | Harden webhook target URL validation against SSRF. | Not started | TBD | Localhost, private IPs, link-local, metadata IPs, and non-HTTPS URLs are rejected. |
| P8-007 | Cap and redact webhook delivery response bodies. | Not started | TBD | Stored delivery logs do not contain oversized or sensitive bodies. |
| P8-008 | Restrict internal and partner API docs in production. | Not started | TBD | Production access test denies public docs unless explicitly allowed. |
| P8-009 | Add rate limits for sensitive back-office and partner endpoints. | Not started | TBD | Rate-limit tests pass. |

## Phase 9: Frontend And Backend Contract Alignment

Goal: make production UI screens work against real backend responses.

| ID | Fix item | Status | Owner | Verification |
| --- | --- | --- | --- | --- |
| P9-001 | Standardize API response envelope handling for paginated endpoints. | Not started | TBD | Customer, quotation, policy, claim, and report list screens load real pages. |
| P9-002 | Replace mock fallbacks with real loading, empty, and error states. | Not started | TBD | Screens do not silently show illustrative data in production. |
| P9-003 | Wire or disable users setup screen. | Not started | TBD | Screen uses a real endpoint or is hidden in production. |
| P9-004 | Align audit alert config frontend route with backend route. | Not started | TBD | Alert config save/load works end to end. |
| P9-005 | Wire customer policy and claim tabs to real backend endpoints. | Not started | TBD | Customer detail tabs load real data. |
| P9-006 | Align document template frontend types with backend template types. | Not started | TBD | Template upload and generation work end to end. |
| P9-007 | Remove production access to demo auth mode. | Not started | TBD | Production build cannot enable demo auth accidentally. |
| P9-008 | Add Playwright smoke tests for core production journeys. | Not started | TBD | Login, dashboard, customers, quotes, policies, claims, finance, reports, and setup smoke tests pass. |

## Phase 10: Deployment Architecture

Goal: make the full system reproducible in a live environment.

| ID | Fix item | Status | Owner | Verification |
| --- | --- | --- | --- | --- |
| P10-001 | Add backend Dockerfile. | Not started | TBD | Backend image builds successfully. |
| P10-002 | Add production deployment configuration. | Not started | TBD | Target deployment environment can start all required services. |
| P10-003 | Add database migration job. | Not started | TBD | Migrations run before application traffic is served. |
| P10-004 | Add health and readiness checks. | Not started | TBD | Readiness reflects database, auth, Temporal, and required integrations. |
| P10-005 | Document production environment variable and secret contract. | Not started | TBD | Deployment docs list all required values. |
| P10-006 | Add backend CI image build and deployment workflow. | Not started | TBD | CI produces deployable backend artifact. |
| P10-007 | Add rollback procedure. | Not started | TBD | Rollback steps are documented and tested. |
| P10-008 | Add observability for logs, metrics, traces, workflows, and integration failures. | Not started | TBD | Alerts and dashboards exist for critical failure modes. |

## Phase 11: Full Regression And Release Certification

Goal: prove the system is ready for controlled live deployment.

| ID | Fix item | Status | Owner | Verification |
| --- | --- | --- | --- | --- |
| P11-001 | Run full backend unit and integration tests. | Not started | TBD | All backend tests pass. |
| P11-002 | Run multi-tenant isolation test suite. | Not started | TBD | Tenant isolation tests pass. |
| P11-003 | Run auth and authorization test suite. | Not started | TBD | Role and scope tests pass. |
| P11-004 | Run workflow and integration contract test suites. | Not started | TBD | Temporal, KYC, NAICOM, and NIID tests pass. |
| P11-005 | Run frontend typecheck and end-to-end tests. | Not started | TBD | Typecheck and Playwright tests pass. |
| P11-006 | Run dependency and image vulnerability checks. | Not started | TBD | No release-blocking vulnerabilities remain. |
| P11-007 | Run clean-environment deployment rehearsal. | Not started | TBD | System deploys from scratch using documented process. |
| P11-008 | Produce release readiness sign-off. | Not started | TBD | Decision makers approve controlled deployment. |

## Immediate Next Decision

The first implementation decision is whether to proceed with Phase 1 production safety guardrails before resolving tenant architecture, or to make the tenant architecture decision first.

Recommended order:

1. Complete Phase 0 baseline and tracking.
2. Complete Phase 1 production safety guardrails.
3. Make the Phase 3 tenant architecture decision before changing tenant migrations.
