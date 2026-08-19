# Fixed-Source Report Column Misalignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every fixed-source report map columns by SELECT alias (not by position), so the ~10 garbled CLOSURES reports render correct data, guarded so the bug class cannot silently return.

**Architecture:** `ReportQueryBuilder.execute()` gains a fixed-source branch that fetches results as `jakarta.persistence.Tuple` and reprojects each row into declared-field order by lowercased column alias, feeding the unchanged positional `applyComputedFields`. Business sources keep their existing positional path (already correct-by-construction). A structural guard IT asserts every fixed-source report's declared field keys resolve to a SELECT column label; a value IT asserts correct column→value labeling per report.

**Tech Stack:** Java 21, Spring Boot 3.5.14, Hibernate 6 (`createNativeQuery(sql, Tuple.class)`), JUnit 5 + Testcontainers (Postgres), AssertJ.

**Spec:** `docs/superpowers/specs/2026-08-19-closures-fixed-source-report-columns-design.md`

## Global Constraints

- **Business-source path byte-identical.** Only the `!isBusinessSource(ds)` branch changes; the 55 business reports must stay green (`BusinessReportValueIT`, `SystemReportSmokeIT`).
- **No Flyway migration, no report-definition change.** The declared field keys are the fixed contract; align engine SELECT aliases to them — never edit V18/V44/V64/V66 seeds.
- **Name-based mapping is the invariant for fixed sources.** Positional mapping stays only for business sources.
- **`applyComputedFields` positional logic is unchanged** — the fix reorders fixed-source rows *before* it, so it becomes correct-by-construction there too.
- Alias matching is **case-insensitive** (lowercase both sides — Postgres lowercases unquoted aliases; field keys are lowercase_snake).
- Fixed sources (the exact `isBusinessSource` complement / `BASE_QUERIES` keys): `TRIAL_BALANCE`, `GENERAL_LEDGER`, `GL_PERIOD_LOCK`, `PAA_LRC`, `PAA_GROUPS`, `IFRS17_MOVEMENT`, `IFRS9_HOLDINGS`, `IFRS9_CARRYING`, `IFRS9_MOVEMENT`, `RM_COMMISSION`, `UNDERWRITING_PERFORMANCE`. `PAA_LRC` has **no** SYSTEM report (skip in value coverage; guard/mechanism still cover it harmlessly).

**Fixed-source SYSTEM report inventory (16 reports), with the "canary" column** (the first declared field, which the leading-identity-column shift corrupts today):

| # | data_source | report name | canary field | shifts today? |
|---|---|---|---|---|
| 1 | TRIAL_BALANCE | Trial Balance | account_code | no (SELECT leads with declared cols) |
| 2 | GENERAL_LEDGER | General Journal Listing | business_date | **yes** (← `je.id` UUID) |
| 3 | GENERAL_LEDGER | Account Movement Statement | business_date | **yes** |
| 4 | GENERAL_LEDGER | Premium Receivable ECL Schedule | business_date | **yes** |
| 5 | GL_PERIOD_LOCK | Period Lock Audit Trail | period_start | **yes** (← `pl.id`) |
| 6 | PAA_GROUPS | Contract Groups Listing | portfolio_code | **yes** (← `g.id`) |
| 7 | IFRS17_MOVEMENT | LRC Roll-forward Schedule | portfolio_name | **yes** (← `pma.period_id`) |
| 8 | IFRS17_MOVEMENT | LIC Roll-forward Schedule | portfolio_name | **yes** |
| 9 | IFRS17_MOVEMENT | Insurance Service Result Summary | portfolio_name | **yes** |
| 10 | IFRS9_HOLDINGS | Investment Holdings Schedule | isin | **yes** (← `h.id`) |
| 11 | IFRS9_CARRYING | Investment Carrying Value Movement | period_start | **yes** (← `cv.id`) |
| 12 | IFRS9_MOVEMENT | §B5.5.39 Combined Movement Analysis | period_start | **yes** (← `imv.period_id`) |
| 13 | RM_COMMISSION | RM Commission Accrual | relationship_manager_name | no |
| 14 | UNDERWRITING_PERFORMANCE | Loss Ratio Report | class_of_business | no |
| 15 | UNDERWRITING_PERFORMANCE | Combined Ratio Report | class_of_business | no |
| 16 | UNDERWRITING_PERFORMANCE | Annual Revenue Account (NAICOM) | class_of_business | no |

---

### Task 1: Alias-reprojection engine mechanism

**Files:**
- Modify: `cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportQueryBuilder.java` (the result-handling tail of `execute()`, ~lines 420-427; add helper `reprojectByAlias`)
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/reports/FixedSourceReportValueIT.java` (new)

**Interfaces:**
- Consumes: `config.getFields()` → `List<ReportField>` with `.getKey()`, `.isComputed()`; `isBusinessSource(DataSource)`; `applyComputedFields(List<Object[]>, ReportConfig)`.
- Produces: `List<Object[]> reprojectByAlias(List<Tuple> tuples, ReportConfig config)` — rows in declared-non-computed-field order, aligned to the existing positional `applyComputedFields`.

- [ ] **Step 1: Write the failing value test (GENERAL_LEDGER "General Journal Listing")**

Create `FixedSourceReportValueIT`. It extends `FinanceWebItSupport` (same base as `BusinessReportValueIT`), is `@Transactional` (JDBC seeds roll back per method; the report runner shares the transaction so uncommitted seeds are visible before rollback), and `@Autowired ReportRunnerService reportRunnerService` + `@Autowired JdbcTemplate jdbc` (exactly as `BusinessReportValueIT`). Copy its two helpers verbatim:

```java
private UUID reportId(String name) {
    return jdbc.queryForObject(
        "SELECT id FROM report_definition WHERE name = ? AND type = 'SYSTEM'", UUID.class, name);
}
private List<Map<String, Object>> run(String reportName, Map<String, String> filters) {
    ReportRunRequest req = new ReportRunRequest();
    req.setReportId(reportId(reportName));
    req.setFilters(filters);
    return reportRunnerService.run(req).getRows();
}
private static final Map<String,String> WIDE = Map.of("date_from","2000-01-01","date_to","2100-01-01");
```

Seed one journal entry + line on a real COA account and assert the `business_date` column holds a date, not the JE UUID:

```java
@Test
void generalJournalListing_businessDateColumnHoldsDate_notUuid() {
    // COA is V32-seeded in the test tenant; look up a real postable account id by code.
    UUID accountId = jdbc.queryForObject(
        "SELECT id FROM chart_of_account WHERE code = '1330'", UUID.class);
    UUID jeId = UUID.randomUUID();
    jdbc.update("INSERT INTO journal_entry " +
        "(id, posting_date, business_date, source_module, source_event_type, source_reference, narrative, status) " +
        "VALUES (?, DATE '2026-03-15', DATE '2026-03-15', 'policy', 'POLICY_APPROVED', 'POL-1', 'test', 'POSTED')",
        jeId);
    jdbc.update("INSERT INTO journal_entry_line " +
        "(id, journal_entry_id, account_id, debit_amount, credit_amount) VALUES (?, ?, ?, 1000.00, 0.00)",
        UUID.randomUUID(), jeId, accountId);

    List<Map<String,Object>> rows = run("General Journal Listing", WIDE);

    assertThat(rows).isNotEmpty();
    Object businessDate = rows.get(0).get("business_date");
    // Correct: a date/timestamp. Bug (positional): the JE UUID string/UUID.
    assertThat(businessDate).isNotInstanceOf(UUID.class);
    assertThat(String.valueOf(businessDate)).startsWith("2026-03-15");
    // account_code must be the code, not a downstream-shifted value.
    assertThat(String.valueOf(rows.get(0).get("account_code"))).isEqualTo("1330");
}
```

- [ ] **Step 2: Run — expect FAIL**

Run: `mvn -pl cia-api -am verify -Dit.test=FixedSourceReportValueIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -DfailIfNoTests=false -Dmaven.compiler.fork=true`
Expected: FAIL — `business_date` holds the JE UUID (positional mapping puts `je.id` in slot 0).

- [ ] **Step 3: Implement the reprojection**

In `ReportQueryBuilder`, add imports `jakarta.persistence.Tuple`, `jakarta.persistence.TupleElement`, `java.util.Locale`, `java.util.HashMap`. Replace the result-handling tail of `execute()` (currently):

```java
        Query query = entityManager.createNativeQuery(sql.toString());
        for (int i = 0; i < params.size(); i++) {
            query.setParameter(i + 1, params.get(i));
        }
        query.setMaxResults(maxRows);

        List<Object[]> rawRows = query.getResultList();
        return applyComputedFields(rawRows, config);
```

with:

```java
        Query query = isBusinessSource(ds)
                ? entityManager.createNativeQuery(sql.toString())
                : entityManager.createNativeQuery(sql.toString(), Tuple.class);
        for (int i = 0; i < params.size(); i++) {
            query.setParameter(i + 1, params.get(i));
        }
        query.setMaxResults(maxRows);

        if (isBusinessSource(ds)) {
            // Business SELECT is emitted in declared-field order (buildBusinessSql) — positional is correct.
            List<Object[]> rawRows = query.getResultList();
            return applyComputedFields(rawRows, config);
        }
        // Fixed source: the hand-written BASE_QUERIES SELECT is NOT in declared-field order and
        // leads with undeclared identity/date columns, so map each declared field key to its
        // column by alias (case-insensitive), then feed the unchanged positional applyComputedFields.
        List<Tuple> tuples = query.getResultList();
        return applyComputedFields(reprojectByAlias(tuples, config), config);
```

Add the helper:

```java
    /**
     * Reproject fixed-source Tuple rows into declared-non-computed-field order by matching
     * each field key to the SELECT column of the same (lowercased) alias — NULL if the source
     * SELECT has no such alias (a mismatch the alias guard IT forbids). This makes the
     * downstream positional applyComputedFields correct for fixed sources exactly as
     * buildBusinessSql's declared-order SELECT makes it correct for business sources.
     */
    private List<Object[]> reprojectByAlias(List<Tuple> tuples, ReportConfig config) {
        List<String> keys = config.getFields() == null ? List.of()
                : config.getFields().stream()
                        .filter(f -> !f.isComputed())
                        .map(f -> f.getKey().toLowerCase(Locale.ROOT))
                        .toList();
        return tuples.stream().map(t -> {
            Map<String, Object> byLabel = new HashMap<>();
            for (TupleElement<?> el : t.getElements()) {
                if (el.getAlias() != null) {
                    byLabel.put(el.getAlias().toLowerCase(Locale.ROOT), t.get(el));
                }
            }
            Object[] row = new Object[keys.size()];
            for (int i = 0; i < keys.size(); i++) {
                row[i] = byLabel.get(keys.get(i));
            }
            return row;
        }).toList();
    }
```

- [ ] **Step 4: Run — expect PASS**

Run: same command as Step 2. Expected: PASS. Then run `mvn -pl cia-api -am verify -Dit.test=BusinessReportValueIT,SystemReportSmokeIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -DfailIfNoTests=false -Dmaven.compiler.fork=true` — both stay green (business path unchanged).

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportQueryBuilder.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/reports/FixedSourceReportValueIT.java
git commit -m "fix(reports): fixed-source column mapping by alias, not position (GENERAL_LEDGER proof)"
```

---

### Task 2: Anti-regression alias guard + alias hygiene

**Files:**
- Modify: `cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportQueryBuilder.java` (add public `fixedSourceColumnLabels(DataSource)`; correct `BASE_QUERIES` aliases where a declared key has no matching label)
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/reports/FixedSourceReportAliasGuardIT.java` (new)

**Interfaces:**
- Consumes: `BASE_QUERIES` (private map), `entityManager`, `isBusinessSource(DataSource)`.
- Produces: `public List<String> fixedSourceColumnLabels(DataSource ds)` — lowercased column labels of the source's SELECT (empty for business sources).

- [ ] **Step 1: Add the column-label accessor**

In `ReportQueryBuilder`, add (uses a `LIMIT 0` subquery so no rows/seed are needed; reads JDBC `ResultSetMetaData`):

```java
    /** Lowercased column labels of a fixed source's SELECT (LIMIT 0, no rows). Empty for business sources. */
    public List<String> fixedSourceColumnLabels(DataSource ds) {
        if (isBusinessSource(ds)) return List.of();
        String base = BASE_QUERIES.get(ds);
        String probe = "SELECT * FROM (" + base + ") _cols LIMIT 0";
        return entityManager.unwrap(org.hibernate.Session.class).doReturningWork(conn -> {
            try (java.sql.PreparedStatement ps = conn.prepareStatement(probe);
                 java.sql.ResultSet rs = ps.executeQuery()) {
                java.sql.ResultSetMetaData md = rs.getMetaData();
                List<String> labels = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    labels.add(md.getColumnLabel(i).toLowerCase(Locale.ROOT));
                }
                return labels;
            }
        });
    }
```

- [ ] **Step 2: Write the guard IT (expect it to reveal any alias gaps)**

`FixedSourceReportAliasGuardIT extends FinanceWebItSupport`, `@Autowired ReportQueryBuilder reportQueryBuilder` + the SYSTEM-report source used by `SystemReportSmokeIT`:

```java
@Test
void everyFixedSourceReportFieldKeyResolvesToASelectAlias() {
    List<ReportDefinition> defs = reportDefinitionRepository.findAll().stream()
            .filter(d -> d.getType() == ReportType.SYSTEM)                       // match SystemReportSmokeIT's filter
            .filter(d -> !isBusinessSource(d.getDataSource()))                    // fixed sources only (see note)
            .toList();
    assertThat(defs).as("15+ fixed-source SYSTEM reports").hasSizeGreaterThanOrEqualTo(15);

    Map<DataSource, List<String>> labelCache = new HashMap<>();
    List<String> problems = new ArrayList<>();
    for (ReportDefinition def : defs) {
        List<String> labels = labelCache.computeIfAbsent(def.getDataSource(),
                reportQueryBuilder::fixedSourceColumnLabels);
        for (ReportField f : def.getConfig().getFields()) {
            if (f.isComputed()) continue;
            if (!labels.contains(f.getKey().toLowerCase(Locale.ROOT))) {
                problems.add(def.getName() + " [" + def.getDataSource() + "] field '" + f.getKey()
                        + "' has no matching SELECT alias " + labels);
            }
        }
    }
    assertThat(problems).as("fixed-source field keys must all resolve to a SELECT alias").isEmpty();
}
```
Note: `isBusinessSource` is private in `ReportQueryBuilder`; add a `public boolean isBusinessSource(DataSource)` accessor (or a `public boolean isFixedSource(DataSource)`), or reproduce the `SOURCE_COLUMNS.containsKey` check via a small public predicate. Expose the minimal predicate the IT needs.

- [ ] **Step 3: Run — expect FAIL listing the mismatches**

Run: `mvn -pl cia-api -am verify -Dit.test=FixedSourceReportAliasGuardIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -DfailIfNoTests=false -Dmaven.compiler.fork=true`
Expected: FAIL (or PASS if every alias already matches) — the failure message enumerates exactly which `(report, field)` pairs lack a matching SELECT alias.

- [ ] **Step 4: Fix `BASE_QUERIES` aliases for each reported mismatch**

For every `(source, field)` the guard reports, add/correct the alias in that source's `BASE_QUERIES` SELECT so the column's label equals the declared field key. Example shape: if `GL_PERIOD_LOCK` declares `lock_type` but its SELECT has `pl.lock_kind AS lock_type` missing, change the column to `... AS lock_type`. **Do not** reorder columns, remove the leading identity columns, or touch report seeds — only align aliases. Re-run Step 3 until the guard passes.

- [ ] **Step 5: Run — expect PASS**

Run: same as Step 3 → PASS. Then re-run `FixedSourceReportValueIT` (Task 1) to confirm no alias change broke the GL proof.

- [ ] **Step 6: Commit**

```bash
git add cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportQueryBuilder.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/reports/FixedSourceReportAliasGuardIT.java
git commit -m "test(reports): guard every fixed-source report's field keys resolve to a SELECT alias + align aliases"
```

---

### Task 3: Per-report value coverage for all fixed sources

**Files:**
- Modify: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/reports/FixedSourceReportValueIT.java` (add one value test per remaining fixed-source report)

**Interfaces:**
- Consumes: the `run(name, filters)` + `reportId(name)` helpers and the `jdbc.update(...)` seed pattern from Task 1; backing-table DDL per source (in the referenced migrations); existing seed patterns in `MovementAnalysisServiceIT` (PAA), the IFRS9 engine ITs (investment tables), the reconciliation-gate IT (GL).

- [ ] **Step 1: Add a value test per remaining report (15), each asserting the canary column type**

For each report in the Global-Constraints inventory table (rows 1, 3–16 — row 2 done in Task 1), add a test that: seeds the minimum backing rows for that `data_source`, runs `run("<report name>", WIDE)`, and asserts the **canary** column holds the correct value (never a `UUID`, and matching the seeded value). Seed each source directly via `jdbc.update("INSERT INTO <table> ...", args...)` reading the column list from the source migration (GL: V31; PAA `group_of_contracts`/`portfolio`/`paa_lrc`: V36/V78; IFRS9 `investment_holding`/`investment_carrying_value`: V39; period_lock: V31; RM_COMMISSION over `policies`: V2; UNDERWRITING_PERFORMANCE over `policies`/`claims`/`claim_expenses`: V2). Reuse the seed shape of the named existing ITs where a view is involved (`IFRS17_MOVEMENT` reads the `paa_movement_analysis` view → seed `paa_lrc`+group as `MovementAnalysisServiceIT` does; `IFRS9_MOVEMENT` reads `ifrs9_investment_movement_analysis` → seed as the IFRS9 movement IT does).

Fully-worked second example — **PAA_GROUPS "Contract Groups Listing"** (canary `portfolio_code`, shifts from `g.id` today):

```java
@Test
void contractGroupsListing_portfolioCodeColumnHoldsCode_notUuid() {
    UUID portfolioId = UUID.randomUUID();
    jdbc.update("INSERT INTO portfolio (id, code, name, contract_nature) " +
        "VALUES (?, 'FIN-MOTOR', 'Motor', 'DIRECT')", portfolioId);
    jdbc.update("INSERT INTO group_of_contracts " +
        "(id, portfolio_id, cohort_year, onerousness, status) " +
        "VALUES (?, ?, 2026, 'NOT_ONEROUS', 'OPEN')", UUID.randomUUID(), portfolioId);

    List<Map<String,Object>> rows = run("Contract Groups Listing", WIDE);

    assertThat(rows).isNotEmpty();
    assertThat(rows.get(0).get("portfolio_code")).isNotInstanceOf(UUID.class);
    assertThat(String.valueOf(rows.get(0).get("portfolio_code"))).isEqualTo("FIN-MOTOR");
    assertThat(String.valueOf(rows.get(0).get("cohort_year"))).isEqualTo("2026");
}
```
(Match `group_of_contracts` / `portfolio` columns exactly to V36/V76/V78 DDL — read them; if `group_of_contracts` requires additional NOT NULL columns, include them.)

For the **correct-today** sources (rows 1, 13–16: TRIAL_BALANCE, RM_COMMISSION, UNDERWRITING_PERFORMANCE), the value test still asserts the canary (`account_code` / `relationship_manager_name` / `class_of_business`) holds the seeded value — proving name-based mapping keeps them correct (regression cover).

- [ ] **Step 2: Run the whole fixed-source value IT — expect PASS**

Run: `mvn -pl cia-api -am verify -Dit.test=FixedSourceReportValueIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -DfailIfNoTests=false -Dmaven.compiler.fork=true`
Expected: PASS — every fixed-source report's canary column holds the right value.

- [ ] **Step 3: Commit**

```bash
git add cia-backend/cia-api/src/test/java/com/nubeero/cia/api/reports/FixedSourceReportValueIT.java
git commit -m "test(reports): per-report value assertions across all fixed-source reports"
```

---

## After all tasks

- [ ] **Full reactor verify:** `mvn -q verify -pl cia-api -am -Dmaven.compiler.fork=true` — 0 failures/errors across the reactor (report ITs + everything else green).
- [ ] **No migration / no report-def change:** `git diff --name-only main... | grep -E 'db/migration|report_definition'` returns nothing.
- [ ] **OpenAPI snapshot unchanged:** no controller change here; `docs-site/static/internal-api.json` must not be dirty.
- [ ] **cia-log session entry + backlog reconciliation:** mark `closures-fixed-source-report-column-misalignment` (P1) resolved; note `contract_nature` now surfaces on any PAA_GROUPS/IFRS17_MOVEMENT report that declares it.
- [ ] Use superpowers:finishing-a-development-branch.

## Self-review notes

- **Spec coverage:** §Core mechanism → Task 1; §Alias hygiene → Task 2 (Step 4); §Anti-regression guard → Task 2; §Test strategy (per-report value IT + smoke) → Task 1 + Task 3; §Scope (all `BASE_QUERIES` sources; PAA_LRC has no report) → Global Constraints + inventory table; §Non-goals (business untouched, no Flyway/report-def) → Global Constraints + After-all-tasks checks; §Acceptance criteria 1–5 → Tasks 1–3 + After-all-tasks.
- **Type consistency:** `reprojectByAlias(List<Tuple>, ReportConfig) → List<Object[]>` feeds the existing `applyComputedFields(List<Object[]>, ReportConfig)`; `fixedSourceColumnLabels(DataSource) → List<String>` consumed by the guard IT; `isBusinessSource`/`isFixedSource` predicate exposed public for the guard.
- **Known follow-up baked in:** the guard is operation/label-level (field-key ⊆ labels); the per-report value tests catch wrong-column-same-label cases. Together they pin the fix.
- **Risk note for the implementer:** if a fixed-source SELECT has two columns with the same lowercased alias, `reprojectByAlias`'s `byLabel` map would keep the last — the guard won't catch that (both labels present). Not expected in the current SELECTs, but if `fixedSourceColumnLabels` returns a duplicate label, flag it (the guard IT could additionally assert no duplicate labels per source).
