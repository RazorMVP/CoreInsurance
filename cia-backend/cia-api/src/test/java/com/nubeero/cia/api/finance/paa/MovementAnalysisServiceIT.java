package com.nubeero.cia.api.finance.paa;

import com.nubeero.cia.finance.gl.ChartOfAccountService;
import com.nubeero.cia.finance.gl.FiscalPeriodResolver;
import com.nubeero.cia.finance.gl.JournalEntryService;
import com.nubeero.cia.finance.gl.PostingRuleService;
import com.nubeero.cia.finance.paa.DiscountUnwindEngine;
import com.nubeero.cia.finance.paa.LicEngine;
import com.nubeero.cia.finance.paa.LrcEngine;
import com.nubeero.cia.finance.paa.MovementAnalysis;
import com.nubeero.cia.finance.paa.MovementAnalysisService;
import com.nubeero.cia.finance.paa.OnerousContractTestEngine;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Testcontainers IT for {@link MovementAnalysisService} —
 * Slice 2.8. Each test seeds a complete period-close fixture, runs the
 * upstream engines (LRC + LIC + Unwind + Onerous), then asserts the
 * §103 movement analysis output.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Empty period: no rows in paa_lrc/paa_lic → byGroup empty, all totals zero.</li>
 *   <li>Single-policy, no claims: LRC totals populated, LIC zero.</li>
 *   <li>Single-policy with claim: LRC + LIC both populated.</li>
 *   <li>Onerous group: loss component appears in LRC totals.</li>
 *   <li>Per-group breakdown ordered by (portfolio_code, cohort_year, onerousness).</li>
 *   <li>Aggregate totals = sum of per-group rows.</li>
 *   <li>Total opening / closing liability = LRC + LIC.</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    ChartOfAccountService.class,
    FiscalPeriodResolver.class,
    JournalEntryService.class,
    PostingRuleService.class,
    LrcEngine.class,
    LicEngine.class,
    DiscountUnwindEngine.class,
    OnerousContractTestEngine.class,
    MovementAnalysisService.class,
    MovementAnalysisServiceIT.TestSupportConfig.class
})
class MovementAnalysisServiceIT {

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
        registry.add("spring.flyway.target", () -> "38");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired private MovementAnalysisService service;
    @Autowired private LrcEngine lrcEngine;
    @Autowired private LicEngine licEngine;
    @Autowired private OnerousContractTestEngine onerousEngine;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    private UUID janPeriodId;

    @BeforeEach
    void seedFiscalYearAndPeriod() {
        UUID fiscalYearId = UUID.randomUUID();
        janPeriodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            fiscalYearId, "FY-MA-2026",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ACTIVE", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            janPeriodId, fiscalYearId, "MONTH",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "OPEN", "test");
    }

    // ── 1. Empty period: all zeros, empty byGroup ────────────────────────────
    @Test
    @DisplayName("period with no paa_lrc / paa_lic rows: empty byGroup, all totals zero")
    void emptyPeriod() {
        MovementAnalysis ma = service.compute(janPeriodId);

        assertThat(ma.byGroup()).isEmpty();
        assertThat(ma.lrcTotals().opening()).isEqualByComparingTo("0.00");
        assertThat(ma.lrcTotals().closing()).isEqualByComparingTo("0.00");
        assertThat(ma.licTotals().opening()).isEqualByComparingTo("0.00");
        assertThat(ma.licTotals().closing()).isEqualByComparingTo("0.00");
        assertThat(ma.totalOpeningLiability()).isEqualByComparingTo("0.00");
        assertThat(ma.totalClosingLiability()).isEqualByComparingTo("0.00");
        assertThat(ma.periodStart()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(ma.periodEnd()).isEqualTo(LocalDate.of(2026, 1, 31));
    }

    // ── 2. Single policy, no claims: LRC populated, LIC zero ────────────────
    @Test
    @DisplayName("single policy with no claims: LRC totals reflect earnings; LIC zero")
    void singlePolicyNoClaims() {
        UUID groupId = seedGroup("PORT-NC", 2026);
        seedPolicyAndAssignment(groupId, "POL-NC",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "365000.00");
        runMeasurementUpstream();

        MovementAnalysis ma = service.compute(janPeriodId);

        assertThat(ma.byGroup()).hasSize(1);
        assertThat(ma.lrcTotals().premiumsReceived()).isEqualByComparingTo("365000.00");
        // 365000 × 31/365 = 31000
        assertThat(ma.lrcTotals().premiumEarned()).isEqualByComparingTo("31000.00");
        assertThat(ma.licTotals().opening()).isEqualByComparingTo("0.00");
        assertThat(ma.licTotals().closing()).isEqualByComparingTo("0.00");
    }

    // ── 3. Single policy with claim: LRC + LIC both populated ───────────────
    @Test
    @DisplayName("single policy with claim: LRC + LIC both populated, total = sum")
    void singlePolicyWithClaim() {
        UUID groupId = seedGroup("PORT-WC", 2026);
        UUID policyId = seedPolicyAndAssignment(groupId, "POL-WC",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "365000.00");
        seedClaim(policyId, "CLM-WC", "APPROVED",
            ts(2026, 1, 15, 10, 0), null, "50000.00", null);
        runMeasurementUpstream();

        MovementAnalysis ma = service.compute(janPeriodId);

        assertThat(ma.byGroup()).hasSize(1);
        assertThat(ma.licTotals().claimsIncurred()).isEqualByComparingTo("50000.00");
        // Total closing liability = lrcClosing + licClosing
        assertThat(ma.totalClosingLiability())
            .isEqualByComparingTo(ma.lrcTotals().closing().add(ma.licTotals().closing()));
    }

    // ── 4. Onerous group: loss component flows through to totals ────────────
    @Test
    @DisplayName("onerous group: loss_component + change appear in LRC totals")
    void onerousGroupLossComponent() {
        UUID groupId = seedGroup("PORT-ONEROUS-MA", 2026);
        UUID policyId = seedPolicyAndAssignment(groupId, "POL-ONEROUS-MA",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "365000.00");
        // earn 31000, claim 100000 → onerous test recognises LC = 69000
        seedClaim(policyId, "CLM-ONEROUS-MA", "APPROVED",
            ts(2026, 1, 15, 10, 0), null, "100000.00", null);
        runMeasurementUpstream();
        onerousEngine.test(janPeriodId);
        entityManager.flush();

        MovementAnalysis ma = service.compute(janPeriodId);

        assertThat(ma.byGroup()).hasSize(1);
        assertThat(ma.lrcTotals().lossComponent()).isEqualByComparingTo("69000.00");
        assertThat(ma.lrcTotals().lossComponentChange()).isEqualByComparingTo("69000.00");
    }

    // ── 5. Per-group ordering: by portfolio_code, cohort_year, onerousness ─
    @Test
    @DisplayName("byGroup ordered by (portfolio_code, cohort_year, onerousness)")
    void byGroupOrdering() {
        UUID groupZ = seedGroup("PORT-Z-MA", 2026);
        UUID groupA = seedGroup("PORT-A-MA", 2026);
        UUID groupM = seedGroup("PORT-M-MA", 2027);
        seedPolicyAndAssignment(groupZ, "POL-Z-MA",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "100000.00");
        seedPolicyAndAssignment(groupA, "POL-A-MA",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "100000.00");
        // groupM (2027) — policy starts in Jan 2026 but the group's cohort_year
        // is set to 2027 just for ordering — the engine doesn't care about the
        // semantic mismatch for this ordering test.
        seedPolicyAndAssignment(groupM, "POL-M-MA",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "100000.00");
        runMeasurementUpstream();

        MovementAnalysis ma = service.compute(janPeriodId);

        assertThat(ma.byGroup())
            .extracting(MovementAnalysis.GroupMovementEntry::portfolioCode)
            .containsExactly("PORT-A-MA", "PORT-M-MA", "PORT-Z-MA");
    }

    // ── 6. Aggregate totals = sum of per-group rows ─────────────────────────
    @Test
    @DisplayName("aggregate totals equal the sum across per-group rows")
    void aggregateTotalsMatch() {
        UUID groupA = seedGroup("PORT-AGG-A", 2026);
        UUID groupB = seedGroup("PORT-AGG-B", 2026);
        UUID policyA = seedPolicyAndAssignment(groupA, "POL-AGG-A",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "365000.00");
        UUID policyB = seedPolicyAndAssignment(groupB, "POL-AGG-B",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "730000.00");
        seedClaim(policyA, "CLM-AGG-A", "APPROVED",
            ts(2026, 1, 10, 10, 0), null, "10000.00", null);
        seedClaim(policyB, "CLM-AGG-B", "APPROVED",
            ts(2026, 1, 12, 10, 0), null, "20000.00", null);
        runMeasurementUpstream();

        MovementAnalysis ma = service.compute(janPeriodId);

        // Per-group sum equals total
        BigDecimal sumOpening = ma.byGroup().stream()
            .map(MovementAnalysis.GroupMovementEntry::totalOpening)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumClosing = ma.byGroup().stream()
            .map(MovementAnalysis.GroupMovementEntry::totalClosing)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(ma.totalOpeningLiability()).isEqualByComparingTo(sumOpening);
        assertThat(ma.totalClosingLiability()).isEqualByComparingTo(sumClosing);

        // Insurance liability = LRC + LIC
        assertThat(ma.totalOpeningLiability())
            .isEqualByComparingTo(ma.lrcTotals().opening().add(ma.licTotals().opening()));
        assertThat(ma.totalClosingLiability())
            .isEqualByComparingTo(ma.lrcTotals().closing().add(ma.licTotals().closing()));
    }

    // ── 7. Group dimensions preserved (cohort_year, onerousness, currency) ─
    @Test
    @DisplayName("group entries carry cohort_year, onerousness, currency dimensions")
    void groupDimensionsPreserved() {
        UUID groupId = seedGroup("PORT-DIMS-MA", 2026);
        seedPolicyAndAssignment(groupId, "POL-DIMS-MA",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "365000.00");
        runMeasurementUpstream();

        MovementAnalysis ma = service.compute(janPeriodId);

        assertThat(ma.byGroup()).hasSize(1);
        var entry = ma.byGroup().get(0);
        assertThat(entry.cohortYear()).isEqualTo(2026);
        assertThat(entry.onerousness()).isEqualTo("NOT_ONEROUS");
        assertThat(entry.groupStatus()).isEqualTo("OPEN");
        assertThat(entry.currencyCode()).isEqualTo("NGN");
        assertThat(entry.portfolioCode()).isEqualTo("PORT-DIMS-MA");
        assertThat(entry.portfolioName()).isEqualTo("Test PORT-DIMS-MA");
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

    private UUID seedPolicyAndAssignment(UUID groupId, String policyNumber,
                                          LocalDate startDate, LocalDate endDate, String netPremium) {
        UUID policyId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO policies (id, policy_number, customer_id, customer_name, " +
            "product_id, product_name, product_code, product_rate, " +
            "class_of_business_id, class_of_business_name, class_of_business_code, " +
            "policy_start_date, policy_end_date, net_premium, currency_code, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            policyId, policyNumber, UUID.randomUUID(), "Test Customer",
            UUID.randomUUID(), "Test Product", "PROD-MA", new BigDecimal("0.0500"),
            UUID.randomUUID(), "Test COB", "COB-MA",
            startDate, endDate, new BigDecimal(netPremium), "NGN", "APPROVED", "test");
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

    private void runMeasurementUpstream() {
        entityManager.flush();
        lrcEngine.recognise(janPeriodId);
        licEngine.recognise(janPeriodId);
        entityManager.flush();
    }

    @TestConfiguration
    @EnableCaching
    static class TestSupportConfig {

        @Bean
        CacheManager testCacheManager() {
            return new ConcurrentMapCacheManager(
                ChartOfAccountService.CACHE_BY_CODE,
                ChartOfAccountService.CACHE_BY_IFRS17,
                ChartOfAccountService.CACHE_BY_IFRS9,
                ChartOfAccountService.CACHE_TREE,
                PostingRuleService.CACHE_BY_EVENT_TYPE);
        }
    }
}
