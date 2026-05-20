---
id: period-end-closures-foundations-plan
title: Period-End Closures — Foundations Plan (Phases 1–3)
sidebar_label: Period-End Closures (Foundations Plan)
---

# Period-End Closures — Foundations Plan (Phases 1–3)

Plan date: 2026-05-09

Status: **Historical** — Phases 1, 2, 3 all shipped (see [`period-end-closures-implementation-plan.md`](./period-end-closures-implementation-plan.md) for shipped slice anchors).

Branch (integration target — now merged): `module-12-period-end-closures`

Scope: This document expands [`period-end-closures-implementation-plan.md`](./period-end-closures-implementation-plan.md) Phases 1, 2, and 3 to PR-slice granularity. **All three phases shipped end-to-end** between 2026-05-12 and 2026-05-19 across 27 slices. Phase 4 (NAICOM monthly recap submissions) was subsequently scoped at the parent-plan granularity and shipped 2026-05-19/20 across 10 slices (see commit `50e5b11`); Phase 5 (frontend) and Phase 6 (cross-tenant platform admin) remain on backlog.

## 0. Implementation Note — Module Layout As Shipped

The plan below referenced two new modules — `cia-investments` (Phase 3) and `cia-closure` (Phase 4–7 orchestration) — that were never created. The shipped layout co-locates all Module 12 code as subpackages of `cia-finance`:

| Planned module | As shipped |
| --- | --- |
| `cia-investments` (IFRS 9 measurement) | `cia-finance/ifrs9/` |
| `cia-closure` (period-close orchestration) | `cia-finance/paa/PaaPeriodCloseService` + `cia-finance/naicom/NaicomSubmissionService` (per-domain orchestrators, not unified) |

Rationale: IFRS 9 measurement posts journal entries the GL gateway in `cia-finance` immediately consumes — splitting it out would create a circular dependency. The same argument that kept IFRS 17 inside `cia-finance/ifrs17/` (since renamed to `cia-finance/paa/`) applies to IFRS 9. The unified `ClosureWorkflow` covering EOD/EOM/EOQ/HY/EOY was deferred in favour of per-domain orchestrators with distinct state machines (see [`period-end-closures-design.md`](./period-end-closures-design.md) §0 for full rationale). The 85% coverage target for these subpackages still applies — measured as part of `cia-finance` rather than separately.

Read the slice-by-slice plan below as the contract we delivered against. The commit-anchored slice tables in [`period-end-closures-implementation-plan.md`](./period-end-closures-implementation-plan.md) §2–§6 are the source of truth for what landed.

## 1. Why A Foundations Plan

The three foundation phases set every contract that the orchestration, NAICOM, frontend, and finality phases consume. A wrong table column or service signature in Phase 1 forces a rewrite later. A weak journal-entry contract in Phase 1 means Phase 2 IFRS 17 reserve postings and Phase 3 IFRS 9 fair-value postings either don't compose, or duplicate work. PR-slice planning at this depth is the cheapest insurance against a rebuild halfway through.

The slices below are sequenced so that:

- **Each slice merges independently to the integration branch** with its own reconciliation gate evidence.
- **Cross-phase dependencies are explicit** (Phase 2 and 3 wait for the journal-entry contract from Phase 1.4).
- **The reconciliation gate harness (Phase 1.9) lands before any business posting code** so all subsequent slices ship with a passing trial-balance gate.

## 2. Phase Critical Path

```
Phase 1 — GL Foundation
  1.1 Schema migrations
   └─► 1.2 COA seed
        └─► 1.3 ChartOfAccountService
             └─► 1.4 JournalEntryService + TrialBalanceService  ◄─── GATEWAY for 2.6, 3.5, 3.6
                  ├─► 1.5 SubledgerPostingService listeners
                  ├─► 1.6 FiscalYearService
                  │    └─► 1.7 PeriodLockService Hibernate interceptor
                  ├─► 1.8 Retroactive JE backfill
                  └─► 1.9 Reconciliation gate harness  ◄─── GATEWAY for every later slice

Phase 2 — IFRS 17 PAA Measurement (depends on 1.4 + 1.9)
  2.1 Schema → 2.2 Portfolio seed → 2.3 LRC → 2.4 LIC → 2.5 Onerous test
  → 2.6 PaaMeasurementService → 2.7 RI ceded mirror → 2.8 Roll-forward reports

Phase 3 — Investments + IFRS 9 (depends on 1.4 + 1.5 + 1.9)
  3.1 Module skeleton + schema → 3.2 InstrumentService → 3.3 HoldingService + IFRS 9 routing
  → 3.4 ValuationService manual MTM → 3.5 EclService + stages
  → 3.6 InvestmentIncomeService → 3.7 Closure activities + reconciliation
```

**Gateway slices** (1.4 and 1.9) gate the start of all downstream work. Phase 2 and Phase 3 slices that don't post journal entries (2.1 schema, 2.2 portfolios, 3.1 skeleton, 3.2 instruments) can begin in parallel with later Phase 1 slices, but no Phase 2/3 slice that posts a JE merges before 1.4 + 1.9 are green.

## 3. Conventions

- **Slice numbering** is `<phase>.<slice>`. Each slice is one PR against the integration branch.
- **Each slice declares:** Goal, Deliverables, Tests, Reconciliation gate evidence (where applicable), Exit criteria, Dependencies.
- **Reconciliation gate evidence** = the artefact added to the PR description proving the trial balance, LRC roll-forward, or IFRS 9 movement still reconciles after the change. Required on every slice that touches the GL or any reservable balance.
- **Test coverage minimums:** 85% line coverage on `cia-finance/ifrs17`, `cia-investments`, and `cia-closure`. Stricter than the project default (80%) because these are financial-control modules.
- **All migrations are forward-only.** No `V*__rollback_*.sql`. Rolling back means writing a new forward migration that compensates the previous one.
- **Branching:** every slice is a feature branch off `module-12-period-end-closures`. Naming: `module-12/<phase>.<slice>-<short-description>` (e.g. `module-12/1.4-journal-entry-service`).
- **Review model:** synchronous review for all financial slices (Phases 1, 2, 3). Frontend-only slices (Phase 6) move to async review. Two reviewers minimum on every Phase 1 / 2 / 3 slice; one must have read the IFRS 17 / IFRS 9 design sections of `period-end-closures-design.md`.

## 4. Phase 1 — GL Foundation (PR Slices)

Total slices: 9. Estimated calendar time: 4–6 weeks single engineer; 3–4 weeks if a second engineer takes 1.5 + 1.6 + 1.8 in parallel after 1.4 lands.

### Slice 1.1 — Schema Migrations

**Goal:** create all GL tables in a single Flyway migration, with the right indexes, constraints, and JSONB columns. No services yet.

**Deliverables:**

- `V31__create_gl_foundation.sql` (tenant schema):
  - `chart_of_account` (id, code, name, account_type [ASSET|LIABILITY|EQUITY|INCOME|EXPENSE], parent_id, ifrs17_role, ifrs9_role, is_active, audit columns)
  - `journal_entry` (id, posting_date, business_date, period_id, source_module, source_event_type, source_reference, narrative, posted_by, reversal_of, status, audit columns)
  - `journal_entry_line` (id, journal_entry_id, line_no, account_id, debit_amount, credit_amount, currency_code, dimension_tags JSONB, audit columns) with CHECK that exactly one of debit/credit > 0
  - `fiscal_year` (id, name, start_date, end_date, status, audit columns)
  - `fiscal_period` (id, fiscal_year_id, period_type [DAY|MONTH|QUARTER|HALF_YEAR|YEAR], start_date, end_date, status, soft_closed_at, hard_closed_at, audit columns)
  - `posting_rule` (id, source_event_type, debit_account_code, credit_account_code, narrative_template, is_active, audit columns)
  - `period_lock` (id, fiscal_period_id, locked_at, lock_type [SOFT|HARD], grace_window_until, released_at, released_by, release_reason, audit columns)
- Indexes: `journal_entry(posting_date)`, `journal_entry(business_date)`, `journal_entry_line(account_id)`, `journal_entry(period_id)`, `journal_entry(source_module, source_reference)`.
- All FKs enforced; all amount columns `NUMERIC(20,2) NOT NULL`.

**Tests:** Flyway migration applies cleanly to a fresh tenant Testcontainer; all CHECK / UNIQUE / FK constraints exercised by negative-path tests.

**Reconciliation gate evidence:** N/A (schema-only).

**Exit criteria:** PR adds zero runtime behaviour; `mvn verify` green on `cia-finance` and `cia-api`; no other module touched.

**Dependencies:** none.

---

### Slice 1.2 — COA Seed

**Goal:** seed the standard Nigerian general-insurance chart of accounts, including IFRS 17 disclosure-roll-forward accounts and IFRS 9 classification accounts up front.

**Deliverables:**

- `V32__seed_chart_of_accounts.sql`:
  - Asset, liability, equity, income, expense accounts at the level required by NAICOM monthly recapitalisation reporting.
  - IFRS 17 accounts: LRC opening / movements / closing; LIC opening / movements / closing; insurance revenue; insurance service expense; risk adjustment unwinding; onerous deficit recognition; reinsurance ceded LRC / LIC mirrors.
  - IFRS 9 accounts: investment income; fair-value gains/losses on FVPL; FVOCI debt / equity OCI movements; ECL expense; amortised-cost interest accrual.
- Idempotent seed via `ON CONFLICT (code) DO NOTHING`.

**Tests:** account count matches expected fixture; every IFRS 17 / IFRS 9 role-tagged account is present and active.

**Reconciliation gate evidence:** account-tree dump committed as `cia-finance/src/test/resources/coa/expected-tree.txt` referenced by the test.

**Exit criteria:** seeded accounts visible after Flyway migrate; PR description includes the COA tree.

**Dependencies:** 1.1.

---

### Slice 1.3 — ChartOfAccountService

**Goal:** read-only service exposing the COA. CRUD is intentionally out of scope until tenant-customisation is required (post-Phase 7).

**Deliverables:**

- `ChartOfAccountService` with `findByCode(String)`, `findByIfrs17Role(Ifrs17Role)`, `findByIfrs9Role(Ifrs9Role)`, `getTree()`.
- Account caching by code (Spring `@Cacheable`); cache eviction on Flyway redo (test only).
- REST controller `GET /api/v1/finance/chart-of-accounts` returning the tree; protected by `finance:view`.

**Tests:** unit tests for each finder; integration test for the controller; cache hit/miss tests.

**Reconciliation gate evidence:** N/A (read-only).

**Exit criteria:** controller passes Springdoc OpenAPI generation; service available for injection by 1.4.

**Dependencies:** 1.2.

---

### Slice 1.4 — JournalEntryService + TrialBalanceService (GATEWAY)

**Goal:** the canonical posting and balance contract that every later financial slice depends on.

**Deliverables:**

- `JournalEntryService.post(PostJournalEntryRequest)` returning `JournalEntryDto`. Validates exactly one of debit/credit > 0 per line; sum of debits = sum of credits per JE; both account codes resolve; status set to POSTED.
- `JournalEntryService.reverse(UUID journalEntryId, String reason)` posting a mirror JE and linking via `reversal_of`.
- `TrialBalanceService.compute(LocalDate asOf)` returning per-account debit/credit totals plus the global net (must be zero).
- `TrialBalanceService.computeForPeriod(UUID fiscalPeriodId)` constraining to a period.
- REST: `POST /api/v1/finance/journal-entries`, `POST /api/v1/finance/journal-entries/{id}/reverse`, `GET /api/v1/finance/trial-balance?asOf=...`.
- Authorisation: `finance:post_je`, `finance:view_trial_balance`.

**Tests:**
- Unbalanced JE rejected with 400.
- Reversal produces equal-and-opposite lines.
- Trial balance net is zero on a Testcontainers fixture of 100 random JEs.
- Trial balance net is zero after a reversal.
- Period filter restricts to in-period JEs only.

**Reconciliation gate evidence:** PR includes the trial-balance JSON output of the 100-JE fixture confirming `net == 0`. This is the canonical reference output that Slice 1.9 hardens into a CI gate.

**Exit criteria:** Slices 1.5, 1.8, 2.6, 3.5, 3.6 are now unblocked.

**Dependencies:** 1.3.

---

### Slice 1.5 — SubledgerPostingService Listeners

**Goal:** wire the existing `cia-finance` events to the GL so historical sub-ledger movements (debit notes, credit notes, receipts, payments) post journal entries automatically.

**Deliverables:**

- `SubledgerPostingService` Spring component with `@EventListener` methods for `DebitNoteApprovedEvent`, `CreditNoteIssuedEvent`, `ReceiptPostedEvent`, `PaymentPostedEvent`.
- Each listener resolves the matching `posting_rule`, builds a `PostJournalEntryRequest`, and calls `JournalEntryService.post(...)`.
- `posting_rule` rows seeded for each event type via `V33__seed_posting_rules.sql`.
- Idempotency: each listener checks `journal_entry(source_module, source_event_type, source_reference)` and skips if already posted.

**Tests:**
- Each event posts the expected JE shape.
- Replaying the same event twice produces exactly one JE.
- Trial balance remains balanced after a sample run of 50 mixed events.

**Reconciliation gate evidence:** PR includes the trial-balance output before and after the 50-event run.

**Exit criteria:** new sub-ledger transactions automatically appear in trial balance.

**Dependencies:** 1.4.

---

### Slice 1.6 — FiscalYearService

**Goal:** tenant-configurable fiscal year and period generation. Default: calendar year ending Dec 31.

**Deliverables:**

- `FiscalYearService.create(CreateFiscalYearRequest)` accepting start/end dates, generating period rows for DAY (lazy on demand), MONTH, QUARTER, HALF_YEAR, YEAR.
- `FiscalYearService.activate(UUID fiscalYearId)` deactivating siblings.
- `FiscalYearService.findActiveYear(LocalDate asOf)` and `findPeriod(periodType, asOf)`.
- REST: `GET /api/v1/finance/fiscal-years`, `POST /api/v1/finance/fiscal-years`, `POST /api/v1/finance/fiscal-years/{id}/activate`.
- Frontend Setup → Fiscal Year page (single page, calendar default, change-with-warning when year already has JEs).

**Tests:** period generation produces 12 months / 4 quarters / 2 halves / 1 year per fiscal year; period boundary edge cases (29 Feb, year-roll); active-year resolution.

**Reconciliation gate evidence:** N/A (no GL postings yet).

**Exit criteria:** `findPeriod(...)` returns the correct period for any date in the active year.

**Dependencies:** 1.1, 1.4 (uses period_id on JEs).

---

### Slice 1.7 — PeriodLockService Hibernate Interceptor + Lock Mechanism (CANARY)

**Goal:** ship the lock mechanism + the `JournalEntry` canary opt-in. The Phase 1 exit criterion ("every business module covered") is satisfied across Slice 1.7 + 1.7a + 1.7b — opting in 30+ entities in one PR makes review impossible.

**Scope adjustments after expert critique pass (2026-05-15):**

- **Split override permission into two roles** — `finance:override_soft_close` (grace bypass) and `finance:reopen_period` (HARD release). One bundled role is a segregation-of-duties audit finding waiting to happen.
- **Lock anchor is the BOOKING date, not the business-effective date** — endorsements return `bookedDate`, not `effectiveDate`. IFRS 17 measurement uses effective dates and never flows through this interceptor.
- **Reversal carve-out is a first-class interface concern** — `LockableByPeriod.isReversal()` default `false`; entities with a reversal model override. Without this, post-close corrections become impossible and finance teams disable the interceptor "just this once."
- **Lock-history table already in V31** — the `period_lock` table is a Type-2 SCD (each soft/hard/release event is a row, `released_at IS NULL` = active). No separate `period_lock_history` is needed — the `period_lock` rows themselves are the NAICOM-grade evidence trail.
- **Structured error contract** — `PeriodLockedException` extends `CiaException` and a dedicated `PeriodLockExceptionHandler` renders `{ code, periodLabel, status, graceEndsAt, overrideRoles }` as the response meta. Frontend toast reads fields by name; no string parsing.
- **Bulk preview API in this slice** — `GET /api/v1/finance/period-locks/preview?from&to` returns one `LockReportEntry` per business date so Slice 1.8 backfill and Module 8 bulk receipts pre-check before kicking off the workflow, not discover the lock on row 4,837.
- **Scope-aware fiscal-period lookup cache** — `FiscalPeriodLookupCache` originally shipped as `@RequestScope`; refactored in Slice 1.7-fix to a singleton with two storage backends: `SCOPE_REQUEST` attribute when an HTTP request is bound (production HTTP path, auto-cleaned by Spring) + per-thread `HashMap` fallback (Temporal activities, scheduled jobs). Cache key is `(tenantId, lockDate)` to prevent cross-tenant hits on pooled worker threads. Non-HTTP callers invoke `clearThreadCache()` at activity boundaries; Slice 1.8's Temporal `WorkerInterceptor` owns that lifecycle.
- **Benchmark target tightened to <2 % p99** — 5 % on a 100M-write/year tenant is 5M wasted writes. Anything between 1 % and 2 % requires a flame-graph in the PR description.
- **CFO + compliance email notification on every reopen** — `PeriodReopenedEvent` published from `cia-finance`, consumed by `PeriodReopenedNotificationListener` in `cia-api` (bridges to `NotificationService`). Recipients via Spring property `cia.finance.period-reopen-recipients` (CSV) for v1; per-tenant config table is a Slice 1.7c follow-up.

**Deliverables (shipped):**

- `cia-common/LockableByPeriod` interface (`getLockDate`, default `isReversal`).
- `cia-finance/gl/PeriodLock` entity over the V31 `period_lock` table + `PeriodLockRepository`.
- `cia-finance/gl/LockType` (SOFT / HARD), `LockOutcome` (ALLOW / REJECT / OVERRIDE), `LockDecision` (structured envelope).
- `cia-finance/gl/PeriodLockedException` (extends `CiaException`, HTTP 423).
- `cia-finance/gl/FiscalPeriodLookupCache` — scope-aware singleton: request-attribute storage when an HTTP request is bound, ThreadLocal fallback otherwise; cache key `(tenantId, lockDate)`. (Slice 1.7 shipped `@RequestScope`; refactored in Slice 1.7-fix to unblock Slice 1.8 Temporal workflows.)
- `cia-finance/gl/PeriodLockService` — `softClose / hardClose / reopen / previewLock / checkWrite / history / daysSinceSoftClose`. Auto-soft-before-hard to honour `ck_fiscal_period_close_chronology`.
- `cia-finance/gl/PeriodLockInterceptor` (Hibernate `Interceptor`) — registered via `cia-finance/gl/PeriodLockInterceptorConfig` (`HibernatePropertiesCustomizer`).
- `cia-finance/gl/PeriodLockController` — `POST /soft-close`, `POST /hard-close`, `POST /reopen`, `GET /history`, `GET /preview`.
- `cia-finance/gl/PeriodLockExceptionHandler` — structured 423 LOCKED body with `meta.{periodId, periodLabel, status, graceEndsAt, overrideRoles}`.
- `cia-finance/gl/PeriodReopenedEvent` + `PeriodReopenedLogListener` (in-module log).
- `cia-api/.../PeriodReopenedNotificationListener` — bridges to `NotificationService`.
- `cia-finance/gl/JournalEntry implements LockableByPeriod` — `getLockDate = businessDate`, `isReversal = reversalOf != null`.
- `cia-common.AuditAction` extended with `CLOSE`, `REOPEN`, `LOCK_OVERRIDE`.
- Unit tests: `PeriodLockServiceTest` — 9-state decision matrix + lifecycle + business-day arithmetic (18 tests).
- Integration test: `PeriodLockInterceptorIT` — Testcontainers, real Hibernate flush, 7 scenarios including reversal carve-out and override allow.
- Benchmark scaffolding: `PeriodLockInterceptorBenchmark` (`@Disabled` JUnit pending JMH wiring follow-up).

**Tests:**
- Backdated entry past grace blocked with `PeriodLockedException` ✓
- Override permission allows the entry; audit-log entry recorded ✓
- Hard-closed period blocks even with override ✓
- Reversal entry succeeds despite HARD lock ✓
- Lock history accumulates soft/hard/release rows chronologically ✓
- Benchmark CI workflow `module-12-benchmark.yml` — follow-up commit, see scaffolding class

**Exit criteria:** lock mechanism in place + JournalEntry opted in + unit/IT green; entity-opt-in sweep tracked in 1.7a (Receipt, Payment, ClaimExpense, Endorsement) and 1.7b (remaining monetary entities).

**Dependencies:** 1.6.

---

### Slice 1.7a — LockableByPeriod opt-in for Finance entities (follow-up) — SHIPPED (Session 69)

**Goal:** opt the four entities with direct monetary impact into the lock mechanism. One file per entity, easy to review, each module owner can sign off independently.

**Shipped:**

- `Receipt implements LockableByPeriod { getLockDate() = paymentDate; isReversal() = reversedAt != null }`.
- `Payment implements LockableByPeriod { getLockDate() = paymentDate; isReversal() = reversedAt != null }`.
- `ClaimExpense implements LockableByPeriod { getLockDate() = approvedAt?.toLocalDate(); isReversal() = cancelledAt != null }`.
- `Endorsement implements LockableByPeriod { getLockDate() = approvedAt?.toLocalDate(); isReversal() = cancelledAt != null }`. **Critical:** booking date, NOT effectiveDate.
- Per-entity contract test for each (`ReceiptLockableByPeriodTest`, `PaymentLockableByPeriodTest`, `ClaimExpenseLockableByPeriodTest`, `EndorsementLockableByPeriodTest`).

**Dependencies:** 1.7.

---

### Slice 1.7b — LockableByPeriod opt-in sweep (remaining business modules) — SHIPPED (Session 69)

**Shipped:**

- `DebitNote`, `CreditNote`, `RiAllocation` — `getLockDate() = getCreatedAt()?.toLocalDate()` (UTC). These entities have no explicit booked-date field; `BaseEntity.createdAt` IS the booking date.
- `RiFacCover` — `getLockDate() = approvedAt?.toLocalDate()`; `isReversal() = cancelledAt != null`. Same shape as Endorsement.
- Per-entity contract test for each (reflectively sets `BaseEntity.createdAt` in pure unit tests).

**Dependencies:** 1.7, 1.7a.

---

### Slice 1.7c — Prior-Period Adjustment posting workflow + tenant CFO config + holiday calendar — SHIPPED (Session 69)

**Goal:** add the IFRS-compliant prior-period-adjustment path so audit-found errors in closed periods are posted as PPAs in the OPEN period (with IAS 8 disclosure metadata), not by reopening the closed period. Also adds the per-tenant CFO + compliance distro config + NAICOM holiday calendar.

**Shipped:**

- Flyway V35 — `journal_entry.prior_period_adjustment BOOLEAN NOT NULL DEFAULT FALSE` + `prior_period_adjustment_reason TEXT` + partial index `idx_journal_entry_ppa`. New tables `tenant_reopen_recipient` and `tenant_holiday`.
- REST endpoint `POST /api/v1/finance/journal-entries/prior-period-adjustment` gated by `FINANCE_APPROVE_PPA`. The service forces `business_date=today`, `source_module="finance"`, `source_event_type="PRIOR_PERIOD_ADJUSTMENT"` so every PPA lands in the OPEN period regardless of which closed period the error originated in.
- `TenantHoliday` + `TenantHolidayRepository`. `PeriodLockService.addBusinessDays` overload accepts `Set<LocalDate>`; instance method `addBusinessDaysWithHolidays` loads from the table. `softClose` uses the holiday-aware path.
- `TenantReopenRecipient` + `TenantReopenRecipientRepository`. `PeriodReopenedNotificationListener` reads DB-first; falls back to the `cia.finance.period-reopen-recipients` CSV property only when no rows are configured (smooth migration).
- `PeriodLockServiceHolidayTest` — 6 unit tests covering weekend skip, mid-week holiday shift, consecutive holidays, weekend-flagged-as-holiday no-op, and the back-compat 2-arg ↔ 3-arg equivalence.

**Dependencies:** 1.7.

---

### Slice 1.8 — Retroactive JE Backfill

Split into two parts during design pass:

#### Slice 1.8a — Mechanism (SHIPPED)

**Goal:** per-tenant Temporal workflow that replays the six sub-ledger events as JEs over a date range. Idempotent and rerunnable.

**Deliverables (shipped):**

- `RetroactiveJournalBackfillWorkflow` + `RetroactiveJournalBackfillActivities` interfaces in `cia-workflow` (queue `BACKFILL_QUEUE`).
- Activities impl in `cia-finance/backfill/` — native SQL against `policies`, `claims`, `endorsements`, `claim_expenses`, `ri_fac_covers`; reuses the live event path via new public `replay*` methods on `SubledgerPostingService`.
- `SubledgerPostingService` extracted: `@EventListener` methods delegate to public `replay*(event)` / `replay*(event, businessDate)` overloads. The 1-arg form (live path) preserves `today()`; the 2-arg form (backfill path) takes the historical date so JEs land in the period the approval actually occurred in.
- `TenantAwareWorkerInterceptor` (in `cia-workflow/interceptor/`) plus `ActivityThreadCleanup` hook contract. Wired in `TemporalConfig`. `cia-finance` contributes `FinanceActivityCleanup` to clear the scope-aware `FiscalPeriodLookupCache` ThreadLocal on every activity boundary.
- Pre-flight period-lock check via `PeriodLockService.previewLock` (D6) — refuses runs that cross HARD-closed or SOFT-past-grace periods.
- Dry-run support from day one (D7) — `BackfillRequest.dryRun=true` builds the events and counts what would be posted without writing.
- Admin REST endpoint `POST /api/v1/admin/finance/backfill-journal-entries` gated by `PLATFORM_ADMIN` (D8); `BackfillAdminService` writes an `audit_log` row capturing the request before starting the workflow.
- Chunk size 100 (workflow constant); Temporal retry `maximumAttempts=3` with 5s→2m exponential backoff; heartbeat every 10 rows.

**Tests (shipped):**

- `RetroactiveJournalBackfillActivitiesImplTest` — 7 unit tests covering preflight blocked/allowed, happy path, dry-run, duplicate→alreadyExists, unexpected→failed+continue, empty→exhausted.
- `RetroactiveBackfillIT` — Testcontainers IT: 3 approved policies → 3 balanced JEs (total debits = total credits = ₦600k); second run reports `alreadyExists=3, posted=0` (idempotency held); HARD-close + preview = `hasBlockingLocks=true` with period label.

**Exit criteria met:**

- Re-running the backfill produces zero new JEs (DB UNIQUE on idempotency triple).
- Pre-flight refuses HARD-closed range.
- All cia-finance unit tests pass after the `SubledgerPostingService` refactor.

#### Slice 1.8b — Operations & Polish (SHIPPED)

**Deliverables (shipped):**

- `BackfillCliRunner` in `cia-api/finance/backfill/` — Spring `ApplicationRunner` gated by `@ConditionalOnProperty("cia.backfill.enabled")`. Reads `--cia.backfill.{tenant,from,to,event-types,dry-run}`, sets `TenantContext` for the duration, starts the workflow via `BackfillAdminService`, polls every 2 seconds, prints per-status transitions, exits via `SpringApplication.exit(...)` so `@PreDestroy` hooks run cleanly. Exit codes 0/1/2/3/4 map to SUCCESS / PARTIAL_FAILURE / REFUSED / Temporal-failure / bad-input.
- `GET /api/v1/admin/finance/backfill-journal-entries/{workflowId}` status endpoint. Implemented on Temporal's raw gRPC `DescribeWorkflowExecutionRequest` (the SDK 1.25.0 `WorkflowStub` interface does not expose a typed `describe()`); returns NOT_FOUND inside the response body, not as HTTP 404. Two-layer status — `executionStatus` (Temporal-level: RUNNING / COMPLETED / FAILED / CANCELED / TERMINATED / TIMED_OUT / NOT_FOUND) plus `result` (workflow-level: SUCCESS / PARTIAL_FAILURE / REFUSED, populated only when executionStatus = COMPLETED).
- Operational runbook at `docs-site/docs/operations/period-end-closures-backfill.md` covering purpose, idempotency, pre-flight, REST + CLI execution, exit codes, status polling cadence, mid-run-crash recovery, performance budgets, audit trail, trial-balance verification.
- `BackfillStatusResponse` DTO in `cia-finance/backfill/dto/` with `notFound(workflowId)` factory.

**Tests (shipped):**

- `backfillIsResumableAfterPartialRun` IT — seeds 5 policies, runs `processChunk(offset=0, limit=2)` (models worker crash after 2 of 5 rows), then runs `processChunk(offset=0, limit=100)` and asserts `alreadyExists=2`, `posted=3`, total JEs=5, balanced trial balance ₦1.5M Dr=Cr. Proves the canonical durability claim that a crashed backfill restarts cleanly without duplicates.
- `backfillOf10kEventsCompletesUnderBudget` IT — gated by `@EnabledIfSystemProperty("backfill.benchmark", "true")`. Bulk-seeds 10k `POLICY_APPROVED` rows via `jdbcTemplate.batchUpdate`, loops chunks of 200, asserts wall-clock < 5 minutes. Documents the per-row Hibernate flush cost as the dominant scaling factor.

**Exit criteria met:**

- Status endpoint never blocks (gRPC describe returns immediately even mid-flight).
- CLI exits via Spring lifecycle so DB pool + Temporal worker drain cleanly — no hanging gRPC connections to bite the next ops step.
- Aborted-run-resumes test green on local CI (Testcontainers Postgres).
- 10k benchmark runs inside its 5-minute budget on developer-laptop Postgres.

**Dependencies:** 1.8a (shipped), 1.5.

---

### Slice 1.9 — Reconciliation Gate Harness (GATEWAY)

**Goal:** durable CI gate that fails any future PR which leaves trial balance unbalanced after running the seeded event fixture set.

**Status:** **1.9a + 1.9b BOTH SHIPPED** (Sessions 67 + 68, on `module-12-period-end-closures`). Phase 1 complete.

**1.9a deliverables (shipped):**

- `ReconciliationGateIT` integration test (Testcontainers) at `cia-api/src/test/java/.../finance/reconciliation/`:
  1. Starts a fresh tenant schema (Flyway target 34).
  2. Plays the canonical 50-event fixture (`cia-api/src/test/resources/reconciliation/events.json`) via `ApplicationEventPublisher` → `SubledgerPostingService` → `JournalEntryService`.
  3. Calls `TrialBalanceService.trialBalanceAsOf(...)`.
  4. Asserts the trial balance matches the snapshot `expected-trial-balance.json` exactly (per-account `{debit, credit}` keyed by account code).
- **Mutation guard** test in the same class: deliberately swaps Dr/Cr on the POLICY_APPROVED posting rule, asserts the snapshot match FAILS — proves the gate is not a tautology.
- GitHub workflow `.github/workflows/module-12-reconciliation.yml` scoped to `cia-finance/**`, GL Flyway migrations, fixture/snapshot files, and the IT class itself. Faster signal than the full `mvn verify`.
- Snapshot-update flow: `mvn test -pl cia-api -Dtest=ReconciliationGateIT -Dsnapshot.update=true`. PR description must explain why the snapshot moved.

**1.9b deliverables (pending):**

- Scale fixture from 50 → 200 events with edge cases: FX-rounding boundary amounts, mid-period business dates, zero-net endorsement pairs, claim approve-then-settle pairs on the same claim.
- Per-JE evidence file output (companion to the per-account snapshot) so finance can audit individual postings at PR-review time. Lives under `cia-api/src/test/resources/reconciliation/per-je-evidence.json`.

**Tests (1.9a):** the gate test itself + the mutation guard meta-test that breaks deliberately to confirm CI catches it.

**Reconciliation gate evidence:** the gate is the evidence; PR shows green workflow run.

**Exit criteria:** every later slice in this plan must run this workflow green.

**Dependencies:** 1.5, 1.8 (uses fixture set in part derived from backfill scenarios).

## 5. Phase 2 — IFRS 17 PAA Measurement (PR Slices)

Total slices: 8. Estimated calendar time: 4–6 weeks single engineer. May start at Slice 2.1 in parallel with Phase 1 once Slice 1.1 has merged; cannot post any JE before Slice 1.4 + 1.9 are green.

### Slice 2.1 — IFRS 17 Schema

**Goal:** all measurement tables in a single Flyway migration.

**Deliverables:**

- `V34__create_ifrs17_paa.sql` (tenant schema):
  - `insurance_portfolio` (id, code, name, product_id, line_of_business, currency_code, audit columns)
  - `contract_group` (id, portfolio_id, cohort_year, group_status [PROFITABLE|NEAR_ONEROUS|ONEROUS], audit columns)
  - `lrc_balance` (id, contract_group_id, fiscal_period_id, opening_balance, new_business, premium_received, revenue_recognised, acquisition_cost_amortisation, closing_balance, currency_code, audit columns)
  - `lic_balance` (id, contract_group_id, fiscal_period_id, opening_balance, claims_notified, claims_paid, risk_adjustment, risk_adjustment_unwind, closing_balance, currency_code, audit columns)
  - `onerous_test_result` (id, contract_group_id, fiscal_period_id, expected_premium, expected_claims, expected_expenses, expected_acquisition_cost, fulfilment_cash_flows, is_onerous, deficit, audit columns)
  - Indexes on `(contract_group_id, fiscal_period_id)` for both balance tables.

**Tests:** schema applies; constraints enforced; FKs to `fiscal_period` and `products` enforced.

**Reconciliation gate evidence:** N/A.

**Exit criteria:** PR adds zero runtime behaviour.

**Dependencies:** 1.1, 1.6.

---

### Slice 2.2 — Portfolio Seeding

**Goal:** seed `insurance_portfolio` rows from existing `products`. One portfolio per product family at first; tenant admins may re-bucket later (out-of-scope here).

**Deliverables:**

- `V35__seed_insurance_portfolios.sql` reading from `products` and inserting one portfolio per `product.product_family` (or per product if no family field).
- Backfill task assigning every existing in-force policy to a `contract_group` based on its product's portfolio and `start_date.year` cohort.

**Tests:** every active policy has a non-null `contract_group_id` after backfill.

**Reconciliation gate evidence:** N/A (no JEs yet).

**Exit criteria:** policies are queryable by portfolio + cohort.

**Dependencies:** 2.1.

---

### Slice 2.3 — LrcCalculationService

**Goal:** compute LRC roll-forward per contract group per period.

**Deliverables:**

- `LrcCalculationService.computePeriodMovement(UUID contractGroupId, UUID fiscalPeriodId)` returning an `LrcMovement` DTO.
- Inputs: opening LRC, period premiums received (from `cia-finance` aggregations), period revenue (earned premium), acquisition cost amortisation rate.
- Output: persisted `lrc_balance` row with movement components.
- REST: `POST /api/v1/finance/ifrs17/lrc/calculate` accepting `{ portfolioId?, contractGroupId?, fiscalPeriodId }`; returns the calculated balances.

**Tests:**
- Opening LRC + new business − revenue recognised − acquisition amortisation == closing LRC (tolerance 0.01).
- A portfolio with zero new business produces a strictly decreasing LRC.
- Currency mismatch between policy and portfolio rejected.

**Reconciliation gate evidence:** sample LRC roll-forward attached.

**Exit criteria:** LRC calculable for every active contract group.

**Dependencies:** 2.2.

---

### Slice 2.4 — LicCalculationService With Risk Adjustment Placeholder

**Goal:** compute LIC roll-forward including a placeholder risk-adjustment value, with a clear marker that production risk adjustment requires actuarial calibration.

**Deliverables:**

- `LicCalculationService.computePeriodMovement(...)` returning `LicMovement`.
- Risk adjustment computed via 75th-percentile bootstrap on per-portfolio claims development triangles. Code-tagged `// TODO: actuarial calibration — requires sign-off`.
- `RiskAdjustmentService.compute(portfolioId, fiscalPeriodId)` isolated for later replacement.
- Persisted `lic_balance` row with all components.

**Tests:**
- Opening LIC + claims notified − claims paid + risk adjustment unwind == closing LIC.
- Bootstrap on synthetic triangle returns a value in expected range.
- Service deterministic when given the same triangle input.

**Reconciliation gate evidence:** sample LIC roll-forward attached.

**Exit criteria:** LIC calculable for every active contract group; the actuarial-calibration TODO is referenced by an open issue tracked in `docs-site/docs/architecture/period-end-closures-design.md` provisional-layers section.

**Dependencies:** 2.3.

---

### Slice 2.5 — OnerousContractTestService

**Goal:** the IFRS 17 onerous-contract test, persisting per-period results and updating `contract_group.group_status`.

**Deliverables:**

- `OnerousContractTestService.runTest(UUID contractGroupId, UUID fiscalPeriodId)` returning `OnerousTestResult`.
- Inputs: expected premium (from forward-looking quote/policy data), expected claims (from triangles), expected expenses, acquisition cost.
- Output: persisted `onerous_test_result`; `contract_group.group_status` updated to ONEROUS / NEAR_ONEROUS / PROFITABLE.

**Tests:**
- Profitable group classified PROFITABLE.
- Group with deficit > threshold classified ONEROUS, deficit recorded.
- Boundary test on NEAR_ONEROUS threshold.

**Reconciliation gate evidence:** N/A (no JE yet — see 2.6).

**Exit criteria:** test runnable per group; `group_status` updates persistent.

**Dependencies:** 2.4.

---

### Slice 2.6 — PaaMeasurementService With JE Posting

**Goal:** orchestrate LRC + LIC + onerous test for every contract group in a period and post the resulting IFRS 17 JEs.

**Deliverables:**

- `PaaMeasurementService.measurePeriod(UUID fiscalPeriodId)` driving the per-group calculations and producing a `PeriodMeasurementReport`.
- IFRS 17 JEs posted via `JournalEntryService.post(...)`:
  - Insurance revenue recognition.
  - Insurance service expense (claims paid + risk-adjustment unwind).
  - LRC movement transfer.
  - LIC movement transfer.
  - Onerous deficit recognition (only for ONEROUS groups).
- Idempotency via `source_event_type = 'IFRS17_PAA_MEASUREMENT'` and `source_reference = '<periodId>:<contractGroupId>'`.
- REST: `POST /api/v1/finance/ifrs17/measure-period` (admin scope).

**Tests:**
- Re-running the same period produces no new JEs (idempotency).
- Trial balance balanced after measurement run.
- JE narrative templates resolve with correct portfolio + cohort tags.

**Reconciliation gate evidence:** trial balance + LRC/LIC roll-forward outputs attached. Slice 1.9 reconciliation gate must remain green.

**Exit criteria:** end-to-end period measurement runnable from a single REST call; reconciliation gate green.

**Dependencies:** 1.4, 1.9, 2.5.

---

### Slice 2.7 — ReinsuranceContractsHeldService Mirror

**Goal:** mirror PAA measurement to the ceded side for reinsurance contracts held.

**Deliverables:**

- `ReinsuranceContractsHeldService.measurePeriod(...)` mirroring 2.6 output at the cession percentage from `cia-reinsurance.treaty` allocations.
- Ceded LRC / LIC tracked in mirror tables `reinsurance_lrc_balance`, `reinsurance_lic_balance` (added by `V36__create_ri_held_balances.sql`).
- Ceded JEs posted to ceded-side accounts.

**Tests:**
- Ceded LRC == gross LRC × cession percentage on a quota-share treaty.
- Surplus treaty ceded LRC computed against retention/surplus split.
- Trial balance balanced after a gross + ceded run.

**Reconciliation gate evidence:** trial balance attached.

**Exit criteria:** every gross measurement triggers a ceded mirror; both reconciled.

**Dependencies:** 2.6.

---

### Slice 2.8 — Roll-Forward Reports

**Goal:** the LRC and LIC roll-forward reports consumed by the EOM disclosure pack and Phase 5 NAICOM submissions.

**Deliverables:**

- `LrcRollForwardReportGenerator` and `LicRollForwardReportGenerator` producing CSV + PDF (Apache PDFBox) per portfolio / cohort / period.
- REST: `GET /api/v1/finance/ifrs17/reports/lrc-roll-forward?periodId=...&format=...`, similarly for LIC.
- Stored as report definitions in `report_definition` (the `cia-reports` module convention).

**Tests:**
- Generated CSV totals tie to the underlying `lrc_balance` / `lic_balance` rows.
- PDF renders without throwing on a 5,000-group input.

**Reconciliation gate evidence:** CSV totals == DB totals (asserted in test).

**Exit criteria:** reports downloadable from the back-office and exposed as `cia-reports` definitions.

**Dependencies:** 2.7.

## 6. Phase 3 — Investments + IFRS 9 (PR Slices)

Total slices: 7. Estimated calendar time: 4–5 weeks single engineer. Slices 3.1 + 3.2 may run in parallel with Phase 1 once Slice 1.1 has merged.

### Slice 3.1 — Module Skeleton + Schema

**Goal:** new `cia-investments` Maven module with empty domain / service / controller layers, plus all tables in a single migration.

**Deliverables:**

- New module `cia-investments/` with `pom.xml` depending on `cia-common`, `cia-auth`, `cia-finance` (for `JournalEntryService` injection).
- Module added to `cia-api` assembly so the module's controllers are auto-scanned.
- `V37__create_investments.sql` (tenant schema):
  - `instrument` (id, isin?, code, name, instrument_type [BOND|EQUITY|FUND|TBILL|MMD|OTHER], issuer, currency_code, coupon_rate?, maturity_date?, audit columns)
  - `holding` (id, instrument_id, custodian, ifrs9_classification [FVPL|FVOCI_DEBT|FVOCI_EQUITY|AMORTISED_COST], business_model, sppi_passed, acquisition_date, acquisition_cost, units, status, audit columns)
  - `valuation` (id, holding_id, valuation_date, market_price, fair_value, source [MANUAL|MARKET_DATA], audit columns)
  - `income_accrual` (id, holding_id, accrual_date, accrual_type [INTEREST|DIVIDEND|COUPON], amount, currency_code, audit columns)
  - `ecl_provision` (id, holding_id, fiscal_period_id, stage [STAGE1|STAGE2|STAGE3|POCI], pd_12m, pd_lifetime, lgd, ead, ecl_amount, audit columns)
- Module-level `cia-investments-test` source set wired into CI.

**Tests:** module compiles; schema applies; module's empty controller list registers.

**Reconciliation gate evidence:** N/A.

**Exit criteria:** integration test confirms module beans are discovered.

**Dependencies:** 1.1.

---

### Slice 3.2 — InstrumentService

**Goal:** instrument master data CRUD.

**Deliverables:**

- `InstrumentService.create / update / archive / find / list`.
- REST: full CRUD under `/api/v1/investments/instruments` gated by `investments:create`, `investments:view`, `investments:update`.
- Validation: ISIN format if provided; coupon rate required for BOND/TBILL/MMD.

**Tests:** unit + integration; negative-path validation; soft-delete archive.

**Reconciliation gate evidence:** N/A.

**Exit criteria:** instruments queryable from frontend (frontend lands in Phase 6).

**Dependencies:** 3.1.

---

### Slice 3.3 — HoldingService + IFRS 9 Classification

**Goal:** holdings CRUD with automatic IFRS 9 classification routing on creation.

**Deliverables:**

- `HoldingService.create(CreateHoldingRequest)` — runs `Ifrs9ClassificationService.classify(...)` then persists.
- `Ifrs9ClassificationService.classify(...)` implementing:
  - Business-model question (Hold-to-Collect | Hold-to-Collect-and-Sell | Trading | Other).
  - SPPI test (Solely Payments of Principal and Interest) for debt instruments.
  - Routing matrix → FVPL / FVOCI_DEBT / FVOCI_EQUITY / AMORTISED_COST.
- `HoldingService.reclassify(...)` (admin-only, reason required, audit-logged).
- REST: holdings CRUD under `/api/v1/investments/holdings`.

**Tests:**
- Bond passing SPPI + held-to-collect → AMORTISED_COST.
- Bond passing SPPI + held-to-collect-and-sell → FVOCI_DEBT.
- Bond failing SPPI → FVPL.
- Equity → FVOCI_EQUITY (when irrevocable election made) or FVPL.
- Reclassification recorded with audit entry.

**Reconciliation gate evidence:** N/A (no JE yet).

**Exit criteria:** holdings auto-classified deterministically.

**Dependencies:** 3.2.

---

### Slice 3.4 — ValuationService Manual MTM

**Goal:** monthly mark-to-market with manual valuation entry. Market-data integration is explicitly out of scope.

**Deliverables:**

- `ValuationService.recordValuation(holdingId, valuationDate, marketPrice)` computing `fair_value = units × market_price` and persisting.
- `ValuationService.runManualMtm(fiscalPeriodId)` workflow: list active holdings; require manual entry per holding; produce a coverage report (`X of Y holdings valued`).
- REST: `POST /api/v1/investments/holdings/{id}/valuations`, `GET /api/v1/investments/valuations/coverage?periodId=...`.

**Tests:** valuation persists; fair_value derivation correct; coverage report accurate.

**Reconciliation gate evidence:** N/A (JE postings come in Slice 3.6).

**Exit criteria:** monthly MTM doable end-to-end manually.

**Dependencies:** 3.3.

---

### Slice 3.5 — EclService + Stage Transitions

**Goal:** ECL computation and stage-transition mechanics for AMORTISED_COST and FVOCI_DEBT holdings.

**Deliverables:**

- `EclService.computeStage1(holding)`, `computeStage2(holding)`, `computeStage3(holding)` returning ECL amount per stage.
- Simple credit-rating-band PD/LGD lookup (seeded via `V38__seed_credit_rating_bands.sql`). Sophisticated PD/LGD modelling is explicitly future-phase.
- Stage transition rules:
  - STAGE1 → STAGE2 on significant credit deterioration.
  - STAGE2 → STAGE3 on credit-impaired indicator.
  - POCI on acquisition of credit-impaired assets.
- Persisted `ecl_provision` per holding per period.
- IFRS 9 ECL JEs posted via `JournalEntryService.post(...)`:
  - ECL expense Dr / ECL provision Cr.
- Idempotency via `source_event_type = 'IFRS9_ECL_PROVISION'` and `source_reference = '<periodId>:<holdingId>'`.

**Tests:**
- Stage1 ECL == 12-month expected loss.
- Stage transition on threshold breach.
- Trial balance balanced after a sample portfolio run.

**Reconciliation gate evidence:** trial balance attached; reconciliation gate must remain green.

**Exit criteria:** ECL calculable per period; reconciliation gate green.

**Dependencies:** 1.4, 1.9, 3.4.

---

### Slice 3.6 — InvestmentIncomeService

**Goal:** interest accrual (per coupon schedule) and dividend accrual.

**Deliverables:**

- `InvestmentIncomeService.accrueInterest(holdingId, asOf)` for AMORTISED_COST and FVOCI_DEBT.
- `InvestmentIncomeService.recordDividend(holdingId, amount)` for FVOCI_EQUITY.
- `accruePeriod(fiscalPeriodId)` workflow producing per-holding accruals.
- IFRS 9 JEs posted:
  - Interest income Dr investment / Cr investment income.
  - Dividend income Dr cash / Cr investment income.
  - FVPL fair-value gain/loss Dr/Cr fair value gain/loss.
  - FVOCI debt OCI movements Dr/Cr OCI.
- Idempotency keyed by `(periodId, holdingId, accrual_type)`.

**Tests:**
- Coupon date triggers correct accrual.
- Trial balance balanced after a 50-holding accrual run.
- Re-running idempotent.

**Reconciliation gate evidence:** trial balance attached.

**Exit criteria:** monthly accrual end-to-end runnable; reconciliation gate green.

**Dependencies:** 1.4, 1.5, 1.9, 3.5.

---

### Slice 3.7 — Closure Activities + Reconciliation

**Goal:** stub the three Phase 4 closure activities so Phase 4 can register them later, and run a final cross-module reconciliation pass.

**Deliverables:**

- `RunMtmValuationActivity`, `AccrueInvestmentIncomeActivity`, `RecalculateEclActivity` implementing a placeholder `ClosureActivity` interface (the real interface will be defined in Phase 4 — so this slice declares a `cia-investments` local interface and adapts in Phase 4).
- Cross-module reconciliation test: sample tenant runs Phase 1 sub-ledger postings + Phase 2 PAA measurement + Phase 3 EOM activities → trial balance still balanced; LRC + LIC + ECL roll-forwards tie to GL totals.

**Tests:** the cross-module reconciliation test described above runs in CI on every PR via the existing reconciliation workflow.

**Reconciliation gate evidence:** the cross-module test is the evidence; Slice 1.9 gate covers it.

**Exit criteria:** Phases 1, 2, 3 reconcile end-to-end; foundations are ready for Phase 4 wiring.

**Dependencies:** 2.6, 3.6.

## 7. Replan Checkpoints

The plan above is a forecast. The following checkpoints exist explicitly to revise it before the next phase starts:

- **End of Week 4 (after Phase 1.1–1.4 merged):** confirm the JE contract is stable. If any consumer slice (1.5, 2.6, 3.5, 3.6) needs fields added, do it now while only one consumer exists. Risk: a JE contract change after Slice 1.5 lands forces 1.5 + 2.6 + 3.5 + 3.6 reworks.
- **End of Week 7 (Phase 1 complete):** re-estimate Phase 2 + Phase 3 calendar based on actual Phase 1 velocity. Decide whether to continue the parallel-engineer plan or serialise.
- **End of Week 13 (Phases 1+2+3 complete):** update `period-end-closures-implementation-plan.md` Phases 4–7 to PR-slice granularity using the same template. The Phase 4 slice plan informs the orchestration agent dispatch model.

## 8. Open Implementation Decisions

These are deliberate design questions deferred to the slice that needs them rather than pre-decided in this plan:

- **Slice 1.4:** the JE narrative template language (Mustache vs StringFormat). Deferred to slice author; reviewer must approve the choice before the controller endpoint signs off.
- **Slice 1.7:** whether to use Hibernate `Interceptor` (legacy API) or the new `StatementInspector` route. Benchmark in 1.7 includes both paths; the faster one wins.
- **Slice 1.8:** whether the backfill is invokable per-tenant only or platform-wide. Default per-tenant; platform-wide gated by an explicit `--all-tenants` flag with extra confirmation.
- **Slice 2.4:** the bootstrap window for the claims development triangle (3 vs 5 vs 7 years). Default 5 years; configurable per portfolio after launch.
- **Slice 3.5:** the credit-rating-band granularity (5 bands vs 10). Default 5 (AAA, AA, A, BBB, sub-investment); upgradeable later via a Flyway data migration.

## 9. Reconciliation Gate Evidence — What "Green" Means On A PR

Every PR in this plan that touches the GL or any reservable balance must include in its description:

```
## Reconciliation evidence

- Trial balance net: 0.00 (≤ 0.01 tolerance) — see workflow run [link]
- LRC roll-forward (Phase 2 slices only): opening + new business − revenue − amortisation == closing — see test [link]
- LIC roll-forward (Phase 2 slices only): opening + claims notified − claims paid + RA unwind == closing — see test [link]
- ECL provision (Phase 3 slices only): closing == opening + period change — see test [link]
- IFRS 9 income (Phase 3 slices only): GL total == sum(income_accrual) — see test [link]
```

Reviewers reject the PR if any line is missing or red. The integration branch never accepts a PR with an unbalanced trial balance.

## Related Documents

- `period-end-closures-design.md` — full technical design
- `period-end-closures-implementation-plan.md` — phase-level plan; this document expands Phases 1–3 of that plan
- `production-readiness-tracker.md` — adjacent gates (Temporal worker pattern, PII handling) that the foundations inherit
- `database-migration-runbook.md` — Flyway migration deployment procedure
