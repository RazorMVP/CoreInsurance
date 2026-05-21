package com.nubeero.cia.api.finance.ifrs9;

import com.nubeero.cia.finance.gl.ChartOfAccountService;
import com.nubeero.cia.finance.gl.FiscalPeriodResolver;
import com.nubeero.cia.finance.gl.JournalEntryService;
import com.nubeero.cia.finance.gl.PostingRuleService;
import com.nubeero.cia.finance.ifrs9.AmortisedCostEngine;
import com.nubeero.cia.finance.ifrs9.AssetType;
import com.nubeero.cia.finance.ifrs9.BusinessModel;
import com.nubeero.cia.finance.ifrs9.FairValueEngine;
import com.nubeero.cia.finance.ifrs9.FairValueResult;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Testcontainers IT for {@link FairValueEngine} — Slice 3.4.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>FVPL debt gain → Dr 1220 / Cr 4250; carrying_value inserted</li>
 *   <li>FVPL equity loss → Dr 5330 / Cr 1210</li>
 *   <li>FVOCI_EQUITY gain → Dr 1240 / Cr 3420 (OCI route, no recycling)</li>
 *   <li>FVOCI_DEBT after AC engine: UPDATE existing row, OCI route</li>
 *   <li>Idempotency: re-run skips holdings where closing_fair_value set</li>
 *   <li>AC holding ignored (not FV-eligible)</li>
 *   <li>Zero FV change posts no JE but writes carrying_value</li>
 *   <li>Holdings absent from request are not processed</li>
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
    FairValueEngine.class,
    FairValueEngineIT.TestSupportConfig.class
})
class FairValueEngineIT {

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

    @Autowired private FairValueEngine engine;
    @Autowired private AmortisedCostEngine acEngine;
    @Autowired private InvestmentClassificationService classificationService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    private UUID janPeriodId;

    @BeforeEach
    void seedFiscalYearAndPeriod() {
        UUID fyId = UUID.randomUUID();
        janPeriodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            fyId, "FY-FV-2026",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ACTIVE", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            janPeriodId, fyId, "MONTH",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "OPEN", "test");
    }

    // ── 1. FVPL debt gain → Dr 1220 / Cr 4250 ─────────────────────────────────
    @Test
    @DisplayName("FVPL debt gain → Dr 1220 / Cr 4250; carrying_value inserted with fair_value_change_pnl")
    void fvplDebtGain() {
        InvestmentHolding h = classificationService.register(new RegisterHoldingRequest(
            null, "Trading Bond", "Issuer", AssetType.DEBT,
            LocalDate.of(2025, 12, 1), new BigDecimal("100000.00"),
            new BigDecimal("100000.00"), new BigDecimal("0.10000"),
            LocalDate.of(2027, 12, 1), "NGN",
            true, BusinessModel.SELL_FIRST, false));
        entityManager.flush();

        // Mark fair value 105000 (gain of 5000)
        FairValueResult result = engine.recognise(janPeriodId,
            Map.of(h.getId(), new BigDecimal("105000.00")));
        entityManager.flush();

        assertThat(result.totalFairValueChangePnl()).isEqualByComparingTo("5000.00");
        assertThat(result.totalFairValueChangeOci()).isEqualByComparingTo("0.00");

        Map<String, Object> je = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry " +
            "WHERE source_module = 'ifrs9' AND source_event_type = 'FAIR_VALUE_REMEASUREMENT' " +
            "AND source_reference = ?",
            janPeriodId + ":" + h.getId());
        assertLine((UUID) je.get("id"), "1220", "5000.00", "0.00");
        assertLine((UUID) je.get("id"), "4250", "0.00", "5000.00");

        Map<String, Object> cv = jdbcTemplate.queryForMap(
            "SELECT fair_value_change_pnl, fair_value_change_oci, closing_balance, closing_fair_value " +
            "FROM investment_carrying_value WHERE holding_id = ?",
            h.getId());
        assertThat((BigDecimal) cv.get("fair_value_change_pnl")).isEqualByComparingTo("5000.00");
        assertThat((BigDecimal) cv.get("fair_value_change_oci")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) cv.get("closing_balance")).isEqualByComparingTo("105000.00");
        assertThat((BigDecimal) cv.get("closing_fair_value")).isEqualByComparingTo("105000.00");
    }

    // ── 2. FVPL equity loss → Dr 5330 / Cr 1210 ──────────────────────────────
    @Test
    @DisplayName("FVPL equity loss → Dr 5330 / Cr 1210")
    void fvplEquityLoss() {
        InvestmentHolding h = classificationService.register(new RegisterHoldingRequest(
            null, "Equity Ord Shares", "Issuer", AssetType.EQUITY,
            LocalDate.of(2025, 12, 1), new BigDecimal("50000.00"),
            null, null, null, "NGN",
            null, null, false));
        entityManager.flush();

        FairValueResult result = engine.recognise(janPeriodId,
            Map.of(h.getId(), new BigDecimal("45000.00")));
        entityManager.flush();

        assertThat(result.totalFairValueChangePnl()).isEqualByComparingTo("-5000.00");

        Map<String, Object> je = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry WHERE source_reference = ?",
            janPeriodId + ":" + h.getId());
        assertLine((UUID) je.get("id"), "5330", "5000.00", "0.00");
        assertLine((UUID) je.get("id"), "1210", "0.00", "5000.00");
    }

    // ── 3. FVOCI_EQUITY gain → Dr 1240 / Cr 3420 ─────────────────────────────
    @Test
    @DisplayName("FVOCI_EQUITY gain → Dr 1240 / Cr 3420 (OCI route, no recycling)")
    void fvociEquityGain() {
        InvestmentHolding h = classificationService.register(new RegisterHoldingRequest(
            null, "Strategic Stake", "BigCo", AssetType.EQUITY,
            LocalDate.of(2025, 12, 1), new BigDecimal("200000.00"),
            null, null, null, "NGN",
            null, null, true));
        entityManager.flush();

        FairValueResult result = engine.recognise(janPeriodId,
            Map.of(h.getId(), new BigDecimal("220000.00")));
        entityManager.flush();

        assertThat(result.totalFairValueChangePnl()).isEqualByComparingTo("0.00");
        assertThat(result.totalFairValueChangeOci()).isEqualByComparingTo("20000.00");

        Map<String, Object> je = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry WHERE source_reference = ?",
            janPeriodId + ":" + h.getId());
        assertLine((UUID) je.get("id"), "1240", "20000.00", "0.00");
        assertLine((UUID) je.get("id"), "3420", "0.00", "20000.00");
    }

    // ── 4. FVOCI_DEBT after AC engine: UPDATE existing row ────────────────────
    @Test
    @DisplayName("FVOCI_DEBT after AC engine ran: UPDATE existing carrying_value, post OCI JE")
    void fvociDebtAfterAcEngine() {
        InvestmentHolding h = classificationService.register(new RegisterHoldingRequest(
            null, "FVOCI Bond", "Issuer", AssetType.DEBT,
            LocalDate.of(2025, 1, 1), new BigDecimal("1000000.00"),
            new BigDecimal("1000000.00"), new BigDecimal("0.12000"),
            LocalDate.of(2031, 1, 1), "NGN",
            true, BusinessModel.HOLD_TO_COLLECT_AND_SELL, false));
        entityManager.flush();

        // AC engine runs first → writes carrying_value with effective_interest_income
        acEngine.recognise(janPeriodId);
        entityManager.flush();

        BigDecimal preFvBalance = jdbcTemplate.queryForObject(
            "SELECT closing_balance FROM investment_carrying_value WHERE holding_id = ?",
            BigDecimal.class, h.getId());
        // Interest accrual: 1000000 × 12% × 31/365 = 10191.78
        assertThat(preFvBalance).isEqualByComparingTo("1010191.78");

        // FV engine now marks the holding at 1,020,000 — gain of (1,020,000 − 1,010,191.78) = 9,808.22
        FairValueResult result = engine.recognise(janPeriodId,
            Map.of(h.getId(), new BigDecimal("1020000.00")));
        entityManager.flush();

        assertThat(result.totalFairValueChangeOci()).isEqualByComparingTo("9808.22");

        // Existing row was updated, NOT a duplicate inserted
        Long cvCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM investment_carrying_value WHERE holding_id = ?",
            Long.class, h.getId());
        assertThat(cvCount).isEqualTo(1L);

        Map<String, Object> cv = jdbcTemplate.queryForMap(
            "SELECT effective_interest_income, fair_value_change_oci, closing_balance, closing_fair_value " +
            "FROM investment_carrying_value WHERE holding_id = ?",
            h.getId());
        assertThat((BigDecimal) cv.get("effective_interest_income")).isEqualByComparingTo("10191.78");
        assertThat((BigDecimal) cv.get("fair_value_change_oci")).isEqualByComparingTo("9808.22");
        assertThat((BigDecimal) cv.get("closing_balance")).isEqualByComparingTo("1020000.00");
        assertThat((BigDecimal) cv.get("closing_fair_value")).isEqualByComparingTo("1020000.00");

        Map<String, Object> je = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry WHERE source_event_type = 'FAIR_VALUE_REMEASUREMENT' " +
            "AND source_reference = ?",
            janPeriodId + ":" + h.getId());
        assertLine((UUID) je.get("id"), "1230", "9808.22", "0.00");
        assertLine((UUID) je.get("id"), "3410", "0.00", "9808.22");
    }

    // ── 5. Idempotency: re-run skips holdings with closing_fair_value set ────
    @Test
    @DisplayName("Re-run: holdings already FV-recognised (closing_fair_value set) skipped silently")
    void idempotentRerun() {
        InvestmentHolding h = classificationService.register(new RegisterHoldingRequest(
            null, "Idem Equity", "Issuer", AssetType.EQUITY,
            LocalDate.of(2025, 12, 1), new BigDecimal("100000.00"),
            null, null, null, "NGN",
            null, null, false));
        entityManager.flush();

        FairValueResult first = engine.recognise(janPeriodId,
            Map.of(h.getId(), new BigDecimal("110000.00")));
        entityManager.flush();
        FairValueResult second = engine.recognise(janPeriodId,
            Map.of(h.getId(), new BigDecimal("999999.00")));
        entityManager.flush();

        assertThat(first.holdingsWithJournalEntry()).isEqualTo(1);
        assertThat(second.holdingsProcessed()).isZero();

        // Single JE — second run was a no-op
        Long jeCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM journal_entry WHERE source_reference = ?",
            Long.class, janPeriodId + ":" + h.getId());
        assertThat(jeCount).isEqualTo(1L);

        // closing_fair_value still reflects FIRST run's value, not the second
        BigDecimal cfv = jdbcTemplate.queryForObject(
            "SELECT closing_fair_value FROM investment_carrying_value WHERE holding_id = ?",
            BigDecimal.class, h.getId());
        assertThat(cfv).isEqualByComparingTo("110000.00");
    }

    // ── 6. AC holding ignored (not FV-eligible) ───────────────────────────────
    @Test
    @DisplayName("AC holding in valuations is skipped with warning; no JE")
    void acHoldingIgnored() {
        InvestmentHolding h = classificationService.register(new RegisterHoldingRequest(
            null, "Held to Maturity", "Issuer", AssetType.DEBT,
            LocalDate.of(2025, 12, 1), new BigDecimal("100000.00"),
            new BigDecimal("100000.00"), new BigDecimal("0.10000"),
            LocalDate.of(2030, 12, 1), "NGN",
            true, BusinessModel.HOLD_TO_COLLECT, false));
        entityManager.flush();

        FairValueResult result = engine.recognise(janPeriodId,
            Map.of(h.getId(), new BigDecimal("105000.00")));
        entityManager.flush();

        assertThat(result.holdingsProcessed()).isZero();

        Long jeCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM journal_entry WHERE source_event_type = 'FAIR_VALUE_REMEASUREMENT'",
            Long.class);
        assertThat(jeCount).isZero();
    }

    // ── 7. Zero FV change writes carrying_value but posts no JE ──────────────
    @Test
    @DisplayName("FV unchanged from acquisition: no JE posted but carrying_value written")
    void zeroFvChange() {
        InvestmentHolding h = classificationService.register(new RegisterHoldingRequest(
            null, "Stable", "Issuer", AssetType.EQUITY,
            LocalDate.of(2025, 12, 1), new BigDecimal("100000.00"),
            null, null, null, "NGN",
            null, null, false));
        entityManager.flush();

        FairValueResult result = engine.recognise(janPeriodId,
            Map.of(h.getId(), new BigDecimal("100000.00")));
        entityManager.flush();

        assertThat(result.holdingsProcessed()).isEqualTo(1);
        assertThat(result.holdingsWithJournalEntry()).isZero();
        assertThat(result.totalFairValueChangePnl()).isEqualByComparingTo("0.00");

        // carrying_value row IS written so re-runs idempotently skip
        Long cvCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM investment_carrying_value WHERE holding_id = ?",
            Long.class, h.getId());
        assertThat(cvCount).isEqualTo(1L);
    }

    // ── 8. Holdings absent from request not processed ─────────────────────────
    @Test
    @DisplayName("Multi-holding portfolio: only valuations in request are processed")
    void partialBatch() {
        InvestmentHolding inBatch = classificationService.register(new RegisterHoldingRequest(
            null, "In Batch", "Issuer", AssetType.EQUITY,
            LocalDate.of(2025, 12, 1), new BigDecimal("100000.00"),
            null, null, null, "NGN", null, null, false));
        InvestmentHolding notInBatch = classificationService.register(new RegisterHoldingRequest(
            null, "Not In Batch", "Issuer", AssetType.EQUITY,
            LocalDate.of(2025, 12, 1), new BigDecimal("200000.00"),
            null, null, null, "NGN", null, null, false));
        entityManager.flush();

        Map<UUID, BigDecimal> batch = new LinkedHashMap<>();
        batch.put(inBatch.getId(), new BigDecimal("110000.00"));
        FairValueResult result = engine.recognise(janPeriodId, batch);
        entityManager.flush();

        assertThat(result.holdingsProcessed()).isEqualTo(1);

        Long cvForOmitted = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM investment_carrying_value WHERE holding_id = ?",
            Long.class, notInBatch.getId());
        assertThat(cvForOmitted).isZero();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void assertLine(UUID journalEntryId, String accountCode,
                             String expectedDebit, String expectedCredit) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT l.debit_amount, l.credit_amount " +
            "FROM journal_entry_line l " +
            "JOIN chart_of_account a ON a.id = l.account_id " +
            "WHERE l.journal_entry_id = ? AND a.code = ?",
            journalEntryId, accountCode);
        assertThat((BigDecimal) row.get("debit_amount"))
            .as("debit for " + accountCode)
            .isEqualByComparingTo(expectedDebit);
        assertThat((BigDecimal) row.get("credit_amount"))
            .as("credit for " + accountCode)
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
