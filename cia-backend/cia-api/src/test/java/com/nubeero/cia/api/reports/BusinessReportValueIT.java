package com.nubeero.cia.api.reports;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.reports.controller.dto.ReportResultDto;
import com.nubeero.cia.reports.controller.dto.ReportRunRequest;
import com.nubeero.cia.reports.service.ReportRunnerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Per-business-source value assertions: seed minimal rows, run a representative
 * SYSTEM report, and assert the projected columns carry the right values — proving
 * the SOURCE_COLUMNS fieldKey->expr mapping is correct, not merely non-crashing.
 * Complements {@link SystemReportSmokeIT} (which proves every SYSTEM report executes).
 *
 * <p>{@code @Transactional} so the JDBC-seeded rows roll back per method — these ITs
 * share a singleton Postgres with {@code SystemReportSmokeIT} + {@code RmCommissionReportIT}
 * via Spring's context cache, and rollback keeps them hermetic (no residue that could
 * skew another report IT's row-count assertion). The report query joins the test's
 * transaction, so the uncommitted seeds are visible to it before rollback.
 *
 * @since reports-base-query-table-drift fix (Option A)
 */
@Transactional
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

    /**
     * Inserts a minimal policies row satisfying every NOT NULL constraint
     * and returns the generated id. Used as a FK target by claims + ri_allocations tests.
     */
    private UUID insertMinimalPolicy(String policyNumber) {
        UUID policyId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO policies (id, customer_id, customer_name, "
                + "product_id, product_name, product_code, product_rate, "
                + "class_of_business_id, class_of_business_name, class_of_business_code, "
                + "policy_start_date, policy_end_date, policy_number) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            policyId,
            UUID.randomUUID(), "Fixture Corp",
            UUID.randomUUID(), "Fire Special", "FIRE", new BigDecimal("2.5000"),
            UUID.randomUUID(), "Fire", "FIR",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            policyNumber);
        return policyId;
    }

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

    /** Insert a claim with explicit class + reserve (FK to a real policy). */
    private void insertClaimForAgg(String claimNumber, UUID policyId, String className, String reserve) {
        jdbc.update(
            "INSERT INTO claims (claim_number, policy_id, policy_number, "
                + "policy_start_date, policy_end_date, "
                + "customer_id, customer_name, "
                + "product_id, product_name, "
                + "class_of_business_id, class_of_business_name, "
                + "status, reserve_amount, approved_amount, "
                + "reported_date, incident_date, description) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            claimNumber, policyId, "POL-LR",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            UUID.randomUUID(), "AggCo",
            UUID.randomUUID(), className,
            UUID.randomUUID(), className,
            "APPROVED", new BigDecimal(reserve),
            new BigDecimal("0.00"), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 2, 20),
            "Agg test claim");
    }

    /** Like insertClaimForAgg but returns the generated claim id (for expense FK). */
    private UUID insertClaimReturningId(String claimNumber, UUID policyId, String className, String reserve) {
        UUID claimId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO claims (id, claim_number, policy_id, policy_number, "
                + "policy_start_date, policy_end_date, customer_id, customer_name, "
                + "product_id, product_name, class_of_business_id, class_of_business_name, "
                + "status, reserve_amount, approved_amount, reported_date, incident_date, description) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            claimId, claimNumber, policyId, "POL-FOR-" + claimNumber,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            UUID.randomUUID(), "AggCo", UUID.randomUUID(), className,
            UUID.randomUUID(), className, "APPROVED", new BigDecimal(reserve),
            new BigDecimal("0.00"), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 2, 20),
            "Agg test claim");
        return claimId;
    }

    /** Insert a claim_expenses row (NOT-NULL set: claim_id, expense_type, status, vendor_name, amount, description). */
    private void insertClaimExpense(UUID claimId, String amount, String status) {
        jdbc.update(
            "INSERT INTO claim_expenses (claim_id, expense_type, status, vendor_name, amount, description) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
            claimId, "ADJUSTER", status, "Test Vendor", new BigDecimal(amount), "Adjuster fee");
    }

    /** Like insertClaimForAgg but with an explicit reported_date (the CLAIMS-branch event_date). */
    private void insertClaimDated(String claimNumber, UUID policyId, String className,
                                  String reserve, LocalDate reportedDate) {
        jdbc.update(
            "INSERT INTO claims (claim_number, policy_id, policy_number, "
                + "policy_start_date, policy_end_date, customer_id, customer_name, "
                + "product_id, product_name, class_of_business_id, class_of_business_name, "
                + "status, reserve_amount, approved_amount, reported_date, incident_date, description) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            claimNumber, policyId, "POL-FOR-" + claimNumber,
            LocalDate.of(2010, 1, 1), LocalDate.of(2010, 12, 31),
            UUID.randomUUID(), "AggCo", UUID.randomUUID(), className,
            UUID.randomUUID(), className, "APPROVED", new BigDecimal(reserve),
            new BigDecimal("0.00"), reportedDate, reportedDate, "Out-of-window claim");
    }

    @Test
    void netWrittenPremiumAggregatesSumByClass() {
        insertPolicyForAgg("POL-AGG-1", "ZZ-AGG-FIRE", "Fire Special", "100000.00");
        insertPolicyForAgg("POL-AGG-2", "ZZ-AGG-FIRE", "Fire Special", "200000.00");
        insertPolicyForAgg("POL-AGG-3", "ZZ-AGG-MOTOR", "Motor Comp",  "50000.00");

        List<Map<String, Object>> rows = run("Net Written Premium", WIDE);

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
        // GROUP BY (class, product): the same (class, product) pair collapses to one
        // summed row, while a different product in the SAME class is a SEPARATE row.
        // This distinguishes group-by-(class,product) [→2 rows] from both no-aggregation
        // [→3 rows] and group-by-class-only [→1 row], so product is proven a dimension.
        insertPolicyForAgg("POL-GWP-1", "ZZ-GWP-FIRE", "Fire Std",     "100000.00");
        insertPolicyForAgg("POL-GWP-2", "ZZ-GWP-FIRE", "Fire Std",     "300000.00");
        insertPolicyForAgg("POL-GWP-3", "ZZ-GWP-FIRE", "Fire Premium", "50000.00");

        List<Map<String, Object>> fire = run("Gross Written Premium", WIDE).stream()
            .filter(r -> "ZZ-GWP-FIRE".equals(r.get("class_of_business"))).toList();

        assertThat(fire).as("GWP groups by (class, product) → 2 products → 2 rows").hasSize(2);
        Map<String, Object> std = fire.stream()
            .filter(r -> "Fire Std".equals(r.get("product_name"))).findFirst().orElseThrow();
        assertThat(new BigDecimal(std.get("premium").toString())).isEqualByComparingTo("400000.00");
        Map<String, Object> prem = fire.stream()
            .filter(r -> "Fire Premium".equals(r.get("product_name"))).findFirst().orElseThrow();
        assertThat(new BigDecimal(prem.get("premium").toString())).isEqualByComparingTo("50000.00");
    }

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

    @Test
    void policyRegisterMapsDenormalisedColumns() {
        jdbc.update(
            "INSERT INTO policies (customer_id, customer_name, "
                + "product_id, product_name, product_code, product_rate, "
                + "class_of_business_id, class_of_business_name, class_of_business_code, "
                + "policy_start_date, policy_end_date, policy_number, "
                + "total_sum_insured, total_premium, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), "Globex Ltd",
            UUID.randomUUID(), "Fire Special", "FIRE", new BigDecimal("2.5000"),
            UUID.randomUUID(), "Fire", "FIR",
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
        // claims.policy_id is a NOT NULL FK → policies(id); insert a fixture policy first.
        UUID policyId = insertMinimalPolicy("POL-CLM-FIX-001");

        jdbc.update(
            "INSERT INTO claims (claim_number, policy_id, policy_number, "
                + "policy_start_date, policy_end_date, "
                + "customer_id, customer_name, "
                + "product_id, product_name, "
                + "class_of_business_id, class_of_business_name, "
                + "status, reserve_amount, approved_amount, "
                + "reported_date, incident_date, description) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "CLM-VAL-001", policyId, "POL-VAL-001",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            UUID.randomUUID(), "Globex Ltd",
            UUID.randomUUID(), "Fire Special",
            UUID.randomUUID(), "Fire",
            "APPROVED", new BigDecimal("300000.00"),
            new BigDecimal("250000.00"),
            LocalDate.of(2026, 3, 1), LocalDate.of(2026, 2, 20),
            "Fire loss at warehouse");

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
                + "customer_id, customer_name, description, amount, total_amount, status, due_date) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "DN-VAL-001", "POLICY", UUID.randomUUID(), "POL-VAL-001",
            UUID.randomUUID(), "Globex Ltd",
            "Premium debit note for POL-VAL-001",
            new BigDecimal("125000.00"),
            new BigDecimal("125000.00"), "PENDING", LocalDate.of(2026, 2, 1));

        Map<String, Object> row = run("Debit Note Analysis", WIDE).stream()
            .filter(r -> "DN-VAL-001".equals(r.get("debit_note_number"))).findFirst().orElseThrow();
        assertThat(row.get("policy_number")).isEqualTo("POL-VAL-001");
        assertThat(row.get("customer_name")).isEqualTo("Globex Ltd");
        assertThat(new BigDecimal(row.get("amount").toString())).isEqualByComparingTo("125000.00");
    }

    @Test
    void riPremiumBordereauxMapsTreatyLabel() {
        // ri_allocations.policy_id is a NOT NULL FK → policies(id); insert a fixture policy first.
        UUID policyId = insertMinimalPolicy("POL-RI-FIX-001");

        UUID treatyId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO ri_treaties (id, treaty_type, treaty_year, description, status, "
                + "effective_date, expiry_date) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            treatyId, "SURPLUS", 2026, "Main Surplus Treaty 2026", "ACTIVE",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        jdbc.update(
            "INSERT INTO ri_allocations (allocation_number, policy_id, policy_number, treaty_id, "
                + "treaty_type, retained_amount, ceded_amount, "
                + "our_share_sum_insured, our_share_premium, retained_premium, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "RIA-VAL-001", policyId, "POL-VAL-001", treatyId, "SURPLUS",
            new BigDecimal("1000000.00"), new BigDecimal("4000000.00"),
            new BigDecimal("5000000.00"), new BigDecimal("125000.00"),
            new BigDecimal("25000.00"), "APPROVED");

        // RI report projects: policy_number, treaty_name, ceded_amount, status.
        // Filter by the unique ceded_amount we seeded to isolate this row.
        Map<String, Object> row = run("RI Premium Bordereaux", WIDE).stream()
            .filter(r -> new BigDecimal("4000000.00")
                .compareTo(new BigDecimal(r.get("ceded_amount").toString())) == 0)
            .findFirst().orElseThrow();
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

    /**
     * Exercises the {@code account_code} + {@code source_module} filter-injection
     * branches in {@code ReportQueryBuilder.execute()} for a GENERAL_LEDGER report
     * ("Account Movement Statement" declares both filters). No GL data is seeded — the
     * assertion is that both branches inject valid SQL and the query executes (an empty
     * result is correct). Closes the smoke IT's coverage gap on these two filter keys.
     */
    @Test
    void generalLedgerReportAppliesAccountCodeAndSourceModuleFilters() {
        Map<String, String> filters = Map.of(
            "date_from", "2000-01-01", "date_to", "2100-01-01",
            "account_code", "5130", "source_module", "POLICY");
        assertThatCode(() -> run("Account Movement Statement", filters))
            .doesNotThrowAnyException();
    }

    @Test
    void combinedRatioIncludesApprovedExpensesOnly() {
        // priced policy gross 1,000,000; claims 300k + 200k = 500k incurred;
        // expenses 50k APPROVED (counts) + 99,999 PENDING (excluded).
        UUID host = insertMinimalPolicy("POL-CR-HOST");          // claims FK target (class "Fire")
        insertPolicyForAgg("POL-CR-1", "ZZ-CR-PERF", "Fire Special", "1000000.00");
        UUID c1 = insertClaimReturningId("CLM-CR-1", host, "ZZ-CR-PERF", "300000.00");
        insertClaimReturningId("CLM-CR-2", host, "ZZ-CR-PERF", "200000.00");
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

    @Test
    void periodFilterExcludesOutOfWindowClaims() {
        UUID host = insertMinimalPolicy("POL-PF-HOST");          // claims FK target (class "Fire")
        insertPolicyForAgg("POL-PF-1", "ZZ-PF-PERF", "Fire Special", "1000000.00");
        insertClaimForAgg("CLM-PF-IN", host, "ZZ-PF-PERF", "100000.00");          // reported 2026-03-01 (in window)
        insertClaimDated("CLM-PF-OUT", host, "ZZ-PF-PERF", "999999.00",
                         LocalDate.of(2010, 1, 1));                                // reported 2010 (excluded)

        Map<String, String> window = Map.of("date_from", "2026-01-01", "date_to", "2026-12-31");
        List<Map<String, Object>> rows = run("Loss Ratio Report", window).stream()
            .filter(r -> "ZZ-PF-PERF".equals(r.get("class_of_business"))).toList();

        assertThat(rows).hasSize(1);
        assertThat(new BigDecimal(rows.get(0).get("claims_incurred").toString()))
            .as("2010 claim excluded by ev.event_date window").isEqualByComparingTo("100000.00");
    }
}
