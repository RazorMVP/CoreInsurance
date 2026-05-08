---
id: period-end-closures-implementation-plan
title: Period-End Closures — Implementation Plan
sidebar_label: Period-End Closures (Implementation Plan)
---

# Period-End Closures — Implementation Plan

Plan date: 2026-05-09

Status: Draft for review

Branch: `main`

This plan operationalises the design specified in `period-end-closures-design.md`. It breaks the build into seven phases, defines per-phase deliverables and exit criteria, and identifies the critical path. Phases are sized for a 2–3 engineer team working in parallel where possible.

## 1. Phasing Strategy

The work splits naturally along three independent foundations and four orchestration layers:

```
Foundations  │  Phase 1  GL foundation (chart of accounts, journal entries, period locks)
             │  Phase 2  IFRS 17 PAA measurement service
             │  Phase 3  cia-investments + IFRS 9 measurement
             │
Orchestration│  Phase 4  Closure activity registry + Temporal workflows
             │  Phase 5  NAICOM submission pack generators
             │  Phase 6  Frontend admin UI + investments UI
             │  Phase 7  Soft → hard transitions + reopening + cross-tenant platform view
             │
Continuous   │  Tests, observability, documentation, performance, security review
```

Phases 1–3 are independent and can run in parallel by separate engineers. Phase 4 requires Phase 1 (GL foundation) and at least the data-model side of Phase 2. Phases 5–7 require Phase 4. Detailed dependency graph in Section 9.

## 2. Phase 1 — GL Foundation

**Duration: 4–6 weeks** (single engineer)

**Goal:** add chart of accounts, journal entries, fiscal year configuration, and period locking to `cia-finance`. No business behaviour changes — purely additive infrastructure that future phases post into.

**Deliverables:**

- Flyway migrations for `chart_of_account`, `journal_entry`, `journal_entry_line`, `fiscal_year`, `fiscal_period`, `posting_rule`, `period_lock`
- Seed migration: default Nigerian insurance chart of accounts (assets, liabilities, equity, income, expense classes plus IFRS 17 / IFRS 9 role-tagged accounts)
- `ChartOfAccountService`, `JournalEntryService`, `TrialBalanceService`, `FiscalYearService` with full unit + integration test coverage
- `SubledgerPostingService` listening for `DebitNoteApprovedEvent`, `CreditNoteIssuedEvent`, `ReceiptPostedEvent`, `PaymentPostedEvent` → posts JEs against the COA
- `PeriodLockService` Hibernate interceptor enforcing soft/hard period locks with the 5-business-day cutoff
- Tenant fiscal year admin UI under Setup → Fiscal Year (single page, default Dec 31, configurable)
- Backfill migration: retroactive JEs for in-force policies' premium recognition

**Exit criteria:**

- All sub-ledger events (DN/CN/Receipt/Payment) post correctly to the GL
- Trial balance produces the expected sum-of-zero across debits and credits
- Backdated transactions outside the 5-day cutoff are rejected
- Backfill produces a clean trial balance for an existing tenant
- No regression in existing finance flows (verified by full backend regression)

**Engineering watch-outs:**

- The Hibernate interceptor for period locking must use `EmptyInterceptor` or `Hibernate6Interceptor` semantics — touching every persistent entity. Test under load to ensure no throughput cliff.
- The retro-active JE backfill is a one-time per-tenant migration. Make it idempotent and rerunnable in case of partial failure.
- COA seeds must include IFRS 17 disclosure-roll-forward accounts upfront (LRC opening / movements / closing; LIC opening / movements / closing) — easier to seed once than to migrate later.

## 3. Phase 2 — IFRS 17 PAA Measurement

**Duration: 4–6 weeks** (single engineer; can run parallel to Phase 1 if data-model work decoupled)

**Goal:** implement IFRS 17 PAA measurement under `cia-finance/ifrs17/`. Produces LRC, LIC, risk adjustment, onerous test results, and IFRS 17-tagged journal entries.

**Deliverables:**

- Flyway migration: `insurance_portfolio`, `contract_group`, `lrc_balance`, `lic_balance`, `onerous_test_result`
- Seeded portfolios mapped 1:1 from `products` (one portfolio per product family initially)
- `PaaMeasurementService`, `LrcCalculationService`, `LicCalculationService`, `RiskAdjustmentService` (75th-percentile confidence-level), `OnerousContractTestService`
- Bootstrap historical claims development triangles per portfolio for risk adjustment seeding
- `ReinsuranceContractsHeldService` mirroring PAA measurement to the ceded side
- Per-policy contract-group assignment logic (cohort_year = policy.start_date.year; portfolio = product.portfolio_id; onerousness = current test result)
- LRC and LIC roll-forward report generators (used by EOM disclosure pack)
- IFRS 17 journal entries: insurance revenue, insurance service expense, reserve movements, risk adjustment unwinding, onerous deficit recognition

**Exit criteria:**

- LRC closing balance matches: opening LRC − insurance revenue + new business written + acquisition cost amortisation
- LIC closing balance matches: opening LIC + new claims notified − claims paid + risk adjustment unwind
- Onerous test produces zero false positives on a known-profitable portfolio sample
- Reinsurance ceded LRC/LIC mirrors gross movements at the cession percentage

**Engineering watch-outs:**

- Initial risk adjustment estimates from claims triangles will be rough. Production calibration may need an actuarial review later — flag in code as `// TODO: actuarial calibration`.
- Cohort assignment for contracts spanning a year-end (rare for general business) needs special handling — a Q4 policy with effective date Dec 28 of year N issuing late could land in cohort N or N+1 depending on initial recognition date. Default to start-date year; flag edge cases.

## 4. Phase 3 — cia-investments + IFRS 9 Measurement

**Duration: 4–5 weeks** (single engineer; fully independent of Phases 1 and 2 until integration)

**Goal:** new `cia-investments` Maven module covering instrument master data, holdings, IFRS 9 classification, valuation, ECL, and income accrual.

**Deliverables:**

- New Maven module with full domain / service / controller layers
- Flyway migrations: `instrument`, `holding`, `valuation`, `income_accrual`, `ecl_provision`
- `Ifrs9ClassificationService` with SPPI test logic + classification routing
- `EclService` with 12-month / lifetime / credit-impaired stages
- `ValuationService` for monthly mark-to-market (manual entry initially; market-data integration is a future phase)
- `InvestmentIncomeService` for interest accrual (per-instrument coupon schedule) and dividend accrual
- IFRS 9 journal entries: investment income, fair value gains/losses, ECL expense, FVOCI OCI movements
- Frontend pages under Setup or as a top-level Investments module
- Activity registration: `RUN_MTM_VALUATION` (EOM), `ACCRUE_INVESTMENT_INCOME` (EOM), `RECALCULATE_ECL` (EOM)

**Exit criteria:**

- Instrument master data CRUD works
- A new holding correctly auto-classifies under IFRS 9 based on its business model + SPPI test result
- A monthly MTM run produces valuations for all active holdings
- ECL computation for a sample portfolio produces sensible 12-month vs lifetime ECL values
- All IFRS 9 JEs post correctly to the GL

**Engineering watch-outs:**

- Market-data integration is **out of scope** for phase 1 — manual valuation entry only. Don't accidentally build a market-data adapter without explicit scope expansion.
- ECL methodology for this phase uses simple credit-rating bands. Sophisticated PD/LGD modelling is a future phase.
- The investments module should be deployable independently — a new tenant could in principle skip the investments module entirely if they don't hold investments. Build with tenant-scoped enable/disable.

## 5. Phase 4 — Closure Orchestration + Activity Registry

**Duration: 3–4 weeks** (single engineer; depends on Phase 1 complete + Phase 2 data model done)

**Goal:** new `cia-closure` Maven module containing the activity registry, closure state machine, and Temporal workflows for all five closure types.

**Deliverables:**

- New Maven module with domain / service / workflow / controller layers
- Flyway migrations: `closure_activity`, `tenant_closure_activity_override` (public schema); `closure_period`, `closure_run`, `closure_activity_execution`, `period_lock` extensions, `closure_approval` (tenant schema)
- Seed migration: register all built-in activities (one row per activity in the design's Q6 menu)
- `ClosureActivity` interface that business modules implement
- `ClosureOrchestrationService.startClosure(...)` entry point
- Temporal workflows: `EodWorkflow`, `EomWorkflow`, `EoqWorkflow`, `HalfYearWorkflow`, `EoyWorkflow`
- Real-time progress streaming via `ClosureProgressService` (Server-Sent Events or WebSockets — choice deferred to UI implementation)
- Approval workflow integration (single-level Finance Manager for soft close; reuses existing `cia-workflow` patterns)
- Refactoring task: each business module's existing batch operations become `ClosureActivity` implementations registered in the registry

**Exit criteria:**

- Admin can click "Run EOD" from the back-office UI and see real-time progress
- All EOD activities listed in the design execute and write to `closure_activity_execution`
- A failed activity triggers Temporal retry behaviour without re-running the successful activities
- EOM transitions the period to SOFT_CLOSED with all financial activities posting their journal entries
- Period lock interceptor blocks backdated entries beyond the 5-day cutoff after soft close

**Engineering watch-outs:**

- The Temporal worker manager pattern from `production-readiness-tracker.md` Phase 7 must be respected — `cia-closure` registers its workers via `@PostConstruct` beans, not by calling `factory.start()` directly.
- Activity idempotency is critical. A failed closure run should be safely retryable. Design `ClosureActivity.execute(...)` so that running it twice with the same context produces the same outcome.
- The activity registry must support tenant-level disable. A tenant that doesn't hold investments should be able to disable the IFRS 9 activities without affecting other tenants.

## 6. Phase 5 — NAICOM Submission Pack Generators

**Duration: 4–6 weeks** (single engineer; depends on Phases 1, 2, 4)

**Goal:** generate the four NAICOM submission packs as part of EOM and EOQ closures.

**Deliverables:**

- `MonthlyRecapitalisationGenerator` (EOM activity)
- `QuarterlyManagementAccountGenerator` (EOQ activity)
- `QuarterlyAlmGenerator` (EOQ activity)
- `AnnualReturnsGenerator` (EOY activity, called from `EoyWorkflow`)
- Document templates per NAICOM filing format (PDF + Excel where applicable)
- `naicom_submission` table tracking generation, submission, acceptance lifecycle
- Frontend page under Reports → NAICOM Submissions: list of generated packs, download, mark-as-submitted, mark-as-accepted (acceptance triggers the soft → hard close transition)

**Exit criteria:**

- All four submission types generate correctly from sample tenant data
- The submitted file shape matches NAICOM's expected format (verified against actual templates if available, or stubbed for review)
- Marking a submission as ACCEPTED triggers the related soft-closed period to transition to HARD_CLOSED

**Engineering watch-outs:**

- The actual NAICOM filing formats are a known-unknown. The design assumes XBRL-or-Excel for monthly/quarterly and PDF for annual. Confirm with the regulatory authority before locking format.
- Auto-submit to NAICOM via API is **out of scope for Phase 5** — generate-and-download only. API integration is a future phase pending NAICOM provider work (per Phase 6 of the production-readiness tracker).

## 7. Phase 6 — Frontend Admin UI + Investments UI

**Duration: 4–5 weeks** (one frontend engineer; can start when Phase 4 controllers are stable)

**Goal:** ship the admin-facing UI for closures and investments.

**Deliverables:**

- New back-office module `apps/back-office/src/modules/closures/`:
  - `ClosuresHomePage` with period summary cards
  - `RunClosurePage` with confirmation + real-time progress UI
  - `ClosureDetailPage` showing executed activities, JEs posted, regulatory submissions
  - `ClosureApprovalsPage` for pending approvals
  - `ClosureConfigurationPage` for tenant configuration (fiscal year, schedule, activity overrides)
- New back-office module `apps/back-office/src/modules/investments/`:
  - `InvestmentsHomePage` with holdings dashboard
  - `InstrumentsPage` for instrument master CRUD
  - `HoldingDetailPage` with valuation history, accrual history, ECL stage timeline
- Sidebar navigation: Closures + Investments as new top-level entries
- React Query hooks following the project's `useGet` / `useList` / `useCreate` / `useUpdate` patterns
- Real-time progress UI: subscribe to `ClosureProgressService` SSE stream, show per-activity status with rolling progress bar
- Server-side rendering of CSV/PDF exports for trial balance, IFRS 17 disclosures, IFRS 9 disclosures

**Exit criteria:**

- Frontend typecheck passes for both back-office and partner workspaces
- All pages load, render real backend data, and have proper loading/empty/error states
- Playwright smoke tests cover the critical paths (Run EOD, Run EOM, View Closure Detail, Investments Dashboard)
- The `bash cia-frontend/scripts/check-api-wiring.sh` CI guard passes — no `console.log`, no top-level mock data, no leftover `// TODO: useMutation` comments

**Engineering watch-outs:**

- Real-time progress streaming requires authentication on the SSE endpoint — Keycloak JWT must be carried through the connection setup. WebSocket may be a simpler implementation if SSE auth proves messy.
- The investments UI is genuinely new territory; lean on the design system patterns from existing modules (DataTable + Sheet form) rather than inventing.

## 8. Phase 7 — Finality Transitions + Cross-Tenant Platform View

**Duration: 2–3 weeks** (single engineer; depends on Phases 4, 5, 6)

**Goal:** complete the soft → hard close transition logic, the reopen flow, and the cross-tenant platform admin view.

**Deliverables:**

- `SoftToHardTransitionWorkflow` Temporal workflow
- Reopen flow: CFO authorises with reason → period transitions to REOPENED → audit trail entry → period_lock released
- `PlatformClosureViewService` cross-tenant query path (read-only iteration over `public.tenants` plus per-tenant aggregation)
- Platform admin frontend page `apps/back-office/src/modules/platform/closures/PlatformClosureOverviewPage` (or a separate platform admin app — consult with team)
- Force-close from platform admin → calls `ClosureOrchestrationService.startClosure(...)` with `trigger_source = FORCE_PLATFORM`
- NAICOM deadline countdown banners on the platform admin page (10 working days post-EOM, 30 days post-EOQ, etc.)

**Exit criteria:**

- A soft-closed period transitions to hard-closed automatically after the grace window expires (test with shortened windows)
- A soft-closed period transitions to hard-closed immediately when its corresponding NAICOM submission is marked ACCEPTED
- A reopened period blocks further transitions until reclosed
- Platform admin can see all tenants' closure state at a glance; force-close emits the correct audit trail

**Engineering watch-outs:**

- The cross-tenant query path is the only place the codebase intentionally crosses tenant schemas. Treat it as a privileged operation with extensive logging.
- Force-close should never run silently. Both the originating tenant and the platform admin should receive notifications.

## 9. Critical Path And Dependencies

```
[ Phase 1 ─── GL ─────────────────┐
[ Phase 2 ─── IFRS 17 ────────────┤  ┌─ Phase 4 ─── Orchestration ─┐
[ Phase 3 ─── Investments ──┘     │  │                             │
                                  │  ├─ Phase 5 ─── NAICOM packs ──┤
                                  │  │                             ├─ Phase 7 ─── Finality + Platform
                                  └──┤                             │
                                     ├─ Phase 6 ─── Frontend ──────┘
                                     │
```

**Critical path: Phase 1 → Phase 4 → Phase 5 → Phase 7.** All other paths flow into this trunk.

**Maximum parallelism:**
- Engineer A: Phase 1 (GL)
- Engineer B: Phase 2 (IFRS 17 PAA measurement) — can begin once Phase 1 schema is finalised
- Engineer C: Phase 3 (Investments + IFRS 9) — fully independent

When Phase 1 + 2 are done:
- Engineer A: Phase 4 (Orchestration)
- Engineer B: Phase 5 (NAICOM packs) — starts when Phase 4 has stable activity-registration plumbing
- Engineer C: Phase 6 (Frontend) — starts when Phase 4 has stable controllers

When Phases 4, 5, 6 are done:
- All engineers: Phase 7 (Finality + Platform view) — small enough to be a coordinated sprint

**Calendar-time estimate with 3 engineers parallelised: 16–20 weeks** (4–5 months).

**Calendar-time estimate with 1 engineer sequential: 30–40 weeks** (7–10 months).

## 10. Risks And Mitigations

| Risk | Impact | Likelihood | Mitigation |
| --- | --- | --- | --- |
| NAICOM submission templates are unavailable | High — Phase 5 cannot complete | High | Identify regulatory authority owner early in project; in parallel, develop a generalised template engine that can be reconfigured when actual templates arrive |
| Period lock Hibernate interceptor causes throughput regression | High — slows every transactional write | Medium | Benchmark before phase 1 lands; if regression detected, switch to advisory locks or delegate to a service-layer check |
| IFRS 17 risk adjustment computation requires actuarial signoff | Medium — Phase 2 may not be production-ready without external review | Medium | Build with bootstrap calibration; flag explicitly that production rollout requires actuarial review; treat as a "phase 1 acceptable, phase 2 must refine" gate |
| Backfill of retroactive JEs takes many hours per tenant | Medium — disruptive deploy window | Medium | Make backfill idempotent and incremental; run as a Temporal workflow with progress reporting |
| Activity idempotency violations on retried failures | Medium — duplicate JEs or duplicate notifications | Medium | Each `ClosureActivity` implementation must declare `isIdempotent()`; non-idempotent activities skip retry; document the contract clearly |
| Tenant fiscal year discovery is wrong on backfill | Low — admin has to manually correct after deployment | Medium | Backfill applies a default of Dec 31 with a clear notification to tenant admin to confirm |
| Investment classification logic produces unexpected FVPL defaults | Low — minor accounting noise until corrected | Low | Provide bulk reclassification UI in Phase 6; treat initial classification as advisory |
| Cross-tenant queries leak data between tenants | High — regulatory issue | Low | Phase 7 cross-tenant query is read-only and aggregates only counts; data values stay tenant-scoped |
| Real-time progress streaming requires extra infrastructure | Low — frontend complexity | Low | Phase 6 can ship with polling-based progress UI initially; SSE/WebSocket is an enhancement |

## 11. Continuous Concerns Across All Phases

These are not phase-bound but apply throughout:

- **Testing:** integration tests with Testcontainers PostgreSQL; minimum 80% line coverage on `cia-finance/ifrs17`, `cia-investments`, `cia-closure`. End-to-end smoke tests in Playwright.
- **Audit trail:** every closure run, every period lock change, every reopen, every force-close gets logged via `cia-audit`. PII fields must be redacted per Phase 8 standards from the production-readiness tracker.
- **Observability:** all closure activities emit OpenTelemetry spans; Prometheus metrics for closure duration, activity count, failure rate per tenant; Grafana dashboard updated in `ops/observability/`.
- **Documentation:** every endpoint added must update `docs-site/static/internal-api.json`; every new module must update `docs-site/docs/architecture/modules.md`; phase completion gets a section in `cia-log.md`.
- **CI gates:** `bash cia-frontend/scripts/check-api-wiring.sh` must pass on every frontend PR; `mvn verify` must pass on every backend PR.

## 12. Sequencing Recommendation

For maximum parallel team utilisation:

1. **Sprint 0 (1 week):** finalise data model details across phases 1–3, make Phase 1's schema canonical so Phases 2 + 3 can plan against it.
2. **Sprints 1–6 (6 weeks):** Phases 1, 2, 3 in parallel.
3. **Sprints 7–10 (4 weeks):** Phase 4 (Engineer A) + Phase 5 starts when Phase 4 plumbing stable (Engineer B).
4. **Sprints 11–14 (4 weeks):** Phase 6 (Engineer C) + Phase 5 completes (Engineer B).
5. **Sprints 15–16 (2 weeks):** Phase 7 (all engineers).
6. **Sprint 17 (1 week):** acceptance, regression, deployment readiness.

**Total: ~17 sprints / 17 weeks calendar** with three engineers fully parallelised. Add 4–6 weeks for inevitable scope discovery, dependency surprises, and review iterations. **Realistic shipping window: 5 months.**

## Related Documents

- `period-end-closures-design.md` — full technical design that this plan implements
- `production-readiness-tracker.md` — adjacent gates (Phase 7 Temporal workers, Phase 8 PII handling, Phase 10 deployment) that this plan inherits
- `database-migration-runbook.md` — established procedure for Flyway migrations during deployment
