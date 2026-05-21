package com.nubeero.cia.api.finance.paa;

import com.nubeero.cia.finance.gl.ChartOfAccountService;
import com.nubeero.cia.finance.gl.FiscalPeriodResolver;
import com.nubeero.cia.finance.gl.JournalEntryService;
import com.nubeero.cia.finance.gl.PostingRuleService;
import com.nubeero.cia.finance.paa.DiscountUnwindEngine;
import com.nubeero.cia.finance.paa.InsuranceServiceResult;
import com.nubeero.cia.finance.paa.InsuranceServiceResultService;
import com.nubeero.cia.finance.paa.LicEngine;
import com.nubeero.cia.finance.paa.LrcEngine;
import com.nubeero.cia.finance.paa.OnerousContractTestEngine;
import com.nubeero.cia.finance.paa.PaaPeriodCloseResult;
import com.nubeero.cia.finance.paa.PaaPeriodCloseService;
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
 * End-to-end Testcontainers IT for {@link PaaPeriodCloseService} and
 * {@link InsuranceServiceResultService} — Slice 2.5.
 *
 * <p>Each test seeds: fiscal year + month, portfolio + group(s), policy
 * + assignment, optional claim, then runs the orchestrator. Tests cover:
 * <ol>
 *   <li>Full happy path: both engines run, service result aggregates.</li>
 *   <li>Idempotency: re-running the orchestrator skips both engines (engine
 *       results = null) and still returns the service result.</li>
 *   <li>Partial state: LRC pre-run, orchestrator skips LRC and runs LIC.</li>
 *   <li>Service result formula: revenue − expense = result, per group and
 *       in totals.</li>
 *   <li>Service result groups appear sorted by (portfolio, cohort,
 *       onerousness) — stable order for §103 disclosure.</li>
 *   <li>Service result of an empty period: zeros, no groups.</li>
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
    InsuranceServiceResultService.class,
    PaaPeriodCloseService.class,
    PaaPeriodCloseServiceIT.TestSupportConfig.class
})
class PaaPeriodCloseServiceIT {

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
        registry.add("spring.flyway.target", () -> "49");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired private PaaPeriodCloseService closeService;
    @Autowired private InsuranceServiceResultService serviceResultService;
    @Autowired private LrcEngine lrcEngine;
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
            fiscalYearId, "FY-PC-2026",
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

    // ── 1. Happy path: orchestrator runs both engines + computes service result ─
    @Test
    @DisplayName("happy path: orchestrator runs LRC + LIC and returns service result")
    void happyPath() {
        UUID groupId = seedGroup("PORT-PC-001", 2026);
        UUID policyId = seedPolicyAndAssignment(groupId, "POL-PC-001",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "120000.00");
        seedClaim(policyId, "CLM-PC-001", "APPROVED",
            ts(2026, 1, 10, 10, 0), null, "30000.00", null);
        entityManager.flush();

        PaaPeriodCloseResult result = closeService.closePeriod(janPeriodId);
        entityManager.flush();

        assertThat(result.lrc()).isNotNull();
        assertThat(result.lic()).isNotNull();
        assertThat(result.insuranceServiceResult()).isNotNull();

        // Revenue = 120000 × 31/365 = 10191.78
        assertThat(result.insuranceServiceResult().totalInsuranceRevenue()).isEqualByComparingTo("10191.78");
        // Expense = claims_incurred = 30000
        assertThat(result.insuranceServiceResult().totalInsuranceServiceExpense()).isEqualByComparingTo("30000.00");
        // Result = 10191.78 - 30000 = -19808.22 (loss-making for this period)
        assertThat(result.insuranceServiceResult().totalInsuranceServiceResult()).isEqualByComparingTo("-19808.22");
    }

    // ── 2. Idempotency: re-running orchestrator skips both engines ─────────
    @Test
    @DisplayName("re-running orchestrator skips both engines (results = null) and still returns service result")
    void rerunSkipsBothEngines() {
        UUID groupId = seedGroup("PORT-PC-002", 2026);
        UUID policyId = seedPolicyAndAssignment(groupId, "POL-PC-002",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "60000.00");
        seedClaim(policyId, "CLM-PC-002", "APPROVED",
            ts(2026, 1, 10, 10, 0), null, "10000.00", null);
        entityManager.flush();

        closeService.closePeriod(janPeriodId);
        entityManager.flush();

        PaaPeriodCloseResult second = closeService.closePeriod(janPeriodId);
        entityManager.flush();

        assertThat(second.lrc()).as("second run skips LRC").isNull();
        assertThat(second.lic()).as("second run skips LIC").isNull();
        // Service result is still freshly computed.
        assertThat(second.insuranceServiceResult().totalInsuranceServiceExpense()).isEqualByComparingTo("10000.00");
    }

    // ── 3. Partial state: LRC pre-run, orchestrator skips LRC + runs LIC ───
    @Test
    @DisplayName("LRC pre-run → orchestrator skips LRC engine but still runs LIC engine")
    void partialStateSkipsLrcOnly() {
        UUID groupId = seedGroup("PORT-PC-003", 2026);
        UUID policyId = seedPolicyAndAssignment(groupId, "POL-PC-003",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "60000.00");
        seedClaim(policyId, "CLM-PC-003", "APPROVED",
            ts(2026, 1, 10, 10, 0), null, "5000.00", null);
        entityManager.flush();

        // Pre-run LRC only.
        lrcEngine.recognise(janPeriodId);
        entityManager.flush();

        PaaPeriodCloseResult result = closeService.closePeriod(janPeriodId);
        entityManager.flush();

        assertThat(result.lrc()).as("LRC was already run; orchestrator skipped it").isNull();
        assertThat(result.lic()).as("LIC ran for the first time").isNotNull();
        assertThat(result.insuranceServiceResult().totalInsuranceServiceExpense()).isEqualByComparingTo("5000.00");
    }

    // ── 4. Service result formula per group: revenue − expense = result ────
    @Test
    @DisplayName("service result per group: insuranceServiceResult = revenue − expense")
    void serviceResultFormulaPerGroup() {
        UUID groupA = seedGroup("PORT-PC-004A", 2026);
        UUID groupB = seedGroup("PORT-PC-004B", 2026);
        UUID policyA = seedPolicyAndAssignment(groupA, "POL-PC-004A",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "365000.00");
        // Group B is seeded so the §103 disclosure picks up its zero-claim revenue.
        // The returned policy id isn't needed because no claim references it.
        seedPolicyAndAssignment(groupB, "POL-PC-004B",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "73000.00");
        seedClaim(policyA, "CLM-A", "APPROVED",
            ts(2026, 1, 10, 10, 0), null, "10000.00", null);
        entityManager.flush();

        InsuranceServiceResult result = closeService.closePeriod(janPeriodId).insuranceServiceResult();
        entityManager.flush();

        assertThat(result.byGroup()).hasSize(2);
        for (var group : result.byGroup()) {
            assertThat(group.insuranceServiceResult())
                .isEqualByComparingTo(group.insuranceRevenue().subtract(group.insuranceServiceExpense()));
        }
        // Group A: revenue = 365000 × 31/365 = 31000; expense = 10000; result = 21000
        // Group B: revenue = 73000 × 31/365 = 6200; expense = 0; result = 6200
        // Totals: revenue 37200; expense 10000; result 27200
        assertThat(result.totalInsuranceRevenue()).isEqualByComparingTo("37200.00");
        assertThat(result.totalInsuranceServiceExpense()).isEqualByComparingTo("10000.00");
        assertThat(result.totalInsuranceServiceResult()).isEqualByComparingTo("27200.00");
    }

    // ── 5. Stable ordering for §103 disclosure ─────────────────────────────
    @Test
    @DisplayName("service result groups ordered by (portfolio code, cohort year, onerousness)")
    void stableOrdering() {
        // Two portfolios, each with a 2026 NOT_ONEROUS group. Insert in
        // alpha-reverse order to prove the SQL ORDER BY does its work.
        UUID groupZ = seedGroup("PORT-Z-2026", 2026);
        UUID groupA = seedGroup("PORT-A-2026", 2026);
        UUID policyZ = seedPolicyAndAssignment(groupZ, "POL-Z-2026",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "365000.00");
        UUID policyA = seedPolicyAndAssignment(groupA, "POL-A-2026",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "365000.00");
        // Touch each group with a claim so they appear in the service result.
        seedClaim(policyZ, "CLM-Z", "APPROVED", ts(2026, 1, 10, 10, 0), null, "1000.00", null);
        seedClaim(policyA, "CLM-A", "APPROVED", ts(2026, 1, 10, 10, 0), null, "1000.00", null);
        entityManager.flush();

        InsuranceServiceResult result = closeService.closePeriod(janPeriodId).insuranceServiceResult();
        entityManager.flush();

        assertThat(result.byGroup())
            .extracting(InsuranceServiceResult.GroupResult::portfolioCode)
            .containsExactly("PORT-A-2026", "PORT-Z-2026");
    }

    // ── 6. Empty period: no rows, zero totals ──────────────────────────────
    @Test
    @DisplayName("period with no paa_lrc / paa_lic rows returns zero totals and empty byGroup")
    void emptyPeriodReturnsZeros() {
        // No groups, no policies, no claims seeded — period truly empty.
        InsuranceServiceResult result = serviceResultService.compute(janPeriodId);

        assertThat(result.byGroup()).isEmpty();
        assertThat(result.totalInsuranceRevenue()).isEqualByComparingTo("0.00");
        assertThat(result.totalInsuranceServiceExpense()).isEqualByComparingTo("0.00");
        assertThat(result.totalInsuranceServiceResult()).isEqualByComparingTo("0.00");
        assertThat(result.periodStart()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(result.periodEnd()).isEqualTo(LocalDate.of(2026, 1, 31));
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
            UUID.randomUUID(), "Test Product", "PROD-PC", new BigDecimal("0.0500"),
            UUID.randomUUID(), "Test COB", "COB-PC",
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
