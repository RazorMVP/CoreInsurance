---
id: period-end-closures-implementation-plan
title: Period-End Closures — Implementation Plan
sidebar_label: Period-End Closures (Implementation Plan)
---

# Period-End Closures — Implementation Plan

**Original draft:** 2026-05-09 (Status: Draft for review).
**Reconciled to shipped reality:** 2026-05-21. **Phases 1–5 are complete and merged to `main`.** Phase 1 + Phase 2 + Phase 3 merged via `fe904f3`; Phase 4 (all 10 slices) merged via `50e5b11`; Slice 1.10 (GL-substrate enrichment — `class_of_business_id` promoted onto `journal_entry_line` + N01 over GL with reconciliation assertion) merged via `fd795f6`; Phase 5 (Module 12 back-office frontend, 16 slices F5.1–F5.16) shipped 2026-05-21 across commits `3d9e932..b12c052` plus closeout fixes in `e56847b`. Phase 6 (cross-tenant platform admin view) remains.

**Phase numbering reconciled.** The original May-9 plan defined seven phases, with NAICOM submissions as "Phase 5" and a generic "Closure Orchestration" layer as "Phase 4." The team chose **per-domain orchestrators** (PaaPeriodCloseService for IFRS 17, NaicomSubmissionService for NAICOM) over a generic activity registry, so the original Phase 4 was deferred and the remaining phases shifted down by one. Current numbering:

| Original plan | Current numbering | Status |
|---|---|---|
| §2 Phase 1 — GL Foundation | **Phase 1** | Shipped (12 slices + Slice 1.10a/b GL-substrate enrichment) |
| §3 Phase 2 — IFRS 17 PAA Measurement | **Phase 2** | Shipped (8 slices) |
| §4 Phase 3 — cia-investments + IFRS 9 | **Phase 3** | Shipped (7 slices) — lives in `cia-finance/ifrs9/`, not a separate module |
| §5 Phase 4 — Closure Orchestration + Activity Registry | **Deferred** (per-domain orchestration chosen instead) | Not built; see "Deferred work" section below |
| §6 Phase 5 — NAICOM Submission Pack Generators | **Phase 4** | **Shipped (all 10 slices)** |
| §7 Phase 6 — Frontend Admin UI + Investments UI | **Phase 5** | **Shipped (16 slices, F5.1–F5.16)** |
| §8 Phase 7 — Finality Transitions + Cross-Tenant Platform View | **Phase 6** | Partial — per-period finality absorbed into Slice 1.7c (PPA workflow + period reopen + tenant_holiday calendar); cross-tenant platform view still not built |

For slice-level detail on Phases 1–3 see `cia-log.md` sessions 60–72. For Phase 4 + Slice 1.10 see the Phase 4 + Slice 1.10 session entries in `cia-log.md`.

---

## 1. Phasing Strategy

The work splits along three independent foundations and three orchestration layers:

```
Foundations  │  Phase 1  GL foundation (chart of accounts, journal entries, period locks)                    │  Phase 2  IFRS 17 PAA measurement service                                                      │  Phase 3  IFRS 9 measurement (in cia-finance, no separate module)                              │
Orchestration│  Phase 4  NAICOM submission pack generators (formerly plan-§6 Phase 5)                        │  Phase 5  Frontend admin UI for Module 12                                                      │  Phase 6  Cross-tenant platform admin view + force-close                                       │
Continuous   │  Tests, observability, documentation, performance, security review
```

Phases 1–5 are complete. Slice 1.10 closed the original Phase 1 ↔ Phase 4 substrate gap (`class_of_business_id` on JE lines, enabling N01 to read directly from GL with reconciliation against `TrialBalanceService`). Phase 5 ships the Module 12 back-office frontend in full (16/16 slices across periods, COA, posting rules, JEs, trial balance, backfill, PAA close, §103 movement, contract groups, holdings, IFRS 9 measurement, §B5.5.39 movement, NAICOM submissions, NAICOM artifacts). Phase 6 (Platform) depends on Phases 4–5 — still the only outstanding workstream.

---

## 2. Phase 1 — GL Foundation

**Status:** Shipped 2026-05-15 (Slice 1.7) through 2026-05-18 (Slice 1.9b). 12 slices total. Reconciled into `main` 2026-05-19 (merge commit `fe904f3`).

**Goal achieved:** chart of accounts, journal entries, fiscal year configuration, period locking, and retroactive backfill — purely additive infrastructure that Phases 2 and 4 post into.

**What shipped:**

| Slice | Scope |
|---|---|
| 1.1 | V31 GL schema (`fiscal_year`, `fiscal_period`, `period_lock`, `journal_entry`, `journal_entry_line`, `posting_rule`, `chart_of_account`) |
| 1.2 | V32 chart-of-account seed — 129 rows in a 3-level hierarchy, **including IFRS-17 and IFRS-9 role-tagged accounts upfront** (LRC/LIC, AmortisedCost/FVOCI/FVPL, ECL allowances). Engines look up by role enum, not hardcoded codes. |
| 1.3 | `ChartOfAccountService` (read-only) |
| 1.4 | `JournalEntryService` + `TrialBalanceService` — the **JE-posting gateway**. Every engine posts through this service. Idempotency triple: `(source_module, source_event_type, source_reference)` |
| 1.5 | `SubledgerPostingService` event listeners — `PolicyApprovedEvent`, `EndorsementApprovedEvent`, `ClaimApprovedEvent`, `ClaimSettledEvent`, `FacPremiumCededEvent`, `ClaimExpenseApprovedEvent` → JE post |
| 1.6 | `FiscalYearService` + period generation (12 MONTH + 4 QUARTER + 2 HALF_YEAR + 1 YEAR = 19 rows, all eager at FY-create) |
| 1.7 | `PeriodLockService` + Hibernate `PeriodLockInterceptor`. 5-business-day grace window. Opt-in via the `LockableByPeriod` marker interface (NOT a config table — the marker IS the opt-in mechanism). HTTP 423 LOCKED with structured `meta`. `period_lock` table is a Type-2 SCD; the row sequence IS the audit history. |
| 1.7a/b | Period-lock entity opt-in sweep — `Receipt`, `Payment`, `ClaimExpense`, `Endorsement`, `DebitNote`, `CreditNote`, `RiAllocation`, `RiFacCover` all implement `LockableByPeriod`. `getLockDate()` returns the **booking date**, not the business-effective date. |
| 1.7c | V35 IAS-8 PPA workflow (prior-period adjustment for accepted-late-correction scenarios) + `tenant_reopen_recipient` config (CSV fallback) + `tenant_holiday` calendar (makes `addBusinessDays` NAICOM-aware) |
| 1.8a/b | `RetroactiveJournalBackfillWorkflow` (Temporal) — per-tenant admin endpoint, status polling, Spring Boot CLI, idempotent activities, 10k-event wall-clock benchmark, abort-and-resume durability test, operational runbook |
| 1.9a/b | Reconciliation Gate Harness — 200-event fixture in CI (`module-12-reconciliation.yml`), per-JE evidence snapshot, mutation-guard test that detects silent posting-rule regressions |

**Plus T1 follow-up** (Slice T1, merged separately at `8797c59`): 6 upstream contract tests asserting each of the consumed events publishes with the correct payload — protects the producer side of the JE-gateway contract.

**Plus 1.10 scoped follow-up** (queued in cia-log.md): promote `class_of_business` into `journal_entry_line.dimension_tags` so Phase 4's N01 engine can read class-broken-down totals from the GL instead of source tables. Recommended execution: after Phase 4 merges.

**Design decisions captured (lessons from the original watch-outs):**

- **Period-lock Hibernate interceptor performance** — concern from the original plan. Resolved by the `LockableByPeriod` marker-interface pattern: the interceptor checks `instanceof LockableByPeriod` per save, which is a single bytecode test, not a per-entity introspection. No measurable throughput cliff. Two distinct override roles (`FINANCE_OVERRIDE_LOCK` for soft-close grace bypass; `FINANCE_REOPEN_PERIOD` for HARD release) prevent the segregation-of-duties audit finding that would result from bundling.
- **Retroactive JE backfill** — implemented as a Temporal workflow per the original guidance. Idempotent via the JE-gateway uniqueness triple. Resumable from any abort point. CLI plus admin-endpoint plus status-poll endpoint per the operational runbook.
- **COA seeding upfront for IFRS 17 / IFRS 9 disclosure-roll-forward accounts** — done in Slice 1.2. Phases 2 and 3 didn't add a single new account; everything was already there. Justified the upfront seeding cost.
- **`SubledgerPostingService` event surface differs from the original plan** — the plan listed `DebitNoteApprovedEvent`, `CreditNoteIssuedEvent`, `ReceiptPostedEvent`, `PaymentPostedEvent`. In practice the upstream events were policy-/endorsement-/claim-level (the financial-document events sit downstream of those). The actual surface is the six events listed in slice 1.5. Slice T1 contract-tests that surface.
- **`FiscalPeriodLookupCache` scope evolved** — originally `@RequestScope`. Refactored in Slice 1.7-fix into a scope-aware singleton (HTTP requests store the cache map as a `SCOPE_REQUEST` attribute; non-HTTP callers fall back to a ThreadLocal cleared at activity boundaries). This was the blocker for Slice 1.8 retroactive backfill running off the HTTP path.

**Test coverage at phase-end:** 31 ITs (~7 in cia-api/finance/gl + retroactive backfill IT + 200-event reconciliation gate) plus unit tests for math/routing helpers.

---

## 3. Phase 2 — IFRS 17 PAA Measurement

**Status:** Shipped 2026-05-19 (Slices 2.1–2.8). 8 slices total. Reconciled into `main` 2026-05-19.

**Goal achieved:** IFRS 17 Premium Allocation Approach measurement under `cia-finance/paa/`. LRC, LIC, discount unwind, onerous-test results, and IFRS 17-tagged journal entries. §103 movement-analysis disclosure view.

**What shipped:**

| Slice | Scope |
|---|---|
| 2.1 | V36 PAA foundation — `portfolio`, `group_of_contracts`, `paa_lrc`, `paa_lic`, `paa_config`. 5 entities, 4 enums, 5 repos. FK promotion on `journal_entry_line.portfolio_id` and `.contract_group_id` from V31 placeholders. |
| 2.2 | V37 `policy_group_assignment` — **full UNIQUE(policy_id)** for IFRS 17 §22 permanent grouping. `ContractGroupingService` event listener (`@EventListener(PolicyApprovedEvent)`); lazy portfolio creation by COB; group assignment at policy approval. *(Later generalised to the polymorphic `contract_group_assignment` — `UNIQUE(contract_type, contract_id)` — by `fac-ifrs17-paa-workstream` V77, adding `onFacInwardAccepted`/`onFacPremiumCeded` listeners so inward + outward FAC ride the same PAA rails via `portfolio.contract_nature` V76.)* |
| 2.3 | `LrcEngine` — straight-line daily premium recognition. Posts `Dr 2110 / Cr 4110` via the JE gateway. Stateless period computation; idempotent re-runs. |
| 2.4 | `LicEngine` — claim roll-forward via SQL conditional-sum. v1 posts NO JE because the underlying GL is already correct via `SubledgerPostingService` (1.5). |
| 2.5 | `PaaPeriodCloseService` orchestrator + IFRS 17 §83/§84 `InsuranceServiceResult`. **First production-code surface of the `entityManager.flush()` architectural rule** — service writes via JPA then reads via JdbcTemplate within the same `@Transactional` boundary; explicit flush required between writes and reads. |
| 2.6 | `DiscountUnwindEngine` — §87-92 P&L vs OCI routing per `paa_config.oci_election` (§88(b) election). Posts `Dr 5520 / Cr 2140` (P&L) or `Dr 3430 / Cr 2140` (OCI). |
| 2.7 | `OnerousContractTestEngine` — §47-49 loss component. Cumulative-state target reconciliation; delta-based JE. Posts `Dr 5150 / Cr 2130` (recognise) or reverse. |
| 2.8 | V38 `paa_movement_analysis` SQL view + `MovementAnalysisService` for §103 disclosure. Read-rarely view (not materialised); Phase 4's Ifrs17DisclosureEngine reads this directly. |

**Design decisions captured:**

- **§22 permanence via FULL UNIQUE.** The plan suggested "per-policy contract-group assignment logic"; the schema enforces it. `policy_group_assignment.policy_id` has a full UNIQUE (not partial) — group reassignment is a §22 violation by design. Audit corrections must UPDATE in place. **(Superseded by `fac-ifrs17-paa-workstream` V77: the table is now the polymorphic `contract_group_assignment` with `UNIQUE(contract_type, contract_id)` — same §22 permanence, now covering FAC contracts as well as policies.)**
- **Risk adjustment + IBNR deferred to v2** — the original watch-out flagged the need for actuarial review. Decision: ship the `paa_lic` columns (`ibnr_estimate`, `ibnr_change`, `risk_adjustment`, `risk_adjustment_change`) ready, but engines fill them with zero in v1. Slice 2.7b is the placeholder for actuarial-method swaps.
- **Stateless period computation beats opening = previous-closing chaining.** Every Phase 2 engine computes target state from policy/claim data + period boundaries, never reads prior `paa_*` rows. Idempotency is natural; out-of-order processing is harmless; re-runs are bit-identical.
- **`paa_lrc.closing_balance` is point-in-time, not arithmetic-derived.** Surprised by this during Slice 2.7 — closing IS NOT `opening + received − earned`; it's computed by a separate `closingAmount()` function. Roll-forward components are independent point-in-time snapshots, not arithmetic-related.

**Test coverage at phase-end:** 53 PAA-related ITs across the 8 slices.

---

## 4. Phase 3 — IFRS 9 Measurement

**Status:** Shipped 2026-05-19 (Slices 3.1–3.7). 7 slices total. Reconciled into `main` 2026-05-19.

**Goal achieved:** IFRS 9 financial-instrument classification, measurement, ECL, and disclosure for investment holdings AND premium-receivable simplified-approach ECL. §B5.5.39 / IFRS 7 §35M disclosure view.

**Significant divergence from the original plan: NO separate `cia-investments` Maven module.** The IFRS 9 work lives in `cia-finance/ifrs9/`. The "investments module should be deployable independently" hypothesis from the original plan turned out to be unnecessary — investments are tightly coupled to the GL substrate and the period-close workflow, so a separate module would have added Maven plumbing without architectural benefit. Tenant-scoped enable/disable of IFRS 9 is still achievable via the `ifrs9_config` singleton.

**What shipped:**

| Slice | Scope |
|---|---|
| 3.1 | V39 IFRS 9 foundation — `investment_holding`, `investment_carrying_value`, `investment_classification_history` (Type-2 SCD for §B4.1.26 reclassifications), `ifrs9_config` (singleton via partial unique index on `singleton_marker`). FK promotion on `journal_entry_line.holding_id`. |
| 3.2 | `InvestmentClassificationService` — pure §4.1 `classify()` + `register()` + `reclassify()`. SPPI test + business-model criteria. Reclassifications produce Type-2 audit-history rows. |
| 3.3 | `AmortisedCostEngine` — §5.4.1 effective interest method. Posts `Dr 1250 / Cr 4210` (interest accrual) and `Dr 1230 / Cr 1250` (coupon receipt). |
| 3.4 | `FairValueEngine` — §5.7 remeasurement with classification-driven routing. FVPL → P&L (4250 gain / 5330 loss); FVOCI_DEBT → OCI reserve 3410; FVOCI_EQUITY → OCI reserve 3420; AC refuses remeasurement. **`closing_fair_value IS NULL` is the idempotency sentinel** — re-runs skip silently. |
| 3.5 | `InvestmentEclEngine` — §5.5 + §5.7.10A. AC ECL reduces asset directly (`Dr 5310 / Cr 1140`). FVOCI_DEBT ECL routes to OCI reserve (`Dr 5310 / Cr 3410`) while carrying value stays at fair value — the §5.7.10A "ECL in OCI" rule. FVPL: no ECL (impairment IS the fair-value movement). |
| 3.6 | `PremiumReceivableEclEngine` — §5.5.15 simplified approach. Admin supplies aging-bucket provision matrix; engine computes `lifetime ECL = Σ(outstanding × rate)` and posts the delta vs cumulative prior allowance. **Provision matrix embedded verbatim in the JE narrative** — the JE table doubles as the §B5.5.36 disclosure substrate (no separate `premium_provision_matrix` history table in v1). |
| 3.7 | V40 `ifrs9_investment_movement_analysis` SQL view + `Ifrs9MovementAnalysisService`. Composes two sections: investments (from V40 view) + premium-receivable ECL (from JE aggregate on account 1340 by `business_date`). |

**Design decisions captured:**

- **`closing_fair_value IS NULL` sentinel pattern** — when a column's nullability already encodes the operation's idempotency state, no helper flag is needed. Generalisable rule from Slice 3.4.
- **§5.7.10A OCI-routing for FVOCI_DEBT ECL** — the subtlest IFRS 9 rule. ECL routes to OCI reserve, NOT to the asset's carrying value (which stays at fair value). Routing matrix in `InvestmentEclEngine.routeJe` mirrors the standard structurally.
- **JE narrative as disclosure substrate** — Slice 3.6. The provision-matrix-in-narrative pattern is reusable for any small disclosure history we don't want to schema-evolve.
- **Disclosure views (V38, V40) feed Phase 4 directly** — Phase 4's IFRS-17 and IFRS-9 disclosure engines read these views, not the underlying tables. Read-rarely, write-once-per-period semantics; not materialised. The view re-evaluation cost is well below the auditor-acceptable threshold.

**Test coverage at phase-end:** 60+ IFRS 9-related ITs across the 7 slices (including migration tests for V39 and V40).

**Market-data integration remains out of scope.** Same as the original plan — manual valuation entry only. Live market-data adapters are a future phase.

---

## 5. Deferred — Closure Orchestration + Activity Registry

**Status:** Deliberately not built. The original Phase 4 (§5 of the May-9 plan) proposed a new `cia-closure` Maven module with a generic `ClosureActivity` interface, an activity registry, and five `Eod/Eom/Eoq/HalfYear/Eoy` Temporal workflows. The team chose **per-domain orchestrators** instead:

- `PaaPeriodCloseService` (Slice 2.5) orchestrates the IFRS 17 close (LRC → LIC → flush → DiscountUnwind → flush → OnerousContractTest → flush → InsuranceServiceResult).
- `RetroactiveJournalBackfillWorkflow` (Slice 1.8a/b) handles the per-tenant backfill use-case originally bundled into the closure workflows.
- `PeriodLockService` (Slice 1.7) owns the soft-close / hard-close / reopen transitions for any individual period.
- The upcoming Phase 4 `SubmissionOrchestrator` (Slice 4.9) will orchestrate NAICOM submission generation.

**Why deferred:** the generic activity-registry pattern adds significant plumbing (`ClosureActivity` interface, registration framework, per-tenant override table, real-time progress streaming infrastructure) without clear payoff at current scale. Per-domain orchestrators are simpler, more testable, and align with each phase's natural transaction boundaries. The trade-off: there is **no single period-level orchestrator** that chains "hard-close → run IFRS 17 close → run IFRS 9 close → run NAICOM submissions → transition state." The admin runs these as separate steps, or a Phase 5 UI ties them together client-side.

**When to revisit:** if the per-domain pattern starts producing scheduling bugs (forgotten steps, wrong ordering, missing audit trail across domains), the generic orchestrator becomes worth the plumbing. Until then it's correctly classified as YAGNI.

---

## 6. Phase 4 — NAICOM Submission Pack Generators

**Status:** **Shipped 2026-05-19.** All 10 slices merged to `main` via merge commit `50e5b11`. 113 NAICOM ITs green on the merge tip; 275 cia-api failsafe ITs green across the full reactor. **Scope expanded from the original plan** — the May-9 plan listed 4 submission types (monthly recap, quarterly management account, quarterly ALM, annual returns); the actual scope covers all 8 NAICOM N-reports (N01–N08) plus 2 IFRS disclosure packs (IFRS-17 §103, IFRS-9 §B5.5.39).

**Goal achieved:** generate every NAICOM submission as a durable, period-bound, regulator-grade artifact tied to a fiscal period. State machine (DRAFT → SUBMITTED → ACKNOWLEDGED → ARCHIVED with RETRACTED branch) enforced by `NaicomSubmissionService`. Render to PDF (auditor canonical, via Apache PDFBox) + CSV (RFC 4180 streaming for NAICOM e-portal ingest) + JSON (canonical machine-readable). Upload pipeline reuses the existing `NaicomService` stub/REST adapter pattern; live API swap deferred to when credentials land.

**Slice ledger (all shipped):**

| Slice | Scope | Commit |
|---|---|---|
| 4.1 | V41 schema (`naicom_submission` + `naicom_submission_artifact` + `naicom_submission_event` Type-2 SCD) + 3 entities + 3 enums + 3 repositories | `b8179b7` |
| 4.2 | `PremiumBordereauxEngine` (N05) + `ClaimsBordereauxEngine` (N06) — register-style engines reading `policies` + `claims` | `f2d5104` |
| 4.3 | `AnnualRevenueAccountEngine` (N01, originally underwriting view; rewritten over GL in Slice 1.10b) + `BalanceSheetEngine` (N02, GL-driven via `TrialBalanceService`) | `f34d6b4` |
| 4.4 | `PrudentialReturnEngine` (N03) — solvency margin from balance-sheet aggregates + period-bounded income | `32fa3d9` |
| 4.5 | `RiQuarterlyReturnEngine` (N04) — ceded premium per treaty + per reinsurer rollup | `517925e` |
| 4.6 | `Ifrs17DisclosureEngine` — service-relay over `MovementAnalysisService` (V38) | `6da6c7d` |
| 4.7 | `Ifrs9DisclosureEngine` (relay over `Ifrs9MovementAnalysisService` / V40) + `InvestmentStatementEngine` (N08, direct-source point-in-time snapshot) | `8b48bda` |
| 4.8 | `NiidStatusSnapshotEngine` (N07) — period-end NIID upload status freeze (NIID, not NAICOM, but shares submission infrastructure) | `202f298` |
| 4.9 | `NaicomSubmissionService` orchestrator + REST controllers (`/api/v1/finance/naicom/submissions`) + state machine + 4 exceptions w/ `@ResponseStatus` + `FINANCE_VIEW`/`FINANCE_APPROVE` RBAC + retrofit of all 10 engines to `NaicomSubmissionEngine` interface | `c913c92` |
| 4.10 | Artifact rendering (JSON via Jackson + CSV via RFC 4180 + PDF via Apache PDFBox) + `SubmissionArtifactService` + storage via `DocumentStorageService` + 3 REST endpoints for render / list / download | `b5184ed` |

**Slice 1.10a/b — GL-substrate enrichment (shipped 2026-05-20, merge `fd795f6`):**

| Slice | Scope | Commit |
|---|---|---|
| 1.10a | V42 (`class_of_business_id` column + partial index on `journal_entry_line`) + V43 backfill across 5 event-type code paths + `PolicyClassResolver` + `SubledgerPostingService` refactor (resolves class per event) + 9-arg back-compat constructor on `JournalEntryLineRequest` + 34 IT flyway-target bumps | `e324367` |
| 1.10b | `AnnualRevenueAccountEngine` re-implementation over GL (SUM(credit_amount) on POLICY_APPROVED / CLAIM_APPROVED JEs, JOIN `classes_of_business` for display) + IT rewrite seeding JEs directly + reconciliation assertion against independent JE aggregate | `7b8c5ad` |

**Architecture invariants this phase established:**

- **Submissions never post JEs.** Read-side aggregates over already-posted ledger state. JE gateway uninvolved.
- **Idempotency triple = `(submission_type, period_id, tenant_id)`.** Partial UNIQUE under `deleted_at IS NULL`. Re-running an engine for an existing DRAFT updates the payload in place; once SUBMITTED, payload is frozen.
- **Period-lock precondition: HARD_CLOSED required.** Enforced in `NaicomSubmissionService` (Slice 4.9), not by DB constraint. The regulator's expectation is that submitted figures don't change post-submission.
- **State history via Type-2 SCD.** `naicom_submission_event` records every transition. Auditors traverse the row sequence to reconstruct the path.
- **Retract / archive soft-delete to vacate the UNIQUE slot.** A retracted submission frees the `(submission_type, period_id)` key for a fresh corrected submission; the original row survives via `deleted_at` for audit.
- **N01 over GL.** Originally Slice 4.3 read source tables because `journal_entry_line` had no `class_of_business_id`. Slice 1.10a + 1.10b closed that gap — N01 now reads class-broken-down totals from `journal_entry_line` aggregates with an explicit reconciliation assertion against an independent JE aggregate.

**Exit criteria — all met:**

- ✓ All 10 submission types generate from sample tenant data with deterministic, replayable payloads (113 ITs cover the engines + orchestrator end-to-end).
- ✓ HARD_CLOSED period precondition correctly rejects DRAFT generation on non-hard-closed periods (`PeriodNotHardClosedException` → HTTP 422).
- ✓ State-machine transitions are atomic and auditable; the event chain reproduces every transition with `from_state`, `to_state`, `actor`, `reason`, `occurred_at`.
- ✓ Marking a submission as ACKNOWLEDGED populates `naicom_uid` (V41 CHECK constraint enforces it NOT NULL once ACKNOWLEDGED).
- ✓ `mvn verify` green: 275 cia-api failsafe ITs, 0 failures, 0 errors, 1 intentional benchmark skip.

**Deferred to v2 (documented in each engine's javadoc and in the Slice 4.10 commit body):**

- **Live NAICOM API swap** — Slice 4.10 ships against `StubNaicomSubmissionService`. Live `NaicomRestService` swap when credentials + API spec arrive. Same Spring-profile pattern as the existing per-policy `NaicomService`.
- **Per-submission-type purpose-built CSV / PDF templates** — v1 ships generic layouts (flattened scalars + section-per-list for CSV; cover page + paginated JSON body for PDF). NAICOM-prescribed forms can be implemented per submission type when the regulator publishes them.
- **PDF Naira-sign + em-dash glyph coverage** — `PdfArtifactRenderer` strips chars outside WinAnsi (the standard14 fonts cover Latin-1 only). v2 should embed a TTF that covers Latin Extended + currency-symbol ranges.
- **Phase 2 PAA engine `class_of_business_id` resolution** — PAA engines (`LrcEngine`, `LicEngine`, `DiscountUnwindEngine`, `OnerousContractTestEngine`) post JEs with the back-compat constructor that defaults `class_of_business_id` to null. Resolving class from the policies in the contract group is a future slice; doesn't block N01 (PAA JEs don't feed the revenue account).
- **`PrudentialReturnEngine` admitted-assets refinement** — N03's solvency-margin formula uses the conservative-defensible 15% minimum-capital-of-premium-written calculation. NAICOM Operational Guideline's full admitted-assets exclusions + statutory floor + Tier-1/Tier-2 logic are deferred to v2; engine documents this explicitly in the payload's `notes` field.

---

## 7. Phase 5 — Frontend Admin UI for Module 12

**Status:** Shipped 2026-05-21. 16 slices (F5.1–F5.16), one route module `apps/back-office/src/modules/closures/` mounting a 13-tab navigation, all backed by zod schemas in `@cia/api-client/finance-closures.ts`. Plus three closeout fixes that landed against the backend during the same session.

**Goal achieved:** finance / CFO user can drive Module 12 end-to-end from the UI — period close, period browse, submission generation + review + submit + acknowledge + archive + retract, IFRS-17/9 disclosure rendering, CFO reopen flow, retroactive backfill (PLATFORM_ADMIN). 0 mocks, 0 TODOs, all forms `useMutation`, all reads `validatedGet` zod-checked. No SSE/WebSocket — polling on the artifacts query is sufficient.

**Scope landed (revised from the original Phase 6 plan):**

The original plan bundled "Investments UI" into this phase. That work was smaller than originally scoped because Phase 3 stayed inside `cia-finance` rather than spinning out as `cia-investments`. Module 12's investment-related UI is the IFRS-9 disclosure viewer that surfaces V40 data + a holdings list with classification-history sheet — not a full instrument-master / valuation / accrual UI. The artifact rendering loop (F5.16) ended up larger than scoped because the backend exposes JSON/CSV/PDF (XML reserved-but-not-implemented) per-format render + download endpoints.

**What shipped:**

| Slice | Scope |
|---|---|
| F5.1 | `PeriodLockListPage` (FY + granularity selectors, 4 StatCards, period DataTable with status-gated row actions) + `ClosePeriodDialog` (soft/hard) + `ReopenPeriodDialog` (HARD only, CFO role) + `LockHistorySheet` (Type-2 SCD `period_lock` history) + `CreateFiscalYearSheet` |
| F5.3 | Read-only `ChartOfAccountsPage` — 129-row 3-level tree with expand/collapse, account-type filter, IFRS-17 + IFRS-9 role badges per node |
| F5.7 | Read-only `PostingRulesPage` — 6 V33-seeded rules with Dr/Cr code + COA-resolved name + monospaced narrative template + ACTIVE badge + FAC carve-out footer. Backend gap closed: `PostingRuleController` (`GET /api/v1/finance/posting-rules`), `PostingRuleService.findAll()`, `PostingRuleResponse` enriched via `Function<String,String>` COA-name resolver |
| F5.4 | `JournalEntryBrowserPage` (status / source-module / account / business-date filters, cursor pagination, 3 StatCards) + `JournalEntryDetailSheet` (idempotency triple, line table with COA-resolved names + class-of-business chip) |
| F5.5 | `TrialBalanceReportPage` — cumulative-since-inception balance at chosen business date, account-type sub-totals, Σdr = Σcr footer with JE-line backing count |
| F5.6 | PLATFORM_ADMIN `BackfillAdminPage` — start dry-run / live, parameters form, localStorage workflow tracking with polling status, Temporal workflow ID + activity log per run |
| F5.8 | `PaaPeriodClosePage` — FY + Period selectors, Run PAA close button (orchestrator), §83/§84 `InsuranceServiceResult` card with per-engine breakdown |
| F5.9/10 | `PaaMovementAnalysisPage` (collapsed when the single endpoint already returned both halves) — §103 LRC + LIC roll-forward tables via shared generic `RollforwardTable<T extends Record<string, number>>` component; per-group breakdown rows |
| F5.11 | `ContractGroupsPage` — portfolio + cohort + onerousness + status filters; §22 permanent-assignment empty state pointing at `ContractGroupingService` event-driven creation |
| F5.12 | `HoldingsListPage` (asset-type + classification + status filters; 4 StatCards) + `HoldingClassificationHistorySheet` (Type-2 SCD §B4.1.26 reclassification trail) |
| F5.13 | `Ifrs9MeasurementPage` — per-engine run buttons (AmortisedCost / FairValue / InvestmentECL / PremiumReceivableECL), per-engine result cards, FINANCE_APPROVE gated |
| F5.14 | `Ifrs9MovementAnalysisPage` — combined investment roll-forward + premium-receivable ECL section via shared `RollforwardTable<T>`; relays V40 view |
| F5.15 | `NaicomSubmissionsPage` — FY + Period + State filter row, 4 StatCards, submissions table with N01–N08 type codes, `enabled: canList` query gate (mirrors backend "at least one filter" guard), `GenerateSubmissionDialog` with 8 NAICOM types; `NaicomSubmissionDetailSheet` state-machine console (Submit / Acknowledge / Retract / Archive depending on state) + Type-2 SCD event timeline + collapsible payload JSON preview |
| F5.16 | "Rendered artifacts" block inside `NaicomSubmissionDetailSheet` — JSON / CSV / PDF rows (XML excluded — no backend renderer); render mutation keyed by `ArtifactFormat` doubles as per-row spinner state via `mutation.variables === format`; download via `apiClient.get { responseType: 'blob' }` + synthesized filename; Re-render gated on FINANCE_APPROVE |

**Closeout fixes (same session, against backend):**

- `MinioStorageService.@PostConstruct ensureBucketExists()` — `BucketExistsArgs → MakeBucketArgs`, non-fatal on failure. Eliminates "fresh dev MinIO 500s every first-time upload" surfaced by F5.16 artifact testing.
- `FiscalYearService.close()` now cascades hard-close on every non-HARD child period via `PeriodLockService.hardClose` — closes the OpenAPI-doc promise the service had never delivered. Per-period delegation; idempotent on already-CLOSED FY. Existing CLOSED FYs with OPEN children stay inconsistent rather than being silently repaired (segregation-of-duties trade-off).
- Deleted unused `FiscalPeriodResolver.resolveDayForBusinessDate` infrastructure — zero production callers, JEs anchor to MONTH per Slice 1.4 D1=A. `FiscalPeriodType.DAY` enum value retained for schema-level reservation (V31 CHECK constraint binds — "never edit existing migrations"). Surfaced two pre-existing `FiscalYearServiceIT` bugs in the same pass (missing `PeriodLockService` mock + missing `CiaCommonAutoConfiguration` `@Import` for `@EnableJpaAuditing`) which are also fixed.

**Test coverage at phase-end:** 274 cia-api failsafe ITs (down from 275 with the lazy-DAY IT deletion), 0 failures, 0 errors, 1 intentional benchmark skip. `FiscalYearServiceIT` was failing all 12 tests since at least commit `b12c052`; now green at 11/11. *(The reactor later grew to **595** failsafe ITs as subsequent workstreams landed — including the FAC↔IFRS-17 PAA extension, V76–V79.)*

**Engineering decisions captured:**

- **`RollforwardTable<T extends Record<string, number>>` is the only shared UI component extracted in Phase 5.** Used by both PAA and IFRS 9 movement-analysis pages. Rule-of-three rather than rule-of-two — two flagged-not-extracted patterns sit in `cia-log.md` under the F5.15 entry: state-conditional transition controls (F5.1 + F5.15) and `enabled: canList` filter-shape-validity gate (single occurrence at F5.15).
- **`@cia/api-client/finance-closures.ts` enum convention.** All `z.enum(...)` declarations live in a single "Enums" section at the top of the file. DTO sections may reference any enum + any earlier DTO. Recursive shapes use `z.lazy()` with explicit `z.ZodType<...>`. Established after the F5.14 ordering bug (IFRS 9 schemas inserted above their enum dependencies).
- **The `enabled: canList` gate pattern (F5.15) is the right way to mirror "supply at least one of X or Y" backend guards.** Frontend computes `canList` from the filter state, threads it into `useQuery.enabled`. Backend never sees a guaranteed-to-fail request; user sees a smart empty-state hint instead of an error toast.
- **F5.16 artifact mutation key doubles as per-row spinner state.** `useMutation<…, …, ArtifactFormat>` — `mutation.variables === format` is true only for the in-flight row, so each format's button can flip independently to `…` without any local `isRenderingPdf`/`isRenderingCsv` state. Co-locates the loading flag with React Query's mutation lifecycle.

**Engineering watch-outs that turned out fine:**

- No real-time progress streaming required in v1 — polling sufficient. SSE / WebSocket can be a Phase 5b enhancement if user feedback demands it.
- Reopen flow stayed simple. Plain confirmation dialog with reason textarea; typed-confirmation pattern not needed because the CFO role + audit trail provide the friction.

---

## 8. Phase 6 — Cross-Tenant Platform Admin View

**Status:** Not started. Original Phase 7 scope (per-period finality transitions, reopen flow) was largely absorbed into Slice 1.7c (PPA workflow, period reopen, tenant_holiday calendar). What remains is the **cross-tenant platform admin view**.

**Goal:** read-only platform-admin view of every tenant's closure state, plus a force-close capability from the platform side for tenants that haven't closed by the NAICOM deadline.

**Scope (significantly reduced from the original Phase 7 plan):**

- `PlatformClosureViewService` — cross-tenant query path (read-only iteration over `public.tenants` plus per-tenant aggregation of fiscal-period status). The ONLY place in the codebase that intentionally crosses tenant schemas; treat as a privileged operation with extensive logging.
- Platform admin frontend page `apps/back-office/src/modules/platform/closures/PlatformClosureOverviewPage` (or a separate platform admin app — coordinate with team).
- Force-close from platform admin → calls the per-tenant `PaaPeriodCloseService.closePeriod(...)` + period-lock transition through a privileged endpoint with `PLATFORM_FORCE_CLOSE` RBAC.
- NAICOM deadline countdown banners on the platform admin page (10 working days post-EOM, 30 days post-EOQ, 90 days post-EOY per NAICOM regulation).
- Notifications: both the originating tenant and the platform admin receive force-close notifications.

**Out of scope here** (originally Phase 7, now done in Phase 1 / will be done in Phase 4):

- ~~Soft → hard close transition logic~~ — already in Slice 1.7 PeriodLockService.
- ~~Reopen flow~~ — already in Slice 1.7c with the PPA workflow.
- ~~NAICOM-acknowledgement-triggers-hard-close logic~~ — Slice 4.9 (`SubmissionAcknowledgedEvent` listener triggers the existing PeriodLockService.hardClose).

**Exit criteria:**

- Platform admin can see every tenant's closure state at a glance.
- Force-close from platform admin emits the correct audit trail in both tenant `audit_log` and platform-level logs.
- A reopened period blocks further force-closes until the tenant reclosees.

**Engineering watch-outs:**

- **Cross-tenant queries are the only privileged data path in the codebase.** Treat with care: extensive logging, no PII surfaced (aggregate counts only), separate authentication path (platform-admin RBAC distinct from tenant-admin RBAC).
- **Force-close should never run silently.** Per the original plan, this remains true.

---

## 9. Critical Path And Dependencies

```
[ Phase 1 — GL ] ────┐
                     │
[ Phase 2 — IFRS 17 ]┼──── [ Phase 4 — NAICOM (in progress) ] ────┐
                     │                                            │
[ Phase 3 — IFRS 9 ] ┘                                            ├──── [ Phase 5 — Frontend ] ────┐
                                                                  │                                │
                                                                  └────────────────────────────────┴──── [ Phase 6 — Platform ]
```

**Critical path:** Phase 1 → Phase 4 → Phase 5 → Phase 6. All other paths flow into this trunk. Phases 1, 2, 3 are merged on `main`; the trunk is now waiting on Phase 4.

---

## 10. Risks And Mitigations

| Risk | Original assessment | Reconciled status |
|---|---|---|
| NAICOM submission templates unavailable | High impact / High likelihood | **Still open.** Phase 4 ships against `StubNaicomSubmissionService` per the original mitigation. Spec-arrival-then-swap pattern preserved. |
| Period-lock Hibernate interceptor causes throughput regression | High impact / Medium likelihood | **Mitigated.** The `LockableByPeriod` marker pattern from Slice 1.7 makes the interceptor's per-flush cost a single `instanceof` test. No measurable cliff observed. |
| IFRS 17 risk adjustment requires actuarial signoff | Medium impact / Medium likelihood | **Deferred to v2.** Phase 2 ships with RA + IBNR columns ready but engines fill zero. Slice 2.7b is the placeholder for actuarial-method swap. Production rollout still requires the originally-flagged review. |
| Backfill of retroactive JEs takes many hours per tenant | Medium impact / Medium likelihood | **Mitigated.** Slice 1.8a/b ships an idempotent Temporal workflow with abort-and-resume; the 10k-event benchmark documents the wall-clock budget; the operational runbook covers deploy-window planning. |
| Activity idempotency violations | Medium impact / Medium likelihood | **Mitigated.** The JE-gateway idempotency triple (Slice 1.4) makes this DB-level. The original "ClosureActivity.isIdempotent()" annotation pattern from the deferred orchestration phase is moot — every engine posts through the gateway. |
| Tenant fiscal year discovery wrong on backfill | Low impact / Medium likelihood | **Resolved.** Slice 1.6 made fiscal year explicit per-tenant config; no defaults applied silently. |
| Investment classification produces unexpected FVPL defaults | Low impact / Low likelihood | **Mitigated.** Slice 3.2's `InvestmentClassificationService` pure-function `classify()` is fully unit-tested across the SPPI × business-model matrix. Bulk reclassification UI deferred to Phase 5. |
| Cross-tenant queries leak data | High impact / Low likelihood | **Still open until Phase 6.** No cross-tenant code exists yet; the risk surface won't materialise until Phase 6 ships. |
| Real-time progress streaming infrastructure | Low impact / Low likelihood | **Avoided.** Phase 5 plans polling; SSE/WebSocket deferred indefinitely unless user feedback demands. |
| Period-level orchestrator missing | (Not in original plan) | **Open by design.** Per-domain orchestrators chosen instead of generic activity registry. If scheduling bugs surface (forgotten steps, wrong ordering), the orchestrator becomes worth building. |

---

## 11. Continuous Concerns Across All Phases

Unchanged from the original plan; reaffirmed by actual practice:

- **Testing:** Testcontainers PostgreSQL ITs; minimum 80% line coverage on Module 12 code. Reconciliation gate (Slice 1.9) catches silent posting-rule regressions on a 200-event fixture. The `mvn verify` failsafe binding is canonical since Slice 1.7-fix.
- **Audit trail:** every JE post, every period-lock transition, every submission state change emits an audit-log entry. PII fields encrypted at rest per V24.
- **Observability:** every engine logs at INFO with structured fields (period, counts, totals). Module 12 doesn't yet emit OpenTelemetry spans — Phase 5/6 will wire that.
- **Documentation:** every internal endpoint added updates `docs-site/static/internal-api.json`; every new module updates `docs-site/docs/architecture/modules.md`; every phase / slice gets a session entry in `cia-log.md`.
- **CI gates:** `bash cia-frontend/scripts/check-api-wiring.sh` on every frontend PR; `mvn verify` (now binding failsafe) on every backend PR; `module-12-reconciliation.yml` 200-event gate on Phase-1-touching PRs.

---

## 12. Sequencing Recommendation (revised)

Original plan estimated 17 sprints / 17 weeks for the full Module 12 build with three engineers parallelised. Actual sequencing:

| Sprint | Calendar | What shipped |
|---|---|---|
| Sprints 1–6 | 2026-03 → 2026-05-15 | Phase 1 (12 slices) shipped iteratively. Pace much faster than the original estimate (4–6 weeks for Phase 1 became ~10 weeks across both Phase 1 work and supporting infrastructure like Testcontainers / docker-java overrides). |
| Sprint 7 | 2026-05-19 (single session) | Phase 2 (8 slices) shipped in one extended session. The pure-function math + Spring service wrapper pattern, plus the V32 COA foresight payoff (zero new accounts needed), enabled this pace. |
| Sprint 8 | 2026-05-19 (continued) | Phase 3 (7 slices) shipped same session. Same pattern. |
| Sprint 9 | 2026-05-19 | Slice T1 (focused upstream contract tests for the 6 events Module 12 consumes). |
| Sprint 10 | 2026-05-19 | Phase 4 slices 4.1–4.10 shipped end-to-end. Merge to `main` via `50e5b11`. |
| Sprint 11 | 2026-05-20 | Slice 1.10a + 1.10b — GL-substrate enrichment (`class_of_business_id` on JE lines) + N01 over GL with reconciliation assertion. Merge to `main` via `fd795f6`. |
| Sprint 12 | 2026-05-21 (single session) | Phase 5 (16 slices F5.1–F5.16) shipped end-to-end across `cia-frontend/apps/back-office/src/modules/closures/`. Plus three closeout fixes against the backend (MinIO bucket bootstrap, FY-close cascade, lazy-DAY infrastructure deletion). Tests baseline went from 275 → 274 (dead IT removed); `FiscalYearServiceIT` recovered from a long-standing failure that was masked by context-startup errors. |

**Calendar-time reality:** Phases 1–5 + T1 + Slice 1.10 took ~10 weeks of calendar time with one developer plus AI-assisted slice execution. The original estimate of 16–20 weeks with 3 engineers turned out to be wildly conservative for this pace; the original estimate of 30–40 weeks for one engineer sequential is what you'd get without the slice discipline and the JE-gateway architecture.

**Remaining estimate:** Phase 6 (cross-tenant platform admin view) is ~1 week (small scope after Phase 1 absorbed most of Phase 7's original work).

---

## Related Documents

- `period-end-closures-design.md` — full technical design that this plan implements.
- `period-end-closures-foundations-plan.md` — earlier PR-slice expansion of Phases 1–3; superseded by the slice-level detail in `cia-log.md` sessions 60–72.
- `production-readiness-tracker.md` — adjacent gates (Temporal worker management, PII handling, deployment) that this plan inherits.
- `database-migration-runbook.md` — established procedure for Flyway migrations during deployment.
- `cia-log.md` — session-level shipping log (sessions 60–72 cover Phases 1–3 + T1; the Phase 4 + Slice 1.10 session entry covers the Module 12 finishing work that landed 2026-05-19/20).
