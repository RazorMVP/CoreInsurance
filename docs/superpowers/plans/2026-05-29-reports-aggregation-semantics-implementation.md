# Reports Aggregation Semantics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the 6 business SYSTEM reports that declare a `groupBy` aggregate in SQL (GROUP BY + SUM) instead of returning per-row data the frontend renders wrong.

**Architecture:** Extend `ReportQueryBuilder`'s business-source path (the Option-A dynamic projection shipped in `485d08f`) with an aggregate mode: when a business source's report has a non-blank `config.groupBy`, non-computed MONEY/NUMBER/INTEGER fields are `SUM`-ed, non-computed STRING/DATE fields form the `GROUP BY` (built by a new `buildBusinessGroupBy` at the existing tail-insertion point), and computed fields stay Java-post-processed. Declared-field order is preserved so the positional `applyComputedFields()` keeps working.

**Tech Stack:** Java 21, Spring Boot 3, Hibernate native query, PostgreSQL, Testcontainers, JUnit 5 (failsafe ITs in `cia-api`).

---

## Background the implementer must know

- **The bug:** `ReportQueryBuilder.execute()` (in `cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportQueryBuilder.java`) only appends a GROUP BY for the two sources in `BASE_QUERY_TAILS` (TRIAL_BALANCE, RM_COMMISSION). The 6 business reports that declare `"groupBy":"class_of_business"` in their V18 config return one row per underlying record. The frontend does NOT aggregate (`ReportChart.tsx` feeds raw rows into Recharts), so e.g. "Gross Written Premium by class" draws one bar per policy with duplicate class labels.
- **The 6 reports (all `groupBy=class_of_business`):**
  - POLICIES: `Gross Written Premium` `[class_of_business STRING, product_name STRING, premium MONEY]` sortBy=premium; `Net Written Premium` `[class_of_business, premium MONEY]` sortBy=premium; `Premium Earned vs Unearned` `[class_of_business, premium MONEY]` sortBy=premium.
  - CLAIMS: `Loss Ratio Report` `[class_of_business, reserve_amount MONEY] + computed loss_ratio` sortBy=loss_ratio; `Combined Ratio Report` `[class_of_business, reserve_amount MONEY] + computed loss_ratio, combined_ratio` sortBy=combined_ratio; `Annual Revenue Account (NAICOM)` `[class_of_business, reserve_amount MONEY] + computed loss_ratio` sortBy=reserve_amount.
- **Existing relevant code** (verified current):
  - `buildBusinessSql(DataSource ds, ReportConfig config)` builds the SELECT + FROM for business sources from declared non-computed field keys via `SOURCE_COLUMNS` (`fieldKey → SQL expr`), aliasing each `expr AS sanitizeColumnName(key)`, plus a trailing sort-column injection. Source map keys: POLICIES `premium → p.total_premium`, `class_of_business → p.class_of_business_name`, `product_name → p.product_name`; CLAIMS `reserve_amount → cl.reserve_amount`, `class_of_business → cl.class_of_business_name`.
  - `execute()` tail block (current):
    ```java
        // Apply aggregation tail (GROUP BY / HAVING) before ORDER BY
        String tail = BASE_QUERY_TAILS.get(ds);
        if (tail != null && !tail.isBlank()) {
            sql.append(' ').append(tail);
        }
    ```
    (`ds` is `definition.getDataSource()`, already a local in `execute()`; `config` is `definition.getConfig()`, already a local.)
  - `isComputedField(config, sortBy)` already guards ORDER BY against computed sort keys; `sanitizeColumnName` guards the sort column. `applyComputedFields` maps `fields[i] ← row[i]` over non-computed fields, then post-processes computed fields (`loss_ratio = computeRatio(map, "claims_incurred", "premium_earned")` → returns `0.00` when those keys are absent — the known, out-of-scope ratio gap).
  - `ReportField` has `getKey()`, `getType()` (String: STRING/MONEY/PERCENT/DATE/NUMBER/INTEGER), `isComputed()`. `ReportConfig` has `getFields()`, `getGroupBy()`, `getSortBy()`, `getSortDir()`.
- **IT base:** `BusinessReportValueIT` extends `FinanceWebItSupport` (full `@SpringBootTest`, singleton Postgres at Flyway target 64), is `@Transactional` (seeds roll back per method → hermetic), autowires `ReportRunnerService` + `JdbcTemplate`, and has helpers `reportId(name)`, `run(reportName, filters)`, `insertMinimalPolicy(policyNumber)` (returns a valid `policies.id` FK target), and `private static final Map<String,String> WIDE = Map.of("date_from","2000-01-01","date_to","2100-01-01")`.
- **Shared-DB robustness:** other ITs share the singleton DB. Aggregation assertions MUST filter result rows by a UNIQUE seeded `class_of_business_name` (e.g. `"ZZ-AGG-FIRE"`) and assert on those rows only — never assert a total row count, which residue could pollute.

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `cia-backend/cia-reports/.../service/ReportQueryBuilder.java` | Build + run tenant-scoped SQL | Add `isAggregateMode` + `isMeasure` + `buildBusinessGroupBy`; extend `buildBusinessSql` aggregate branch; switch the `execute()` tail to the dynamic business GROUP BY |
| `cia-backend/cia-api/.../reports/BusinessReportValueIT.java` | Per-source report value/aggregation assertions | Add 3 aggregation tests |
| `CLAUDE.md` | Reports API Design note | Extend the dual-model note with the aggregation rule |
| `cia-log.md` | Session log + backlog | Entry + drain `reports-aggregation-semantics-gap` + add `reports-loss-ratio-premium-input` |

---

### Task 1: Failing aggregation ITs (red)

**Files:**
- Modify: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/reports/BusinessReportValueIT.java`

- [ ] **Step 1: Add a premium-seeding helper + 3 aggregation tests**

Add this helper (alongside the existing `insertMinimalPolicy`):

```java
    /** Insert a policy with explicit class name, product, and premium (for aggregation tests). */
    private void insertPolicyForAgg(String policyNumber, String className,
                                    String productName, String premium) {
        jdbc.update(
            "INSERT INTO policies (customer_id, customer_name, product_id, product_name, "
                + "product_code, product_rate, class_of_business_id, class_of_business_name, "
                + "class_of_business_code, policy_start_date, policy_end_date, policy_number, "
                + "total_sum_insured, total_premium, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), "AggCo", UUID.randomUUID(), productName, "PRD",
            new BigDecimal("2.5000"), UUID.randomUUID(), className, "CLS",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), policyNumber,
            new BigDecimal("1000000.00"), new BigDecimal(premium), "ACTIVE");
    }
```

Add these 3 tests:

```java
    @Test
    void netWrittenPremiumAggregatesSumByClass() {
        // 2 policies in FIRE (100k + 200k) + 1 in MOTOR (50k), unique class names.
        insertPolicyForAgg("POL-AGG-1", "ZZ-AGG-FIRE", "Fire Special", "100000.00");
        insertPolicyForAgg("POL-AGG-2", "ZZ-AGG-FIRE", "Fire Special", "200000.00");
        insertPolicyForAgg("POL-AGG-3", "ZZ-AGG-MOTOR", "Motor Comp",  "50000.00");

        List<Map<String, Object>> rows = run("Net Written Premium", WIDE);

        // FIRE: exactly ONE row (2 policies collapsed → aggregation), premium = 300k.
        List<Map<String, Object>> fire = rows.stream()
            .filter(r -> "ZZ-AGG-FIRE".equals(r.get("class_of_business"))).toList();
        assertThat(fire).as("2 FIRE policies aggregate to 1 row").hasSize(1);
        assertThat(new BigDecimal(fire.get(0).get("premium").toString()))
            .isEqualByComparingTo("300000.00");

        Map<String, Object> motor = rows.stream()
            .filter(r -> "ZZ-AGG-MOTOR".equals(r.get("class_of_business"))).findFirst().orElseThrow();
        assertThat(new BigDecimal(motor.get("premium").toString())).isEqualByComparingTo("50000.00");
    }

    @Test
    void grossWrittenPremiumGroupsByClassAndProduct() {
        // 2 FIRE policies with different products → group by (class, product) → 2 rows.
        insertPolicyForAgg("POL-GWP-1", "ZZ-GWP-FIRE", "Fire Std",     "100000.00");
        insertPolicyForAgg("POL-GWP-2", "ZZ-GWP-FIRE", "Fire Premium", "300000.00");

        List<Map<String, Object>> fire = run("Gross Written Premium", WIDE).stream()
            .filter(r -> "ZZ-GWP-FIRE".equals(r.get("class_of_business"))).toList();

        assertThat(fire).as("GWP groups by (class, product) → one row per product").hasSize(2);
        Map<String, Object> std = fire.stream()
            .filter(r -> "Fire Std".equals(r.get("product_name"))).findFirst().orElseThrow();
        assertThat(new BigDecimal(std.get("premium").toString())).isEqualByComparingTo("100000.00");
        Map<String, Object> prem = fire.stream()
            .filter(r -> "Fire Premium".equals(r.get("product_name"))).findFirst().orElseThrow();
        assertThat(new BigDecimal(prem.get("premium").toString())).isEqualByComparingTo("300000.00");
    }

    @Test
    void lossRatioReportAggregatesReserveButRatioStaysZero() {
        // 2 claims in one class → reserve_amount SUM; loss_ratio uncomputable (no premium
        // on CLAIMS source) → 0.00 (documents the reports-loss-ratio-premium-input gap).
        UUID p1 = insertMinimalPolicy("POL-LR-1");
        UUID p2 = insertMinimalPolicy("POL-LR-2");
        insertClaimForAgg("CLM-LR-1", p1, "ZZ-LR-CLASS", "300000.00");
        insertClaimForAgg("CLM-LR-2", p2, "ZZ-LR-CLASS", "200000.00");

        List<Map<String, Object>> rows = run("Loss Ratio Report", WIDE).stream()
            .filter(r -> "ZZ-LR-CLASS".equals(r.get("class_of_business"))).toList();

        assertThat(rows).as("2 claims aggregate to 1 row").hasSize(1);
        assertThat(new BigDecimal(rows.get(0).get("reserve_amount").toString()))
            .isEqualByComparingTo("500000.00");
        assertThat(new BigDecimal(rows.get(0).get("loss_ratio").toString()))
            .as("loss_ratio uncomputable on CLAIMS source → 0").isEqualByComparingTo("0.00");
    }

    /** Insert a claim with explicit class + reserve (FK to a real policy). */
    private void insertClaimForAgg(String claimNumber, UUID policyId, String className, String reserve) {
        jdbc.update(
            "INSERT INTO claims (claim_number, policy_id, policy_number, customer_id, customer_name, "
                + "class_of_business_id, class_of_business_name, status, reserve_amount, "
                + "approved_amount, reported_date, incident_date) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            claimNumber, policyId, "POL-LR", UUID.randomUUID(), "AggCo",
            UUID.randomUUID(), className, "APPROVED", new BigDecimal(reserve),
            new BigDecimal("0.00"), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 2, 20));
    }
```

- [ ] **Step 2: Run to verify they FAIL (red)**

Run (from `cia-backend/`):
```bash
mvn -q -pl cia-api -o test-compile process-resources failsafe:integration-test failsafe:verify -Dit.test=BusinessReportValueIT -DfailIfNoTests=false
```
Expected: the 3 new tests FAIL. `netWrittenPremiumAggregatesSumByClass` fails on `hasSize(1)` (gets 2 — un-aggregated per-policy rows); `grossWrittenPremiumGroupsByClassAndProduct` fails likewise; `lossRatioReportAggregatesReserveButRatioStaysZero` fails on `hasSize(1)`. The 6 pre-existing tests still pass. (If a NOT NULL violation occurs on a seed insert, add the missing column to that insert per the real schema in `cia-api/src/main/resources/db/migration/V2__create_tenant_schema_template.sql` / V6 / V9 — fixture only.)

- [ ] **Step 3: Commit the red tests**

```bash
git add cia-backend/cia-api/src/test/java/com/nubeero/cia/api/reports/BusinessReportValueIT.java
git commit -m "test(reports): failing aggregation ITs for groupBy reports (red)

Drives reports-aggregation-semantics-gap. Asserts Net Written Premium + Gross
Written Premium + Loss Ratio Report aggregate (GROUP BY + SUM) instead of
returning per-row data; currently fail because execute() emits no GROUP BY for
business sources. Filters on unique seeded class names for shared-DB robustness.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Implement SQL aggregation for groupBy business reports (green)

**Files:**
- Modify: `cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportQueryBuilder.java`

- [ ] **Step 1: Add the classification helpers**

Add these three private methods near `buildBusinessSql` (e.g. just below it):

```java
    /** Aggregate mode: a business source whose report declares a non-blank groupBy. */
    private boolean isAggregateMode(DataSource ds, ReportConfig config) {
        return SOURCE_COLUMNS.containsKey(ds)
            && config.getGroupBy() != null && !config.getGroupBy().isBlank();
    }

    /** A measure is a non-computed numeric field — SUM-ed in aggregate mode. */
    private boolean isMeasure(ReportField f) {
        String t = f.getType();
        return "MONEY".equals(t) || "NUMBER".equals(t) || "INTEGER".equals(t);
    }

    /**
     * GROUP BY clause for an aggregate-mode business source: the SQL expressions of the
     * report's non-computed dimension (non-measure) fields, in declared order. Returns
     * "" when not in aggregate mode (the 49 no-groupBy business reports stay per-row).
     * Group by the SQL expression (not the alias) for portability; mirrors the
     * dimension/measure split buildBusinessSql uses, so SELECT and GROUP BY agree.
     */
    private String buildBusinessGroupBy(DataSource ds, ReportConfig config) {
        if (!isAggregateMode(ds, config)) return "";
        Map<String, String> columns = SOURCE_COLUMNS.get(ds);
        List<String> dims = new ArrayList<>();
        if (config.getFields() != null) {
            for (ReportField f : config.getFields()) {
                if (f.isComputed() || isMeasure(f)) continue;
                dims.add(columns.getOrDefault(f.getKey(), "NULL"));
            }
        }
        return dims.isEmpty() ? "" : "GROUP BY " + String.join(", ", dims);
    }
```

- [ ] **Step 2: Extend `buildBusinessSql` with the aggregate branch**

Replace the existing `buildBusinessSql` body so measures are SUM-ed and the sort-injection is suppressed in aggregate mode:

```java
    private String buildBusinessSql(DataSource ds, ReportConfig config) {
        Map<String, String> columns = SOURCE_COLUMNS.get(ds);
        boolean aggregate = isAggregateMode(ds, config);
        List<String> selects = new ArrayList<>();
        Set<String> projectedKeys = new HashSet<>();
        if (config.getFields() != null) {
            for (ReportField f : config.getFields()) {
                if (f.isComputed()) continue;
                String expr = columns.getOrDefault(f.getKey(), "NULL");
                // Sanitize the alias before interpolating into SQL: a CUSTOM report's
                // field key is persisted unvalidated by ReportDefinitionService, so an
                // unsanitized `AS <key>` would be a SQL-injection vector for privileged
                // report authors. The alias is cosmetic — applyComputedFields keys the
                // result map by the raw config key positionally, not by this alias.
                String alias = sanitizeColumnName(f.getKey());
                // In aggregate mode, numeric measures are SUM-ed and the remaining
                // (dimension) fields form the GROUP BY (see buildBusinessGroupBy).
                // Declared-field order is preserved either way, so the positional
                // applyComputedFields stays correct by construction.
                if (aggregate && isMeasure(f)) {
                    selects.add("SUM(" + expr + ") AS " + alias);
                } else {
                    selects.add(expr + " AS " + alias);
                }
                projectedKeys.add(alias);
            }
        }
        // Sort-column injection: project a backed-but-undeclared sortBy column so the
        // ORDER BY alias resolves in PostgreSQL. Suppressed in aggregate mode — a bare
        // injected column would be illegal under GROUP BY (and no groupBy report needs
        // it: all 6 sort by a SUM-ed measure alias or a computed field that ORDER BY skips).
        if (!aggregate) {
            String sortBy = config.getSortBy();
            if (sortBy != null && !sortBy.isBlank()) {
                String sortKey = sanitizeColumnName(sortBy);
                if (!projectedKeys.contains(sortKey) && columns.containsKey(sortKey)) {
                    selects.add(columns.get(sortKey) + " AS " + sortKey);
                }
            }
        }
        if (selects.isEmpty()) selects.add("1");  // degenerate guard: report with no raw fields
        return "SELECT " + String.join(", ", selects) + " " + SOURCE_FROM.get(ds);
    }
```

- [ ] **Step 3: Switch the `execute()` tail to the dynamic business GROUP BY**

Replace the tail block in `execute()`:

```java
        // Apply aggregation tail (GROUP BY / HAVING) before ORDER BY
        String tail = BASE_QUERIES.containsKey(ds)
                ? BASE_QUERY_TAILS.get(ds)
                : buildBusinessGroupBy(ds, config);
        if (tail != null && !tail.isBlank()) {
            sql.append(' ').append(tail);
        }
```

> Rationale: business sources are NOT in `BASE_QUERIES` (they use `SOURCE_COLUMNS`), so `BASE_QUERIES.containsKey(ds)` is false for them → they get `buildBusinessGroupBy`; the closures/aggregate/view sources keep `BASE_QUERY_TAILS`. (Equivalently `SOURCE_COLUMNS.containsKey(ds) ? buildBusinessGroupBy(...) : BASE_QUERY_TAILS.get(ds)` — use whichever reads clearer; both partition the same way.)

- [ ] **Step 4: Build cia-reports + run the IT (green)**

```bash
mvn -q -pl cia-reports -am -o install -DskipTests \
 && mvn -q -pl cia-api -o test-compile process-resources failsafe:integration-test failsafe:verify -Dit.test=BusinessReportValueIT -DfailIfNoTests=false
```
Expected: PASS — all `BusinessReportValueIT` tests green (the 6 prior + 3 new = 9). The `install` of cia-reports is required so cia-api picks up the changed SNAPSHOT.

- [ ] **Step 5: Confirm no regression on the smoke IT**

```bash
mvn -q -pl cia-api -o test-compile process-resources failsafe:integration-test failsafe:verify -Dit.test=SystemReportSmokeIT -DfailIfNoTests=false
```
Expected: PASS (all SYSTEM reports still execute; the 6 now with GROUP BY).

- [ ] **Step 6: Commit**

```bash
git add cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportQueryBuilder.java
git commit -m "fix(reports): SQL aggregation for groupBy business reports

Business sources whose report declares a non-blank groupBy now aggregate in SQL:
non-computed MONEY/NUMBER/INTEGER fields are SUM-ed, non-computed STRING/DATE
fields form the GROUP BY (buildBusinessGroupBy at the existing tail position).
The sort-injection is suppressed in aggregate mode. Declared-field order is
preserved so applyComputedFields stays correct. The 49 no-groupBy business
reports + closures sources are unchanged. Fixes the per-row data the frontend
(Recharts, no client aggregation) was rendering as one bar per record.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Docs + backlog reconciliation + authoritative verify

**Files:**
- Modify: `CLAUDE.md`
- Modify: `cia-log.md`

- [ ] **Step 1: Extend the CLAUDE.md dual-model note**

In `CLAUDE.md`, find the `ReportQueryBuilder` dual-model bullet under "### Reports API Design (cia-reports specific)" (it begins "`ReportQueryBuilder` uses a **dual model**."). Append to the end of that same bullet:

```markdown
 A business source whose report declares a non-blank `groupBy` aggregates in SQL: non-computed MONEY/NUMBER/INTEGER fields are `SUM`-ed and non-computed STRING/DATE fields form the `GROUP BY` (`buildBusinessGroupBy`, emitted at the same tail position as `BASE_QUERY_TAILS`); computed fields stay Java-post-processed. The 49 business reports with no `groupBy` stay per-row. **Adding a measure to a `groupBy` report requires its field `type` to be MONEY/NUMBER/INTEGER** (that is what marks it for `SUM`).
```

- [ ] **Step 2: cia-log.md entry + backlog drain/add**

In the canonical backlog table near the top of `cia-log.md`, remove the `reports-aggregation-semantics-gap` row, and add:

```markdown
| reports-loss-ratio-premium-input | P3 | Loss/Combined/Annual-Revenue ratio columns are uncomputable | The 3 CLAIMS-source ratio reports compute `loss_ratio` = claims ÷ premium and `combined_ratio`, but the CLAIMS source carries no premium column and the reports don't declare a premium field, so `applyComputedFields` returns `0.00`. The aggregation slice (Session 136) correctly SUMs their `reserve_amount` by class but leaves the ratio columns at 0. Real fix: a combined premium+claims-by-class data source (JOIN `policies`+`claims` or a dedicated `DataSource`) + re-seed the 3 ratio configs with premium/claims measure fields. Larger data-model change; deliberately excluded from the aggregation slice (user-confirmed scope cap). |
```

Add a session entry directly above the most recent Session 136 entry:

```markdown
## 2026-05-29 — Session 136 (`main`): reports aggregation semantics (GROUP BY + SUM)

Drains `reports-aggregation-semantics-gap` (P3). The 6 business SYSTEM reports that
declare `groupBy=class_of_business` returned per-row data; the frontend doesn't
aggregate (`ReportChart` feeds raw rows to Recharts), so they rendered one bar per
record. Spec `docs/superpowers/specs/2026-05-29-reports-aggregation-semantics-design.md`.

### What landed
- **`ReportQueryBuilder` aggregate mode.** A business source with a non-blank
  `config.groupBy` now SUMs non-computed MONEY/NUMBER/INTEGER fields and GROUP BYs
  non-computed STRING/DATE fields (`buildBusinessGroupBy` at the existing
  `BASE_QUERY_TAILS` insertion point; `isAggregateMode` + `isMeasure` helpers;
  sort-injection suppressed in aggregate mode). Declared-field order preserved →
  positional `applyComputedFields` unchanged. The 49 no-`groupBy` business reports +
  closures sources are untouched.
- **3 aggregation ITs** in `BusinessReportValueIT` (Net Written Premium SUM-by-class;
  Gross Written Premium group-by-class+product; Loss Ratio Report SUMs reserve_amount
  with the ratio column documented at 0). Filter on unique seeded class names for
  shared-DB robustness.

### Outcome
The 3 premium reports (GWP / NWP / Premium Earned) are now fully correct. The 3 ratio
reports correctly aggregate `reserve_amount` per class; their `loss_ratio`/
`combined_ratio` columns stay `0.00` (no premium on the CLAIMS source) — tracked by
the new `reports-loss-ratio-premium-input` row.

### Known follow-ups + backlog reconciliation
- **Backlog row DRAINED (1):** `reports-aggregation-semantics-gap`.
- **Backlog row ADDED (1):** `reports-loss-ratio-premium-input` (P3, data-model — the
  uncomputable ratio columns; a user-confirmed scope cap of the aggregation slice).
- **Unchanged:** `bindFromQuote-rm-derivation-it` (P3), `R7-termii-prod` / `R7-twilio-prod` (P3).
```

- [ ] **Step 3: Authoritative full cia-api verify**

```bash
mvn -q -pl cia-reports -am -o install -DskipTests \
 && mvn -q -pl cia-api -o failsafe:integration-test failsafe:verify -DfailIfNoTests=false
```
Then aggregate:
```bash
python3 - <<'PY'
import glob, re
t=f=e=s=0; fails=[]
for p in glob.glob('cia-api/target/failsafe-reports/*.txt'):
    m=re.search(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)', open(p).read())
    if m:
        a,b,c,d=map(int,m.groups()); t+=a;f+=b;e+=c;s+=d
        if b or c: fails.append(p.split('/')[-1])
print(f"Tests run: {t}, Failures: {f}, Errors: {e}, Skipped: {s}"); print("Failing:", fails or "NONE")
PY
```
Expected: 0 failures, 0 errors (count = prior 453 + 3 new aggregation tests = ~456, minus the 1 intentional skip elsewhere).

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md cia-log.md
git commit -m "docs(reports): record aggregation rule + drain backlog row

CLAUDE.md dual-model note gains the groupBy aggregation rule. cia-log.md Session
136 entry; drains reports-aggregation-semantics-gap; adds the data-model follow-up
reports-loss-ratio-premium-input.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**1. Spec coverage:**
- §4.1 classification helper → Task 2 Step 1 (`isAggregateMode`, `isMeasure`). ✓
- §4.2 SELECT aggregate branch + sort-injection suppression → Task 2 Step 2. ✓
- §4.3 `buildBusinessGroupBy` + execute() tail switch → Task 2 Steps 1+3. ✓
- §4.4 interactions (ORDER BY alias, filters, applyComputedFields) → preserved (no edits to those paths); covered by Task 2 Step 5 smoke + Task 1 ratio test. ✓
- §5 outcome (premium correct; ratio reserve summed, ratio 0) → Task 1 tests 1+3. ✓
- §6 testing (premium-by-class, multi-dim GWP, ratio-stays-0) → Task 1 three tests. ✓
- §7 CLAUDE.md → Task 3 Step 1. ✓
- §8 backlog drain + add → Task 3 Step 2. ✓

**2. Placeholder scan:** No TBD/TODO; every code step has complete code. The NOT NULL caveat in Task 1 Step 2 is explicit about fixture-only adjustment.

**3. Type consistency:** `isAggregateMode(DataSource, ReportConfig)`, `isMeasure(ReportField)`, `buildBusinessGroupBy(DataSource, ReportConfig)` used consistently across Task 2. `ReportField.getType()`/`isComputed()`/`getKey()`, `ReportConfig.getGroupBy()`/`getFields()` match the real signatures. The `execute()` tail uses `BASE_QUERIES.containsKey(ds)` to partition — `BASE_QUERIES` and `SOURCE_COLUMNS` are disjoint over the DataSource enum (verified in `485d08f`), so the partition is exhaustive. Test helper names `insertPolicyForAgg`/`insertClaimForAgg` are new and self-consistent; `insertMinimalPolicy` is the existing helper.
