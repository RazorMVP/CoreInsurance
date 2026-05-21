package com.nubeero.cia.api.finance.paa;

import com.nubeero.cia.finance.paa.LicEngine;
import com.nubeero.cia.finance.paa.LicRecognitionAlreadyDoneException;
import com.nubeero.cia.finance.paa.LicRecognitionResult;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end Testcontainers IT for {@link LicEngine} — Slice 2.4.
 *
 * <p>Each test seeds: fiscal year + months, portfolio + group, a policy,
 * a policy_group_assignment, then one or more claims at the right
 * approved_at / settled_at / dv_amount values to exercise the SQL roll-
 * forward. Invokes {@link LicEngine#recognise(UUID)} and verifies the
 * resulting {@code paa_lic} row.
 *
 * <p>Tests cover:
 * <ol>
 *   <li>single claim approved in period → opening 0, incurred = amount,
 *       closing = amount, no payment yet;</li>
 *   <li>same claim settled in the next period → opening = amount, paid =
 *       amount, closing 0;</li>
 *   <li>claim approved and settled in the same period → opening 0, incurred
 *       = amount, paid = amount, closing 0;</li>
 *   <li>two groups in same period each get their own paa_lic row;</li>
 *   <li>idempotency: re-running raises LicRecognitionAlreadyDoneException;</li>
 *   <li>group with no claim activity is skipped (no paa_lic row);</li>
 *   <li>dv_amount &lt; approved_amount → reserve true-up surfaces as
 *       (incurred − paid) &gt; (closing − opening) discrepancy and is
 *       documented (will be reconciled by Slice 2.7's case-reserve
 *       movement tracking).</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    LicEngine.class
})
class LicEngineIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ciatest")
            .withUsername("ciatest")
            .withPassword("ciatest");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.target", () -> "47");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired private LicEngine engine;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    private UUID fiscalYearId;
    private UUID janPeriodId;
    private UUID febPeriodId;

    @BeforeEach
    void seedFiscalYearAndPeriods() {
        fiscalYearId = UUID.randomUUID();
        janPeriodId = UUID.randomUUID();
        febPeriodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            fiscalYearId, "FY-LIC-2026",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ACTIVE", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            janPeriodId, fiscalYearId, "MONTH",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "OPEN", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            febPeriodId, fiscalYearId, "MONTH",
            LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), "OPEN", "test");
    }

    // ── 1. Claim approved in period, not yet settled ─────────────────────────
    @Test
    @DisplayName("claim approved in period → incurred = amount, closing = amount, no payment")
    void approvedNotYetSettled() {
        UUID groupId = seedGroup("PORT-LIC-001", 2026);
        UUID policyId = seedPolicyAndAssignment(groupId, "POL-LIC-001");
        seedClaim(policyId, "CLM-LIC-001", "APPROVED",
            ts(2026, 1, 15, 10, 0), null,
            "50000.00", null);
        entityManager.flush();

        LicRecognitionResult result = engine.recognise(janPeriodId);
        entityManager.flush();

        assertThat(result.groupsProcessed()).isEqualTo(1);
        assertThat(result.totalClaimsIncurred()).isEqualByComparingTo("50000.00");
        assertThat(result.totalClaimsPaid()).isEqualByComparingTo("0.00");

        Map<String, Object> lic = loadLic(groupId, janPeriodId);
        assertThat((BigDecimal) lic.get("opening_balance")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) lic.get("claims_incurred")).isEqualByComparingTo("50000.00");
        assertThat((BigDecimal) lic.get("claims_paid")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) lic.get("closing_balance")).isEqualByComparingTo("50000.00");
    }

    // ── 2. Claim approved in Jan, settled in Feb ─────────────────────────────
    @Test
    @DisplayName("claim spanning two periods: incurred in Jan, paid in Feb")
    void approvedJanSettledFeb() {
        UUID groupId = seedGroup("PORT-LIC-002", 2026);
        UUID policyId = seedPolicyAndAssignment(groupId, "POL-LIC-002");
        seedClaim(policyId, "CLM-LIC-002", "SETTLED",
            ts(2026, 1, 15, 10, 0),
            ts(2026, 2, 10, 14, 0),
            "75000.00", "75000.00");
        entityManager.flush();

        engine.recognise(janPeriodId);
        entityManager.flush();
        // January: incurred = 75000, paid = 0 (settled in Feb, not yet)
        Map<String, Object> janLic = loadLic(groupId, janPeriodId);
        assertThat((BigDecimal) janLic.get("claims_incurred")).isEqualByComparingTo("75000.00");
        assertThat((BigDecimal) janLic.get("claims_paid")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) janLic.get("closing_balance")).isEqualByComparingTo("75000.00");

        LicRecognitionResult feb = engine.recognise(febPeriodId);
        entityManager.flush();
        // February: opening = 75000 (still open at Feb 1), paid = 75000, closing = 0
        Map<String, Object> febLic = loadLic(groupId, febPeriodId);
        assertThat((BigDecimal) febLic.get("opening_balance")).isEqualByComparingTo("75000.00");
        assertThat((BigDecimal) febLic.get("claims_incurred")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) febLic.get("claims_paid")).isEqualByComparingTo("75000.00");
        assertThat((BigDecimal) febLic.get("closing_balance")).isEqualByComparingTo("0.00");

        assertThat(feb.totalClaimsPaid()).isEqualByComparingTo("75000.00");
    }

    // ── 3. Same-period approved + settled ─────────────────────────────────────
    @Test
    @DisplayName("claim approved AND settled in same period → opening 0, closing 0, both incurred and paid")
    void approvedAndSettledSamePeriod() {
        UUID groupId = seedGroup("PORT-LIC-003", 2026);
        UUID policyId = seedPolicyAndAssignment(groupId, "POL-LIC-003");
        seedClaim(policyId, "CLM-LIC-003", "SETTLED",
            ts(2026, 1, 5, 10, 0),
            ts(2026, 1, 20, 14, 0),
            "30000.00", "30000.00");
        entityManager.flush();

        engine.recognise(janPeriodId);
        entityManager.flush();

        Map<String, Object> lic = loadLic(groupId, janPeriodId);
        assertThat((BigDecimal) lic.get("opening_balance")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) lic.get("claims_incurred")).isEqualByComparingTo("30000.00");
        assertThat((BigDecimal) lic.get("claims_paid")).isEqualByComparingTo("30000.00");
        assertThat((BigDecimal) lic.get("closing_balance")).isEqualByComparingTo("0.00");
    }

    // ── 4. Two groups in same period each get their own paa_lic row ──────────
    @Test
    @DisplayName("two groups produce two paa_lic rows in one engine run")
    void twoGroupsTwoRows() {
        UUID groupA = seedGroup("PORT-LIC-004A", 2026);
        UUID groupB = seedGroup("PORT-LIC-004B", 2026);
        UUID policyA = seedPolicyAndAssignment(groupA, "POL-LIC-A");
        UUID policyB = seedPolicyAndAssignment(groupB, "POL-LIC-B");
        seedClaim(policyA, "CLM-LIC-A", "APPROVED",
            ts(2026, 1, 10, 10, 0), null, "10000.00", null);
        seedClaim(policyB, "CLM-LIC-B", "APPROVED",
            ts(2026, 1, 12, 10, 0), null, "20000.00", null);
        entityManager.flush();

        LicRecognitionResult result = engine.recognise(janPeriodId);
        entityManager.flush();

        assertThat(result.groupsProcessed()).isEqualTo(2);
        assertThat(result.totalClaimsIncurred()).isEqualByComparingTo("30000.00");

        Long licCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM paa_lic WHERE period_id = ?", Long.class, janPeriodId);
        assertThat(licCount).isEqualTo(2L);
    }

    // ── 5. Idempotency: re-run raises LicRecognitionAlreadyDoneException ─────
    @Test
    @DisplayName("re-running raises LicRecognitionAlreadyDoneException; original row intact")
    void rerunRaisesAlreadyDone() {
        UUID groupId = seedGroup("PORT-LIC-005", 2026);
        UUID policyId = seedPolicyAndAssignment(groupId, "POL-LIC-005");
        seedClaim(policyId, "CLM-LIC-005", "APPROVED",
            ts(2026, 1, 10, 10, 0), null, "10000.00", null);
        entityManager.flush();

        engine.recognise(janPeriodId);
        entityManager.flush();

        assertThatThrownBy(() -> engine.recognise(janPeriodId))
            .isInstanceOf(LicRecognitionAlreadyDoneException.class);

        Long rows = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM paa_lic WHERE group_id = ? AND period_id = ?",
            Long.class, groupId, janPeriodId);
        assertThat(rows).isEqualTo(1L);
    }

    // ── 6. Group with no claim activity → no paa_lic row written ─────────────
    @Test
    @DisplayName("group with no claim activity in period is skipped (no paa_lic row)")
    void groupWithNoActivitySkipped() {
        UUID groupId = seedGroup("PORT-LIC-006", 2026);
        seedPolicyAndAssignment(groupId, "POL-LIC-006");
        // No claims seeded.
        entityManager.flush();

        LicRecognitionResult result = engine.recognise(janPeriodId);
        entityManager.flush();

        assertThat(result.groupsProcessed()).isZero();

        Long rows = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM paa_lic WHERE group_id = ?", Long.class, groupId);
        assertThat(rows).isZero();
    }

    // ── 7. dv_amount < approved_amount → reserve true-up gap surfaces ────────
    @Test
    @DisplayName("dv_amount < approved_amount: paid uses dv_amount; reserve true-up gap is the difference")
    void dvAmountLessThanApprovedSurfaceesTrueUp() {
        UUID groupId = seedGroup("PORT-LIC-007", 2026);
        UUID policyId = seedPolicyAndAssignment(groupId, "POL-LIC-007");
        // approved 100k, settled for 80k (negotiated down)
        seedClaim(policyId, "CLM-LIC-007", "SETTLED",
            ts(2026, 1, 5, 10, 0),
            ts(2026, 1, 20, 14, 0),
            "100000.00", "80000.00");
        entityManager.flush();

        engine.recognise(janPeriodId);
        entityManager.flush();

        Map<String, Object> lic = loadLic(groupId, janPeriodId);
        assertThat((BigDecimal) lic.get("claims_incurred")).isEqualByComparingTo("100000.00");
        // paid takes dv_amount (the actual cash out)
        assertThat((BigDecimal) lic.get("claims_paid")).isEqualByComparingTo("80000.00");
        // closing 0 because the claim IS settled (no remaining liability)
        assertThat((BigDecimal) lic.get("closing_balance")).isEqualByComparingTo("0.00");
        // Residual = incurred − paid − (closing − opening) = 100000 − 80000 − 0 = 20000
        // sits in 2140 as a reserve true-up to be cleared by Slice 2.7's case-reserve
        // movement tracking. The roll-forward identity does NOT hold in this case;
        // documented behaviour for v1.
    }

    // ── 8. Rejected / Withdrawn claims excluded ─────────────────────────────
    @Test
    @DisplayName("REJECTED and WITHDRAWN claims are excluded from LIC aggregation")
    void rejectedClaimsExcluded() {
        UUID groupId = seedGroup("PORT-LIC-008", 2026);
        UUID policyId = seedPolicyAndAssignment(groupId, "POL-LIC-008");
        // One real approval and one rejected claim
        seedClaim(policyId, "CLM-OK-008", "APPROVED",
            ts(2026, 1, 10, 10, 0), null, "10000.00", null);
        seedClaim(policyId, "CLM-REJ-008", "REJECTED",
            ts(2026, 1, 11, 10, 0), null, "99000.00", null);
        entityManager.flush();

        LicRecognitionResult result = engine.recognise(janPeriodId);
        entityManager.flush();

        assertThat(result.totalClaimsIncurred()).isEqualByComparingTo("10000.00");
        Map<String, Object> lic = loadLic(groupId, janPeriodId);
        assertThat((BigDecimal) lic.get("claims_incurred")).isEqualByComparingTo("10000.00");
    }

    // ── 9. v1 simplification fields stay at zero ────────────────────────────
    @Test
    @DisplayName("v1 leaves case_reserve_change / ibnr / RA / discount_unwind at zero")
    void v1SimplificationsAreZero() {
        UUID groupId = seedGroup("PORT-LIC-009", 2026);
        UUID policyId = seedPolicyAndAssignment(groupId, "POL-LIC-009");
        seedClaim(policyId, "CLM-LIC-009", "APPROVED",
            ts(2026, 1, 10, 10, 0), null, "50000.00", null);
        entityManager.flush();

        engine.recognise(janPeriodId);
        entityManager.flush();

        Map<String, Object> lic = jdbcTemplate.queryForMap(
            "SELECT case_reserve_change, ibnr_estimate, ibnr_change, risk_adjustment, " +
            "risk_adjustment_change, discount_unwind FROM paa_lic WHERE group_id = ? AND period_id = ?",
            groupId, janPeriodId);
        assertThat((BigDecimal) lic.get("case_reserve_change")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) lic.get("ibnr_estimate")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) lic.get("ibnr_change")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) lic.get("risk_adjustment")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) lic.get("risk_adjustment_change")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) lic.get("discount_unwind")).isEqualByComparingTo("0.00");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Timestamp ts(int y, int m, int d, int hour, int min) {
        return Timestamp.valueOf(LocalDateTime.of(LocalDate.of(y, m, d), LocalTime.of(hour, min)));
    }

    private UUID seedGroup(String portfolioCode, int cohortYear) {
        UUID portfolioId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO portfolio (id, code, name, created_by) VALUES (?, ?, ?, ?)",
            portfolioId, portfolioCode, "Test " + portfolioCode, "test");
        jdbcTemplate.update(
            "INSERT INTO group_of_contracts (id, portfolio_id, cohort_year, onerousness, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            groupId, portfolioId, cohortYear, "NOT_ONEROUS", "OPEN", "test");
        return groupId;
    }

    private UUID seedPolicyAndAssignment(UUID groupId, String policyNumber) {
        UUID policyId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO policies (id, policy_number, customer_id, customer_name, " +
            "product_id, product_name, product_code, product_rate, " +
            "class_of_business_id, class_of_business_name, class_of_business_code, " +
            "policy_start_date, policy_end_date, net_premium, currency_code, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            policyId, policyNumber, UUID.randomUUID(), "Test Customer",
            UUID.randomUUID(), "Test Product", "PROD-LIC", new BigDecimal("0.0500"),
            UUID.randomUUID(), "Test COB", "COB-LIC",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            new BigDecimal("100000.00"), "NGN", "APPROVED", "test");
        jdbcTemplate.update(
            "INSERT INTO policy_group_assignment (id, policy_id, group_id, assigned_at, created_by) " +
            "VALUES (?, ?, ?, now(), ?)",
            UUID.randomUUID(), policyId, groupId, "test");
        return policyId;
    }

    private void seedClaim(UUID policyId, String claimNumber, String status,
                            Timestamp approvedAt, Timestamp settledAt,
                            String approvedAmount, String dvAmount) {
        UUID claimId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO claims (id, claim_number, status, policy_id, policy_number, " +
            "policy_start_date, policy_end_date, customer_id, customer_name, product_id, product_name, " +
            "class_of_business_id, class_of_business_name, incident_date, reported_date, description, " +
            "reserve_amount, approved_amount, currency_code, approved_at, settled_at, dv_amount, " +
            "approved_by, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            claimId, claimNumber, status, policyId, "POL-FOR-" + claimNumber,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            UUID.randomUUID(), "Test Customer",
            UUID.randomUUID(), "Test Product",
            UUID.randomUUID(), "Test COB",
            LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 6), "Incident description",
            new BigDecimal("0"),
            approvedAmount == null ? null : new BigDecimal(approvedAmount),
            "NGN",
            approvedAt, settledAt,
            dvAmount == null ? null : new BigDecimal(dvAmount),
            "test", "test");
    }

    private Map<String, Object> loadLic(UUID groupId, UUID periodId) {
        return jdbcTemplate.queryForMap(
            "SELECT opening_balance, claims_incurred, claims_paid, closing_balance " +
            "FROM paa_lic WHERE group_id = ? AND period_id = ?",
            groupId, periodId);
    }
}
