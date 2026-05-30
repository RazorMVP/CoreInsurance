# Reports loss-ratio premium input (cross-entity by-class aggregate) — design spec

**Status:** Approved by user 2026-05-30 (brainstorm Q1–Q5 + whole-design approval).
**Drains backlog row:** `reports-loss-ratio-premium-input` (P3).
**Builds on:** the reports query-builder (Option-A projection + aggregation slices, on `main`).

---

## 1. Goal

Make the 3 CLAIMS-source ratio reports — **Loss Ratio Report**, **Combined Ratio Report**, **Annual Revenue Account (NAICOM)** — compute their `loss_ratio` / `combined_ratio` columns, which are currently always `0.00`. `ReportQueryBuilder.applyComputedFields()` computes those from per-row keys `premium_earned`, `claims_incurred`, and `expenses`, but the reports run on the CLAIMS source, which carries no premium and the configs never declare those keys — so the ratio denominators are absent and `computeRatio` returns 0. This slice adds a cross-entity by-class aggregate data source that supplies all three keys and re-seeds the 3 reports onto it.

## 2. Problem (investigated 2026-05-30)

- `applyComputedFields`: `loss_ratio = claims_incurred ÷ premium_earned × 100`; `combined_ratio = (claims_incurred + expenses) ÷ premium_earned × 100` (`computeCombinedRatio` reads `claims_incurred`, `expenses`, `premium_earned`).
- The 3 reports declare `[class_of_business, reserve_amount, loss_ratio*]` (Combined Ratio adds `combined_ratio*`) — no `premium_earned` / `claims_incurred` / `expenses`, and the CLAIMS source has no premium column. So every ratio is 0.
- Premium lives on `policies`, incurred claims on `claims`, loss-adjustment expenses on `claim_expenses` (→ `claims` → class). A single top-level `WHERE` date filter (the generic filter loop) cannot period-filter such a cross-entity aggregate because policy dates and claim dates are on different rows.

## 3. Decisions (brainstorm)

| # | Decision | Choice | Rationale |
|---|---|---|---|
| Q1 | Data source mechanism + period filtering | **UNION-ALL event-stream `BASE_QUERIES` source** | Flattening policies/claims/claim_expenses into one row stream, each row carrying a single `event_date`, lets `date_from`/`date_to` filter at the top level exactly like `TRIAL_BALANCE` / `RM_COMMISSION`. No new filter plumbing; per-period loss ratios. |
| Q2 | `claims_incurred` | **`SUM(reserve_amount)`** | Estimated ultimate cost — the conventional reserves-as-incurred proxy; already what these reports label "Claims Incurred". |
| Q3 | `expenses` | **`SUM(claim_expenses.amount)` where `status='APPROVED'`** | Real approved loss-adjustment expenses; cancelled/pending excluded. |
| Q4 | `premium_earned` | **`SUM(total_premium)` (gross written)** | Consistent with the existing premium reports (`premium → p.total_premium`); written-not-earned is a documented proxy (no earned-premium calc exists). |
| Q5 | Scope of reports fixed | **All 3, incl. Annual Revenue Account** | The new source exists anyway; all 3 become correct. The Module 11 "Annual Revenue Account (NAICOM)" is a lightweight per-class management view, distinct from the canonical GL-driven Module 12 `AnnualRevenueAccountEngine` (N01), which remains the regulatory source of truth. |

**Definitional note (not an approximation-to-fix):** `combined_ratio` here = (incurred claims + approved loss-adjustment expenses) ÷ gross written premium. Acquisition / management expenses are **out of this report's scope by design** — they live in the GL (the Module 12 engines' domain), not the Module 11 report query path. This is what the column measures; it is not a tracked gap.

## 4. Architecture

All builder changes are in `cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportQueryBuilder.java`; the re-seed is a new Flyway migration in `cia-api`.

### 4.1 New `DataSource.UNDERWRITING_PERFORMANCE`

Add the enum value to `com.nubeero.cia.reports.domain.DataSource`. It is a fixed-aggregate source (a `BASE_QUERIES` entry, NOT a business/dynamic `SOURCE_COLUMNS` source), so `isBusinessSource(ds)` is false → `execute()` uses `BASE_QUERIES.get(ds)` + `BASE_QUERY_TAILS.get(ds)`; the business aggregation path (`buildBusinessGroupBy`) is not involved.

### 4.2 `BASE_QUERIES` entry (UNION-ALL event stream)

```sql
SELECT ev.cob AS class_of_business,
       SUM(ev.premium_earned)  AS premium_earned,
       SUM(ev.claims_incurred) AS claims_incurred,
       SUM(ev.expenses)        AS expenses
FROM (
  SELECT class_of_business_name AS cob, class_of_business_id AS cob_id,
         total_premium AS premium_earned,
         CAST(0 AS DECIMAL(18,2)) AS claims_incurred,
         CAST(0 AS DECIMAL(18,2)) AS expenses,
         created_at AS event_date
    FROM policies WHERE deleted_at IS NULL
  UNION ALL
  SELECT class_of_business_name, class_of_business_id,
         CAST(0 AS DECIMAL(18,2)), reserve_amount, CAST(0 AS DECIMAL(18,2)), reported_date
    FROM claims WHERE deleted_at IS NULL
  UNION ALL
  SELECT cl.class_of_business_name, cl.class_of_business_id,
         CAST(0 AS DECIMAL(18,2)), CAST(0 AS DECIMAL(18,2)), ce.amount, ce.created_at
    FROM claim_expenses ce JOIN claims cl ON cl.id = ce.claim_id
    WHERE ce.deleted_at IS NULL AND ce.status = 'APPROVED'
) ev WHERE 1=1
```

- Per the `BASE_QUERIES` contract, the string ends at the last `WHERE` condition (`WHERE 1=1`); the filter loop appends ` AND ev.event_date >= ?` etc.; the GROUP BY lives in `BASE_QUERY_TAILS`.
- Explicit `CAST(0 AS DECIMAL(18,2))` keeps the UNION-ALL column types unambiguous (the non-contributing measure in each branch).
- `claim_expenses` filters `status = 'APPROVED'` (Q3); each branch keeps its own `deleted_at IS NULL`.

### 4.3 `BASE_QUERY_TAILS` entry

```
DataSource.UNDERWRITING_PERFORMANCE → "GROUP BY ev.cob"
```

### 4.4 Filter helpers (exhaustive switches — adding the enum is compile-enforced)

- `createdAtCol(UNDERWRITING_PERFORMANCE)` → `"ev.event_date"` (so `date_from`/`date_to` inject `AND ev.event_date >= ?` / `< ?` at the top level).
- `statusCol(UNDERWRITING_PERFORMANCE)` → `null` (an aggregate has no single per-row status filter).
- `cobFilterCol(UNDERWRITING_PERFORMANCE)` → `"ev.cob_id"` (the optional `class_of_business_id` filter narrows the event stream pre-aggregation).

### 4.5 Positional mapping

`applyComputedFields` maps non-computed `fields[i] ← row[i]`. The SELECT emits exactly 4 columns in order `[class_of_business, premium_earned, claims_incurred, expenses]`. Each re-seeded report declares its non-computed fields as a **prefix** of that order, so the positional mapping aligns and the computed `loss_ratio` / `combined_ratio` find their inputs (`premium_earned`, `claims_incurred`, `expenses`) in the row map.

## 5. Re-seed the 3 reports — new Flyway migration `V66`

`V66__reseed_ratio_reports_underwriting_performance.sql` deletes the 3 by name (type=SYSTEM) and re-inserts them onto `UNDERWRITING_PERFORMANCE`. Each preserves its existing **category** (Loss Ratio + Combined Ratio: `CLAIMS`; Annual Revenue Account: its existing value, read from V18 at plan time), **is_pinnable** flag, and **filters** (`date_from` required, `date_to` required, `class_of_business_id` optional MULTI_SELECT). New configs (non-computed field order = SELECT prefix):

- **Loss Ratio Report** — fields `[class_of_business STRING, premium_earned MONEY, claims_incurred MONEY, loss_ratio PERCENT computed]`; `groupBy:class_of_business`, `sortBy:loss_ratio` DESC; chart BAR (xAxis class_of_business, yAxis loss_ratio).
- **Combined Ratio Report** — fields `[class_of_business, premium_earned, claims_incurred, expenses MONEY, loss_ratio computed, combined_ratio PERCENT computed]`; `sortBy:combined_ratio` DESC; chart BAR (yAxis combined_ratio).
- **Annual Revenue Account (NAICOM)** — fields `[class_of_business, premium_earned, claims_incurred, expenses MONEY, loss_ratio computed]`; `sortBy:premium_earned` DESC (its old `sortBy:reserve_amount` references a now-removed field); chart BAR (yAxis premium_earned). Preserve its existing category.

Idempotent: `DELETE FROM report_definition WHERE type='SYSTEM' AND name IN (...)` then `INSERT`. (Runs after V18's bulk seed, so it replaces the 3 stale definitions on every build.)

> `groupBy` is set on these configs but is **not** what drives aggregation here — `UNDERWRITING_PERFORMANCE` is a fixed `BASE_QUERIES` aggregate (its GROUP BY is in `BASE_QUERY_TAILS`), not a business/dynamic source. `groupBy` is retained only as a frontend chart-dimension hint, consistent with the other aggregate sources.

## 6. Testing

New ITs in `BusinessReportValueIT` (already `@Transactional` / hermetic). Seed in a unique class name (shared-DB robustness):

1. **Loss ratio computes non-zero** — seed 1 policy (`total_premium` 1,000,000) + 2 claims (`reserve_amount` 300,000 + 200,000) in class `ZZ-LR-PERF`, all within the window. Run "Loss Ratio Report"; filter to the class; assert `premium_earned`=1,000,000, `claims_incurred`=500,000, `loss_ratio`=`50.00` (500k/1m). Proves the cross-entity join + the previously-0 column now computes.
2. **Combined ratio includes approved expenses** — same seed + 1 `claim_expenses` (amount 50,000, `status='APPROVED'`) + 1 (amount 99,999, `status='PENDING'` — must be excluded). Run "Combined Ratio Report"; assert `expenses`=50,000 and `combined_ratio`=`55.00` ((500k+50k)/1m).
3. **Period filter excludes out-of-window rows** — seed a policy + claim in-window and another claim dated outside [date_from,date_to]; assert the out-of-window claim is excluded from `claims_incurred` (the `ev.event_date` top-level filter works).

`SystemReportSmokeIT` already guards that all SYSTEM reports (now incl. these 3 on the new source) execute without SQL error.

## 7. CLAUDE.md update

Extend the `ReportQueryBuilder` dual-model note: a third source kind — `UNDERWRITING_PERFORMANCE`, a UNION-ALL event-stream `BASE_QUERIES` aggregate that flattens policies + claims + approved claim_expenses into a per-row stream (single `ev.event_date` for top-level period filtering) and GROUP BYs by class to feed the loss/combined-ratio computed fields. Note the metric definitions (claims_incurred = reserve_amount; expenses = approved loss-adjustment expenses; premium = gross written) and that acquisition/management expenses are out of scope by design (GL/Module-12 domain).

## 8. Backlog reconciliation

- **Drains:** `reports-loss-ratio-premium-input` (P3).
- **No new rows.** The expense scope is a documented definition, not a deferred gap; the written-vs-earned premium is a documented proxy. Neither is a tracked follow-up.
- Side-discoveries during execution follow slice discipline.
