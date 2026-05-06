---
id: production-readiness-tracker
title: Production Readiness Fix Tracker
sidebar_label: Production Readiness Tracker
---

# Production Readiness Fix Tracker

Last updated: 2026-05-06 16:38 Africa/Lagos

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
| Build gate | Backend, frontend, docs, and Docker config build successfully from a clean checkout. | In progress | Phase 0 working-tree baseline passed on branch `production-readiness-phase-0`; repeat from clean checkout after committing the baseline. |
| Security gate | Production cannot run with dev profile, default secrets, mock providers, or unauthenticated endpoints. | Not started | Blocks deployment. |
| Authorization gate | Role and scope checks are enforced and tested for critical endpoints. | Not started | Blocks deployment. |
| Tenant isolation gate | Tenant data isolation is proven with automated tests. | Not started | Blocks deployment. |
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
| P1-001 | Remove `dev` as the default backend profile. | Not started | TBD | Backend requires an explicit profile. |
| P1-002 | Fail startup if a production-like environment uses the dev security profile. | Not started | TBD | Production config test fails when `SPRING_PROFILES_ACTIVE=dev`. |
| P1-003 | Reject known development PII keys outside dev/test. | Not started | TBD | Startup validation test rejects default PII key. |
| P1-004 | Fail startup if production uses mock KYC or stub NAICOM/NIID providers. | Not started | TBD | Production config validation tests cover all provider defaults. |
| P1-005 | Fail startup when required production JWT, database, storage, or integration config is missing. | Not started | TBD | Missing config tests fail clearly. |
| P1-006 | Update local setup docs so developer startup remains simple after profile changes. | Not started | TBD | Local setup instructions are tested from a clean checkout. |

## Phase 2: Authentication And Authorization

Goal: enforce backend access control consistently.

| ID | Fix item | Status | Owner | Verification |
| --- | --- | --- | --- | --- |
| P2-001 | Enable Spring method security for `@PreAuthorize` checks. | Not started | TBD | Method-level authorization tests fail before roles and pass with roles. |
| P2-002 | Review all admin, setup, finance, policy, claim, customer, report, document, audit, and approval endpoints. | Not started | TBD | Endpoint authorization matrix is documented. |
| P2-003 | Standardize Keycloak roles, backend authorities, and frontend route guards. | Not started | TBD | Role naming is consistent across docs, backend, and frontend. |
| P2-004 | Add negative authorization tests for critical endpoints. | Not started | TBD | Authenticated users without required roles are denied. |
| P2-005 | Add partner scope tests for partner API endpoints. | Not started | TBD | Tokens without scope are denied; scoped tokens are allowed. |

## Phase 3: Tenant Architecture And Isolation

Goal: make tenant isolation real and testable.

| ID | Fix item | Status | Owner | Verification |
| --- | --- | --- | --- | --- |
| P3-001 | Confirm final tenancy model: schema-per-tenant or single-schema `tenant_id`. | Blocked | TBD | Architecture decision record is approved. |
| P3-002 | Correct tenant filter ordering so JWT claims are available before tenant resolution. | Not started | TBD | Tenant context test proves JWT tenant claim is read. |
| P3-003 | Validate tenant IDs against the tenant registry. | Not started | TBD | Unknown tenant claim is rejected. |
| P3-004 | Remove fallback to `public` tenant outside dev/test. | Not started | TBD | Missing tenant fails safely in production profile. |
| P3-005 | Implement tenant provisioning. | Not started | TBD | New tenant can be provisioned automatically. |
| P3-006 | Implement per-tenant migrations if schema-per-tenant is retained. | Blocked | TBD | Every tenant schema receives required business tables. |
| P3-007 | Add two-tenant isolation tests. | Not started | TBD | Tenant A cannot read or mutate Tenant B data. |

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
