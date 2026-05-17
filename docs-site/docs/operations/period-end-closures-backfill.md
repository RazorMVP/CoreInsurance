---
title: Retroactive JE Backfill Runbook
sidebar_label: GL Backfill Runbook
---

# Retroactive Journal-Entry Backfill — Operational Runbook

> Module 12 — Period-End Closures, Slice 1.8 (a + b).
> Audience: platform-engineering on-call, finance-admin who is paired with engineering for the run.
> Last edited 2026-05-17.

## 1. Purpose

The retroactive backfill workflow walks every relevant sub-ledger source table
inside a tenant schema and posts the journal entries that *would* have been
written if `SubledgerPostingService` had been live at the time the source
events fired. It is the one-time on-ramp from "we have business history but no
GL history" to "the GL faithfully reflects the business history" — typically
needed:

- Immediately after V31 (GL foundation) is applied to a long-lived tenant.
- After a regression that disabled posting for one or more event types and
  the backlog needs reconstructing.
- After a forensic data fix that re-flagged source rows as APPROVED /
  SETTLED retroactively.

## 2. What it does (and does not) touch

| Event type | Source table | Posting rule |
|---|---|---|
| `POLICY_APPROVED` | `policies` (`status='APPROVED'`) | `policy_approved.v1` |
| `CLAIM_APPROVED` | `claims` (`status='APPROVED'`) | `claim_approved.v1` |
| `CLAIM_SETTLED` | `claims` (`status='SETTLED'`) | `claim_settled.v1` |
| `CLAIM_EXPENSE_APPROVED` | `claim_expenses` (`status='APPROVED'`) | `claim_expense_approved.v1` |
| `ENDORSEMENT_APPROVED` | `endorsements` (`status='APPROVED'`) | `endorsement_approved.v1` |
| `FAC_PREMIUM_CEDED` | `ri_fac_covers` (FAC outward, `status='CONFIRMED'`) | `fac_premium_ceded.v1` |

What it **does not** touch:

- Source business rows. The workflow is read-only against the source tables.
- Periods that are HARD-closed or SOFT-closed past their grace window — the
  pre-flight check refuses the run (see §5).
- The IFRS 17 measurement engine. That reads business-effective dates
  separately and does not flow through `SubledgerPostingService`.

## 3. Idempotency guarantee

Every `journal_entry` row carries the triple `(source_module,
source_event_type, source_reference)` under a DB UNIQUE constraint. The
activity treats `JournalEntryDuplicateException` as the canonical "already
posted" signal, counted as `alreadyExists`. **Re-running the same workflow
over the same date range is a no-op for rows that posted successfully the
first time.** This is enforced at the DB level, not in application code, so a
race between two concurrent workflows ends with the loser counting that row
as `alreadyExists` rather than producing a duplicate.

## 4. Pre-flight — always do this first

A backfill that crosses a HARD-closed period (or SOFT-closed past the grace
window) is refused by the workflow before any rows are written. Even when
the range is open, **always do a dry run first** to size the work and
validate the date window.

```bash
curl -s -XPOST "https://api.cia.app/api/v1/admin/finance/backfill-journal-entries" \
  -H "Authorization: Bearer ${PLATFORM_ADMIN_TOKEN}" \
  -H "X-Tenant-ID: tenant_acme" \
  -H "Content-Type: application/json" \
  -d '{"fromDate":"2026-01-01","toDate":"2026-04-30","dryRun":true}' | jq
```

Then poll the workflow id from the response:

```bash
curl -s "https://api.cia.app/api/v1/admin/finance/backfill-journal-entries/${WORKFLOW_ID}" \
  -H "Authorization: Bearer ${PLATFORM_ADMIN_TOKEN}" \
  -H "X-Tenant-ID: tenant_acme" | jq
```

The dry-run output gives you the per-event-type attempted/already-exists
breakdown without writing any JEs. Use it to confirm:

- The counts look plausible against your business expectations (rough sanity
  check: did POLICY_APPROVED return ~the number of policies you know are in
  range?).
- `alreadyExists` is 0 if you expect a clean reconstruction; non-zero if
  this is a re-run that should be safe.

## 5. Refused runs (HTTP-level COMPLETED, business-level REFUSED)

If the requested range overlaps a HARD-closed period, the workflow completes
quickly with `result.status = REFUSED` and a `refusalReason` like:

```
Range crosses 1 locked period(s): April 2026 — reopen or narrow range before retrying
```

Recovery options:

1. **Narrow the range** to skip the locked period.
2. **Reopen the locked period** via `PeriodLockService.reopen` (requires
   `FINANCE_REOPEN_PERIOD` role; emits `PeriodReopenedEvent` → CFO email).
3. **Override** the lock for this single backfill — not supported in
   Slice 1.8; reopen is the only path.

## 6. Executing the real backfill

### 6a. Via the REST endpoint

Identical to §4 but with `dryRun: false`:

```bash
curl -s -XPOST "https://api.cia.app/api/v1/admin/finance/backfill-journal-entries" \
  -H "Authorization: Bearer ${PLATFORM_ADMIN_TOKEN}" \
  -H "X-Tenant-ID: tenant_acme" \
  -H "Content-Type: application/json" \
  -d '{"fromDate":"2026-01-01","toDate":"2026-04-30","dryRun":false}' | jq
```

### 6b. Via the CLI (preferred for initial migrations and ops scripts)

The CLI mode boots Spring with the web server disabled, runs the workflow,
prints progress every 2 seconds, and exits with a meaningful code (see §7).
Best for first-tenant migration when no `PLATFORM_ADMIN` JWT exists yet, or
when scripting across a list of tenants.

```bash
java -jar cia-api.jar \
  --spring.main.web-application-type=NONE \
  --cia.backfill.enabled=true \
  --cia.backfill.tenant=tenant_acme \
  --cia.backfill.from=2026-01-01 \
  --cia.backfill.to=2026-04-30 \
  --cia.backfill.dry-run=false
```

Optional narrowed event-type list:

```bash
--cia.backfill.event-types=POLICY_APPROVED,CLAIM_SETTLED
```

The CLI sets `TenantContext` itself, so no `X-Tenant-ID` header is needed.

## 7. CLI exit codes

| Code | Meaning |
|---|---|
| 0 | `SUCCESS` — every row landed; `failed = 0` |
| 1 | `PARTIAL_FAILURE` — at least one row raised an exception other than the idempotency duplicate. Review activity logs to identify the row(s); the JE for those rows is missing and the source rows are still posted (no rollback). |
| 2 | `REFUSED` — pre-flight blocked the run on a closed period. See §5. |
| 3 | Temporal-level failure (`FAILED` / `TIMED_OUT` / `CANCELED` / `TERMINATED`) or polling timeout. Workflow state and partial progress are still in Temporal — re-run the CLI to resume. |
| 4 | Bad input (missing tenant, malformed date, unknown event type). Nothing was sent to Temporal. |

## 8. Status polling cadence

The CLI polls every 2 seconds. The REST endpoint never blocks — call it as
often as the operator wants. Temporal's visibility retention window for
completed workflows is 7 days by default; after that, `GET /{workflowId}`
returns `NOT_FOUND`. Audit history of "who requested what" survives forever in
`audit_log` (entity_type = `JournalBackfillJob`).

## 9. Recovering from a crash mid-run

The workflow is built on Temporal's durability primitives: if the worker JVM
crashes mid-run, Temporal re-schedules the in-flight activity on another
worker and the activity restarts the chunk. The chunk is per-row idempotent
(§3) so any rows that already posted will count as `alreadyExists` on
restart. **No operator action is required to resume.** Just keep polling.

If Temporal itself was down for the whole window, the workflow's
last-recorded state is preserved in Temporal's event history. When Temporal
comes back, the workflow advances from its last persisted progress.

If you actually need to abandon a run — for instance the date range was
wrong — terminate the workflow in the Temporal UI (`tctl workflow terminate
--workflow_id ${WORKFLOW_ID}` or the temporal-web button) and start a new
one with the corrected input. The terminated run's already-posted rows
remain — they are *correct* JEs, just incomplete coverage of the range; the
new run completes the picture.

## 10. Performance budget

The Slice 1.8b benchmark (`mvn test -pl cia-api -Dtest=RetroactiveBackfillIT
-Dbackfill.benchmark=true`) seeds 10,000 `POLICY_APPROVED` rows and asserts
the workflow completes within 5 minutes wall-clock. Observed on local
Testcontainers Postgres: ~30 ms/row → ~5 minutes for 10k rows.

For larger tenants:

| Rows | Expected wall-clock |
|---|---|
| 10,000 | ~5 minutes |
| 100,000 | ~50 minutes |
| 1,000,000 | ~8 hours (run during a planned window) |

Performance is roughly linear because the dominant cost is per-row Hibernate
flush in `SubledgerPostingService.postTwoLine`. The chunk size (default 100,
benchmark 200) trades activity-overhead-per-chunk against retry-blast-radius
per activity failure; do not tune below 50 or above 1000 without a
production performance analysis.

## 11. Audit trail

Every backfill produces:

1. **One** `audit_log` row, `entity_type = JournalBackfillJob`, `action =
   CREATE`, written by `BackfillAdminService` *before* the workflow starts.
   This is the "who asked for it" record.
2. **One** `audit_log` row per posted JE, written by the existing
   `JournalEntryService.post` audit hook — same shape as any other JE
   created via the live event path.
3. **Temporal workflow history** with the full activity trace, retained for
   the cluster's configured `WorkflowExecutionRetentionTtl` (default 7
   days for completed; 30 days for failed).

The combination lets internal audit reconstruct: who requested → what
range/event-types → which rows posted → balanced trial balance.

## 12. Verifying the result

After a successful backfill the canonical check is a balanced trial balance
restricted to the new JEs:

```sql
SELECT
  SUM(jel.debit)  AS total_debits,
  SUM(jel.credit) AS total_credits
FROM journal_entry_line jel
JOIN journal_entry je ON je.id = jel.journal_entry_id
WHERE je.source_event_type IN (
  'POLICY_APPROVED', 'CLAIM_APPROVED', 'CLAIM_SETTLED',
  'CLAIM_EXPENSE_APPROVED', 'ENDORSEMENT_APPROVED', 'FAC_PREMIUM_CEDED'
)
AND je.business_date BETWEEN '2026-01-01' AND '2026-04-30';
```

`total_debits = total_credits` is a hard invariant — every JE produced by
the activity is balanced by construction (two-line, equal Dr/Cr). If the
counts diverge, the backfill is buggy; open an incident.

A secondary check is per-event-type count vs source row count:

```sql
SELECT je.source_event_type, COUNT(*) AS je_count
FROM journal_entry je
WHERE je.business_date BETWEEN '2026-01-01' AND '2026-04-30'
GROUP BY je.source_event_type;
```

Expect this to match `byEventType` in the `BackfillResult` returned by the
workflow.

## 13. Related design docs

- Architecture → [Period-End Closures Foundations Plan](../architecture/period-end-closures-foundations-plan.md) — slice-by-slice ledger of the foundations phase.
- Module 12 entity & class diagrams live alongside `cia-finance/gl/`.
- The companion CI gate (Slice 1.9 — Reconciliation Gate Harness) will
  fail-the-build any PR that reduces JE coverage of the source events; not
  yet shipped at time of writing.
