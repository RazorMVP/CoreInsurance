---
id: phase-11-go-live-evidence
title: Phase 11 Go-Live Evidence Checklist
sidebar_label: Phase 11 Go-Live Evidence
---

# Phase 11 Go-Live Evidence Checklist

This checklist defines the evidence required to move Core Insurance from
controlled pre-live readiness to approved live deployment. It is intentionally
separate from implementation notes so decision makers can see what is proven,
what is blocked, and what must be attached before approval.

## Current Position

The repository-verifiable Phase 11 work is passing for the current branch:

| Evidence area | Current status | Required before approval |
| --- | --- | --- |
| Backend regression | Passed | Re-run if the release commit changes. |
| Frontend contract and E2E | Passed | Re-run if frontend routes or auth contracts change. |
| Dependency and image CVE gate | Passed | Re-run Backend Image workflow for the exact release commit. |
| Production config rendering | Passed with example env | Re-run against the real target environment file. |
| Provider contract tests | Blocked | Requires live or pre-production KYC, NAICOM, and NIID credentials. |
| Clean-environment rehearsal | Blocked | Requires target infrastructure, vault secrets, monitoring, and rollback access. |
| Formal sign-off | Blocked | Requires all evidence below. |

## Provider Contract Evidence

Do not remove the live-provider startup blocks until the matching provider row
has complete evidence.

| Provider | Evidence required | Pass criteria | Evidence reference |
| --- | --- | --- | --- |
| KYC | Provider contract, base URL, auth method, sample individual/corporate/director requests, sandbox or pre-production credentials, rate limits, support contact. | Individual, corporate, and director checks return expected verified, failed, pending, timeout, and provider-error states without logging ID numbers or secrets. | TBD |
| NAICOM | Approved endpoint, auth method, policy upload schema, certificate/UID response schema, duplicate handling, retry limits, reconciliation process. | Policy upload succeeds, duplicate upload is handled deterministically, provider failure leaves a retryable workflow state, and certificate UID is stored only after confirmed success. | TBD |
| NIID | Approved endpoint, auth method, motor/marine payload schema, reference response schema, duplicate handling, retry limits, reconciliation process. | NIID upload succeeds for applicable policy classes, non-applicable classes are skipped safely, provider failure leaves a retryable workflow state, and NIID reference is stored only after confirmed success. | TBD |

Minimum provider test cases:

1. Successful request with valid credentials.
2. Authentication failure with invalid or expired credentials.
3. Validation failure from malformed or incomplete payload.
4. Duplicate submission or idempotency behavior.
5. Timeout and retry behavior.
6. Provider `5xx` failure behavior.
7. Redaction proof for request logs, response logs, audit records, and workflow history.
8. Rate-limit behavior at the agreed provider threshold.

## Clean-Environment Rehearsal Evidence

The rehearsal must start from a clean checkout and a real secret-managed
environment. It must not use checked-in placeholders, local endpoints, mock
providers, stub providers, `latest` image tags, or disabled rate limits.

| Step | Command or action | Evidence to attach |
| --- | --- | --- |
| 1 | Confirm release commit and immutable backend image tag or digest. | Commit SHA, image reference, Backend Image workflow run URL. |
| 2 | Validate target environment file. | `scripts/validate-production-env.sh .env.production` output. |
| 3 | Render production Compose. | `docker compose --env-file .env.production -f docker/production/docker-compose.yml config` output. |
| 4 | Confirm target secrets are from vault or platform secret manager. | Secret manager path list, not secret values. |
| 5 | Take database backup or snapshot. | Backup id, timestamp, retention location, restore owner. |
| 6 | Run migration job with `CIA_MIGRATION_ONLY=true`. | Migration container log, Flyway version, tenant schema migration summary. |
| 7 | Start or roll `cia-api`. | Deployment log and image digest. |
| 8 | Wait for readiness. | `/actuator/health/readiness` response showing `UP`. |
| 9 | Run smoke checks. | Auth, tenant, customer, quote, policy, claim, finance, report, setup, audit, partner API, and webhook smoke logs. |
| 10 | Import monitoring assets. | Prometheus alert import result and Grafana dashboard import result. |
| 11 | Rehearse rollback. | Restore or rollback decision log, last-known-good image, readiness after rollback or approved forward-fix note. |

## Smoke Test Scope

The clean-environment smoke test must prove these workflows with a test tenant
and test users:

| Area | Minimum proof |
| --- | --- |
| Authentication | Back-office user login, partner token issuance, invalid token rejection. |
| Tenant isolation | Request for tenant A cannot read tenant B data. |
| Customer/KYC | Customer creation reaches the configured KYC provider path or remains blocked if provider testing is not approved. |
| Quote and policy | Quote creation, approval path, policy issuance, debit note creation. |
| Claims | Claim registration, approval, settlement validation. |
| Finance | Receipt/payment posting cannot overpost and updates outstanding balances. |
| Reports | Seeded operational and regulatory reports execute against the tenant schema. |
| Documents/storage | Upload policy is enforced and storage writes stay tenant-scoped. |
| Workflows | Temporal worker readiness is `UP`, approval workflow executes, NAICOM/NIID workflows follow provider-test decision. |
| Webhooks | HTTPS target validation, signed delivery, sanitized delivery history, retry behavior. |

## Sign-Off Register

Live deployment approval requires all sign-off rows to be completed.

| Role | Decision | Required evidence | Name/date |
| --- | --- | --- | --- |
| Business owner | Approve or reject live deployment. | Provider readiness and operational acceptance. | TBD |
| Technical owner | Approve or reject live deployment. | Regression, migration, deployment, rollback, and smoke evidence. | TBD |
| Security owner | Approve or reject live deployment. | Secret handling, auth, tenant isolation, CVE scan, PII redaction, and provider redaction evidence. | TBD |
| Operations owner | Approve or reject live deployment. | Monitoring, alerting, backup, restore, incident escalation, and on-call coverage. | TBD |

## Approval Rule

The release can be approved only when every blocker in
`release-readiness-signoff.md` is either closed with attached evidence or
explicitly accepted by the named decision maker. Any code or dependency change
after the final evidence run requires the affected Phase 11 checks to be run
again before approval.
