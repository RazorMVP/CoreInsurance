---
id: period-end-closures-design
title: Period-End Closures — Design Document
sidebar_label: Period-End Closures (Design)
---

# Period-End Closures — Design Document

Design date: 2026-05-09

Status: **Historical** — Phases 1–4 shipped (see implementation note below).

Branch: `main`

This document specifies the original design for end-of-day, end-of-month, end-of-quarter, half-year, and end-of-year business closures in CIAGB. It is the technical companion to the locked-in scope decisions captured in Session 55 of the project log (`cia-log.md`, dated 2026-05-09). The corresponding phasing and rollout plan lives in [`period-end-closures-implementation-plan.md`](./period-end-closures-implementation-plan.md).

## 0. Implementation Reality — As Shipped 2026-05-20

The design below is preserved for historical reference. The implementation diverged in two ways:

| Designed | Shipped | Reason |
| --- | --- | --- |
| New module `cia-investments` for IFRS 9 (§2.1) | `cia-finance/ifrs9/` subpackage | IFRS 9 measurement produces journal entries the GL layer (also in `cia-finance`) immediately consumes — splitting creates a circular dependency. The §2.3 rationale for keeping IFRS 17 inside `cia-finance/ifrs17/` applies equally to IFRS 9. |
| New module `cia-closure` with unified `ClosureWorkflow` covering EOD/EOM/EOQ/Half-Year/EOY (§2.2, §5.1) | Per-domain orchestrators: `cia-finance/paa/PaaPeriodCloseService` (IFRS 17 measurement close) + `cia-finance/naicom/NaicomSubmissionService` (regulatory submissions state machine) | A unified workflow conflates orthogonal concerns — IFRS 17 measurement (always per-period), IFRS 9 measurement (always per-period), and NAICOM submissions (per-submission-type, retract-and-resubmit semantics). Per-domain orchestrators let each have its own state machine, idempotency triple, and lock semantics. |

As-shipped module layout for Module 12:

```
cia-finance/
├── gl/                              # Phase 1 — GL Foundation
│   ├── ChartOfAccountService
│   ├── JournalEntryService          # The Slice 1.4 gateway — every engine posts through here
│   ├── TrialBalanceService
│   ├── FiscalYearService
│   ├── PeriodLockService            # 5-business-day grace, Hibernate Interceptor, 423 LOCKED
│   ├── SubledgerPostingService      # @EventListener fanout: DN/CN/Receipt/Payment events → JEs
│   └── PolicyClassResolver          # Slice 1.10 — resolves class_of_business per event
├── paa/                             # Phase 2 — IFRS 17 PAA measurement
│   ├── ContractGroupingService      # §22 permanent group assignment
│   ├── LrcEngine                    # Liability for Remaining Coverage
│   ├── LicEngine                    # Liability for Incurred Claims
│   ├── DiscountUnwindEngine         # §87-92, P&L vs OCI routing
│   ├── OnerousContractTestEngine    # §47-49, loss component
│   ├── PaaPeriodCloseService        # Orchestrator + §83/§84 InsuranceServiceResult
│   └── MovementAnalysisService      # §103 disclosure relay over V38 view
├── ifrs9/                           # Phase 3 — IFRS 9 measurement
│   ├── InvestmentClassificationService  # §4.1 + §B4.1.26 SCD
│   ├── AmortisedCostEngine          # §5.4.1 effective interest method
│   ├── FairValueEngine              # §5.7 — FVPL → P&L, FVOCI_DEBT → OCI, FVOCI_EQUITY → OCI
│   ├── InvestmentEclEngine          # §5.5 + §5.7.10A
│   ├── PremiumReceivableEclEngine   # §5.5.15 simplified approach
│   └── Ifrs9MovementAnalysisService # §B5.5.39 disclosure relay over V40 view
├── naicom/                          # Phase 4 — NAICOM monthly recap submissions
│   ├── PremiumBordereauxEngine (N05) + ClaimsBordereauxEngine (N06)
│   ├── AnnualRevenueAccountEngine (N01) + BalanceSheetEngine (N02)
│   ├── PrudentialReturnEngine (N03) + RiQuarterlyReturnEngine (N04)
│   ├── Ifrs17DisclosureEngine + Ifrs9DisclosureEngine + InvestmentStatementEngine (N08)
│   ├── NiidStatusSnapshotEngine (N07)
│   ├── NaicomSubmissionService      # Orchestrator + DRAFT → SUBMITTED → ACKNOWLEDGED → ARCHIVED + RETRACTED
│   ├── SubmissionArtifactService    # JSON / CSV / PDF rendering + DocumentStorageService
│   └── (10 engines × NaicomSubmissionEngine interface for @PostConstruct dispatch)
└── backfill/                        # Slice 1.8 — retroactive JE backfill (Temporal workflow)
```

What's NOT shipped (still on backlog): the EOD/EOM/EOQ/HY/EOY unified closure orchestration (§2.2, §3.1, §3.2, §5) — the per-domain orchestrators above cover the financial close paths that have shipped. The Module 12 back-office frontend itself shipped 2026-05-21 as Phase 5 (16 slices F5.1–F5.16); a unified admin closure UI that bundles hard-close → IFRS 17 close → NAICOM generation behind one button still belongs to Phase 6.

The sections below preserve the original design as written 2026-05-09 — read them as historical context, not as the current implementation map.

---

## 1. Scope Summary

CIAGB will support five admin-runnable closure types, coordinated as a single capability:

| Closure | Frequency | Operational | Financial | Regulatory |
| --- | --- | --- | --- | --- |
| EOD | Daily | ✓ | — | — |
| EOM | Monthly | ✓ | ✓ | NAICOM monthly recap |
| EOQ | Quarterly | ✓ | ✓ | NAICOM Mgmt Acct + ALM |
| Half-Year | Semi-annual | ✓ | ✓ | Interim board reporting |
| EOY | Annual | ✓ | ✓ | NAICOM annual returns + cohort closure + retained-earnings zero-out |

Closures combine **operational batch processing** (renewals, NIID uploads, snapshots, alerts) with **financial accounting close** for monthly+ closures (chart-of-accounts journal posting, IFRS 17 PAA measurement, IFRS 9 investment classification, sub-ledger reconciliation, period locking, regulatory pack generation).

Authoritative scope decisions are in the project log (Session 55) under "Locked-in scope (7 clarifying questions, all answered)" — not repeated here to avoid drift.

## 2. New And Modified Modules

### 2.1 New module: `cia-investments`

Owns the financial-asset side of the balance sheet under IFRS 9. Independent of insurance-contract logic.

```
cia-investments/
├── domain/
│   ├── Instrument.java                  // Government Bond / Corporate Bond / Equity / Money Market / T-Bill / Fixed Deposit / Mutual Fund
│   ├── Holding.java                     // Tenant's position in an instrument
│   ├── Valuation.java                   // Mark-to-market history per holding
│   ├── IncomeAccrual.java               // Interest / dividend accruals per holding
│   ├── Ifrs9Classification.java         // FVPL / FVOCI_DEBT / FVOCI_EQUITY / AMORTISED_COST
│   ├── EclProvision.java                // 12-month / lifetime ECL provisions per holding
│   └── BusinessModel.java               // HOLD_TO_COLLECT / HOLD_TO_COLLECT_AND_SELL / OTHER
├── service/
│   ├── InstrumentService.java
│   ├── HoldingService.java
│   ├── ValuationService.java            // Monthly MTM at EOM
│   ├── Ifrs9ClassificationService.java  // SPPI test logic + classification routing
│   ├── EclService.java                  // 12-month + lifetime ECL stages
│   └── InvestmentIncomeService.java     // Period interest/dividend accrual
└── controller/
    ├── InstrumentController.java
    ├── HoldingController.java
    └── ValuationController.java
```

Depends on: `cia-common`, `cia-auth`. Does not depend on any business module.

### 2.2 New module: `cia-closure`

Owns the closure orchestration state machine, the activity registry, period locks, and the soft/hard finality transitions. Sits at the same layer as `cia-audit` and `cia-reports` — depends on `cia-common` + `cia-auth` only, never on a business module. Business modules contribute closure activities through the registry, never the other way around.

```
cia-closure/
├── domain/
│   ├── ClosurePeriod.java               // Tenant + closure type + period start/end + state
│   ├── ClosureRun.java                  // Single execution attempt — success / partial / failed
│   ├── ClosureActivityRegistry.java     // Static registry of all known activities
│   ├── ClosureActivityExecution.java    // Per-run record of each activity's outcome
│   ├── PeriodLock.java                  // Lock metadata per (tenant, period type, period)
│   └── ClosureApproval.java             // Approval trail per closure run
├── service/
│   ├── ClosureOrchestrationService.java // Public entry point — admin clicks → workflow start
│   ├── ClosureActivityRegistryService.java
│   ├── PeriodLockService.java           // Block backdated entries; enforce 5-day cutoff
│   ├── PeriodFinalityService.java       // Soft → hard transitions
│   └── ClosureProgressService.java      // Real-time progress streaming for UI
└── workflow/                            // Temporal workflows + activities
    ├── ClosureWorkflow.java
    ├── EodWorkflow.java
    ├── EomWorkflow.java
    ├── EoqWorkflow.java
    ├── HalfYearWorkflow.java
    ├── EoyWorkflow.java
    └── SoftToHardTransitionWorkflow.java
```

### 2.3 Extensions to `cia-finance`

Adds the general-ledger layer that monthly+ closures post to.

```
cia-finance/  (additions)
├── domain/
│   ├── ChartOfAccount.java              // Per-tenant account hierarchy
│   ├── AccountType.java                 // ASSET / LIABILITY / EQUITY / INCOME / EXPENSE
│   ├── JournalEntry.java                // Header
│   ├── JournalEntryLine.java            // Debit / credit per line
│   ├── TrialBalance.java                // Computed snapshot per period
│   ├── FiscalYear.java                  // Tenant fiscal year config
│   ├── FiscalPeriod.java                // Day / month / quarter / half / year
│   └── PostingRule.java                 // Sub-ledger event → GL accounts mapping
├── service/
│   ├── ChartOfAccountService.java       // CRUD + tenant-default seeding on tenant creation
│   ├── JournalEntryService.java         // Post + reverse + period-lock awareness
│   ├── TrialBalanceService.java
│   ├── FiscalYearService.java
│   └── SubledgerPostingService.java     // Listens for DN/CN/Receipt/Payment events → posts JEs
└── ifrs17/
    ├── domain/
    │   ├── InsurancePortfolio.java       // Group of contracts managed together (e.g., Motor Comprehensive 2026 cohort)
    │   ├── ContractGroup.java            // Annual cohort × portfolio × onerousness bucket (3 buckets)
    │   ├── Lrc.java                      // Liability for Remaining Coverage
    │   ├── Lic.java                      // Liability for Incurred Claims
    │   ├── RiskAdjustment.java           // 75th-percentile confidence-level method
    │   ├── OnerousContractTest.java      // Per-cohort PAA onerous deficit test
    │   └── InsuranceFinanceFlow.java     // Discount unwinding on LIC
    └── service/
        ├── PaaMeasurementService.java
        ├── LrcCalculationService.java
        ├── LicCalculationService.java
        ├── RiskAdjustmentService.java
        ├── OnerousContractTestService.java
        └── ReinsuranceContractsHeldService.java   // Mirror approach for `cia-reinsurance`
```

The IFRS 17 measurement code lives inside `cia-finance/ifrs17/` rather than as a separate module because (a) it produces journal entries the GL layer immediately consumes, and (b) it shares the period-lock and fiscal-year primitives. Splitting it out would create a circular dependency.

### 2.4 Module dependency additions

```
cia-investments  →  cia-common, cia-auth
cia-closure      →  cia-common, cia-auth, cia-workflow (Temporal)
cia-finance      →  (existing) + reads from cia-policy, cia-claims, cia-endorsement, cia-reinsurance via Spring events only — never direct calls
cia-api          →  + cia-investments, cia-closure
```

Critically, `cia-closure` does not depend on any business module. Business modules contribute closure activities by registering Spring `@Component` beans implementing `ClosureActivity`; the registry discovers and runs them. This mirrors how `cia-audit` consumes events without coupling to producers.

## 3. Data Model

### 3.1 Closure activity registry

```sql
CREATE TABLE closure_activity (
  id              UUID PRIMARY KEY,
  name            VARCHAR(128) NOT NULL UNIQUE,         -- e.g., 'PROCESS_RENEWAL_NOTICES'
  description     TEXT NOT NULL,
  closure_types   VARCHAR(64)[] NOT NULL,                -- {'EOD','EOM','EOQ','HALF_YEAR','EOY'}
  module          VARCHAR(64) NOT NULL,                  -- 'cia-policy', 'cia-finance', etc.
  bean_name       VARCHAR(128) NOT NULL,                 -- Spring bean implementing ClosureActivity
  sort_order      INT NOT NULL,
  enabled         BOOLEAN NOT NULL DEFAULT TRUE,
  is_financial    BOOLEAN NOT NULL DEFAULT FALSE,        -- Only financial activities require period lock
  is_regulatory   BOOLEAN NOT NULL DEFAULT FALSE,        -- Triggers NAICOM submission generator
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_closure_activity_types ON closure_activity USING GIN (closure_types);
```

Activities are seeded by Flyway and live in the **public** schema (shared metadata, not tenant data). Tenants can disable individual activities through a `tenant_closure_activity_override` table:

```sql
CREATE TABLE tenant_closure_activity_override (
  tenant_id              UUID NOT NULL REFERENCES public.tenants(id),
  closure_activity_id    UUID NOT NULL REFERENCES public.closure_activity(id),
  enabled                BOOLEAN NOT NULL,
  reason                 TEXT,
  PRIMARY KEY (tenant_id, closure_activity_id)
);
```

### 3.2 Closure runs and progress

Lives **per-tenant schema** because run history is tenant-private.

```sql
CREATE TABLE closure_period (
  id              UUID PRIMARY KEY,
  closure_type    VARCHAR(16) NOT NULL,                  -- EOD / EOM / EOQ / HALF_YEAR / EOY
  period_start    DATE NOT NULL,
  period_end      DATE NOT NULL,
  fiscal_year     INT NOT NULL,
  state           VARCHAR(16) NOT NULL,                  -- OPEN / CLOSING / SOFT_CLOSED / HARD_CLOSED / REOPENED
  soft_closed_at  TIMESTAMPTZ,
  hard_closed_at  TIMESTAMPTZ,
  reopen_count    INT NOT NULL DEFAULT 0,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (closure_type, period_start, period_end)
);

CREATE TABLE closure_run (
  id                       UUID PRIMARY KEY,
  closure_period_id        UUID NOT NULL REFERENCES closure_period(id),
  triggered_by_user_id     UUID NOT NULL,
  trigger_source           VARCHAR(16) NOT NULL,         -- MANUAL / SCHEDULED / FORCE_PLATFORM
  temporal_workflow_id     VARCHAR(255) NOT NULL,
  state                    VARCHAR(16) NOT NULL,         -- RUNNING / SUCCEEDED / FAILED / CANCELLED
  started_at               TIMESTAMPTZ NOT NULL,
  completed_at             TIMESTAMPTZ,
  failure_reason           TEXT,
  created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE closure_activity_execution (
  id                       UUID PRIMARY KEY,
  closure_run_id           UUID NOT NULL REFERENCES closure_run(id),
  closure_activity_name    VARCHAR(128) NOT NULL,
  state                    VARCHAR(16) NOT NULL,         -- PENDING / RUNNING / SUCCEEDED / FAILED / SKIPPED
  started_at               TIMESTAMPTZ,
  completed_at             TIMESTAMPTZ,
  records_processed        INT,
  failure_reason           TEXT,
  output_summary           JSONB                         -- Activity-specific result payload
);

CREATE INDEX idx_closure_run_period ON closure_run (closure_period_id);
CREATE INDEX idx_closure_activity_execution_run ON closure_activity_execution (closure_run_id);
```

### 3.3 Period locks

```sql
CREATE TABLE period_lock (
  id                  UUID PRIMARY KEY,
  closure_period_id   UUID NOT NULL REFERENCES closure_period(id),
  finality            VARCHAR(8) NOT NULL,                -- SOFT / HARD
  effective_at        TIMESTAMPTZ NOT NULL,
  cutoff_window_days  INT NOT NULL DEFAULT 5,
  released_at         TIMESTAMPTZ,                         -- NULL until reopened
  released_by_user_id UUID,
  reopen_reason       TEXT,
  approval_id         UUID                                  -- Reference to approval trail
);
```

Hibernate interceptors enforce locks at the entity level: any insert/update on `policies`, `claims`, `endorsements`, `debit_notes`, `credit_notes`, `receipts`, `payments`, or `journal_entries` checks that the entity's business date is not inside a hard-closed period, and not inside a soft-closed period beyond the 5-business-day cutoff.

### 3.4 Chart of accounts and journal entries

```sql
CREATE TABLE chart_of_account (
  id              UUID PRIMARY KEY,
  account_code    VARCHAR(32) NOT NULL UNIQUE,
  account_name    VARCHAR(255) NOT NULL,
  account_type    VARCHAR(16) NOT NULL,                  -- ASSET/LIABILITY/EQUITY/INCOME/EXPENSE
  parent_id       UUID REFERENCES chart_of_account(id),
  ifrs17_role     VARCHAR(32),                            -- INSURANCE_REVENUE / INSURANCE_SERVICE_EXPENSE / LRC / LIC / etc.
  ifrs9_role      VARCHAR(32),                            -- INVESTMENT_INCOME / FAIR_VALUE_GAIN / ECL_EXPENSE / etc.
  is_active       BOOLEAN NOT NULL DEFAULT TRUE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE journal_entry (
  id                  UUID PRIMARY KEY,
  je_number           VARCHAR(64) NOT NULL UNIQUE,        -- Tenant-formatted, e.g., JE-2026-04-000123
  je_date             DATE NOT NULL,                       -- Business date — drives period assignment
  description         TEXT NOT NULL,
  source_type         VARCHAR(32) NOT NULL,                -- MANUAL / DEBIT_NOTE / CREDIT_NOTE / RECEIPT / PAYMENT / IFRS17_LRC / IFRS17_LIC / IFRS9_MTM / EOY_ZEROOUT
  source_id           UUID,                                -- FK to source entity if applicable
  closure_run_id      UUID REFERENCES closure_run(id),     -- Set if posted by a closure run
  posted_at           TIMESTAMPTZ NOT NULL,
  posted_by_user_id   UUID NOT NULL,
  reversed_by_je_id   UUID REFERENCES journal_entry(id)    -- Reversal chain
);

CREATE TABLE journal_entry_line (
  id                  UUID PRIMARY KEY,
  journal_entry_id    UUID NOT NULL REFERENCES journal_entry(id),
  account_id          UUID NOT NULL REFERENCES chart_of_account(id),
  debit_amount        DECIMAL(18,2) NOT NULL DEFAULT 0,
  credit_amount       DECIMAL(18,2) NOT NULL DEFAULT 0,
  line_description    TEXT,
  cohort_year         INT,                                 -- IFRS 17 annual cohort tagging
  portfolio_id        UUID,                                -- IFRS 17 portfolio tagging
  CONSTRAINT chk_one_side CHECK (
    (debit_amount > 0 AND credit_amount = 0) OR
    (debit_amount = 0 AND credit_amount > 0)
  )
);

CREATE INDEX idx_je_date ON journal_entry (je_date);
CREATE INDEX idx_je_source ON journal_entry (source_type, source_id);
CREATE INDEX idx_jel_account ON journal_entry_line (account_id);
CREATE INDEX idx_jel_cohort ON journal_entry_line (cohort_year, portfolio_id);
```

The `je_date` (business date) is the anchor for period assignment. Per Q7d, this is the policy effective date / claim date-of-loss / receipt posting date — not the entity `created_at`.

### 3.5 IFRS 17 measurement tables

```sql
CREATE TABLE insurance_portfolio (
  id              UUID PRIMARY KEY,
  name            VARCHAR(255) NOT NULL,                  -- e.g., 'Motor Comprehensive', 'Marine Cargo'
  product_id      UUID NOT NULL,                           -- FK to existing products table
  measurement_model VARCHAR(8) NOT NULL DEFAULT 'PAA',    -- PAA / GMM (reserved) / VFA (reserved)
  active          BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE contract_group (
  id                UUID PRIMARY KEY,
  portfolio_id      UUID NOT NULL REFERENCES insurance_portfolio(id),
  cohort_year       INT NOT NULL,
  onerousness       VARCHAR(32) NOT NULL,                  -- ONEROUS / NO_SIGNIFICANT_POSSIBILITY / OTHER
  initial_recognition_date DATE NOT NULL,
  UNIQUE (portfolio_id, cohort_year, onerousness)
);

CREATE TABLE lrc_balance (
  id                       UUID PRIMARY KEY,
  contract_group_id        UUID NOT NULL REFERENCES contract_group(id),
  closure_period_id        UUID NOT NULL REFERENCES closure_period(id),
  unearned_premium         DECIMAL(18,2) NOT NULL,
  acquisition_cash_flows   DECIMAL(18,2) NOT NULL,         -- Deferred acquisition costs amortising over coverage
  loss_component           DECIMAL(18,2) NOT NULL DEFAULT 0,  -- Onerous deficit
  closing_balance          DECIMAL(18,2) NOT NULL,
  computed_at              TIMESTAMPTZ NOT NULL
);

CREATE TABLE lic_balance (
  id                       UUID PRIMARY KEY,
  contract_group_id        UUID NOT NULL REFERENCES contract_group(id),
  closure_period_id        UUID NOT NULL REFERENCES closure_period(id),
  fulfilment_cash_flows    DECIMAL(18,2) NOT NULL,         -- Best estimate of future claim payments
  risk_adjustment          DECIMAL(18,2) NOT NULL,         -- Confidence-level method, 75th percentile
  discount_effect          DECIMAL(18,2) NOT NULL DEFAULT 0,  -- Only if claims paid > 12 months after incurred
  closing_balance          DECIMAL(18,2) NOT NULL,
  computed_at              TIMESTAMPTZ NOT NULL
);

CREATE TABLE onerous_test_result (
  id                       UUID PRIMARY KEY,
  contract_group_id        UUID NOT NULL REFERENCES contract_group(id),
  closure_period_id        UUID NOT NULL REFERENCES closure_period(id),
  is_onerous               BOOLEAN NOT NULL,
  deficit_amount           DECIMAL(18,2) NOT NULL DEFAULT 0,
  computed_at              TIMESTAMPTZ NOT NULL
);
```

### 3.6 IFRS 9 measurement tables (in `cia-investments`)

```sql
CREATE TABLE instrument (
  id                  UUID PRIMARY KEY,
  symbol              VARCHAR(64) NOT NULL UNIQUE,
  name                VARCHAR(255) NOT NULL,
  instrument_type     VARCHAR(32) NOT NULL,                -- GOVERNMENT_BOND / CORPORATE_BOND / EQUITY / MONEY_MARKET / TREASURY_BILL / FIXED_DEPOSIT / MUTUAL_FUND
  currency            VARCHAR(3) NOT NULL DEFAULT 'NGN',
  issuer              VARCHAR(255),
  maturity_date       DATE,
  coupon_rate         DECIMAL(7,4),
  active              BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE holding (
  id                       UUID PRIMARY KEY,
  instrument_id            UUID NOT NULL REFERENCES instrument(id),
  acquired_date            DATE NOT NULL,
  acquired_quantity        DECIMAL(18,4) NOT NULL,
  acquired_unit_price      DECIMAL(18,4) NOT NULL,
  current_quantity         DECIMAL(18,4) NOT NULL,
  business_model           VARCHAR(32) NOT NULL,            -- HOLD_TO_COLLECT / HOLD_TO_COLLECT_AND_SELL / OTHER
  sppi_test_passed         BOOLEAN NOT NULL,                 -- Solely Payments of Principal and Interest
  ifrs9_classification     VARCHAR(16) NOT NULL,            -- AMORTISED_COST / FVOCI_DEBT / FVOCI_EQUITY / FVPL
  ecl_stage                VARCHAR(8) NOT NULL DEFAULT '1', -- 1 / 2 / 3 (12-month / lifetime / credit-impaired)
  disposed_date            DATE,
  CONSTRAINT chk_classification_consistency CHECK (
    (ifrs9_classification IN ('AMORTISED_COST','FVOCI_DEBT') AND sppi_test_passed = TRUE) OR
    ifrs9_classification IN ('FVOCI_EQUITY','FVPL')
  )
);

CREATE TABLE valuation (
  id                  UUID PRIMARY KEY,
  holding_id          UUID NOT NULL REFERENCES holding(id),
  valuation_date      DATE NOT NULL,
  fair_value          DECIMAL(18,2) NOT NULL,
  amortised_cost      DECIMAL(18,2),                       -- Only for AMORTISED_COST and FVOCI_DEBT classifications
  source              VARCHAR(64) NOT NULL,                -- MARKET_DATA / BROKER_QUOTE / MODEL / MANAGEMENT_ESTIMATE
  closure_run_id      UUID REFERENCES closure_run(id)
);

CREATE TABLE income_accrual (
  id                  UUID PRIMARY KEY,
  holding_id          UUID NOT NULL REFERENCES holding(id),
  accrual_date        DATE NOT NULL,
  income_type         VARCHAR(16) NOT NULL,                -- INTEREST / DIVIDEND
  amount              DECIMAL(18,2) NOT NULL
);

CREATE TABLE ecl_provision (
  id                  UUID PRIMARY KEY,
  holding_id          UUID NOT NULL REFERENCES holding(id),
  closure_period_id   UUID NOT NULL REFERENCES closure_period(id),
  stage               VARCHAR(8) NOT NULL,
  twelve_month_ecl    DECIMAL(18,2),
  lifetime_ecl        DECIMAL(18,2),
  provision_amount    DECIMAL(18,2) NOT NULL,
  computed_at         TIMESTAMPTZ NOT NULL
);
```

### 3.7 NAICOM submission tracking

```sql
CREATE TABLE naicom_submission (
  id                  UUID PRIMARY KEY,
  closure_period_id   UUID NOT NULL REFERENCES closure_period(id),
  submission_type     VARCHAR(32) NOT NULL,                -- MONTHLY_RECAPITALISATION / QUARTERLY_MGMT_ACCT / QUARTERLY_ALM / ANNUAL_RETURNS
  generated_at        TIMESTAMPTZ NOT NULL,
  document_path       TEXT NOT NULL,                       -- Object storage URL
  state               VARCHAR(16) NOT NULL,                -- GENERATED / SUBMITTED / ACCEPTED / REJECTED
  submitted_at        TIMESTAMPTZ,
  acceptance_received_at TIMESTAMPTZ,
  external_reference  VARCHAR(128)                          -- NAICOM acknowledgement reference
);
```

Submission acceptance (`ACCEPTED`) is one of the triggers for soft → hard close transition.

## 4. Service Layer Design

### 4.1 PAA measurement service

`PaaMeasurementService` is the IFRS 17 measurement engine for short-duration contracts. Its core operation runs at every monthly+ closure:

```java
public class PaaMeasurementService {
  // Computes LRC for every active contract group as of period_end
  // Produces LrcBalance records + posts JournalEntry for movement
  public List<LrcBalance> measureLrcForPeriod(ClosurePeriod period);

  // Computes LIC for every contract group with reported claims
  // Aggregates: best-estimate cash flows + risk adjustment + discount effect (if applicable)
  public List<LicBalance> measureLicForPeriod(ClosurePeriod period);

  // Per-cohort onerous test — recognise deficit if expected outflows exceed LRC
  public List<OnerousTestResult> runOnerousTest(ClosurePeriod period);

  // Roll-forward report for IFRS 17 disclosures
  public LrcRollForward generateLrcRollForward(ClosurePeriod period);
  public LicRollForward generateLicRollForward(ClosurePeriod period);
}
```

LRC measurement is the existing earned-premium calculation pulled into a formal service: for each in-force policy, pro-rate the gross premium minus deferred acquisition cost over the coverage period, then aggregate by contract group. The novelty is the **contract group classification** — every policy must be assigned to a portfolio (mapped from product), an annual cohort (from policy start year), and an onerousness bucket (from the most recent test result).

> **Extended by `fac-ifrs17-paa-workstream` (V76–V79, merged 2026-08-18):** the LRC engine is now **nature-dispatched** and also earns **inward and outward facultative reinsurance** — not only direct policies. Grouping moved to the polymorphic `contract_group_assignment` (+ `portfolio.contract_nature`); inward FAC posts an LRC liability (`Cr 2210`→`Dr 2210/Cr 4330`), outward FAC a reinsurance-held asset (`Dr 5210/Cr 1410`) with §65 commission-netting; cancellation derecognises per-contract. The DIRECT path in this section is unchanged (byte-identical). Full design in the repo at `docs/superpowers/specs/2026-08-08-fac-ifrs17-paa-design.md`.

Risk adjustment uses the confidence-level method: 75th percentile (Nigerian convention) on the distribution of fulfilment cash flows. The distribution is empirically estimated from historical claims development triangles per portfolio. For the initial build we'll start with a simple bootstrap; refinement is a future polish phase.

### 4.2 IFRS 9 classification + ECL

`Ifrs9ClassificationService` runs the SPPI test and assigns the classification. On instrument creation:

```
if (instrument has equity characteristics):
   classification = FVPL (default) or FVOCI_EQUITY (if irrevocable election made)
elif (SPPI test passed AND business_model = HOLD_TO_COLLECT):
   classification = AMORTISED_COST
elif (SPPI test passed AND business_model = HOLD_TO_COLLECT_AND_SELL):
   classification = FVOCI_DEBT
else:
   classification = FVPL
```

`EclService` runs at every monthly+ close:
- Stage 1 (12-month ECL): no significant credit deterioration since recognition
- Stage 2 (lifetime ECL): significant deterioration but not credit-impaired
- Stage 3 (lifetime ECL, credit-adjusted gross carrying amount): credit-impaired

Stage transitions are evaluated using simple credit-rating thresholds initially; refined per tenant.

### 4.3 Closure orchestration

`ClosureOrchestrationService.startClosure(tenant, closureType, period)` is the single entry point. It:

1. Validates the period is OPEN (not already CLOSING, SOFT_CLOSED, or HARD_CLOSED)
2. Verifies the trigger source is authorised:
   - MANUAL → caller has `closure:run` authority for the closure type
   - SCHEDULED → caller is the system scheduler
   - FORCE_PLATFORM → caller has `PLATFORM_ADMIN` role
3. Inserts a `closure_period` row in CLOSING state and a `closure_run` row in RUNNING state
4. Starts a Temporal `ClosureWorkflow` (typed by closure type)
5. Returns the `closureRunId` for client-side progress tracking

The workflow then:

1. Loads enabled `closure_activity` records for this closure type, filtered by tenant overrides
2. For each activity in `sort_order`: starts a Temporal Activity that invokes the registered Spring bean
3. Each activity records its progress to `closure_activity_execution` after each batch
4. On all activities succeeding → financial activities (if any) post journal entries → period state transitions to SOFT_CLOSED
5. Generates regulatory submission packs if `is_regulatory` activities ran
6. Schedules the `SoftToHardTransitionWorkflow` with a delay matching the grace window

### 4.4 Period locking

`PeriodLockService` is a Hibernate interceptor that runs on every persist/merge of a financially relevant entity. Logic:

```
For each entity with a business date (policy.effective_date / claim.incident_date / receipt.posting_date / etc.):
  Find the closure_period with matching closure_type and date range
  If period is HARD_CLOSED → throw PeriodHardClosedException (block always)
  If period is SOFT_CLOSED:
    grace_window_end = soft_closed_at + cutoff_window_days (business days)
    if now() > grace_window_end → throw PeriodSoftClosedException (block)
    else → allow (within grace window)
  If period is REOPENED → allow (reopen state is valid)
  If period is OPEN or CLOSING → allow
```

Exceptions are caught at the service layer and surfaced as 409 Conflict with structured error messages indicating the period and required reopen action.

## 5. Temporal Workflow Design

### 5.1 ClosureWorkflow signature

```java
@WorkflowInterface
public interface ClosureWorkflow {
  @WorkflowMethod
  ClosureResult run(ClosureWorkflowInput input);

  @SignalMethod
  void cancelClosure();

  @QueryMethod
  ClosureProgress currentProgress();
}
```

Each closure type has its own workflow class extending a shared base — they differ in which activities they execute and how they sequence them. EOM, EOQ, Half-Year, and EOY all share most of the financial close steps but layer different regulatory + final-state activities on top.

### 5.2 Activity registration

Each business module exposes its closure activities as Spring `@Component` beans implementing:

```java
public interface ClosureActivity {
  String getName();
  ClosureActivityResult execute(ClosureActivityContext context);
  default int getBatchSize() { return 1000; }
  default boolean isIdempotent() { return true; }
}
```

The `ClosureWorkflow` looks up the bean by name from the registry and invokes it as a Temporal Activity (so failures retry with backoff, idempotency carries activity guarantees).

### 5.3 SoftToHardTransitionWorkflow

Started after a SOFT_CLOSED period transition. Sleeps for the grace window (configured per closure type), then:

1. Checks if any reopen events occurred during the grace window
2. If reopened, resets the workflow timer to the new soft-close date
3. If grace window expired or NAICOM submission `acceptance_received_at` is set, transitions to HARD_CLOSED
4. Records the transition reason (TIME / REGULATORY_ACCEPTANCE / FORCE_PLATFORM)

## 6. Authorization And Approval

### 6.1 Permissions

| Permission | Granted to | Allows |
| --- | --- | --- |
| `closure:run_eod` | All System Admins | Start an EOD closure |
| `closure:run_eom` | Finance Manager + above | Start an EOM closure |
| `closure:run_eoq` | Finance Manager + above | Start an EOQ closure |
| `closure:run_half_year` | Finance Manager + above | Start a Half-Year closure |
| `closure:run_eoy` | Finance Manager + above | Start an EOY closure |
| `closure:approve_soft` | Finance Manager | Approve a soft-close run |
| `closure:approve_hard` | CFO | Approve hard-close transition (post regulatory acceptance or grace expiry) |
| `closure:reopen` | CFO | Authorise a soft-closed period reopen |
| `closure:platform_force` | PLATFORM_ADMIN | Force-close any tenant from the platform admin console |
| `closure:platform_view` | PLATFORM_ADMIN | View the cross-tenant oversight dashboard |

### 6.2 Approval workflow

Soft-close approval is single-level (Finance Manager). Hard-close transitions are CFO-only. Reopens require CFO + reason. Implementation reuses the existing `cia-workflow` Temporal approval pattern — the same one already used for policy/claim/payment approvals.

## 7. Multi-Tenancy Considerations

- Each tenant has independent closure state. Closure periods, runs, locks, and journal entries all live in the **tenant schema**.
- The `closure_activity` registry lives in the **public** schema (shared metadata), with per-tenant overrides in the tenant schema.
- Platform admin oversight queries cross schemas via a dedicated `PlatformClosureViewService` that iterates over `public.tenants` and aggregates per-tenant period state. This is the only cross-tenant read path; it never writes.
- Force-close from the platform admin runs the same `ClosureOrchestrationService.startClosure(...)` but with `trigger_source = FORCE_PLATFORM` and the platform admin's user ID — preserving the audit trail.

## 8. Frontend Design (Admin UI)

A new module `apps/back-office/src/modules/closures/` with these pages:

| Route | Page | Purpose |
| --- | --- | --- |
| `/closures` | `ClosuresHomePage` | Period summary cards (today's EOD, this month's EOM, etc.), recent runs, pending approvals |
| `/closures/run/:type` | `RunClosurePage` | Confirmation dialog → starts closure → real-time progress UI streaming from `ClosureProgressService` |
| `/closures/:periodId` | `ClosureDetailPage` | Per-period detail: activities executed, journal entries posted, regulatory submissions generated, lock status, reopen history |
| `/closures/approvals` | `ClosureApprovalsPage` | Pending approval queue (soft-close, hard-close, reopen) |
| `/closures/configuration` | `ClosureConfigurationPage` | Tenant fiscal year config; per-closure-type schedule overrides; per-activity enable/disable |
| `/investments` | `InvestmentsHomePage` | Holdings dashboard, valuation history, ECL summary |
| `/investments/instruments` | `InstrumentsPage` | Instrument master data CRUD |
| `/investments/holdings/:id` | `HoldingDetailPage` | Per-holding history: acquisitions, valuations, accruals, classifications, ECL stages |

Platform admin gets a separate page under the platform admin console:

| Route | Page | Purpose |
| --- | --- | --- |
| `/platform/closures` | `PlatformClosureOverviewPage` | Cross-tenant view: which tenants have closed for which periods; NAICOM deadline countdown; force-close tools |

## 9. Migration Strategy

The first Flyway migration introduces all the new tables (chart of accounts, journal entries, closure tables, IFRS 17 measurement tables, IFRS 9 instrument tables, etc.). For existing tenants, an additional Flyway migration:

1. Seeds the chart of accounts with a Nigerian-insurance default mapping
2. Creates retro-active journal entries for all in-force policies' premium recognition (one-time backfill)
3. Initialises closure-period rows for the current fiscal year
4. Sets the tenant's fiscal year config based on existing policy data (best-effort detection; admin can override)

Existing policies, claims, debit/credit notes, and receipts remain unchanged — they gain a `period_assignment_date` derived getter. No existing data is rewritten.

## 10. Open Questions Surfacing During Implementation

These are not gating scope but will need resolution as work proceeds:

- **NAICOM submission templates:** The actual filed forms (XBRL? Excel? PDF?) need either real templates or a regulatory authority. Likely workstream owner.
- **Risk adjustment refinement:** Bootstrap from claims triangles initially; production calibration may need an actuarial review.
- **Onerous test threshold tunables:** Default to a strict "expected loss component > 0" trigger; review if false positives occur.
- **NAICOM submission auto-submit:** Generate-only in phase 1, integrate with NAICOM API in a future phase.
- **ECL credit-rating thresholds:** Initial configuration uses static rating bands; review with risk team.

## 11. Decisions Snapshot — Who Owns What

| Decision | Decided | Authority |
| --- | --- | --- |
| Activity menu | 2026-05-09 | User (Q6) |
| Approval levels | 2026-05-09 | User (Q7a) |
| Trigger model | 2026-05-09 | User (Q7b) |
| Fiscal year-end | 2026-05-09 | User (Q7c) |
| Period assignment by business date | 2026-05-09 | User (Q7d) |
| Investments under IFRS 9 | 2026-05-09 | User (Q7e) |
| Reinsurance contracts held under PAA | Implementation | This document |
| Risk adjustment via 75th-percentile confidence-level | Implementation | This document |
| ECL 12-month default with stage-driven escalation | Implementation | This document |
| Reopen authority = CFO | Implementation | This document |
| Closure activity registry pattern | Implementation | This document |

Items in the second group will be open for review when the corresponding code lands.

## Related Documents

- `period-end-closures-implementation-plan.md` — phasing, dependencies, critical path
- `production-readiness-tracker.md` — broader production-readiness context (some closure dependencies — chart of accounts, period locking — were noted as gaps in earlier phases)
- `pii-classification.md` — IFRS 17 / IFRS 9 disclosure data is generally non-PII; investment instrument data may contain counterparty PII (issuer, broker) requiring NDPR consideration
