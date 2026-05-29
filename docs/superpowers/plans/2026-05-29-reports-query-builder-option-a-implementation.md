# Reports Query Builder Option-A Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make all 59 broken pre-built SYSTEM reports execute correctly by rebuilding `ReportQueryBuilder` for the 6 business data sources via dynamic per-field projection, plus a one-word table-name fix on 2 closures sources.

**Architecture:** `ReportQueryBuilder.execute()` gains a dual model: the 6 business sources (POLICIES, CLAIMS, FINANCE, REINSURANCE, CUSTOMERS, ENDORSEMENTS) build their SELECT dynamically from each report's declared field keys via a per-source `fieldKey → SQL expression` map over a single-table (REINSURANCE: one LEFT JOIN) FROM skeleton; every other source keeps its existing fixed `BASE_QUERIES` string. Because the dynamic SELECT is emitted in declared-field order, the existing positional `applyComputedFields()` becomes correct-by-construction.

**Tech Stack:** Java 21, Spring Boot 3, Hibernate native query, PostgreSQL, Testcontainers, JUnit 5 (failsafe ITs in `cia-api`).

---

## Background the implementer must know

- **The bug:** `ReportQueryBuilder.BASE_QUERIES` (in `cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportQueryBuilder.java`) holds one fixed SELECT per `DataSource`. The 6 business sources reference singular tables (`policy`, `customer`, `class_of_business`, `product`, `claim`, `debit_note`, `endorsement`, `ri_allocation`, `reinsurance_treaty`) and phantom columns (`c.full_name`, `p.sum_insured`, `p.premium`, `cl.total_paid`, `t.name`, `c.channel`, …). The real schema is plural with different column names, and most needed columns are **denormalised** onto the primary table (e.g. `policies.customer_name`, `policies.class_of_business_name`, `policies.product_name`). The closures sources GENERAL_LEDGER and PAA_GROUPS join the singular `class_of_business` (should be `classes_of_business`).
- **Why it was never caught:** `cia-reports` has no test directory; no IT ever ran a SYSTEM report against a real DB.
- **`execute()` flow today:** `StringBuilder sql = BASE_QUERIES.get(ds)` → append `" AND <expr>"` per filter → append `BASE_QUERY_TAILS.get(ds)` (GROUP BY, only for TRIAL_BALANCE + RM_COMMISSION) → append ORDER BY → `entityManager.createNativeQuery(sql)` → `applyComputedFields(rawRows, config)` maps `fields[i] ← row[i]` positionally.
- **The fix keeps `applyComputedFields`, `BASE_QUERY_TAILS`, the filter loop, `sanitizeColumnName`, the computed-field math — all unchanged.** Only the SELECT-source for the 6 business sources changes (now dynamic), plus the 2 closures table-name corrections and the alias updates in `createdAtCol`/`statusCol`/`hasCobJoin`.
- **DataSource enum** is `com.nubeero.cia.reports.domain.DataSource` with values incl. POLICIES, CLAIMS, FINANCE, REINSURANCE, CUSTOMERS, ENDORSEMENTS, TRIAL_BALANCE, GENERAL_LEDGER, GL_PERIOD_LOCK, PAA_LRC, PAA_GROUPS, IFRS17_MOVEMENT, IFRS9_HOLDINGS, IFRS9_CARRYING, IFRS9_MOVEMENT, RM_COMMISSION.
- **Entry point for ITs:** `ReportRunnerService.run(ReportRunRequest)` → `ReportResultDto{ List<ReportField> columns, List<Map<String,Object>> rows }`. `ReportRunRequest` has `setReportId(UUID)` + `setFilters(Map<String,String>)`.
- **IT base:** `com.nubeero.cia.api.finance.FinanceWebItSupport` — full `@SpringBootTest`, singleton Postgres pinned to Flyway target 64 (all 59 definitions seeded: V18 business + V44 closures). `RmCommissionReportIT` is the reference pattern (autowires `ReportRunnerService` + `JdbcTemplate`, JDBC-seeds rows, runs the report, asserts on `result.getRows()`).
- **Real column names** (verified): `policies`(policy_number, customer_name, class_of_business_name, class_of_business_id, product_name, product_id, total_sum_insured, total_premium, status, policy_start_date, policy_end_date, created_at, deleted_at); `claims`(claim_number, policy_number, customer_name, class_of_business_name, class_of_business_id, status, reserve_amount, approved_amount, reported_date, created_at, deleted_at); `debit_notes`(debit_note_number, entity_reference, customer_name, amount, status, due_date, created_at, deleted_at); `ri_allocations`(policy_number, treaty_id, treaty_type, retained_amount, ceded_amount, status, created_at, deleted_at); `ri_treaties`(id, description, treaty_type, deleted_at); `customers`(company_name, first_name, other_names, last_name, customer_type, kyc_status, created_at, deleted_at); `endorsements`(endorsement_number, policy_number, customer_name, endorsement_type, premium_adjustment, effective_date, class_of_business_id, status, created_at, deleted_at).

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportQueryBuilder.java` | Build + run tenant-scoped native SQL from a `ReportConfig` | Add `SOURCE_FROM` + `SOURCE_COLUMNS` maps; dual-model branch in `execute()`; closures cob table-name fix; alias updates in `createdAtCol`/`statusCol`/`hasCobJoin`; drop the 6 business entries from `BASE_QUERIES` |
| `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/reports/SystemReportSmokeIT.java` | Run every SYSTEM report against a real DB; assert no SQL exception | Create |
| `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/reports/BusinessReportValueIT.java` | Per-business-source value assertions proving the key→expr mapping | Create |
| `CLAUDE.md` | Reports API Design note | Modify — record the dual model |
| `cia-log.md` | Session log + backlog | Modify — entry + drain `reports-base-query-table-drift` |

---

### Task 1: Failing smoke IT — run every SYSTEM report against a real DB

**Files:**
- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/reports/SystemReportSmokeIT.java`

- [ ] **Step 1: Write the smoke IT**

```java
package com.nubeero.cia.api.reports;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.reports.controller.dto.ReportResultDto;
import com.nubeero.cia.reports.controller.dto.ReportRunRequest;
import com.nubeero.cia.reports.service.ReportRunnerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke IT: every SYSTEM report definition must execute against a real PostgreSQL
 * schema without a SQL exception. This is the regression guard that the
 * reports-base-query-table-drift bug class lacked — cia-reports had no test dir,
 * so no SYSTEM report's base query had ever run against a real DB.
 *
 * <p>Runs with NO seeded business data: the assertion is that every base query is
 * structurally valid (tables + columns resolve), so empty result lists are expected
 * and correct. Value correctness is covered by {@link BusinessReportValueIT}.
 *
 * @since reports-base-query-table-drift fix (Option A)
 */
class SystemReportSmokeIT extends FinanceWebItSupport {

    @Autowired
    ReportRunnerService reportRunnerService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void everySystemReportExecutes() {
        List<Map<String, Object>> defs = jdbc.queryForList(
            "SELECT id, name, data_source FROM report_definition WHERE type = 'SYSTEM' ORDER BY name");
        assertThat(defs).as("V18 + V44 seed 59 SYSTEM reports").hasSizeGreaterThanOrEqualTo(59);

        for (Map<String, Object> def : defs) {
            UUID id = (UUID) def.get("id");
            String name = (String) def.get("name");

            ReportRunRequest request = new ReportRunRequest();
            request.setReportId(id);
            // Required filters across V18/V44 SYSTEM configs are date_from/date_to only.
            // A wide window admits any seeded/empty data; other filters default to absent.
            request.setFilters(Map.of("date_from", "2000-01-01", "date_to", "2100-01-01"));

            try {
                ReportResultDto result = reportRunnerService.run(request);
                assertThat(result.getRows())
                    .as("report '%s' (%s) returned a non-null row list", name, def.get("data_source"))
                    .isNotNull();
            } catch (Exception e) {
                throw new AssertionError(
                    "SYSTEM report '" + name + "' (data_source=" + def.get("data_source")
                        + ") failed to execute: " + e.getMessage(), e);
            }
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails (red)**

Run (from `cia-backend/`):
```bash
mvn -q -pl cia-api -o test-compile process-resources failsafe:integration-test failsafe:verify -Dit.test=SystemReportSmokeIT -DfailIfNoTests=false
```
Expected: FAIL. The first business-source report executed throws e.g. `relation "policy" does not exist` (or `column ... does not exist`), surfaced as the `AssertionError` above.

- [ ] **Step 3: Commit the red test**

```bash
git add cia-backend/cia-api/src/test/java/com/nubeero/cia/api/reports/SystemReportSmokeIT.java
git commit -m "test(reports): failing smoke IT — every SYSTEM report must execute (red)

Drives the reports-base-query-table-drift fix. Runs all 59 SYSTEM report
definitions through ReportRunnerService against a real Postgres schema; currently
fails on the first business-source report (phantom table/column). cia-reports had
no test dir, which is why this drift went unnoticed.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Option-A rebuild — dynamic per-field projection for the 6 business sources

**Files:**
- Modify: `cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportQueryBuilder.java`

- [ ] **Step 1: Remove the 6 business entries from `BASE_QUERIES`**

In `BASE_QUERIES` (the `Map.ofEntries(...)` starting near line 38), DELETE the six `Map.entry(...)` blocks for `DataSource.POLICIES`, `DataSource.CLAIMS`, `DataSource.FINANCE`, `DataSource.REINSURANCE`, `DataSource.CUSTOMERS`, and `DataSource.ENDORSEMENTS`. Keep every closures/aggregate/view entry (TRIAL_BALANCE, GENERAL_LEDGER, GL_PERIOD_LOCK, PAA_LRC, PAA_GROUPS, IFRS17_MOVEMENT, IFRS9_HOLDINGS, IFRS9_CARRYING, IFRS9_MOVEMENT, RM_COMMISSION).

- [ ] **Step 2: Add the business-source registries**

Immediately after the `BASE_QUERIES` map declaration, add:

```java
    // ── Business-source dynamic projection (Option A) ─────────────────────────
    // The 6 business sources build their SELECT dynamically from each report's
    // declared field keys (see buildBusinessSql). SOURCE_FROM holds the
    // FROM/JOIN/WHERE skeleton; SOURCE_COLUMNS maps each declarable field key to a
    // SQL expression. A declared key absent from the map projects NULL (so a report
    // referencing an unbacked field — e.g. customers.channel — runs with an empty
    // column rather than throwing). Most sources are single-table over denormalised
    // columns; only REINSURANCE needs a join (for the treaty label).
    private static final Map<DataSource, String> SOURCE_FROM = Map.of(
        DataSource.POLICIES,     "FROM policies p WHERE p.deleted_at IS NULL",
        DataSource.CLAIMS,       "FROM claims cl WHERE cl.deleted_at IS NULL",
        DataSource.FINANCE,      "FROM debit_notes dn WHERE dn.deleted_at IS NULL",
        DataSource.REINSURANCE,  "FROM ri_allocations ria "
                               + "LEFT JOIN ri_treaties t ON t.id = ria.treaty_id "
                               + "WHERE ria.deleted_at IS NULL",
        DataSource.CUSTOMERS,    "FROM customers c WHERE c.deleted_at IS NULL",
        DataSource.ENDORSEMENTS, "FROM endorsements e WHERE e.deleted_at IS NULL"
    );

    private static final Map<DataSource, Map<String, String>> SOURCE_COLUMNS = Map.of(
        DataSource.POLICIES, Map.ofEntries(
            Map.entry("policy_number",     "p.policy_number"),
            Map.entry("customer_name",     "p.customer_name"),
            Map.entry("class_of_business", "p.class_of_business_name"),
            Map.entry("product_name",      "p.product_name"),
            Map.entry("sum_insured",       "p.total_sum_insured"),
            Map.entry("premium",           "p.total_premium"),
            Map.entry("status",            "p.status"),
            Map.entry("start_date",        "p.policy_start_date"),
            Map.entry("end_date",          "p.policy_end_date"),
            Map.entry("created_at",        "p.created_at")),
        DataSource.CLAIMS, Map.ofEntries(
            Map.entry("claim_number",      "cl.claim_number"),
            Map.entry("policy_number",     "cl.policy_number"),
            Map.entry("customer_name",     "cl.customer_name"),
            Map.entry("class_of_business", "cl.class_of_business_name"),
            Map.entry("status",            "cl.status"),
            Map.entry("reserve_amount",    "cl.reserve_amount"),
            Map.entry("total_paid",        "cl.approved_amount"),
            Map.entry("registered_at",     "cl.reported_date"),
            Map.entry("created_at",        "cl.created_at")),
        DataSource.FINANCE, Map.ofEntries(
            Map.entry("debit_note_number", "dn.debit_note_number"),
            Map.entry("policy_number",     "dn.entity_reference"),
            Map.entry("customer_name",     "dn.customer_name"),
            Map.entry("amount",            "dn.amount"),
            Map.entry("status",            "dn.status"),
            Map.entry("due_date",          "dn.due_date"),
            Map.entry("created_at",        "dn.created_at")),
        DataSource.REINSURANCE, Map.ofEntries(
            Map.entry("policy_number",     "ria.policy_number"),
            Map.entry("treaty_name",       "COALESCE(t.description, ria.treaty_type)"),
            Map.entry("treaty_type",       "ria.treaty_type"),
            Map.entry("retained_amount",   "ria.retained_amount"),
            Map.entry("ceded_amount",      "ria.ceded_amount"),
            Map.entry("status",            "ria.status"),
            Map.entry("created_at",        "ria.created_at")),
        DataSource.CUSTOMERS, Map.ofEntries(
            Map.entry("full_name",
                "COALESCE(c.company_name, "
                + "NULLIF(TRIM(CONCAT_WS(' ', c.first_name, c.other_names, c.last_name)), ''))"),
            Map.entry("customer_type",     "c.customer_type"),
            Map.entry("kyc_status",        "c.kyc_status"),
            Map.entry("created_at",        "c.created_at")),
            // NOTE: "channel" intentionally absent — no backing column; projects NULL.
        DataSource.ENDORSEMENTS, Map.ofEntries(
            Map.entry("endorsement_number",  "e.endorsement_number"),
            Map.entry("policy_number",       "e.policy_number"),
            Map.entry("customer_name",       "e.customer_name"),
            Map.entry("endorsement_type",    "e.endorsement_type"),
            Map.entry("endorsement_premium", "e.premium_adjustment"),
            Map.entry("effective_date",      "e.effective_date"),
            Map.entry("status",              "e.status"),
            Map.entry("created_at",          "e.created_at"))
    );
```

- [ ] **Step 3: Add the dynamic-SELECT builder + branch `execute()`**

In `execute(ReportDefinition definition, Map<String,String> filterValues, int maxRows)`, replace the line:
```java
        StringBuilder sql = new StringBuilder(BASE_QUERIES.get(definition.getDataSource()));
```
with:
```java
        DataSource ds = definition.getDataSource();
        StringBuilder sql = new StringBuilder(SOURCE_COLUMNS.containsKey(ds)
                ? buildBusinessSql(ds, config)
                : BASE_QUERIES.get(ds));
```
(`config` is already in scope — `ReportConfig config = definition.getConfig();` precedes this line.)

Then add this private method (place it just above `applyComputedFields`):

```java
    /**
     * Builds the SELECT + FROM/JOIN/WHERE prefix for a business source by projecting
     * the report's declared non-computed field keys, in order, through SOURCE_COLUMNS.
     * The filter loop in execute() appends ` AND <expr>` after this; there is no
     * GROUP BY tail for business sources. Emitting columns in declared-field order
     * is what makes the positional applyComputedFields() correct.
     *
     * A field key with no SOURCE_COLUMNS entry projects {@code NULL AS <key>} so a
     * report referencing an unbacked field (e.g. customers.channel) still runs.
     */
    private String buildBusinessSql(DataSource ds, ReportConfig config) {
        Map<String, String> columns = SOURCE_COLUMNS.get(ds);
        List<String> selects = new ArrayList<>();
        if (config.getFields() != null) {
            for (ReportField f : config.getFields()) {
                if (f.isComputed()) continue;
                String expr = columns.getOrDefault(f.getKey(), "NULL");
                selects.add(expr + " AS " + f.getKey());
            }
        }
        if (selects.isEmpty()) selects.add("1");  // degenerate guard: report with no raw fields
        return "SELECT " + String.join(", ", selects) + " " + SOURCE_FROM.get(ds);
    }
```

- [ ] **Step 4: Update the filter-helper aliases for the business single-table model**

Replace the business-source arms of `createdAtCol(DataSource ds)`:
```java
            case POLICIES         -> "p.created_at";
            case CLAIMS           -> "cl.created_at";
            case FINANCE          -> "dn.created_at";
            case REINSURANCE      -> "ria.created_at";
            case CUSTOMERS        -> "c.created_at";
            case ENDORSEMENTS     -> "e.created_at";
```
(these alias letters now match the SOURCE_FROM skeletons — POLICIES/CLAIMS/FINANCE/CUSTOMERS/ENDORSEMENTS already used these letters; REINSURANCE changes `ria` alias which is unchanged. Verify each arm matches; closures arms stay as-is.)

Replace the business-source arms of `statusCol(DataSource ds)`:
```java
            case POLICIES         -> "p.status";
            case CLAIMS           -> "cl.status";
            case FINANCE          -> "dn.status";
            case REINSURANCE      -> "ria.status";
            case CUSTOMERS        -> "c.kyc_status";
            case ENDORSEMENTS     -> "e.status";
```

Update `hasCobJoin(DataSource ds)` — it now means "supports the class_of_business_id filter via a denormalised id column" for business sources. The `class_of_business_id` filter branch in `execute()` currently appends `" AND cob.id = ?"`; change that branch so business sources filter on the denormalised id column. In `execute()`, replace the `case "class_of_business_id"` block:
```java
                    case "class_of_business_id" -> {
                        if (hasCobJoin(definition.getDataSource())) {
                            sql.append(" AND cob.id = ?").append(paramIdx++);
                            params.add(UUID.fromString(value));
                        }
                    }
```
with:
```java
                    case "class_of_business_id" -> {
                        String cobCol = cobFilterCol(definition.getDataSource());
                        if (cobCol != null) {
                            sql.append(" AND ").append(cobCol).append(" = ?").append(paramIdx++);
                            params.add(UUID.fromString(value));
                        }
                    }
```
and replace the `hasCobJoin` method with `cobFilterCol`:
```java
    /** Column the class_of_business_id filter targets, or null if the source has none. */
    private String cobFilterCol(DataSource ds) {
        return switch (ds) {
            case POLICIES     -> "p.class_of_business_id";
            case CLAIMS       -> "cl.class_of_business_id";
            case ENDORSEMENTS -> "e.class_of_business_id";
            case GENERAL_LEDGER -> "cob.id";   // joins classes_of_business (Task 3)
            case PAA_GROUPS     -> "cob.id";   // joins classes_of_business (Task 3)
            default           -> null;
        };
    }
```
Also update the `product_id` filter branch — it already guards `definition.getDataSource() == DataSource.POLICIES` and appends `" AND pr.id = ?"`; change `pr.id` to `p.product_id` (POLICIES is now single-table, no `pr` alias):
```java
                    case "product_id" -> {
                        if (definition.getDataSource() == DataSource.POLICIES) {
                            sql.append(" AND p.product_id = ?").append(paramIdx++);
                            params.add(UUID.fromString(value));
                        }
                    }
```

- [ ] **Step 5: Run the smoke IT — business sources now pass, closures cob still fails**

Run:
```bash
mvn -q -pl cia-reports -am -o install -DskipTests \
 && mvn -q -pl cia-api -o test-compile process-resources failsafe:integration-test failsafe:verify -Dit.test=SystemReportSmokeIT -DfailIfNoTests=false
```
(The `install` of cia-reports is required so cia-api picks up the changed SNAPSHOT — see CLAUDE.md "Why install and not compile".)
Expected: still FAIL, but now only on a GENERAL_LEDGER or PAA_GROUPS report — `relation "class_of_business" does not exist`. All 6 business-source reports now execute. (If a business report still fails, the field key→expr map is missing an entry — add it to `SOURCE_COLUMNS`.)

- [ ] **Step 6: Commit**

```bash
git add cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportQueryBuilder.java
git commit -m "fix(reports): dynamic per-field projection for the 6 business sources

Replaces the fixed BASE_QUERIES SELECTs (phantom singular tables + nonexistent
columns) for POLICIES/CLAIMS/FINANCE/REINSURANCE/CUSTOMERS/ENDORSEMENTS with a
SOURCE_FROM skeleton + SOURCE_COLUMNS fieldKey->expr map; execute() builds the
SELECT from each report's declared field keys in order, so the positional
applyComputedFields() is correct-by-construction. Unbacked keys project NULL
(customers.channel). Filter-helper aliases updated to the single-table model.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Closures table-name fix — GENERAL_LEDGER + PAA_GROUPS

**Files:**
- Modify: `cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportQueryBuilder.java`

- [ ] **Step 1: Fix the singular `class_of_business` join in both closures entries**

In the `BASE_QUERIES` entry for `DataSource.GENERAL_LEDGER`, change:
```java
            "LEFT JOIN class_of_business cob ON cob.id = jel.class_of_business_id " +
```
to:
```java
            "LEFT JOIN classes_of_business cob ON cob.id = jel.class_of_business_id " +
```
In the `DataSource.PAA_GROUPS` entry, change:
```java
            "LEFT JOIN class_of_business cob ON cob.id = p.class_of_business_id " +
```
to:
```java
            "LEFT JOIN classes_of_business cob ON cob.id = p.class_of_business_id " +
```

- [ ] **Step 2: Run the smoke IT — all 59 now pass (green)**

Run:
```bash
mvn -q -pl cia-reports -am -o install -DskipTests \
 && mvn -q -pl cia-api -o test-compile process-resources failsafe:integration-test failsafe:verify -Dit.test=SystemReportSmokeIT -DfailIfNoTests=false
```
Expected: PASS — `everySystemReportExecutes` green; all ≥59 SYSTEM reports execute with no SQL exception.

- [ ] **Step 3: Commit**

```bash
git add cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportQueryBuilder.java
git commit -m "fix(reports): correct singular class_of_business join in GL + PAA_GROUPS

The GENERAL_LEDGER and PAA_GROUPS BASE_QUERIES joined the nonexistent singular
class_of_business table; the schema is classes_of_business. Fixes the 4 V44
closures reports on these sources. Smoke IT now green for all 59 SYSTEM reports.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Value-assertion ITs — prove the key→expr mapping per business source

**Files:**
- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/reports/BusinessReportValueIT.java`

- [ ] **Step 1: Write the value IT**

```java
package com.nubeero.cia.api.reports;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.reports.controller.dto.ReportResultDto;
import com.nubeero.cia.reports.controller.dto.ReportRunRequest;
import com.nubeero.cia.reports.service.ReportRunnerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-business-source value assertions: seed minimal rows, run a representative
 * SYSTEM report, and assert the projected columns carry the right values — proving
 * the SOURCE_COLUMNS fieldKey->expr mapping is correct, not merely non-crashing.
 * Complements {@link SystemReportSmokeIT} (which proves all 59 execute).
 *
 * @since reports-base-query-table-drift fix (Option A)
 */
class BusinessReportValueIT extends FinanceWebItSupport {

    private static final Map<String, String> WIDE =
        Map.of("date_from", "2000-01-01", "date_to", "2100-01-01");

    @Autowired ReportRunnerService reportRunnerService;
    @Autowired JdbcTemplate jdbc;

    private UUID reportId(String name) {
        return jdbc.queryForObject(
            "SELECT id FROM report_definition WHERE name = ? AND type = 'SYSTEM'", UUID.class, name);
    }

    private List<Map<String, Object>> run(String reportName, Map<String, String> filters) {
        ReportRunRequest req = new ReportRunRequest();
        req.setReportId(reportId(reportName));
        req.setFilters(filters);
        ReportResultDto result = reportRunnerService.run(req);
        return result.getRows();
    }

    @Test
    void policyRegisterMapsDenormalisedColumns() {
        jdbc.update(
            "INSERT INTO policies (customer_id, customer_name, product_id, product_name, "
                + "product_code, product_rate, class_of_business_id, class_of_business_name, "
                + "class_of_business_code, policy_start_date, policy_end_date, policy_number, "
                + "total_sum_insured, total_premium, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), "Globex Ltd", UUID.randomUUID(), "Fire Special", "FIRE",
            new BigDecimal("2.5000"), UUID.randomUUID(), "Fire", "FIR",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "POL-VAL-001",
            new BigDecimal("5000000.00"), new BigDecimal("125000.00"), "ACTIVE");

        Map<String, Object> row = run("Policy Register", WIDE).stream()
            .filter(r -> "POL-VAL-001".equals(r.get("policy_number"))).findFirst().orElseThrow();
        assertThat(row.get("customer_name")).isEqualTo("Globex Ltd");
        assertThat(row.get("class_of_business")).isEqualTo("Fire");
        assertThat(row.get("product_name")).isEqualTo("Fire Special");
        assertThat(new BigDecimal(row.get("sum_insured").toString())).isEqualByComparingTo("5000000.00");
        assertThat(new BigDecimal(row.get("premium").toString())).isEqualByComparingTo("125000.00");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void claimsRegisterMapsReserveAndProxyPaid() {
        jdbc.update(
            "INSERT INTO claims (claim_number, policy_id, policy_number, customer_id, customer_name, "
                + "class_of_business_id, class_of_business_name, status, reserve_amount, "
                + "approved_amount, reported_date, incident_date) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "CLM-VAL-001", UUID.randomUUID(), "POL-VAL-001", UUID.randomUUID(), "Globex Ltd",
            UUID.randomUUID(), "Fire", "APPROVED", new BigDecimal("300000.00"),
            new BigDecimal("250000.00"), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 2, 20));

        Map<String, Object> row = run("Claims Register", WIDE).stream()
            .filter(r -> "CLM-VAL-001".equals(r.get("claim_number"))).findFirst().orElseThrow();
        assertThat(row.get("customer_name")).isEqualTo("Globex Ltd");
        assertThat(row.get("class_of_business")).isEqualTo("Fire");
        assertThat(new BigDecimal(row.get("reserve_amount").toString())).isEqualByComparingTo("300000.00");
        assertThat(new BigDecimal(row.get("total_paid").toString())).isEqualByComparingTo("250000.00");
    }

    @Test
    void debitNoteAnalysisMapsEntityReference() {
        jdbc.update(
            "INSERT INTO debit_notes (debit_note_number, entity_type, entity_id, entity_reference, "
                + "customer_id, customer_name, amount, total_amount, status, due_date) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "DN-VAL-001", "POLICY", UUID.randomUUID(), "POL-VAL-001",
            UUID.randomUUID(), "Globex Ltd", new BigDecimal("125000.00"),
            new BigDecimal("125000.00"), "PENDING", LocalDate.of(2026, 2, 1));

        Map<String, Object> row = run("Debit Note Analysis", WIDE).stream()
            .filter(r -> "DN-VAL-001".equals(r.get("debit_note_number"))).findFirst().orElseThrow();
        assertThat(row.get("policy_number")).isEqualTo("POL-VAL-001");
        assertThat(row.get("customer_name")).isEqualTo("Globex Ltd");
        assertThat(new BigDecimal(row.get("amount").toString())).isEqualByComparingTo("125000.00");
    }

    @Test
    void riPremiumBordereauxMapsTreatyLabel() {
        UUID treatyId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO ri_treaties (id, treaty_type, treaty_year, description, status) "
                + "VALUES (?, ?, ?, ?, ?)",
            treatyId, "SURPLUS", 2026, "Main Surplus Treaty 2026", "ACTIVE");
        jdbc.update(
            "INSERT INTO ri_allocations (allocation_number, policy_id, policy_number, treaty_id, "
                + "treaty_type, retained_amount, ceded_amount, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            "RIA-VAL-001", UUID.randomUUID(), "POL-VAL-001", treatyId, "SURPLUS",
            new BigDecimal("1000000.00"), new BigDecimal("4000000.00"), "APPROVED");

        Map<String, Object> row = run("RI Premium Bordereaux", WIDE).stream()
            .filter(r -> "POL-VAL-001".equals(r.get("policy_number"))).findFirst().orElseThrow();
        assertThat(row.get("treaty_name")).isEqualTo("Main Surplus Treaty 2026");
        assertThat(new BigDecimal(row.get("ceded_amount").toString())).isEqualByComparingTo("4000000.00");
    }

    @Test
    void activeCustomersMapsFullNameAndNullChannel() {
        jdbc.update(
            "INSERT INTO customers (customer_number, customer_type, company_name, kyc_status, "
                + "customer_status) VALUES (?, ?, ?, ?, ?)",
            "CUS-VAL-CORP", "CORPORATE", "Initech Plc", "VERIFIED", "ACTIVE");
        jdbc.update(
            "INSERT INTO customers (customer_number, customer_type, first_name, last_name, "
                + "kyc_status, customer_status) VALUES (?, ?, ?, ?, ?, ?)",
            "CUS-VAL-IND", "INDIVIDUAL", "Ada", "Lovelace", "VERIFIED", "ACTIVE");

        List<Map<String, Object>> rows = run("Active Customers", WIDE);
        Map<String, Object> corp = rows.stream()
            .filter(r -> "Initech Plc".equals(r.get("full_name"))).findFirst().orElseThrow();
        assertThat(corp.get("customer_type")).isEqualTo("CORPORATE");
        assertThat(corp.get("channel")).as("channel has no backing column → NULL").isNull();

        Map<String, Object> ind = rows.stream()
            .filter(r -> "Ada Lovelace".equals(r.get("full_name"))).findFirst().orElseThrow();
        assertThat(ind.get("customer_type")).isEqualTo("INDIVIDUAL");
    }
}
```

> **Implementer note — verify NOT NULL columns before running.** The JDBC inserts above set the columns the reports read plus the obvious NOT NULLs. If any insert fails with a not-null violation, add the missing column with a sane default by checking the table in `cia-api/src/main/resources/db/migration/V2__create_tenant_schema_template.sql` (and later migrations). Do NOT change the report logic to accommodate — only the test fixture. ENDORSEMENTS has no SYSTEM report; it is covered structurally by `SystemReportSmokeIT` only (its base query executes via the same `buildBusinessSql` path), so no value test is required for it.

- [ ] **Step 2: Run it (red→green in one go — implementation already exists from Tasks 2-3)**

Run:
```bash
mvn -q -pl cia-api -o test-compile process-resources failsafe:integration-test failsafe:verify -Dit.test=BusinessReportValueIT -DfailIfNoTests=false
```
Expected: PASS (5 tests). If a `full_name` concat assertion fails, check the `Active Customers` config field order — the test keys off the result-map keys (`full_name`, `customer_type`, `channel`), which are config-driven, not positional.

- [ ] **Step 3: Commit**

```bash
git add cia-backend/cia-api/src/test/java/com/nubeero/cia/api/reports/BusinessReportValueIT.java
git commit -m "test(reports): per-source value assertions for Option-A projection

Seeds minimal rows and asserts the projected columns carry the right values for
one representative report per business source (Policy Register, Claims Register,
Debit Note Analysis, RI Premium Bordereaux, Active Customers) — proving the
SOURCE_COLUMNS mapping is correct, incl. the treaty_name COALESCE, the total_paid
proxy, full_name concat, and the NULL channel fallback.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Docs + backlog reconciliation + authoritative verify

**Files:**
- Modify: `CLAUDE.md`
- Modify: `cia-log.md`

- [ ] **Step 1: Update the CLAUDE.md Reports API Design note**

In `CLAUDE.md`, find the bullet under "### Reports API Design (cia-reports specific)" that begins "Computed fields (`loss_ratio`, …) are post-processed in Java inside `ReportQueryBuilder.applyComputedFields()`". Immediately after it, add:

```markdown
- `ReportQueryBuilder` uses a **dual model**. The 6 business sources (POLICIES, CLAIMS, FINANCE, REINSURANCE, CUSTOMERS, ENDORSEMENTS) build their SELECT **dynamically** from each report's declared field keys via `SOURCE_FROM` (FROM/JOIN/WHERE skeleton) + `SOURCE_COLUMNS` (`fieldKey → SQL expr`, mostly single-table over denormalised columns). All other sources (TRIAL_BALANCE, GENERAL_LEDGER, the IFRS/PAA views, RM_COMMISSION) keep a fixed `BASE_QUERIES` SELECT. Emitting the dynamic SELECT in declared-field order makes the positional `applyComputedFields()` correct-by-construction. **Adding a new field key to a business-source report requires a matching `SOURCE_COLUMNS` entry** (an unmapped key projects `NULL`). This replaced the original fixed-SELECT-for-all model, whose business-source queries referenced phantom singular tables + nonexistent columns and broke all 55 V18 + 4 V44 SYSTEM reports at runtime (caught by `SystemReportSmokeIT`).
```

- [ ] **Step 2: Add the cia-log.md session entry + drain the backlog row**

In `cia-log.md`, remove the `reports-base-query-table-drift` row from the canonical backlog table near the top. Then add a session entry directly below the "Discoveries policy." block's following `---`, above the most recent Session 136 entry:

```markdown
## 2026-05-29 — Session 136 (`main`): backlog drain — reports query-builder Option-A rebuild

Drains `reports-base-query-table-drift` (P2). `ReportQueryBuilder` queried phantom
singular tables + nonexistent columns for the 6 business data sources, and mapped
result columns to report fields by a fragile positional index — so all 55 V18 +
4 V44 (GENERAL_LEDGER ×3, PAA_GROUPS ×1) SYSTEM reports failed at runtime. No IT
ever ran them (cia-reports had no test dir). Spec at
`docs/superpowers/specs/2026-05-29-reports-query-builder-option-a-design.md`.

### What landed
- **Dual model in `ReportQueryBuilder`.** The 6 business sources now build the SELECT
  dynamically from each report's declared field keys via `SOURCE_FROM` +
  `SOURCE_COLUMNS` (`fieldKey → SQL expr`, single-table over denormalised columns;
  REINSURANCE has one LEFT JOIN for the treaty label). Closures/aggregate/view
  sources keep their fixed `BASE_QUERIES`. Declared-field-order emission makes the
  positional `applyComputedFields()` correct-by-construction. Unmapped keys project
  `NULL` (`customers.channel`). Resolved fields: `treaty_name` →
  `COALESCE(t.description, ria.treaty_type)`, `total_paid` → `cl.approved_amount`
  (proxy), `channel` → NULL.
- **Closures table-name fix.** GENERAL_LEDGER + PAA_GROUPS joined the singular
  `class_of_business`; corrected to `classes_of_business`.
- **First reports ITs.** `SystemReportSmokeIT` runs all ≥59 SYSTEM reports against a
  real DB asserting no SQL exception (the missing regression guard).
  `BusinessReportValueIT` asserts column values for one report per business source.

### Known follow-ups + backlog reconciliation
- **Backlog row DRAINED (1):** `reports-base-query-table-drift`.
- **Unchanged:** `reports-aggregation-semantics-gap` (P3, deferred per design Q3),
  `bindFromQuote-rm-derivation-it` (P3), `R7-termii-prod` / `R7-twilio-prod` (P3).
- **No new rows added.**
```

- [ ] **Step 3: Authoritative full cia-api verify**

Run:
```bash
mvn -q -pl cia-reports -am -o install -DskipTests \
 && mvn -q -pl cia-api -o failsafe:integration-test failsafe:verify -DfailIfNoTests=false
```
Then aggregate the report totals:
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
Expected: 0 failures, 0 errors (count = prior 446 + 1 smoke + 5 value = ~452, minus any pre-existing skip). All green.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md cia-log.md
git commit -m "docs(reports): record dual-model ReportQueryBuilder + drain backlog row

CLAUDE.md Reports API Design note documents the business-source dynamic projection
vs closures fixed-SELECT split + the 'new field key needs a SOURCE_COLUMNS entry'
rule. cia-log.md Session 136 entry + drains reports-base-query-table-drift.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**1. Spec coverage:**
- §4 dual model → Task 2 (registries + `buildBusinessSql` + branch). ✓
- §4 per-source registries (all 6) → Task 2 Step 2. ✓
- §5 filters (alias updates, cob filter, product_id) → Task 2 Step 4. ✓
- §6 closures table-name fix → Task 3. ✓
- §3 Q2 resolved fields (treaty_name / total_paid / channel) → Task 2 map + Task 4 assertions. ✓
- §7 testing (smoke-all + value-subset) → Task 1 (smoke) + Task 4 (values). ✓
- §3 Q3 aggregation deferred → no task; left as P3 (noted Task 5). ✓
- §8 docs → Task 5. ✓
- §9 backlog drain → Task 5 Step 2. ✓

**2. Placeholder scan:** No TBD/TODO; every code step has complete code. The one judgement note (Task 4 NOT NULL columns / ENDORSEMENTS) is explicit about what to do and what NOT to do.

**3. Type consistency:** `buildBusinessSql(DataSource, ReportConfig)`, `cobFilterCol(DataSource)` (replaces `hasCobJoin`), `SOURCE_FROM`, `SOURCE_COLUMNS` used consistently. `ReportField.isComputed()`/`getKey()`, `ReportConfig.getFields()`, `ReportRunRequest.setReportId/setFilters`, `ReportResultDto.getRows()` match the real signatures verified before writing. The `class_of_business_id` filter switch now calls `cobFilterCol`; the old `hasCobJoin` is fully removed (no dangling caller).
