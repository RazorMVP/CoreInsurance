package com.nubeero.cia.api.reports;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.reports.controller.dto.ReportRunRequest;
import com.nubeero.cia.reports.service.ReportRunnerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixed-source (BASE_QUERIES) column mapping value test. Complements
 * {@link BusinessReportValueIT} (business sources) and {@link SystemReportSmokeIT}
 * (every SYSTEM report executes without throwing). Fixed-source SELECTs lead with
 * undeclared identity/date columns not present in the declared field list, so the
 * old positional {@code applyComputedFields} mapping silently garbled every fixed
 * source's columns. Proven here against GENERAL_LEDGER's "General Journal Listing"
 * plus one value test per every other fixed-source SYSTEM report — 16 fixed-source
 * SYSTEM reports total, each asserting its canary column holds the seeded value and
 * is never a UUID (the tell-tale sign of the old positional-mapping bug, which
 * shifted every declared field by however many undeclared identity/date columns
 * led the hand-written SELECT).
 *
 * <p>{@code @Transactional} so the JDBC-seeded rows roll back per method — mirrors
 * {@link BusinessReportValueIT}.
 */
@Transactional
class FixedSourceReportValueIT extends FinanceWebItSupport {

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
        return reportRunnerService.run(req).getRows();
    }

    private static final Map<String, String> WIDE = Map.of("date_from", "2000-01-01", "date_to", "2100-01-01");

    // ── Shared GL seed helpers (Trial Balance / GENERAL_LEDGER reports) ───────────

    /**
     * Minimal fiscal_year + fiscal_period pair satisfying journal_entry.period_id's
     * NOT NULL FK. Fiscal year name is suffixed with a fresh UUID so repeated calls
     * (one per test method) never collide on the uq_fiscal_year_name constraint.
     */
    private UUID seedFiscalPeriod(String tag, LocalDate periodStart, LocalDate periodEnd) {
        UUID fiscalYearId = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();
        jdbc.update("INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, DATE '2026-01-01', DATE '2026-12-31', 'ACTIVE', 'test')",
            fiscalYearId, "FY-FSR-" + tag + "-" + fiscalYearId);
        jdbc.update("INSERT INTO fiscal_period " +
            "(id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, 'MONTH', ?, ?, 'OPEN', 'test')",
            periodId, fiscalYearId, periodStart, periodEnd);
        return periodId;
    }

    /** One POSTED journal entry + one line on a real (V32-seeded) COA account. */
    private UUID seedJournalEntry(UUID periodId, String accountCode, LocalDate businessDate,
                                   String sourceModule, String sourceEventType, String sourceRef,
                                   BigDecimal debit) {
        UUID accountId = jdbc.queryForObject(
            "SELECT id FROM chart_of_account WHERE code = ?", UUID.class, accountCode);
        UUID jeId = UUID.randomUUID();
        jdbc.update("INSERT INTO journal_entry " +
            "(id, posting_date, business_date, period_id, source_module, source_event_type, " +
            "source_reference, narrative, posted_by, status) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, 'test', 'test', 'POSTED')",
            jeId, businessDate, businessDate, periodId, sourceModule, sourceEventType, sourceRef);
        jdbc.update("INSERT INTO journal_entry_line " +
            "(id, journal_entry_id, line_no, account_id, debit_amount, credit_amount) " +
            "VALUES (?, ?, 1, ?, ?, 0.00)",
            UUID.randomUUID(), jeId, accountId, debit);
        return jeId;
    }

    // ── Shared UNDERWRITING_PERFORMANCE seed helpers (3 ratio reports) ────────────

    private UUID insertPolicyForUnderwriting(String policyNumber, String className,
                                              String productName, String premium) {
        UUID policyId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO policies (id, customer_id, customer_name, product_id, product_name, " +
                "product_code, product_rate, class_of_business_id, class_of_business_name, " +
                "class_of_business_code, policy_start_date, policy_end_date, policy_number, " +
                "total_sum_insured, total_premium, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            policyId, UUID.randomUUID(), "UW Fixture Co", UUID.randomUUID(), productName, "PRD",
            new BigDecimal("2.5000"), UUID.randomUUID(), className, "CLS",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), policyNumber,
            new BigDecimal("1000000.00"), new BigDecimal(premium), "ACTIVE");
        return policyId;
    }

    /** Returns the generated claim id, for expense-FK tests (Combined Ratio). */
    private UUID insertClaimForUnderwriting(String claimNumber, UUID policyId, String className, String reserve) {
        UUID claimId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO claims (id, claim_number, policy_id, policy_number, " +
                "policy_start_date, policy_end_date, customer_id, customer_name, " +
                "product_id, product_name, class_of_business_id, class_of_business_name, " +
                "status, reserve_amount, approved_amount, reported_date, incident_date, description) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            claimId, claimNumber, policyId, "POL-UW-" + claimNumber,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            UUID.randomUUID(), "UW Fixture Co", UUID.randomUUID(), className,
            UUID.randomUUID(), className, "APPROVED", new BigDecimal(reserve),
            new BigDecimal("0.00"), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 2, 20),
            "UW fixture claim");
        return claimId;
    }

    // ── 1. GENERAL_LEDGER — "General Journal Listing" (proof report, Task 1) ──────

    @Test
    void generalJournalListing_businessDateColumnHoldsDate_notUuid() {
        // COA is V32-seeded in the test tenant; look up a real postable account id by code.
        UUID accountId = jdbc.queryForObject(
            "SELECT id FROM chart_of_account WHERE code = '1330'", UUID.class);

        // journal_entry.period_id is a NOT NULL FK -> fiscal_period; seed a minimal
        // fiscal_year + fiscal_period to satisfy it (schema has no default seed).
        UUID fiscalYearId = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();
        jdbc.update("INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, DATE '2026-01-01', DATE '2026-12-31', 'ACTIVE', 'test')",
            fiscalYearId, "FY-FSR-" + fiscalYearId);
        jdbc.update("INSERT INTO fiscal_period " +
            "(id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, 'MONTH', DATE '2026-03-01', DATE '2026-03-31', 'OPEN', 'test')",
            periodId, fiscalYearId);

        UUID jeId = UUID.randomUUID();
        jdbc.update("INSERT INTO journal_entry " +
            "(id, posting_date, business_date, period_id, source_module, source_event_type, " +
            "source_reference, narrative, posted_by, status) " +
            "VALUES (?, DATE '2026-03-15', DATE '2026-03-15', ?, 'policy', 'POLICY_APPROVED', 'POL-1', 'test', 'test', 'POSTED')",
            jeId, periodId);
        jdbc.update("INSERT INTO journal_entry_line " +
            "(id, journal_entry_id, line_no, account_id, debit_amount, credit_amount) " +
            "VALUES (?, ?, 1, ?, 1000.00, 0.00)",
            UUID.randomUUID(), jeId, accountId);

        List<Map<String, Object>> rows = run("General Journal Listing", WIDE);

        assertThat(rows).isNotEmpty();
        Object businessDate = rows.get(0).get("business_date");
        // Correct: a date/timestamp. Bug (positional): the JE UUID string/UUID.
        assertThat(businessDate).isNotInstanceOf(UUID.class);
        assertThat(String.valueOf(businessDate)).startsWith("2026-03-15");
        // account_code must be the code, not a downstream-shifted value.
        assertThat(String.valueOf(rows.get(0).get("account_code"))).isEqualTo("1330");
    }

    // ── 2. TRIAL_BALANCE — "Trial Balance" (correct-today; regression cover) ──────

    @Test
    void trialBalance_accountCodeColumnHoldsCode_notUuid() {
        UUID periodId = seedFiscalPeriod("TB", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30));
        seedJournalEntry(periodId, "1330", LocalDate.of(2026, 4, 15),
            "policy", "POLICY_APPROVED", "POL-FSR-TB-1", new BigDecimal("2500.00"));

        List<Map<String, Object>> rows = run("Trial Balance", WIDE);

        Map<String, Object> row = rows.stream()
            .filter(r -> "1330".equals(String.valueOf(r.get("account_code")))).findFirst().orElseThrow();
        assertThat(row.get("account_code")).isNotInstanceOf(UUID.class);
        assertThat(String.valueOf(row.get("account_code"))).isEqualTo("1330");
        assertThat(row.get("total_debit")).isNotNull();
    }

    // ── 3. GENERAL_LEDGER — "Account Movement Statement" ──────────────────────────

    @Test
    void accountMovementStatement_businessDateColumnHoldsDate_notUuid() {
        UUID periodId = seedFiscalPeriod("AMS", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31));
        seedJournalEntry(periodId, "1330", LocalDate.of(2026, 5, 10),
            "policy", "POLICY_APPROVED", "POL-FSR-AMS-1", new BigDecimal("777.00"));

        List<Map<String, Object>> rows = run("Account Movement Statement", WIDE);

        Map<String, Object> row = rows.stream()
            .filter(r -> "POL-FSR-AMS-1".equals(r.get("source_reference"))).findFirst().orElseThrow();
        Object businessDate = row.get("business_date");
        assertThat(businessDate).isNotInstanceOf(UUID.class);
        assertThat(String.valueOf(businessDate)).startsWith("2026-05-10");
    }

    // ── 4. GL_PERIOD_LOCK — "Period Lock Audit Trail" ──────────────────────────────

    @Test
    void periodLockAuditTrail_periodStartColumnHoldsDate_notUuid() {
        UUID periodId = seedFiscalPeriod("PLA", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        jdbc.update("INSERT INTO period_lock (id, fiscal_period_id, lock_type, locked_at, locked_by) " +
            "VALUES (?, ?, 'SOFT', now(), 'fsr-test-user')", UUID.randomUUID(), periodId);

        List<Map<String, Object>> rows = run("Period Lock Audit Trail", WIDE);

        Map<String, Object> row = rows.stream()
            .filter(r -> "fsr-test-user".equals(r.get("locked_by"))).findFirst().orElseThrow();
        Object periodStart = row.get("period_start");
        assertThat(periodStart).isNotInstanceOf(UUID.class);
        assertThat(String.valueOf(periodStart)).isEqualTo("2026-06-01");
    }

    // ── 5-7. IFRS17_MOVEMENT — LRC / LIC / Insurance Service Result Summary ───────

    @Test
    void lrcRollforwardSchedule_portfolioNameColumnHoldsName_notUuid() {
        UUID periodId = seedFiscalPeriod("LRC", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        UUID portfolioId = UUID.randomUUID();
        jdbc.update("INSERT INTO portfolio (id, code, name) VALUES (?, 'FSR-LRC', 'Fixture LRC Portfolio')",
            portfolioId);
        UUID groupId = UUID.randomUUID();
        jdbc.update("INSERT INTO group_of_contracts (id, portfolio_id, cohort_year, onerousness, status) " +
            "VALUES (?, ?, 2026, 'NOT_ONEROUS', 'OPEN')", groupId, portfolioId);
        jdbc.update("INSERT INTO paa_lrc (id, group_id, period_id, opening_balance, premium_received, " +
            "premium_earned, closing_balance) VALUES (?, ?, ?, 0, 500000.00, 100000.00, 400000.00)",
            UUID.randomUUID(), groupId, periodId);

        List<Map<String, Object>> rows = run("LRC Roll-forward Schedule", WIDE);

        Map<String, Object> row = rows.stream()
            .filter(r -> "Fixture LRC Portfolio".equals(r.get("portfolio_name"))).findFirst().orElseThrow();
        assertThat(row.get("portfolio_name")).isNotInstanceOf(UUID.class);
        assertThat(new BigDecimal(row.get("premium_received").toString())).isEqualByComparingTo("500000.00");
    }

    @Test
    void licRollforwardSchedule_portfolioNameColumnHoldsName_notUuid() {
        UUID periodId = seedFiscalPeriod("LIC", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        UUID portfolioId = UUID.randomUUID();
        jdbc.update("INSERT INTO portfolio (id, code, name) VALUES (?, 'FSR-LIC', 'Fixture LIC Portfolio')",
            portfolioId);
        UUID groupId = UUID.randomUUID();
        jdbc.update("INSERT INTO group_of_contracts (id, portfolio_id, cohort_year, onerousness, status) " +
            "VALUES (?, ?, 2026, 'NOT_ONEROUS', 'OPEN')", groupId, portfolioId);
        jdbc.update("INSERT INTO paa_lic (id, group_id, period_id, opening_balance, claims_incurred, " +
            "claims_paid, closing_balance) VALUES (?, ?, ?, 0, 300000.00, 100000.00, 200000.00)",
            UUID.randomUUID(), groupId, periodId);

        List<Map<String, Object>> rows = run("LIC Roll-forward Schedule", WIDE);

        Map<String, Object> row = rows.stream()
            .filter(r -> "Fixture LIC Portfolio".equals(r.get("portfolio_name"))).findFirst().orElseThrow();
        assertThat(row.get("portfolio_name")).isNotInstanceOf(UUID.class);
        assertThat(new BigDecimal(row.get("claims_incurred").toString())).isEqualByComparingTo("300000.00");
    }

    @Test
    void insuranceServiceResultSummary_portfolioNameColumnHoldsName_notUuid() {
        UUID periodId = seedFiscalPeriod("ISR", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        UUID portfolioId = UUID.randomUUID();
        jdbc.update("INSERT INTO portfolio (id, code, name) VALUES (?, 'FSR-ISR', 'Fixture ISR Portfolio')",
            portfolioId);
        UUID groupId = UUID.randomUUID();
        jdbc.update("INSERT INTO group_of_contracts (id, portfolio_id, cohort_year, onerousness, status) " +
            "VALUES (?, ?, 2026, 'NOT_ONEROUS', 'OPEN')", groupId, portfolioId);
        jdbc.update("INSERT INTO paa_lrc (id, group_id, period_id, opening_balance, premium_earned, " +
            "closing_balance) VALUES (?, ?, ?, 0, 250000.00, 0)",
            UUID.randomUUID(), groupId, periodId);

        List<Map<String, Object>> rows = run("Insurance Service Result Summary", WIDE);

        Map<String, Object> row = rows.stream()
            .filter(r -> "Fixture ISR Portfolio".equals(r.get("portfolio_name"))).findFirst().orElseThrow();
        assertThat(row.get("portfolio_name")).isNotInstanceOf(UUID.class);
        assertThat(new BigDecimal(row.get("premium_earned").toString())).isEqualByComparingTo("250000.00");
    }

    // ── 8. PAA_GROUPS — "Contract Groups Listing" (worked example) ────────────────

    @Test
    void contractGroupsListing_portfolioCodeColumnHoldsCode_notUuid() {
        UUID portfolioId = UUID.randomUUID();
        jdbc.update("INSERT INTO portfolio (id, code, name, contract_nature) " +
            "VALUES (?, 'FIN-MOTOR', 'Motor', 'DIRECT')", portfolioId);
        jdbc.update("INSERT INTO group_of_contracts " +
            "(id, portfolio_id, cohort_year, onerousness, status) " +
            "VALUES (?, ?, 2026, 'NOT_ONEROUS', 'OPEN')", UUID.randomUUID(), portfolioId);

        List<Map<String, Object>> rows = run("Contract Groups Listing", WIDE);

        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).get("portfolio_code")).isNotInstanceOf(UUID.class);
        assertThat(String.valueOf(rows.get(0).get("portfolio_code"))).isEqualTo("FIN-MOTOR");
        assertThat(String.valueOf(rows.get(0).get("cohort_year"))).isEqualTo("2026");
    }

    // ── 9. IFRS9_HOLDINGS — "Investment Holdings Schedule" ─────────────────────────

    @Test
    void investmentHoldingsSchedule_isinColumnHoldsIsin_notUuid() {
        UUID holdingId = UUID.randomUUID();
        jdbc.update("INSERT INTO investment_holding (id, isin, security_name, issuer, asset_type, " +
            "classification, acquisition_date, acquisition_cost) " +
            "VALUES (?, 'NGFGN0000001', 'FGN Bond 2030', 'FGN', 'DEBT', 'AMORTISED_COST', " +
            "DATE '2026-01-15', 1000000.00)", holdingId);

        List<Map<String, Object>> rows = run("Investment Holdings Schedule", WIDE);

        Map<String, Object> row = rows.stream()
            .filter(r -> "NGFGN0000001".equals(r.get("isin"))).findFirst().orElseThrow();
        assertThat(row.get("isin")).isNotInstanceOf(UUID.class);
        assertThat(row.get("security_name")).isEqualTo("FGN Bond 2030");
    }

    // ── 10. IFRS9_CARRYING — "Investment Carrying Value Movement" ─────────────────

    @Test
    void investmentCarryingValueMovement_periodStartColumnHoldsDate_notUuid() {
        UUID periodId = seedFiscalPeriod("ICV", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        UUID holdingId = UUID.randomUUID();
        jdbc.update("INSERT INTO investment_holding (id, isin, security_name, asset_type, classification, " +
            "acquisition_date, acquisition_cost) VALUES (?, 'NGFGN0000002', 'FGN Bond 2031', 'DEBT', " +
            "'AMORTISED_COST', DATE '2026-01-15', 2000000.00)", holdingId);
        jdbc.update("INSERT INTO investment_carrying_value (id, holding_id, period_id, opening_balance, " +
            "effective_interest_income, closing_balance) VALUES (?, ?, ?, 2000000.00, 20000.00, 2020000.00)",
            UUID.randomUUID(), holdingId, periodId);

        List<Map<String, Object>> rows = run("Investment Carrying Value Movement", WIDE);

        Map<String, Object> row = rows.stream()
            .filter(r -> "NGFGN0000002".equals(r.get("isin"))).findFirst().orElseThrow();
        Object periodStart = row.get("period_start");
        assertThat(periodStart).isNotInstanceOf(UUID.class);
        assertThat(String.valueOf(periodStart)).isEqualTo("2026-08-01");
    }

    // ── 11. GENERAL_LEDGER — "Premium Receivable ECL Schedule" ────────────────────

    @Test
    void premiumReceivableEclSchedule_businessDateColumnHoldsDate_notUuid() {
        UUID periodId = seedFiscalPeriod("PRE", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        seedJournalEntry(periodId, "1330", LocalDate.of(2026, 9, 12),
            "premium_receivable_ecl", "PREMIUM_RECEIVABLE_ECL", "PRE-ECL-1", new BigDecimal("15000.00"));

        List<Map<String, Object>> rows = run("Premium Receivable ECL Schedule", WIDE);

        Map<String, Object> row = rows.stream()
            .filter(r -> "PRE-ECL-1".equals(r.get("source_reference"))).findFirst().orElseThrow();
        Object businessDate = row.get("business_date");
        assertThat(businessDate).isNotInstanceOf(UUID.class);
        assertThat(String.valueOf(businessDate)).startsWith("2026-09-12");
        assertThat(String.valueOf(row.get("account_code"))).isEqualTo("1330");
    }

    // ── 12. IFRS9_MOVEMENT — "§B5.5.39 Combined Movement Analysis" ────────────────

    @Test
    void combinedMovementAnalysis_periodStartColumnHoldsDate_notUuid() {
        UUID periodId = seedFiscalPeriod("IMV", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        UUID holdingId = UUID.randomUUID();
        jdbc.update("INSERT INTO investment_holding (id, isin, security_name, asset_type, classification, " +
            "acquisition_date, acquisition_cost) VALUES (?, 'NGFGN0000003', 'FGN Bond 2032', 'DEBT', " +
            "'FVOCI_DEBT', DATE '2026-01-15', 3000000.00)", holdingId);
        jdbc.update("INSERT INTO investment_carrying_value (id, holding_id, period_id, opening_balance, " +
            "fair_value_change_oci, closing_balance) VALUES (?, ?, ?, 3000000.00, 15000.00, 3015000.00)",
            UUID.randomUUID(), holdingId, periodId);

        List<Map<String, Object>> rows = run("§B5.5.39 Combined Movement Analysis", WIDE);

        Map<String, Object> row = rows.stream()
            .filter(r -> "NGFGN0000003".equals(r.get("isin"))).findFirst().orElseThrow();
        Object periodStart = row.get("period_start");
        assertThat(periodStart).isNotInstanceOf(UUID.class);
        assertThat(String.valueOf(periodStart)).isEqualTo("2026-10-01");
    }

    // ── 13. RM_COMMISSION — "RM Commission Accrual" (correct-today; regression cover) ─

    @Test
    void rmCommissionAccrual_relationshipManagerNameColumnHoldsName_notUuid() {
        UUID rmId = UUID.randomUUID();
        String rmName = "Fixture RM " + rmId;
        jdbc.update("INSERT INTO relationship_managers (id, name) VALUES (?, ?)", rmId, rmName);

        UUID policyId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO policies (id, customer_id, customer_name, product_id, product_name, " +
                "product_code, product_rate, class_of_business_id, class_of_business_name, " +
                "class_of_business_code, policy_start_date, policy_end_date, policy_number, " +
                "net_premium, commission_source_type, commission_rate, relationship_manager_id, approved_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            policyId, UUID.randomUUID(), "RM Fixture Co", UUID.randomUUID(), "Fire Special", "FIRE",
            new BigDecimal("2.5000"), UUID.randomUUID(), "Fire", "FIR",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "POL-FSR-RM-1",
            new BigDecimal("1000000.00"), "RELATIONSHIP_MANAGER", new BigDecimal("5.0000"),
            rmId, LocalDateTime.of(2026, 3, 1, 0, 0));

        List<Map<String, Object>> rows = run("RM Commission Accrual", WIDE);

        Map<String, Object> row = rows.stream()
            .filter(r -> rmName.equals(r.get("relationship_manager_name"))).findFirst().orElseThrow();
        assertThat(row.get("relationship_manager_name")).isNotInstanceOf(UUID.class);
        assertThat(new BigDecimal(row.get("total_accrued").toString())).isEqualByComparingTo("50000.00");
    }

    // ── 14-16. UNDERWRITING_PERFORMANCE — 3 ratio reports (correct-today; regression cover) ──

    @Test
    void lossRatioReport_classOfBusinessColumnHoldsClassName_notUuid() {
        UUID policyId = insertPolicyForUnderwriting("POL-FSR-LR-1", "ZZ-FSR-LR", "Fire Special", "1000000.00");
        insertClaimForUnderwriting("CLM-FSR-LR-1", policyId, "ZZ-FSR-LR", "300000.00");

        List<Map<String, Object>> rows = run("Loss Ratio Report", WIDE).stream()
            .filter(r -> "ZZ-FSR-LR".equals(r.get("class_of_business"))).toList();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("class_of_business")).isNotInstanceOf(UUID.class);
        assertThat(new BigDecimal(rows.get(0).get("loss_ratio").toString()))
            .as("300000 / 1000000 x 100").isEqualByComparingTo("30.00");
    }

    @Test
    void combinedRatioReport_classOfBusinessColumnHoldsClassName_notUuid() {
        UUID policyId = insertPolicyForUnderwriting("POL-FSR-CR-1", "ZZ-FSR-CR", "Fire Special", "1000000.00");
        UUID claimId = insertClaimForUnderwriting("CLM-FSR-CR-1", policyId, "ZZ-FSR-CR", "300000.00");
        jdbc.update("INSERT INTO claim_expenses (claim_id, expense_type, status, vendor_name, amount, description) " +
            "VALUES (?, 'ADJUSTER', 'APPROVED', 'Test Vendor', 50000.00, 'Adjuster fee')", claimId);

        List<Map<String, Object>> rows = run("Combined Ratio Report", WIDE).stream()
            .filter(r -> "ZZ-FSR-CR".equals(r.get("class_of_business"))).toList();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("class_of_business")).isNotInstanceOf(UUID.class);
        assertThat(new BigDecimal(rows.get(0).get("combined_ratio").toString()))
            .as("(300000 + 50000) / 1000000 x 100").isEqualByComparingTo("35.00");
    }

    @Test
    void annualRevenueAccount_classOfBusinessColumnHoldsClassName_notUuid() {
        UUID policyId = insertPolicyForUnderwriting("POL-FSR-ARA-1", "ZZ-FSR-ARA", "Fire Special", "1000000.00");
        insertClaimForUnderwriting("CLM-FSR-ARA-1", policyId, "ZZ-FSR-ARA", "200000.00");

        List<Map<String, Object>> rows = run("Annual Revenue Account (NAICOM)", WIDE).stream()
            .filter(r -> "ZZ-FSR-ARA".equals(r.get("class_of_business"))).toList();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("class_of_business")).isNotInstanceOf(UUID.class);
        assertThat(new BigDecimal(rows.get(0).get("premium_earned").toString()))
            .isEqualByComparingTo("1000000.00");
    }
}
