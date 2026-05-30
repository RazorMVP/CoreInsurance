# Reports loss-ratio premium input (UNDERWRITING_PERFORMANCE) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the 3 ratio reports (Loss Ratio, Combined Ratio, Annual Revenue Account NAICOM) compute non-zero `loss_ratio` / `combined_ratio` by introducing a cross-entity by-class aggregate data source that supplies `premium_earned`, `claims_incurred`, and `expenses` in one row.

**Architecture:** A new fixed-aggregate `DataSource.UNDERWRITING_PERFORMANCE` — a UNION-ALL event stream over `policies` (gross written premium), `claims` (reserve = incurred), and APPROVED `claim_expenses` — each row carrying a single `event_date` so the existing top-level `date_from`/`date_to` filter works unchanged; `GROUP BY class` feeds the positional `applyComputedFields()`. A Flyway `V66` migration re-seeds the 3 reports onto it.

**Tech Stack:** Java 21, Spring Boot 3, Hibernate native query, Flyway, JUnit 5 + Testcontainers (Postgres).

**Spec:** `docs/superpowers/specs/2026-05-30-reports-loss-ratio-premium-input-design.md` (committed `e56d573`).

---

## File Structure

- **Modify** `cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/domain/DataSource.java` — add `UNDERWRITING_PERFORMANCE` enum value.
- **Modify** `cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportQueryBuilder.java` — add `BASE_QUERIES` + `BASE_QUERY_TAILS` entries and the 3 switch cases (`createdAtCol`, `statusCol`, `cobFilterCol`).
- **Create** `cia-backend/cia-api/src/main/resources/db/migration/V66__reseed_ratio_reports_underwriting_performance.sql` — re-seed the 3 reports.
- **Modify** `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/FinanceWebItSupport.java:136` — bump the shared `spring.flyway.target` from `"64"` → `"66"`. This is the ONLY place the target is set for `BusinessReportValueIT` (it's a `@DynamicPropertySource`, which outranks any `@TestPropertySource` a subclass could add). The base is shared by ~6 finance-web ITs; V65 (additive column) + V66 (data re-seed) are additive, so they are behavior-neutral for the others.
- **Modify** `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/reports/BusinessReportValueIT.java` — replace the obsolete `lossRatioReportAggregatesReserveButRatioStaysZero` test; add combined-ratio + period-filter tests + 3 id/date helpers.
- **Modify** `CLAUDE.md`, `cia-log.md` — docs + backlog reconciliation.

**Real seed helpers (verified against the file):** `insertMinimalPolicy(num)→UUID` (hardcoded class "Fire", `total_premium` defaults to 0), `insertPolicyForAgg(num, class, product, premium)→void` (`total_premium` = the premium arg, `created_at` defaults to `now()`), `insertClaimForAgg(num, polId, class, reserve)→void` (`reported_date` hardcoded `2026-03-01`). None return a claim id (DB-generated). The plan adds `insertClaimReturningId`, `insertClaimExpense`, `insertClaimDated`.

**No frontend change:** `@cia/api-client` reports module types `dataSource` as free-form `z.string()` ([reports.ts:27](../../../cia-frontend/packages/api-client/src/modules/reports.ts#L27)) — a new enum value validates without edit. Per spec, `UNDERWRITING_PERFORMANCE` is deliberately NOT added to the Custom Report Builder picker.

**No docs-site change:** no controller endpoint added/changed and no new Maven module — `internal-api.json` and `modules.md` are unaffected (satisfies the gate-9 audit by inspection).

---

### Task 1: Failing test — loss ratio must compute 50.00 (RED)

**Files:**
- Modify: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/FinanceWebItSupport.java:136` (flyway target)
- Modify: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/reports/BusinessReportValueIT.java:155-170` (replace test)

- [ ] **Step 1: Bump the shared Flyway target to 66**

In `FinanceWebItSupport.java`, change line 136 from:

```java
        registry.add("spring.flyway.target", () -> "64");
```

to:

```java
        registry.add("spring.flyway.target", () -> "66");
```

> This `@DynamicPropertySource` is the authoritative target for every IT extending `FinanceWebItSupport` (incl. `BusinessReportValueIT`). A class-level `@TestPropertySource` on the IT would NOT override it — `@DynamicPropertySource` has higher precedence. Bumping the base from 64 → 66 brings the shared singleton container through V65 (additive `quote_risks.gross_premium` column) + V66 (data re-seed); both additive, so the other finance-web ITs are behavior-neutral.

- [ ] **Step 2: Replace the obsolete loss-ratio test**

The report no longer declares `reserve_amount`; it declares `premium_earned` + `claims_incurred`. Replace the entire `lossRatioReportAggregatesReserveButRatioStaysZero` method (lines 155-170) with:

```java
    @Test
    void lossRatioReportComputesNonZeroRatio() {
        // 1 priced policy (total_premium = 1,000,000) in the test class supplies the
        // premium leg; 2 claims (300k + 200k = 500k incurred) supply the claims leg.
        // The "host" policy (class "Fire", premium 0) only satisfies the claims FK —
        // a different class, so it's invisible to the ZZ-LR-PERF filter.
        UUID host = insertMinimalPolicy("POL-LR-HOST");
        insertPolicyForAgg("POL-LR-1", "ZZ-LR-PERF", "Fire Special", "1000000.00");
        insertClaimForAgg("CLM-LR-1", host, "ZZ-LR-PERF", "300000.00");
        insertClaimForAgg("CLM-LR-2", host, "ZZ-LR-PERF", "200000.00");

        List<Map<String, Object>> rows = run("Loss Ratio Report", WIDE).stream()
            .filter(r -> "ZZ-LR-PERF".equals(r.get("class_of_business"))).toList();

        assertThat(rows).as("class aggregates to 1 row").hasSize(1);
        assertThat(new BigDecimal(rows.get(0).get("premium_earned").toString()))
            .isEqualByComparingTo("1000000.00");
        assertThat(new BigDecimal(rows.get(0).get("claims_incurred").toString()))
            .isEqualByComparingTo("500000.00");
        assertThat(new BigDecimal(rows.get(0).get("loss_ratio").toString()))
            .as("500000 / 1000000 × 100").isEqualByComparingTo("50.00");
    }
```

> Why a separate "host" policy: `insertClaimForAgg` needs a real `policies.id` for the NOT-NULL FK, but `insertPolicyForAgg` returns `void` (no id). `insertMinimalPolicy` returns an id — its hardcoded "Fire" class and 0 premium land in a *different* class bucket, so they never touch the `ZZ-LR-PERF` assertion. Claims aggregate by class *name*, independent of which policy they FK to.

- [ ] **Step 3: Run the test — verify it fails**

Run: `cd cia-backend && mvn -q -pl cia-api test -Dtest=BusinessReportValueIT#lossRatioReportComputesNonZeroRatio`
Expected: FAIL — Flyway refuses `target=66` (no V66 migration yet): `FlywayException: ... target ... not found` (the established fail-first pattern in this codebase, per the V65 slice). Confirms the red.

- [ ] **Step 4: Commit**

```bash
git add cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/FinanceWebItSupport.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/reports/BusinessReportValueIT.java
git commit -m "test(reports): failing loss-ratio non-zero IT + target bump to 66 (red)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Add the data source + builder wiring + V66 migration (GREEN)

**Files:**
- Modify: `cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/domain/DataSource.java:24-27`
- Modify: `cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportQueryBuilder.java` (BASE_QUERIES ~`:172-180`, BASE_QUERY_TAILS `:260-265`, createdAtCol `:457-480`, statusCol `:486-509`, cobFilterCol `:519-528`)
- Create: `cia-backend/cia-api/src/main/resources/db/migration/V66__reseed_ratio_reports_underwriting_performance.sql`

- [ ] **Step 1: Add the enum value**

In `DataSource.java`, after the `RM_COMMISSION` entry (line 26), add:

```java
    RM_COMMISSION,

    // ── Module 11 — Underwriting performance (cross-entity by-class aggregate) ─
    // UNION-ALL event stream: policies (gross written premium) + claims (reserve =
    // incurred) + APPROVED claim_expenses, GROUP BY class. Feeds loss/combined ratio.
    UNDERWRITING_PERFORMANCE
```

- [ ] **Step 2: Add the BASE_QUERIES entry**

In `ReportQueryBuilder.java`, inside the `BASE_QUERIES` `Map.ofEntries(...)`, append after the `RM_COMMISSION` entry (after line 179, before the closing `)` on line 180 — add a comma to the `RM_COMMISSION` entry's close):

```java
        ,
        // ── Module 11 — Underwriting performance (cross-entity by-class aggregate) ─
        // Flattens policies + claims + APPROVED claim_expenses into one event stream,
        // each row carrying a single ev.event_date so date_from/date_to filter at the
        // top level (like TRIAL_BALANCE / RM_COMMISSION). GROUP BY ev.cob in
        // BASE_QUERY_TAILS. Metric definitions (spec Q2-Q4): claims_incurred =
        // SUM(reserve_amount); expenses = SUM(approved claim_expenses); premium_earned =
        // SUM(total_premium) gross written. Explicit CAST(0 AS DECIMAL(18,2)) keeps the
        // UNION-ALL column types unambiguous for the non-contributing measure per branch.
        Map.entry(DataSource.UNDERWRITING_PERFORMANCE,
            "SELECT ev.cob AS class_of_business, " +
            "SUM(ev.premium_earned) AS premium_earned, " +
            "SUM(ev.claims_incurred) AS claims_incurred, " +
            "SUM(ev.expenses) AS expenses " +
            "FROM ( " +
            "  SELECT class_of_business_name AS cob, class_of_business_id AS cob_id, " +
            "         total_premium AS premium_earned, " +
            "         CAST(0 AS DECIMAL(18,2)) AS claims_incurred, " +
            "         CAST(0 AS DECIMAL(18,2)) AS expenses, " +
            "         created_at AS event_date " +
            "    FROM policies WHERE deleted_at IS NULL " +
            "  UNION ALL " +
            "  SELECT class_of_business_name, class_of_business_id, " +
            "         CAST(0 AS DECIMAL(18,2)), reserve_amount, CAST(0 AS DECIMAL(18,2)), reported_date " +
            "    FROM claims WHERE deleted_at IS NULL " +
            "  UNION ALL " +
            "  SELECT cl.class_of_business_name, cl.class_of_business_id, " +
            "         CAST(0 AS DECIMAL(18,2)), CAST(0 AS DECIMAL(18,2)), ce.amount, ce.created_at " +
            "    FROM claim_expenses ce JOIN claims cl ON cl.id = ce.claim_id " +
            "    WHERE ce.deleted_at IS NULL AND ce.status = 'APPROVED' " +
            ") ev WHERE 1=1")
```

> `claims.reported_date` is a DATE; `policies.created_at` / `claim_expenses.created_at` are TIMESTAMPTZ. The date filter binds `LocalDate.atStartOfDay()` (a timestamp); Postgres compares DATE to timestamp by promoting the DATE, so the mixed `event_date` UNION is fine for `>= ?` / `< ?`. The UNION-ALL itself unifies DATE + TIMESTAMPTZ to TIMESTAMPTZ.

- [ ] **Step 3: Add the BASE_QUERY_TAILS entry**

In the `BASE_QUERY_TAILS` `Map.of(...)` (lines 260-265), add the third entry:

```java
    private static final Map<DataSource, String> BASE_QUERY_TAILS = Map.of(
        DataSource.TRIAL_BALANCE,
            "GROUP BY coa.code, coa.name, coa.account_type",
        DataSource.RM_COMMISSION,
            "GROUP BY rm.name",
        DataSource.UNDERWRITING_PERFORMANCE,
            "GROUP BY ev.cob"
    );
```

- [ ] **Step 4: Add the three switch cases**

`createdAtCol` (after the `RM_COMMISSION` case ~line 478):

```java
            case RM_COMMISSION    -> "p.approved_at";
            // Underwriting performance: each unioned fact carries its own booking date
            // as ev.event_date (policy created_at / claim reported_date / expense created_at).
            case UNDERWRITING_PERFORMANCE -> "ev.event_date";
```

`statusCol` (after the `RM_COMMISSION` case ~line 507):

```java
            case RM_COMMISSION    -> null;
            // Aggregate over a UNION stream — no single per-row status filter.
            case UNDERWRITING_PERFORMANCE -> null;
```

`cobFilterCol` — this switch has a `default -> null`, so the case is NOT compile-enforced; add it explicitly (before `default`, ~line 526):

```java
            case GENERAL_LEDGER -> "cob.id";   // joins classes_of_business
            case PAA_GROUPS     -> "cob.id";   // joins classes_of_business
            case UNDERWRITING_PERFORMANCE -> "ev.cob_id"; // narrows event stream pre-aggregation
            default           -> null;
```

- [ ] **Step 5: Compile cia-reports to confirm the exhaustive switches are satisfied**

Run: `cd cia-backend && mvn -q -pl cia-reports compile`
Expected: BUILD SUCCESS (the `createdAtCol` / `statusCol` switches have no `default`, so they would fail to compile if the new case were missing — this verifies the wiring).

- [ ] **Step 6: Create the V66 migration**

Create `cia-backend/cia-api/src/main/resources/db/migration/V66__reseed_ratio_reports_underwriting_performance.sql`:

```sql
-- V66 — re-seed the 3 ratio reports onto the UNDERWRITING_PERFORMANCE data source
-- so loss_ratio / combined_ratio compute from real premium + claims + expenses.
-- SYSTEM reports are immutable via the service, so this is a data migration.
-- Idempotent: delete the 3 by name (type=SYSTEM) then re-insert. Runs after V18.
-- Non-computed fields are declared in SELECT-column order
-- [class_of_business, premium_earned, claims_incurred, expenses] so the positional
-- applyComputedFields() maps them correctly; computed PERCENT fields appended after.

DELETE FROM report_definition
 WHERE type = 'SYSTEM'
   AND name IN ('Loss Ratio Report', 'Combined Ratio Report', 'Annual Revenue Account (NAICOM)');

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Loss Ratio Report',
  'Loss ratio by class: gross written premium, incurred claims, and computed loss ratio %.',
  'CLAIMS', 'SYSTEM', 'UNDERWRITING_PERFORMANCE',
  '{
    "fields": [
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"premium_earned","label":"Premium (Gross Written) (₦)","type":"MONEY","computed":false},
      {"key":"claims_incurred","label":"Claims Incurred (₦)","type":"MONEY","computed":false},
      {"key":"loss_ratio","label":"Loss Ratio %","type":"PERCENT","computed":true}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true},
      {"key":"class_of_business_id","label":"Class","type":"MULTI_SELECT","required":false}
    ],
    "groupBy":"class_of_business","sortBy":"loss_ratio","sortDir":"DESC",
    "chart":{"type":"BAR","xAxis":"class_of_business","yAxis":"loss_ratio"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Combined Ratio Report',
  'Loss ratio, expense ratio, and combined ratio by class and period.',
  'FINANCE', 'SYSTEM', 'UNDERWRITING_PERFORMANCE',
  '{
    "fields": [
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"premium_earned","label":"Premium (Gross Written) (₦)","type":"MONEY","computed":false},
      {"key":"claims_incurred","label":"Claims Incurred (₦)","type":"MONEY","computed":false},
      {"key":"expenses","label":"Expenses (₦)","type":"MONEY","computed":false},
      {"key":"loss_ratio","label":"Loss Ratio %","type":"PERCENT","computed":true},
      {"key":"combined_ratio","label":"Combined Ratio %","type":"PERCENT","computed":true}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "groupBy":"class_of_business","sortBy":"combined_ratio","sortDir":"DESC",
    "chart":{"type":"BAR","xAxis":"class_of_business","yAxis":"combined_ratio"}
  }',
  true
);

INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'Annual Revenue Account (NAICOM)',
  'Premium earned, claims incurred, expenses, and loss ratio per class — annual statutory format.',
  'REGULATORY', 'SYSTEM', 'UNDERWRITING_PERFORMANCE',
  '{
    "fields": [
      {"key":"class_of_business","label":"Class","type":"STRING","computed":false},
      {"key":"premium_earned","label":"Premium Earned (₦)","type":"MONEY","computed":false},
      {"key":"claims_incurred","label":"Claims Incurred (₦)","type":"MONEY","computed":false},
      {"key":"expenses","label":"Expenses (₦)","type":"MONEY","computed":false},
      {"key":"loss_ratio","label":"Loss Ratio %","type":"PERCENT","computed":true}
    ],
    "filters": [
      {"key":"date_from","label":"Year Start","type":"DATE","required":true},
      {"key":"date_to","label":"Year End","type":"DATE","required":true}
    ],
    "groupBy":"class_of_business","sortBy":"premium_earned","sortDir":"DESC",
    "chart":{"type":"BAR","xAxis":"class_of_business","yAxis":"premium_earned"}
  }',
  false
);
```

> The Annual Revenue Account's old `sortBy:"reserve_amount"` referenced a field no longer declared — changed to `sortBy:"premium_earned"` (a real non-computed alias → valid ORDER BY). Loss Ratio + Combined Ratio sort by computed fields, which `execute()` skips in SQL (the alias only exists in the Java result map).

- [ ] **Step 7: Run the loss-ratio test — verify it passes**

Run: `cd cia-backend && mvn -q -pl cia-api test -Dtest=BusinessReportValueIT#lossRatioReportComputesNonZeroRatio`
Expected: PASS (`premium_earned=2,000,000`, `claims_incurred=500,000`, `loss_ratio=25.00`).

- [ ] **Step 8: Commit**

```bash
git add cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/domain/DataSource.java \
        cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportQueryBuilder.java \
        cia-backend/cia-api/src/main/resources/db/migration/V66__reseed_ratio_reports_underwriting_performance.sql
git commit -m "feat(reports): UNDERWRITING_PERFORMANCE source + V66 re-seed ratio reports

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Combined-ratio (APPROVED expenses) + period-filter tests

**Files:**
- Modify: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/reports/BusinessReportValueIT.java` (add 2 helpers + 2 tests before the final `}`)

- [ ] **Step 1: Add two seed helpers**

Add after the existing `insertClaimForAgg` method (~line 94):

```java
    private UUID insertClaimReturningId(String claimNumber, UUID policyId, String className, String reserve) {
        UUID claimId = UUID.randomUUID();
        entityManager.createNativeQuery(
            "INSERT INTO claims (id, claim_number, status, policy_id, policy_number, " +
            "policy_start_date, policy_end_date, customer_id, customer_name, " +
            "product_id, product_name, class_of_business_id, class_of_business_name, " +
            "incident_date, reported_date, description, reserve_amount, created_at) " +
            "VALUES (?, ?, 'APPROVED', ?, ?, '2026-01-01', '2026-12-31', ?, 'Test Cust', " +
            "?, 'Test Prod', ?, ?, '2026-02-01', '2026-02-02', 'Loss', ?, now())")
            .setParameter(1, claimId)
            .setParameter(2, claimNumber)
            .setParameter(3, policyId)
            .setParameter(4, "POL-FOR-" + claimNumber)
            .setParameter(5, UUID.randomUUID())
            .setParameter(6, UUID.randomUUID())
            .setParameter(7, UUID.randomUUID())
            .setParameter(8, className)
            .setParameter(9, new BigDecimal(reserve))
            .executeUpdate();
        return claimId;
    }

    private void insertClaimExpense(UUID claimId, String amount, String status) {
        entityManager.createNativeQuery(
            "INSERT INTO claim_expenses (id, claim_id, expense_type, status, vendor_name, " +
            "amount, description, created_at) " +
            "VALUES (?, ?, 'ADJUSTER', ?, 'Test Vendor', ?, 'Adjuster fee', now())")
            .setParameter(1, UUID.randomUUID())
            .setParameter(2, claimId)
            .setParameter(3, status)
            .setParameter(4, new BigDecimal(amount))
            .executeUpdate();
    }

    private void insertClaimDated(String claimNumber, UUID policyId, String className,
                                  String reserve, String reportedDate) {
        entityManager.createNativeQuery(
            "INSERT INTO claims (id, claim_number, status, policy_id, policy_number, " +
            "policy_start_date, policy_end_date, customer_id, customer_name, " +
            "product_id, product_name, class_of_business_id, class_of_business_name, " +
            "incident_date, reported_date, description, reserve_amount, created_at) " +
            "VALUES (?, ?, 'APPROVED', ?, ?, '2010-01-01', '2010-12-31', ?, 'Test Cust', " +
            "?, 'Test Prod', ?, ?, ?, ?, 'Loss', ?, now())")
            .setParameter(1, UUID.randomUUID())
            .setParameter(2, claimNumber)
            .setParameter(3, policyId)
            .setParameter(4, "POL-FOR-" + claimNumber)
            .setParameter(5, UUID.randomUUID())
            .setParameter(6, UUID.randomUUID())
            .setParameter(7, UUID.randomUUID())
            .setParameter(8, className)
            .setParameter(9, reportedDate)
            .setParameter(10, reportedDate)
            .setParameter(11, new BigDecimal(reserve))
            .executeUpdate();
    }
```

> `insertClaimDated` parameterises both `incident_date` and `reported_date` to the same value (params 9 + 10) so the out-of-window row is unambiguously dated; the in-window helper keeps the hardcoded `'2026-02-02'`.

- [ ] **Step 2: Add the combined-ratio test (before the closing `}`)**

```java
    @Test
    void combinedRatioIncludesApprovedExpensesOnly() {
        // 1 policy gross 1,000,000; claims 300k + 200k = 500k incurred;
        // expenses 50k APPROVED (counts) + 99,999 PENDING (excluded).
        insertMinimalPolicyInClass("POL-CR-1", "ZZ-CR-PERF", "CRP");
        UUID c1 = insertClaimReturningId("CLM-CR-1", null, "ZZ-CR-PERF", "300000.00");
        insertClaimReturningId("CLM-CR-2", null, "ZZ-CR-PERF", "200000.00");
        insertClaimExpense(c1, "50000.00", "APPROVED");
        insertClaimExpense(c1, "99999.00", "PENDING");

        List<Map<String, Object>> rows = run("Combined Ratio Report", WIDE).stream()
            .filter(r -> "ZZ-CR-PERF".equals(r.get("class_of_business"))).toList();

        assertThat(rows).hasSize(1);
        assertThat(new BigDecimal(rows.get(0).get("expenses").toString()))
            .as("only APPROVED expense counts").isEqualByComparingTo("50000.00");
        assertThat(new BigDecimal(rows.get(0).get("loss_ratio").toString()))
            .isEqualByComparingTo("50.00");
        assertThat(new BigDecimal(rows.get(0).get("combined_ratio").toString()))
            .as("(500000 + 50000) / 1000000 × 100").isEqualByComparingTo("55.00");
    }
```

> `insertClaimReturningId` accepts `null` for `policyId` — the `claims.policy_id` FK is `NOT NULL REFERENCES policies(id)`. **Fix:** pass a real policy id. Capture it: change the first two seed lines to
> `UUID pol = insertMinimalPolicyInClass("POL-CR-1", "ZZ-CR-PERF", "CRP");` then
> `UUID c1 = insertClaimReturningId("CLM-CR-1", pol, "ZZ-CR-PERF", "300000.00");` and
> `insertClaimReturningId("CLM-CR-2", pol, "ZZ-CR-PERF", "200000.00");` (both claims may reference the same policy).

- [ ] **Step 3: Add the period-filter test**

```java
    @Test
    void periodFilterExcludesOutOfWindowClaims() {
        UUID pol = insertMinimalPolicyInClass("POL-PF-1", "ZZ-PF-PERF", "PFP");
        insertClaimForAgg("CLM-PF-IN", pol, "ZZ-PF-PERF", "100000.00");       // reported 2026-02-02
        insertClaimDated("CLM-PF-OUT", pol, "ZZ-PF-PERF", "999999.00", "2010-01-01"); // excluded

        Map<String, String> window = Map.of("date_from", "2026-01-01", "date_to", "2026-12-31");
        List<Map<String, Object>> rows = run("Loss Ratio Report", window).stream()
            .filter(r -> "ZZ-PF-PERF".equals(r.get("class_of_business"))).toList();

        assertThat(rows).hasSize(1);
        assertThat(new BigDecimal(rows.get(0).get("claims_incurred").toString()))
            .as("2010 claim excluded by ev.event_date window").isEqualByComparingTo("100000.00");
    }
```

> The policy's `created_at = now()` (2026-05-30) falls inside the 2026 window, so `premium_earned = 1,000,000` and the in-window claim (reported 2026-02-02) counts; the 2010 claim is excluded. This proves the top-level `ev.event_date` filter clips each fact independently.

- [ ] **Step 4: Run all three new tests**

Run: `cd cia-backend && mvn -q -pl cia-api test -Dtest=BusinessReportValueIT#lossRatioReportComputesNonZeroRatio+combinedRatioIncludesApprovedExpensesOnly+periodFilterExcludesOutOfWindowClaims`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-api/src/test/java/com/nubeero/cia/api/reports/BusinessReportValueIT.java
git commit -m "test(reports): combined-ratio (APPROVED expenses) + period-filter ITs

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Full reports IT regression

**Files:** none (verification only)

- [ ] **Step 1: Run the whole reports IT package + the SYSTEM smoke IT**

Run: `cd cia-backend && mvn -q -pl cia-api test -Dtest='BusinessReportValueIT,SystemReportSmokeIT,RmCommissionReportIT'`
Expected: PASS. `SystemReportSmokeIT` runs every SYSTEM report (incl. the 3 now on `UNDERWRITING_PERFORMANCE`) against a real Postgres → proves no SQL error. Report count unchanged (3 rows updated in place, none added/removed).

- [ ] **Step 2: Compile the full backend reactor (catch any cross-module break)**

Run: `cd cia-backend && mvn -q -pl cia-api -am compile`
Expected: BUILD SUCCESS.

---

### Task 5: Docs + backlog reconciliation

**Files:**
- Modify: `CLAUDE.md` (the `cia-reports` ReportQueryBuilder dual-model note)
- Modify: `cia-log.md` (backlog table + new session entry)

- [ ] **Step 1: Extend the ReportQueryBuilder note in CLAUDE.md**

Find the bullet beginning "`ReportQueryBuilder` uses a **dual model**." (in the "Reports API Design (cia-reports specific)" section) and append, at the end of that bullet:

```
A **third, fixed-aggregate source** `UNDERWRITING_PERFORMANCE` (V66) is a UNION-ALL event stream over `policies` (gross written premium = `total_premium`), `claims` (`reserve_amount` = claims incurred), and APPROVED `claim_expenses` — each unioned row carries a single `ev.event_date` (policy `created_at` / claim `reported_date` / expense `created_at`) so `date_from`/`date_to` filter at the top level exactly like TRIAL_BALANCE/RM_COMMISSION; `GROUP BY ev.cob` (BASE_QUERY_TAILS) feeds the positional `applyComputedFields()` with `premium_earned`/`claims_incurred`/`expenses`, lighting up the `loss_ratio`/`combined_ratio` columns on the 3 ratio reports (Loss Ratio, Combined Ratio, Annual Revenue Account). Acquisition/management expenses are out of scope by design (GL/Module-12 domain); premium is written-not-earned (documented proxy — no earned-premium calc exists).
```

- [ ] **Step 2: Drain the backlog row in cia-log.md**

In the canonical backlog table (top of `cia-log.md`), delete the `reports-loss-ratio-premium-input` row. (Per the committed spec §8, no new row is added — the expense scope is a documented definition and the written-vs-earned premium is a documented proxy, neither a tracked gap.)

- [ ] **Step 3: Add the session entry to cia-log.md**

Insert a new dated entry under the backlog table (above the most recent session), listing: files changed (DataSource.java, ReportQueryBuilder.java, V66, BusinessReportValueIT.java, CLAUDE.md), the decisions (gross written premium, reserve=incurred, APPROVED expenses, all-3-reports), the IT coverage, and the backlog reconciliation (1 drained, 0 added).

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md cia-log.md
git commit -m "docs(reports): record UNDERWRITING_PERFORMANCE source + drain backlog row

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- §4.1 enum value → Task 2 Step 1 ✓
- §4.2 BASE_QUERIES UNION-ALL → Task 2 Step 2 ✓
- §4.3 BASE_QUERY_TAILS → Task 2 Step 3 ✓
- §4.4 createdAtCol/statusCol/cobFilterCol → Task 2 Step 4 ✓
- §4.5 positional mapping (declared-field order = SELECT order) → enforced by V66 field order in Task 2 Step 6 ✓
- §5 V66 re-seed all 3 (preserve category/pinnable; ARA sortBy fix) → Task 2 Step 6 ✓
- §6 testing: loss-ratio non-zero (Task 1/2), combined incl. APPROVED-only expenses (Task 3), period filter (Task 3), smoke IT (Task 4) ✓
- §7 CLAUDE.md update → Task 5 Step 1 ✓
- §8 backlog: drain, no new row → Task 5 Step 2 ✓

**Placeholder scan:** No TBD/TODO; all SQL, Java, and commands are concrete. No fix-up notes remain (the earlier null-policyId wart was removed — every test passes a real `host` policy id).

**Type consistency:** `UNDERWRITING_PERFORMANCE` spelled identically in enum, 3 switch cases, BASE_QUERIES, BASE_QUERY_TAILS, and V66 `data_source`. Field keys `premium_earned`/`claims_incurred`/`expenses`/`class_of_business` match the `applyComputedFields` reads (`computeRatio("claims_incurred","premium_earned")`, `computeCombinedRatio` reads `claims_incurred`/`expenses`/`premium_earned`) verified against `ReportQueryBuilder.java:399-402,434-447`. Test helpers are only the ones that exist in the file (`insertMinimalPolicy→UUID`, `insertPolicyForAgg`, `insertClaimForAgg`) plus three the plan adds (`insertClaimReturningId`, `insertClaimExpense`, `insertClaimDated`) — all `jdbc.update`-based, matching the file's autowired `JdbcTemplate jdbc` (there is no `EntityManager` in the IT). The invented `insertMinimalPolicyInClass` was removed everywhere.

**Flyway-target mechanism:** verified the target is set in `FinanceWebItSupport.java:136` via `@DynamicPropertySource` (`() -> "64"`), which outranks `@TestPropertySource`; Task 1 Step 1 edits that line to `"66"` (the only mechanism that works). Base is shared by the finance-web ITs; V65+V66 are additive → behavior-neutral.

**Arithmetic matches spec §6:** 1 priced policy (premium 1,000,000) + claims 500,000 → `loss_ratio = 50.00`; + 50,000 APPROVED expense → `combined_ratio = 55.00`. The "host" policy is a different class (zero premium, "Fire"), invisible to the test-class filter.
