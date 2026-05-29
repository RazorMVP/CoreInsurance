# Reports aggregation semantics (GROUP BY + SUM) — design spec

**Status:** Approved by user 2026-05-29 (brainstorm Q1–Q2 + whole-design approval).
**Drains backlog row:** `reports-aggregation-semantics-gap` (P3).
**Adds backlog row:** `reports-loss-ratio-premium-input` (P3, data-model — out of scope here).
**Builds on:** the Option-A dynamic-projection rebuild (`SOURCE_FROM` / `SOURCE_COLUMNS` in `ReportQueryBuilder`, shipped `485d08f`).

---

## 1. Goal

Make the business SYSTEM reports that declare a `groupBy` actually aggregate in SQL (GROUP BY + SUM), instead of returning per-row data. Today `ReportQueryBuilder.execute()` only appends a GROUP BY for the two sources in `BASE_QUERY_TAILS` (TRIAL_BALANCE, RM_COMMISSION); every business source returns one row per underlying record. The frontend does **not** aggregate (`ReportChart.tsx` feeds raw rows straight into Recharts with `dataKey={xKey}/{yKey}`; `ReportResultTable` lists rows), so a "Gross Written Premium by class" bar chart currently draws one bar **per policy** with duplicate class labels, and the table lists raw policies under sum-style column headers. This slice adds correct SQL aggregation for the affected reports.

## 2. Scope of the gap (investigated 2026-05-29)

Of the 55 business SYSTEM reports, exactly **6 declare a `groupBy`** (all `class_of_business`); the other 49 are detail listings with no `groupBy` and must stay per-row. The 6:

| Report | Source | Non-computed fields (type) | Computed | sortBy |
|---|---|---|---|---|
| Gross Written Premium | POLICIES | class_of_business (STRING), product_name (STRING), premium (MONEY) | — | premium |
| Net Written Premium | POLICIES | class_of_business (STRING), premium (MONEY) | — | premium |
| Premium Earned vs Unearned | POLICIES | class_of_business (STRING), premium (MONEY) | — | premium |
| Loss Ratio Report | CLAIMS | class_of_business (STRING), reserve_amount (MONEY) | loss_ratio | loss_ratio |
| Combined Ratio Report | CLAIMS | class_of_business (STRING), reserve_amount (MONEY) | loss_ratio, combined_ratio | combined_ratio |
| Annual Revenue Account (NAICOM) | CLAIMS | class_of_business (STRING), reserve_amount (MONEY) | loss_ratio | reserve_amount |

Closures sources (TRIAL_BALANCE, RM_COMMISSION) already aggregate via `BASE_QUERY_TAILS` and are untouched.

## 3. Decisions (brainstorm)

| # | Decision | Choice | Rationale |
|---|---|---|---|
| Q1 | Scope | **Aggregation only** (user-confirmed scope cap) | GROUP BY + SUM for all 6 fully fixes the 3 premium reports and correctly aggregates `reserve_amount` for the 3 ratio reports. The ratio columns (`loss_ratio`/`combined_ratio`) need premium, which the CLAIMS source has no column for and the reports don't declare — that is a separate data-model problem (loss ratio inherently combines premiums + claims by class), logged as `reports-loss-ratio-premium-input`, NOT fixed here. |
| Q2 | Measure vs dimension classification | **Type-based** | Non-computed MONEY/NUMBER/INTEGER → SUM (measure); non-computed STRING/DATE → GROUP BY (dimension); computed → skipped in SQL. No config/schema change; leverages the existing `ReportField.type`. Consequence: GWP's `product_name` (STRING) is a second dimension → GWP groups by (class_of_business, product_name), matching its description ("by … class of business, product"). |

## 4. Architecture

All changes are in `cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportQueryBuilder.java`. No frontend, schema, or config changes.

### 4.1 Field classification helper

A shared private helper splits a report's non-computed fields into dimensions and measures by `type`, so the SELECT builder (4.2) and the GROUP BY builder (4.3) agree:

```
isMeasure(ReportField f)  ==  "MONEY".equals(t) || "NUMBER".equals(t) || "INTEGER".equals(t)
                              where t = f.getType()
```
- Dimension = non-computed field that is NOT a measure (STRING / DATE / anything else).
- Measure = non-computed field that IS a measure.
- Computed fields (`f.isComputed()`) are neither (skipped in SQL, post-processed in Java).

"Aggregate mode" for a source `ds` ⇔ `SOURCE_COLUMNS.containsKey(ds)` (business source) AND `config.getGroupBy()` is non-null/non-blank.

### 4.2 SELECT construction (`buildBusinessSql`)

Extend the existing `buildBusinessSql(ds, config)`:
- **Non-aggregate mode (no groupBy):** unchanged from `485d08f` — each non-computed field projects `expr AS sanitizedKey` (or `NULL AS key`); the sort-column injection still applies.
- **Aggregate mode:** for each non-computed field in declared order:
  - measure → `SUM(expr) AS sanitizedKey`
  - dimension → `expr AS sanitizedKey`
  - (computed fields skipped, as today)
  The **sort-column injection is suppressed** in aggregate mode (a bare injected column would be illegal under GROUP BY; none of the 6 reports need it — see 4.4). Declared-field order is preserved, so the positional `applyComputedFields()` mapping stays correct by construction. The `NULL AS key` fallback for an unmapped key still applies (no current aggregate report hits it).

### 4.3 GROUP BY construction (`buildBusinessGroupBy`)

`execute()` currently computes `String tail = BASE_QUERY_TAILS.get(ds)` after the filter loop and before ORDER BY. Change the business branch to compute its tail dynamically:

```java
String tail = SOURCE_COLUMNS.containsKey(ds)
        ? buildBusinessGroupBy(ds, config)
        : BASE_QUERY_TAILS.get(ds);
```

`buildBusinessGroupBy(ds, config)` returns:
- `""` when not in aggregate mode (no groupBy), preserving current per-row behaviour for the 49 detail reports.
- `"GROUP BY <dim expr>[, <dim expr>...]"` in aggregate mode — the dimension expressions (from `SOURCE_COLUMNS`) for the report's non-computed dimension fields, in declared order. (Group by the SQL expression, not the alias, for portability.)

This keeps the GROUP BY at the correct position (after `WHERE` filters, before `ORDER BY`), exactly where `BASE_QUERY_TAILS` already sits.

### 4.4 Interactions (verified)

- **ORDER BY:** the 6 reports sort by `premium`/`reserve_amount` (measure → `SUM(expr) AS premium`; PostgreSQL resolves the output alias in ORDER BY) or `loss_ratio`/`combined_ratio` (computed → already skipped by the existing `isComputedField` guard). No non-grouped bare-column sort exists, so the suppressed sort-injection (4.2) loses nothing. `sanitizeColumnName` still guards the sort column.
- **Filters:** `date_from`/`date_to` (e.g. `p.created_at`), `class_of_business_id` (`p.class_of_business_id`), `product_id` (`p.product_id`) are appended to the `WHERE` before the GROUP BY tail — unchanged.
- **`applyComputedFields`:** unchanged. Positional over declared non-computed fields. For the 3 ratio reports, `loss_ratio`/`combined_ratio` post-process from the row's keys; the inputs (`premium_earned`/`claims_incurred`) are absent → `computeRatio` returns `0.00` (its existing null-denominator branch), exactly as today. This slice does not regress that; the separate `reports-loss-ratio-premium-input` row tracks fixing it.

## 5. Outcome per report

- **GWP / NWP / Premium Earned vs Unearned** — fully correct: one row per group (GWP per class+product; the other two per class), `premium` = `SUM(p.total_premium)`.
- **Loss Ratio / Combined Ratio / Annual Revenue Account** — `reserve_amount` now correctly `SUM`-aggregated per class; `loss_ratio`/`combined_ratio` columns remain `0.00` (premium input not available on the CLAIMS source) — the logged follow-up.

## 6. Testing

Extend `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/reports/BusinessReportValueIT.java` (already `@Transactional`, hermetic):
1. **Premium aggregation by class** — seed 3 POLICIES across 2 classes (e.g. Fire ×2, Motor ×1) with distinct `total_premium`; run "Net Written Premium"; assert the result has exactly **2 rows** (one per class, not 3), and each `premium` equals the per-class SUM.
2. **Multi-dimension GWP** — seed 2 Fire policies with 2 different `product_name`s; run "Gross Written Premium"; assert it groups by (class, product) → 2 Fire rows keyed by product, each `premium` = that product's SUM.
3. **Ratio report aggregates reserve_amount, ratio stays 0** — seed 2 CLAIMS in one class; run "Loss Ratio Report"; assert 1 row, `reserve_amount` = SUM, `loss_ratio` = `0.00` (documents the known gap).

`SystemReportSmokeIT` already guards that all reports (incl. these 6) execute without SQL error against the real schema.

## 7. CLAUDE.md update

Extend the `ReportQueryBuilder` dual-model note: business sources with a non-blank `groupBy` aggregate in SQL — non-computed MONEY/NUMBER/INTEGER fields are `SUM`-ed, non-computed STRING/DATE fields form the GROUP BY (via `buildBusinessGroupBy`); computed fields are post-processed in Java as before. No-`groupBy` business reports stay per-row.

## 8. Backlog reconciliation

- **Drains:** `reports-aggregation-semantics-gap` (P3).
- **Adds:** `reports-loss-ratio-premium-input` (P3) — the 3 ratio reports' `loss_ratio`/`combined_ratio` are uncomputable because loss ratio = claims ÷ premium but the CLAIMS source carries no premium; needs a combined premium+claims-by-class data source (JOIN `policies`+`claims` or a dedicated `DataSource`) + re-seeding those configs with premium/claims measure fields. This is the genuine, larger data-model fix the aggregation slice deliberately excludes.
- Side-discoveries during execution follow slice discipline.
