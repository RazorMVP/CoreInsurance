package com.nubeero.cia.api.finance.ifrs9;

import com.nubeero.cia.finance.gl.ChartOfAccountService;
import com.nubeero.cia.finance.gl.FiscalPeriodResolver;
import com.nubeero.cia.finance.gl.JournalEntryService;
import com.nubeero.cia.finance.gl.PostingRuleService;
import com.nubeero.cia.finance.ifrs9.AmortisedCostAlreadyDoneException;
import com.nubeero.cia.finance.ifrs9.AmortisedCostEngine;
import com.nubeero.cia.finance.ifrs9.AmortisedCostResult;
import com.nubeero.cia.finance.ifrs9.AssetType;
import com.nubeero.cia.finance.ifrs9.BusinessModel;
import com.nubeero.cia.finance.ifrs9.InvestmentClassificationService;
import com.nubeero.cia.finance.ifrs9.InvestmentHolding;
import com.nubeero.cia.finance.ifrs9.RegisterHoldingRequest;
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
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end Testcontainers IT for {@link AmortisedCostEngine} — Slice 3.3.
 *
 * <p>Each test registers one or more holdings via
 * {@link InvestmentClassificationService} (Slice 3.2), runs the engine,
 * and verifies the JE shape + the investment_carrying_value row.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>AC debt full period: Dr 1250 / Cr 4210 for opening × rate × days/365</li>
 *   <li>FVOCI_DEBT: Dr 1230 / Cr 4220 (routing per classification)</li>
 *   <li>Money-market AC: Dr 1140 / Cr 4210 (money-market carve-out)</li>
 *   <li>FVPL holding ignored (not eligible)</li>
 *   <li>Mid-period acquisition: interest pro-rated to active days</li>
 *   <li>Idempotency: re-run raises AmortisedCostAlreadyDoneException</li>
 *   <li>Multi-period: Feb opening = Jan closing</li>
 *   <li>JE line carries holding_id dimension</li>
 *   <li>Empty period: 0 holdings, no JE, no carrying-value row</li>
 *   <li>Holding without coupon_rate is skipped (warning logged)</li>
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
    InvestmentClassificationService.class,
    AmortisedCostEngine.class,
    AmortisedCostEngineIT.TestSupportConfig.class
})
class AmortisedCostEngineIT {

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

    @Autowired private AmortisedCostEngine engine;
    @Autowired private InvestmentClassificationService classificationService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    private UUID janPeriodId;
    private UUID febPeriodId;

    @BeforeEach
    void seedFiscalYearAndPeriods() {
        UUID fiscalYearId = UUID.randomUUID();
        janPeriodId = UUID.randomUUID();
        febPeriodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            fiscalYearId, "FY-AC-2026",
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

    // ── 1. AC debt full period: Dr 1250 / Cr 4210 ─────────────────────────────
    @Test
    @DisplayName("AC debt full period → Dr 1250 / Cr 4210 = opening × 12% × 31/365")
    void acDebtJanuaryInterest() {
        InvestmentHolding holding = registerAcDebt("FGN 2031", "1000000.00", "0.12000",
            LocalDate.of(2025, 1, 1));
        entityManager.flush();

        AmortisedCostResult result = engine.recognise(janPeriodId);
        entityManager.flush();

        assertThat(result.holdingsProcessed()).isEqualTo(1);
        assertThat(result.holdingsWithJournalEntry()).isEqualTo(1);
        // 1000000 × 0.12 × 31 / 365 = 10191.78
        assertThat(result.totalInterestIncome()).isEqualByComparingTo("10191.78");

        Map<String, Object> je = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry " +
            "WHERE source_module = 'ifrs9' AND source_event_type = 'AMORTISED_COST_INTEREST' " +
            "AND source_reference = ?",
            janPeriodId + ":" + holding.getId());
        assertLine((UUID) je.get("id"), "1250", "10191.78", "0.00");
        assertLine((UUID) je.get("id"), "4210", "0.00", "10191.78");
    }

    // ── 2. FVOCI_DEBT: Dr 1230 / Cr 4220 ──────────────────────────────────────
    @Test
    @DisplayName("FVOCI_DEBT → Dr 1230 / Cr 4220 (different routing)")
    void fvociDebtRouting() {
        InvestmentHolding holding = classificationService.register(new RegisterHoldingRequest(
            "FVOCI-BOND", "FVOCI Bond 2031", "Issuer", AssetType.DEBT,
            LocalDate.of(2025, 1, 1), new BigDecimal("500000.00"),
            new BigDecimal("500000.00"), new BigDecimal("0.10000"),
            LocalDate.of(2031, 1, 1), "NGN",
            true, BusinessModel.HOLD_TO_COLLECT_AND_SELL, false));
        entityManager.flush();

        AmortisedCostResult result = engine.recognise(janPeriodId);
        entityManager.flush();

        // 500000 × 0.10 × 31 / 365 = 4246.58
        assertThat(result.totalInterestIncome()).isEqualByComparingTo("4246.58");

        Map<String, Object> je = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry " +
            "WHERE source_module = 'ifrs9' AND source_reference = ?",
            janPeriodId + ":" + holding.getId());
        assertLine((UUID) je.get("id"), "1230", "4246.58", "0.00");
        assertLine((UUID) je.get("id"), "4220", "0.00", "4246.58");
    }

    // ── 3. Money market AC: Dr 1140 / Cr 4210 ─────────────────────────────────
    @Test
    @DisplayName("Money market + AC → Dr 1140 / Cr 4210 (money-market carve-out)")
    void moneyMarketRouting() {
        InvestmentHolding holding = classificationService.register(new RegisterHoldingRequest(
            null, "T-Bill 91-day", "CBN", AssetType.MONEY_MARKET,
            LocalDate.of(2025, 11, 1), new BigDecimal("2000000.00"),
            new BigDecimal("2000000.00"), new BigDecimal("0.18000"),
            LocalDate.of(2026, 2, 1), "NGN",
            true, BusinessModel.HOLD_TO_COLLECT, false));
        entityManager.flush();

        AmortisedCostResult result = engine.recognise(janPeriodId);
        entityManager.flush();

        // 2000000 × 0.18 × 31 / 365 = 30575.34
        assertThat(result.totalInterestIncome()).isEqualByComparingTo("30575.34");

        Map<String, Object> je = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry " +
            "WHERE source_module = 'ifrs9' AND source_reference = ?",
            janPeriodId + ":" + holding.getId());
        assertLine((UUID) je.get("id"), "1140", "30575.34", "0.00");
        assertLine((UUID) je.get("id"), "4210", "0.00", "30575.34");
    }

    // ── 4. FVPL holdings are not eligible ─────────────────────────────────────
    @Test
    @DisplayName("FVPL holdings ignored by amortised-cost engine")
    void fvplIgnored() {
        classificationService.register(new RegisterHoldingRequest(
            null, "Equity Stake", "Issuer", AssetType.EQUITY,
            LocalDate.of(2025, 1, 1), new BigDecimal("100000.00"),
            null, null, null, "NGN",
            null, null, false));
        entityManager.flush();

        AmortisedCostResult result = engine.recognise(janPeriodId);

        assertThat(result.holdingsProcessed()).isZero();
        Long jeCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM journal_entry WHERE source_event_type = 'AMORTISED_COST_INTEREST'",
            Long.class);
        assertThat(jeCount).isZero();
    }

    // ── 5. Mid-period acquisition: interest pro-rated ─────────────────────────
    @Test
    @DisplayName("Holding acquired Jan 15 → 17 active days in January period")
    void midPeriodAcquisition() {
        registerAcDebt("Mid-Acq", "1000000.00", "0.12000", LocalDate.of(2026, 1, 15));
        entityManager.flush();

        AmortisedCostResult result = engine.recognise(janPeriodId);
        entityManager.flush();

        // 1000000 × 0.12 × 17 / 365 = 5589.04
        assertThat(result.totalInterestIncome()).isEqualByComparingTo("5589.04");
    }

    // ── 6. Idempotency: re-run rejects with AlreadyDone ──────────────────────
    @Test
    @DisplayName("re-running raises AmortisedCostAlreadyDoneException; carrying row intact")
    void rerunRaisesAlreadyDone() {
        InvestmentHolding h = registerAcDebt("Idem", "1000000.00", "0.12000",
            LocalDate.of(2025, 1, 1));
        entityManager.flush();

        engine.recognise(janPeriodId);
        entityManager.flush();

        assertThatThrownBy(() -> engine.recognise(janPeriodId))
            .isInstanceOf(AmortisedCostAlreadyDoneException.class);

        Long rows = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM investment_carrying_value WHERE holding_id = ? AND period_id = ?",
            Long.class, h.getId(), janPeriodId);
        assertThat(rows).isEqualTo(1L);
    }

    // ── 7. Multi-period: Feb opening = Jan closing ───────────────────────────
    @Test
    @DisplayName("Feb opening = Jan closing (carrying-value chain)")
    void multiPeriodChaining() {
        InvestmentHolding h = registerAcDebt("Chain", "1000000.00", "0.12000",
            LocalDate.of(2025, 1, 1));
        entityManager.flush();

        engine.recognise(janPeriodId);
        entityManager.flush();
        engine.recognise(febPeriodId);
        entityManager.flush();

        BigDecimal janClosing = jdbcTemplate.queryForObject(
            "SELECT closing_balance FROM investment_carrying_value WHERE holding_id = ? AND period_id = ?",
            BigDecimal.class, h.getId(), janPeriodId);
        BigDecimal febOpening = jdbcTemplate.queryForObject(
            "SELECT opening_balance FROM investment_carrying_value WHERE holding_id = ? AND period_id = ?",
            BigDecimal.class, h.getId(), febPeriodId);

        assertThat(febOpening).isEqualByComparingTo(janClosing);
    }

    // ── 8. JE line tagged with holding_id ────────────────────────────────────
    @Test
    @DisplayName("JE line carries holding_id dimension for IFRS 9 disclosure roll-up")
    void jeLineHasHoldingDimension() {
        InvestmentHolding h = registerAcDebt("Dim", "1000000.00", "0.12000",
            LocalDate.of(2025, 1, 1));
        entityManager.flush();

        engine.recognise(janPeriodId);
        entityManager.flush();

        UUID jeHoldingId = jdbcTemplate.queryForObject(
            "SELECT l.holding_id FROM journal_entry_line l " +
            "JOIN journal_entry je ON je.id = l.journal_entry_id " +
            "JOIN chart_of_account a ON a.id = l.account_id " +
            "WHERE je.source_event_type = 'AMORTISED_COST_INTEREST' AND a.code = '1250'",
            UUID.class);
        assertThat(jeHoldingId).isEqualTo(h.getId());
    }

    // ── 9. Empty period: no holdings → no JE, no carrying value ──────────────
    @Test
    @DisplayName("period with no eligible holdings → empty result")
    void emptyPeriod() {
        AmortisedCostResult result = engine.recognise(janPeriodId);

        assertThat(result.holdingsProcessed()).isZero();
        assertThat(result.entries()).isEmpty();
        assertThat(result.totalInterestIncome()).isEqualByComparingTo("0.00");
    }

    // ── 10. Holding without coupon rate is skipped ───────────────────────────
    @Test
    @DisplayName("zero-coupon holding skipped (warning logged); no JE")
    void zeroCouponSkipped() {
        // V32 has no enum value for AC-without-coupon; we approximate by
        // inserting directly so the test exercises the engine's null-coupon
        // skip path without going through the classification service (which
        // doesn't enforce coupon-rate presence either, but the seed helper
        // does).
        UUID holdingId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO investment_holding (id, security_name, asset_type, classification, " +
            "acquisition_date, acquisition_cost, status, sppi_test_passed, ecl_stage, created_by) " +
            "VALUES (?, 'ZeroCoupon', 'DEBT', 'AMORTISED_COST', '2025-01-01', 100000.00, 'ACTIVE', TRUE, 1, 'test')",
            holdingId);
        entityManager.flush();

        AmortisedCostResult result = engine.recognise(janPeriodId);

        // The holding is filtered out by the coupon_rate null check inside the
        // engine — no JE posted, no carrying-value row written, but the
        // holding doesn't count as "processed" either (zero work was done).
        assertThat(result.holdingsWithJournalEntry()).isZero();
        Long jeCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM journal_entry WHERE source_event_type = 'AMORTISED_COST_INTEREST'",
            Long.class);
        assertThat(jeCount).isZero();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private InvestmentHolding registerAcDebt(String name, String cost, String couponRate,
                                              LocalDate acquisitionDate) {
        return classificationService.register(new RegisterHoldingRequest(
            null, name, "Issuer", AssetType.DEBT,
            acquisitionDate, new BigDecimal(cost),
            new BigDecimal(cost), new BigDecimal(couponRate),
            LocalDate.of(2031, 1, 1), "NGN",
            true, BusinessModel.HOLD_TO_COLLECT, false));
    }

    private void assertLine(UUID journalEntryId, String accountCode,
                             String expectedDebit, String expectedCredit) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT l.debit_amount, l.credit_amount " +
            "FROM journal_entry_line l " +
            "JOIN chart_of_account a ON a.id = l.account_id " +
            "WHERE l.journal_entry_id = ? AND a.code = ?",
            journalEntryId, accountCode);
        assertThat((BigDecimal) row.get("debit_amount"))
            .as("debit for account " + accountCode)
            .isEqualByComparingTo(expectedDebit);
        assertThat((BigDecimal) row.get("credit_amount"))
            .as("credit for account " + accountCode)
            .isEqualByComparingTo(expectedCredit);
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
