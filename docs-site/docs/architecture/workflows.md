---
id: workflows
title: Workflows
sidebar_label: Workflows
---

# Workflows

CIA uses **Temporal** for every multi-step, async, or crash-safe process. Workers run in-process inside `cia-api` — there is no separate worker container.

## Why Temporal

| Property | What it gives us |
| --- | --- |
| **Durable execution** | Workflows survive Spring Boot restarts mid-flight. A NAICOM upload that's been retrying for 6 hours doesn't restart from zero on a deploy. |
| **At-least-once delivery** | Activities are retried on transient failures with configurable backoff. Combined with idempotency-key contracts on every activity, we get exactly-once *business outcomes* without distributed transactions. |
| **First-class signals + queries** | Approval workflows wait on `approved` / `rejected` signals; UI queries pull current state without polling the DB. |
| **Versioning** | Long-running workflows survive workflow-code changes via `Workflow.getVersion(...)` checkpoints. |

## Workflows in Use

| Workflow | Trigger | Long-running steps |
| --- | --- | --- |
| `PolicyApprovalWorkflow` | `POST /api/v1/policies/{id}/submit` | resolveApprover → notifyApprover → wait for signal (approve / reject / timeout) → escalate (multi-level) → approvePolicy → generate PDF → debit note → NAICOM child workflow → NIID child workflow → email PDF |
| `EndorsementApprovalWorkflow` | `POST /api/v1/endorsements/{id}/submit` | Same shape as policy approval; emits debit/credit note depending on type. |
| `ClaimApprovalWorkflow` | `POST /api/v1/claims/{id}/submit` | resolveApprover → notifyApprover → wait for signal → approveClaim → generate DV PDF → credit note to finance |
| `NaicomUploadWorkflow` | Child of `PolicyApprovalWorkflow` (and on-demand via `POST /api/v1/policies/{id}/naicom-upload`) | Retry loop with exponential backoff `5min → 15min → 1hr` (indefinite) until `NaicomIntegrationService.uploadPolicy()` returns a UID. Certificate regenerated with real UID on success. |
| `NiidUploadWorkflow` | Child of `PolicyApprovalWorkflow` (motor/marine only) | Same backoff pattern as NAICOM. Advance motor renewals uploaded on previous policy expiry. |
| `RenewalNotificationWorkflow` | Cron — policy expiry detection | Sequence of in-app + email at `T-60d / T-30d / T-14d / T-7d / T-1d / T-0d / T+1d / T+10d / T+30d / T+60d / T+120d`. |
| `WebhookDispatchWorkflow` | Spring `ApplicationEvent` (policy.bound, claim.approved, etc.) | Fan out to all registered partner webhooks for the tenant; HMAC-SHA256 sign payload; retry `30s → 2min → 10min` on non-2xx; mark webhook degraded after 3 failures. |
| `BulkQuoteUploadWorkflow` | `POST /api/v1/quotes/bulk` | Per-row validation → per-row create activity (commits batched per 100 rows). |
| `BulkClaimRegistrationWorkflow` | `POST /api/v1/claims/bulk` | Mirror of bulk quote — per-row register + per-row missing-doc detection. |
| `BatchReinsuranceReallocationWorkflow` | `POST /api/v1/reinsurance/allocations/batch-reallocate` | Re-runs surplus / QS / XOL allocation for the selected policies against a new treaty; updates `ri_allocations` + `ri_allocation_lines`. |
| `NdprRetentionPurgeWorkflow` | Cron — per-tenant retention policy | Identifies expired audit log + soft-deleted PII rows; purges with audit trail. |
| `RetroactiveJournalBackfillWorkflow` (Module 12, Slice 1.8) | `POST /api/v1/finance/gl/backfill` (admin) | Per-event-type sweep over `policies`, `claims`, `claim_expenses`, `endorsements`, `ri_fac_covers`; idempotent via JE-gateway triple `(source_module, source_event_type, source_reference)`. See [GL backfill runbook](../operations/period-end-closures-backfill.md). |

## Approval Workflow Shape (Policy / Endorsement / Claim / Finance)

```text
POST /api/v1/policies/{id}/submit
  ▼
PolicyService.submitForApproval()
  └── temporalClient.newWorkflowStub(PolicyApprovalWorkflow).start(policyId)
        │
        ├── Activity: resolveApprover()       — find approver for amount tier
        ├── Activity: notifyApprover()        — in-app + email
        │
        │   [Signal: approved | rejected | timeout → escalate]
        │
        ├── [Multi-level] move to next tier, repeat
        │
        ├── Activity: approvePolicy()
        │     ├── policy.status → ACTIVE
        │     ├── Generate PDF (cia-documents)
        │     └── Create debit note (→ cia-finance)
        │
        ├── Activity: uploadToNaicom()        — child workflow, non-blocking
        ├── Activity: uploadToNiid()          — motor / marine only
        └── Activity: sendPolicyDocument()    — email PDF to insured
```

**Why a child workflow for NAICOM/NIID:** approval should never block on a regulator's API. Certificate is generated immediately with `naicom_uid = "PENDING"`; the child workflow regenerates the certificate when the real UID arrives. The on-demand `POST /api/v1/policies/{id}/naicom-upload` endpoint signals a running workflow or starts a new one if missing.

## Stub-to-Live Adapter Pattern

Every external integration that a workflow calls (`NaicomIntegrationService`, `NiidIntegrationService`, `KycVerificationService`, `EmailNotificationService`, `SmsNotificationService`, `DocumentStorageService`) is interface-typed and Spring `@Profile`-swapped. Dev/test profiles bind to stub adapters that respond synchronously with deterministic mock data; production binds to the live REST clients. **Workflow code is identical between profiles** — no `if (env == prod)` branches anywhere.

See [Integrations](./integrations.md) for the full adapter catalogue.

## Worker Embedding

`TemporalAutoConfiguration` registers a single worker inside the `cia-api` Spring context. Activities are Spring beans (`@Component` + `@ActivityImpl`), so they get full dependency injection — repositories, services, `TenantContext`, audit log, etc.

```
cia-api (Spring Boot)
└── TemporalClient → temporal-frontend :7233
└── TemporalWorker (in-process)
    ├── PolicyApprovalWorkflowImpl
    ├── ClaimApprovalWorkflowImpl
    ├── NaicomUploadWorkflowImpl
    ├── WebhookDispatchWorkflowImpl
    └── (all other workflows)
```

**Tenant context inside workflows:** the tenant ID is passed as the first argument to every workflow `start()` call. The first activity invoked sets `TenantContext.setTenantId(...)` so all downstream JPA queries route to the right schema. Workflows never read `TenantContext` directly — they always pass the ID explicitly to activities.

## Period-Lock Awareness (Module 12)

Module 12's `PeriodLockInterceptor` is a Hibernate event listener — it fires on every flush, not at the workflow boundary. Workflows that write to GL-relevant entities (`Receipt`, `Payment`, `ClaimExpense`, `Endorsement`, `DebitNote`, `CreditNote`, `RiAllocation`, `RiFacCover`) implicitly inherit the period-lock check; a soft-closed period past its 5-business-day grace window or a hard-closed period rejects the write with HTTP 423 LOCKED. Reversals are carved out via `LockableByPeriod.isReversal()` so post-close corrections remain possible.

See [Period-End Closures Implementation Plan §1.7](./period-end-closures-implementation-plan.md) for the lock state machine and override roles (`FINANCE_OVERRIDE_LOCK` for soft grace bypass, `FINANCE_REOPEN_PERIOD` for HARD release).

## Local Development

- Temporal server runs in `docker-compose` (auto-setup container; UI at `http://localhost:8088`).
- Workflows execute against the dev cluster automatically — no extra config.
- Re-running a workflow with the same `workflowId` returns the existing handle; use `WorkflowOptions.setWorkflowId(...)` for idempotency keys.

## Production

- Temporal cluster: `temporal-frontend` (1+), `temporal-history` (3+), `temporal-matching` (2+).
- Backing PostgreSQL — separate cluster from the application DB.
- The `cia-api` deployment scales horizontally; workers are stateless from Temporal's point of view (state lives in the cluster).
