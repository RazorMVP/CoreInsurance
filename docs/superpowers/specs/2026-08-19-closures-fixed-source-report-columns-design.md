# Fixed-Source Report Column Misalignment — Design

**Backlog item:** `closures-fixed-source-report-column-misalignment` (P1)
**Date:** 2026-08-19
**Branch:** `fix/closures-fixed-source-report-columns` (own PR — orthogonal to the FAC workstream that surfaced it)

## Goal

Make every **fixed-source** report render its data under the correct column labels. Today ~11 of the 12 CLOSURES SYSTEM reports (GENERAL_LEDGER ×3, GL_PERIOD_LOCK ×1, PAA_GROUPS ×1, IFRS17_MOVEMENT ×3, IFRS9_HOLDINGS ×1, IFRS9_CARRYING ×1, IFRS9_MOVEMENT ×1) render **column-shifted / garbled** data — every one of these SELECTs leads with an undeclared identity/date column (`je.id`, `pl.id`, `g.id`, `pma.period_id`, `h.id`, `cv.id`, `imv.period_id`) that shifts every declared field. The other fixed sources — **TRIAL_BALANCE, RM_COMMISSION, UNDERWRITING_PERFORMANCE** — are correct today only by luck of column ordering: their SELECTs happen to lead with declared columns. After this change, fixed-source column mapping is **name-based** and structurally guarded so the bug class cannot silently return.

## Root cause

`ReportQueryBuilder` has a **dual model** (`isBusinessSource(ds)` predicate):

- **Business sources** (the 6 dynamic sources: POLICIES, CLAIMS, FINANCE, REINSURANCE, CUSTOMERS, ENDORSEMENTS): `buildBusinessSql` emits the SELECT in **declared-field order**, each column `<expr> AS <field_key>` (or `NULL AS <key>`). Correct-by-construction.
- **Fixed sources** (everything in `BASE_QUERIES`): a hand-written SELECT whose column order is **not** the report's declared-field order — every fixed SELECT **leads with undeclared identity/date columns** (`je.id AS journal_entry_id`, `pma.period_id/period_start/period_end`, `g.id`, `h.id`, `lrc.id`, `pl.id`, `cv.id`).

`execute()` runs the SQL as a native query returning positional `List<Object[]>` (`:420-426`) and `applyComputedFields` zips **`declaredField[i] ← row[i]` by position** (`:437-441`). For fixed sources the leading identity columns shift every declared field, so e.g. "General Journal Listing" renders the JE UUID under the "Business Date" column. The bug has existed since V44/Phase-5 and is uncaught because `SystemReportSmokeIT` only asserts `rows != null` and `BusinessReportValueIT` never runs a fixed source.

**A column-reorder cannot fix it:** one `BASE_QUERIES` SELECT serves multiple reports that declare different, scattered field subsets (GENERAL_LEDGER ×3, IFRS17_MOVEMENT ×3), so there is no single column order that satisfies all — proving the fix must be name-based, not positional.

## Approach A — alias-reprojection for fixed sources

The fix is confined to the fixed-source path; the business path is byte-identical.

### 1. Core mechanism

In `execute()`, when `!isBusinessSource(ds)`:

1. Run the native query as **alias-keyed** rather than positional: `entityManager.createNativeQuery(sql, jakarta.persistence.Tuple.class)`.
2. **Reproject** each `Tuple` into declared-field order via a new helper `reprojectByAlias(List<Tuple>, List<ReportField> rawFields)`:
   - Per row, build a `Map<String,Object>` of **lowercased column label → value** by iterating `tuple.getElements()` (each `TupleElement.getAlias()` is the SQL column label; Postgres lowercases unquoted aliases, so lowercase-normalise on both sides for a robust match).
   - Emit `Object[]` where slot *i* = `map.get(rawFields.get(i).getKey().toLowerCase())`, or `null` if absent.
3. Feed that `List<Object[]>` (now in declared-field order) into the **unchanged** positional `applyComputedFields`. It is now correct-by-construction for fixed sources exactly as it already is for business sources.

Leading identity/date columns (`journal_entry_id`, `period_id`, `g.id`, …) are simply never referenced by any declared field key and are dropped. The business branch keeps the existing `getResultList()` → `List<Object[]>` positional path untouched.

**Why `Tuple` and not `setTupleTransformer`:** `createNativeQuery(sql, Tuple.class)` is standard JPA (no Hibernate `unwrap`), and `Tuple.getElements()` exposes the aliases explicitly so we control casing. No existing precedent in the codebase; this introduces the first alias-keyed native fetch.

### 2. Alias hygiene

Name-based reprojection is correct only if **every declared non-computed field key of a fixed-source report matches a SELECT column alias** for that source. Most already align (`account_code`, `portfolio_code`, `class_of_business`, …). The plan will **audit each fixed-source SELECT against the declared field keys of every report on it** (from the V18/V44/V64/V66 seed migrations) and, where a column's label ≠ the declared key, add/correct `AS <key>` in the SELECT string. This is an **engine-side** change (SELECT alias strings); **no** report-definition or Flyway-seed changes — the declared field keys are the fixed contract; the SELECT aliases are aligned to them.

### 3. Anti-regression guard

Add a guard IT (`FixedSourceReportAliasGuardIT`) that, for **every** fixed-source SYSTEM report, asserts each declared non-computed field key resolves to a column label in that source's live SELECT — failing loudly on any future mismatch. This closes the exact hole (no structural check) that let the misalignment rot since V44. **Mechanism (pinned):** wrap the source's `BASE_QUERIES` SELECT as `SELECT * FROM (<sql>) _cols LIMIT 0`, run it, and read the column labels from JDBC `ResultSetMetaData.getColumnLabel(...)` (zero rows, no seed data needed, no brittle SQL string-parsing); assert every declared non-computed field key (lowercased) ⊆ the label set.

### 4. Test strategy

- **`FixedSourceReportValueIT`** (new, mirrors `BusinessReportValueIT`): per fixed-source SYSTEM report, seed representative rows through the real GL/PAA/IFRS9 write paths (reuse existing IT seed helpers), run the report, and assert **specific column→value labeling** — e.g. GENERAL_LEDGER "General Journal Listing": the `business_date` cell is a date (not the JE UUID), `account_code` is the code, `source_module` is the module. At least one value assertion per fixed-source report, targeting the columns the shift previously corrupted.
- **`SystemReportSmokeIT`** stays (broad rows-non-null smoke over all SYSTEM reports).
- The guard IT (§3) runs for every fixed-source report.

## Scope

**In scope — every source in `BASE_QUERIES`** (the exact `isBusinessSource` complement): `TRIAL_BALANCE`, `GENERAL_LEDGER`, `GL_PERIOD_LOCK`, `PAA_LRC`, `PAA_GROUPS`, `IFRS17_MOVEMENT`, `IFRS9_HOLDINGS`, `IFRS9_CARRYING`, `IFRS9_MOVEMENT`, `RM_COMMISSION`, `UNDERWRITING_PERFORMANCE`. The plan enumerates the exact SYSTEM reports on each from the seed migrations.

**Non-goals:**
- Business sources (the 55 dynamic-source reports) — untouched; already correct.
- No report-definition / Flyway changes; the fix is engine-side SELECT-alias + mapping only.
- No change to the dual business/fixed model, filters, computed-field formulas, sort handling, or the ORDER-BY sort-column append (name-based reprojection is inherently robust to a trailing sort column — it is unreferenced by declared keys and dropped).

## Risk

Low. The business path is byte-identical (regression-proof by construction — it never enters the new branch). The only behavior change is fixed-source column labeling: currently wrong → correct, pinned by the new value IT + guard IT. The `contract_nature` column the FAC workstream appended to the PAA_GROUPS / IFRS17_MOVEMENT SELECTs will surface correctly once a report declares that field key (today it is the harmless trailing column).

## Acceptance criteria

1. Every fixed-source SYSTEM report renders each declared column under its correct label (verified by `FixedSourceReportValueIT` value assertions).
2. The alias guard IT passes for every fixed-source report and would fail on any future field-key ↔ alias mismatch.
3. Business-source reports are unchanged (`BusinessReportValueIT` + `SystemReportSmokeIT` stay green).
4. Full `mvn verify` green (no regression across the reactor).
5. No Flyway migration and no report-definition change.

## Files (indicative — the plan refines)

- Modify: `cia-reports/.../service/ReportQueryBuilder.java` — fixed-source branch of `execute()` + `reprojectByAlias()` helper; targeted SELECT-alias corrections in `BASE_QUERIES`.
- Test: `cia-api/src/test/.../reports/FixedSourceReportValueIT.java` (new) + alias guard (new IT or method).
- Unchanged: business-source path, `applyComputedFields` positional logic, all report seed migrations.
