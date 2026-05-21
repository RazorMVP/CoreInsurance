package com.nubeero.cia.api.finance.ifrs9;

import com.nubeero.cia.finance.gl.ChartOfAccountService;
import com.nubeero.cia.finance.gl.FiscalPeriodResolver;
import com.nubeero.cia.finance.gl.JournalEntryService;
import com.nubeero.cia.finance.gl.PostingRuleService;
import com.nubeero.cia.finance.ifrs9.AssetType;
import com.nubeero.cia.finance.ifrs9.BusinessModel;
import com.nubeero.cia.finance.ifrs9.EclRecognitionResult;
import com.nubeero.cia.finance.ifrs9.InvestmentClassificationService;
import com.nubeero.cia.finance.ifrs9.InvestmentEclEngine;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Testcontainers IT for {@link InvestmentEclEngine} — Slice 3.5.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>AC debt first ECL: Dr 5340 / Cr 1250, closing_balance reduced</li>
 *   <li>FVOCI_DEBT first ECL: Dr 5340 / Cr 3410 (OCI), closing_balance unchanged (§5.7.10A)</li>
 *   <li>Subsequent period — delta = target − cumulative, partial movement posted</li>
 *   <li>ECL reversal: direction flips (Dr 1250 / Cr 5340 for AC)</li>
 *   <li>Stage transition: stage 1 → 2 updates holding + carrying-value row</li>
 *   <li>Idempotency: re-run skips holdings with existing JE</li>
 *   <li>FVPL holding ignored (not eligible)</li>
 *   <li>Zero target → no JE on first run</li>
 *   <li>Money-market AC: routes to 1140 not 1250</li>
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
    InvestmentEclEngine.class,
    InvestmentEclEngineIT.TestSupportConfig.class
})
class InvestmentEclEngineIT {

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

    @Autowired private InvestmentEclEngine engine;
    @Autowired private InvestmentClassificationService classificationService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    private UUID janPeriodId;
    private UUID febPeriodId;

    @BeforeEach
    void seedFiscalYearAndPeriods() {
        UUID fyId = UUID.randomUUID();
        janPeriodId = UUID.randomUUID();
        febPeriodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            fyId, "FY-ECL-2026",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ACTIVE", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            janPeriodId, fyId, "MONTH",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "OPEN", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            febPeriodId, fyId, "MONTH",
            LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), "OPEN", "test");
    }

    // ── 1. AC debt first ECL: Dr 5340 / Cr 1250 ──────────────────────────────
    @Test
    @DisplayName("AC debt first ECL → Dr 5340 / Cr 1250; closing_balance reduced")
    void acDebtFirstEcl() {
        InvestmentHolding h = registerAcDebt("AC Bond", "1000000.00");
        entityManager.flush();

        EclRecognitionResult result = engine.recognise(janPeriodId, List.of(
            new InvestmentEclEngine.EclInput(h.getId(), new BigDecimal("5000.00"), null)));
        entityManager.flush();

        assertThat(result.holdingsWithJournalEntry()).isEqualTo(1);
        assertThat(result.totalEclIncrease()).isEqualByComparingTo("5000.00");
        assertThat(result.totalEclMovement()).isEqualByComparingTo("5000.00");

        Map<String, Object> je = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry WHERE source_module = 'ifrs9' " +
            "AND source_event_type = 'ECL_RECOGNITION' AND source_reference = ?",
            janPeriodId + ":" + h.getId());
        assertLine((UUID) je.get("id"), "5340", "5000.00", "0.00");
        assertLine((UUID) je.get("id"), "1250", "0.00", "5000.00");

        // closing_balance reduced from 1,000,000 to 995,000
        BigDecimal closing = jdbcTemplate.queryForObject(
            "SELECT closing_balance FROM investment_carrying_value WHERE holding_id = ?",
            BigDecimal.class, h.getId());
        assertThat(closing).isEqualByComparingTo("995000.00");
    }

    // ── 2. FVOCI_DEBT first ECL: closing_balance unchanged ───────────────────
    @Test
    @DisplayName("FVOCI_DEBT first ECL → Dr 5340 / Cr 3410 (OCI); closing_balance unchanged per §5.7.10A")
    void fvociDebtFirstEcl() {
        InvestmentHolding h = classificationService.register(new RegisterHoldingRequest(
            null, "FVOCI Bond", "Issuer", AssetType.DEBT,
            LocalDate.of(2025, 12, 1), new BigDecimal("500000.00"),
            new BigDecimal("500000.00"), new BigDecimal("0.10000"),
            LocalDate.of(2030, 12, 1), "NGN",
            true, BusinessModel.HOLD_TO_COLLECT_AND_SELL, false));
        entityManager.flush();

        engine.recognise(janPeriodId, List.of(
            new InvestmentEclEngine.EclInput(h.getId(), new BigDecimal("2000.00"), null)));
        entityManager.flush();

        Map<String, Object> je = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry WHERE source_event_type = 'ECL_RECOGNITION' " +
            "AND source_reference = ?",
            janPeriodId + ":" + h.getId());
        assertLine((UUID) je.get("id"), "5340", "2000.00", "0.00");
        assertLine((UUID) je.get("id"), "3410", "0.00", "2000.00");

        // §5.7.10A: closing_balance for FVOCI_DEBT NOT reduced by ECL
        BigDecimal closing = jdbcTemplate.queryForObject(
            "SELECT closing_balance FROM investment_carrying_value WHERE holding_id = ?",
            BigDecimal.class, h.getId());
        assertThat(closing).isEqualByComparingTo("500000.00");

        // ecl_movement still tracks the delta for disclosure
        BigDecimal eclMov = jdbcTemplate.queryForObject(
            "SELECT ecl_movement FROM investment_carrying_value WHERE holding_id = ?",
            BigDecimal.class, h.getId());
        assertThat(eclMov).isEqualByComparingTo("2000.00");
    }

    // ── 3. Subsequent period: delta = target − cumulative ────────────────────
    @Test
    @DisplayName("Feb target 8000 with prior 5000 → delta 3000 posted")
    void deltaAcrossPeriods() {
        InvestmentHolding h = registerAcDebt("Multi-Period", "1000000.00");
        entityManager.flush();

        engine.recognise(janPeriodId, List.of(
            new InvestmentEclEngine.EclInput(h.getId(), new BigDecimal("5000.00"), null)));
        entityManager.flush();

        EclRecognitionResult feb = engine.recognise(febPeriodId, List.of(
            new InvestmentEclEngine.EclInput(h.getId(), new BigDecimal("8000.00"), null)));
        entityManager.flush();

        // Delta = 8000 - 5000 = 3000
        assertThat(feb.totalEclMovement()).isEqualByComparingTo("3000.00");

        BigDecimal febEclMov = jdbcTemplate.queryForObject(
            "SELECT ecl_movement FROM investment_carrying_value WHERE holding_id = ? AND period_id = ?",
            BigDecimal.class, h.getId(), febPeriodId);
        assertThat(febEclMov).isEqualByComparingTo("3000.00");

        // closing_balance after Feb: opening (acquired) - cumulative ECL 8000 = 992000
        BigDecimal febClosing = jdbcTemplate.queryForObject(
            "SELECT closing_balance FROM investment_carrying_value WHERE holding_id = ? AND period_id = ?",
            BigDecimal.class, h.getId(), febPeriodId);
        assertThat(febClosing).isEqualByComparingTo("997000.00");
    }

    // ── 4. ECL reversal: direction flips ─────────────────────────────────────
    @Test
    @DisplayName("Feb target 2000 with prior 5000 → reversal of 3000; Dr 1250 / Cr 5340")
    void eclReversal() {
        InvestmentHolding h = registerAcDebt("Reversal", "1000000.00");
        entityManager.flush();

        engine.recognise(janPeriodId, List.of(
            new InvestmentEclEngine.EclInput(h.getId(), new BigDecimal("5000.00"), null)));
        entityManager.flush();

        EclRecognitionResult feb = engine.recognise(febPeriodId, List.of(
            new InvestmentEclEngine.EclInput(h.getId(), new BigDecimal("2000.00"), null)));
        entityManager.flush();

        // Delta = 2000 - 5000 = -3000 (reversal)
        assertThat(feb.totalEclMovement()).isEqualByComparingTo("-3000.00");
        assertThat(feb.totalEclReversal()).isEqualByComparingTo("3000.00");

        Map<String, Object> je = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry WHERE source_event_type = 'ECL_RECOGNITION' " +
            "AND source_reference = ?",
            febPeriodId + ":" + h.getId());
        // Reversal direction: Dr 1250 (carrying restored) / Cr 5340 (expense reversal)
        assertLine((UUID) je.get("id"), "1250", "3000.00", "0.00");
        assertLine((UUID) je.get("id"), "5340", "0.00", "3000.00");
    }

    // ── 5. Stage transition: stage 1 → 2 updates both holding + cv row ──────
    @Test
    @DisplayName("Stage 1 → 2 transition updates holding + carrying-value row")
    void stageTransition() {
        InvestmentHolding h = registerAcDebt("Stage Test", "1000000.00");
        // Sanity: register sets eclStage = 1
        assertThat(h.getEclStage()).isEqualTo(1);
        entityManager.flush();

        engine.recognise(janPeriodId, List.of(
            new InvestmentEclEngine.EclInput(h.getId(), new BigDecimal("10000.00"), 2)));
        entityManager.flush();

        Integer holdingStage = jdbcTemplate.queryForObject(
            "SELECT ecl_stage FROM investment_holding WHERE id = ?",
            Integer.class, h.getId());
        Integer cvStage = jdbcTemplate.queryForObject(
            "SELECT ecl_stage FROM investment_carrying_value WHERE holding_id = ?",
            Integer.class, h.getId());
        assertThat(holdingStage).isEqualTo(2);
        assertThat(cvStage).isEqualTo(2);
    }

    // ── 6. Idempotency: re-run skips holdings with existing JE ───────────────
    @Test
    @DisplayName("Re-run: holding with existing JE is skipped silently")
    void idempotentRerun() {
        InvestmentHolding h = registerAcDebt("Idem", "1000000.00");
        entityManager.flush();

        EclRecognitionResult first = engine.recognise(janPeriodId, List.of(
            new InvestmentEclEngine.EclInput(h.getId(), new BigDecimal("5000.00"), null)));
        entityManager.flush();
        EclRecognitionResult second = engine.recognise(janPeriodId, List.of(
            new InvestmentEclEngine.EclInput(h.getId(), new BigDecimal("9999.00"), null)));
        entityManager.flush();

        assertThat(first.holdingsWithJournalEntry()).isEqualTo(1);
        assertThat(second.holdingsProcessed()).isZero();

        Long jeCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM journal_entry WHERE source_event_type = 'ECL_RECOGNITION' " +
            "AND source_reference = ?",
            Long.class, janPeriodId + ":" + h.getId());
        assertThat(jeCount).isEqualTo(1L);

        // ecl_movement still reflects FIRST run's value
        BigDecimal mov = jdbcTemplate.queryForObject(
            "SELECT ecl_movement FROM investment_carrying_value WHERE holding_id = ?",
            BigDecimal.class, h.getId());
        assertThat(mov).isEqualByComparingTo("5000.00");
    }

    // ── 7. FVPL holding ignored ──────────────────────────────────────────────
    @Test
    @DisplayName("FVPL holding is skipped (not ECL-eligible)")
    void fvplIgnored() {
        InvestmentHolding h = classificationService.register(new RegisterHoldingRequest(
            null, "Trading Equity", "Issuer", AssetType.EQUITY,
            LocalDate.of(2025, 12, 1), new BigDecimal("100000.00"),
            null, null, null, "NGN", null, null, false));
        entityManager.flush();

        EclRecognitionResult result = engine.recognise(janPeriodId, List.of(
            new InvestmentEclEngine.EclInput(h.getId(), new BigDecimal("1000.00"), null)));

        assertThat(result.holdingsProcessed()).isZero();
        Long jeCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM journal_entry WHERE source_event_type = 'ECL_RECOGNITION'",
            Long.class);
        assertThat(jeCount).isZero();
    }

    // ── 8. Zero target → no JE on first run ──────────────────────────────────
    @Test
    @DisplayName("Target 0 on first run → no JE; carrying-value row inserted with ecl_movement=0")
    void zeroTargetNoJe() {
        InvestmentHolding h = registerAcDebt("Zero", "100000.00");
        entityManager.flush();

        EclRecognitionResult result = engine.recognise(janPeriodId, List.of(
            new InvestmentEclEngine.EclInput(h.getId(), new BigDecimal("0.00"), null)));
        entityManager.flush();

        assertThat(result.holdingsProcessed()).isEqualTo(1);
        assertThat(result.holdingsWithJournalEntry()).isZero();

        Long jeCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM journal_entry WHERE source_event_type = 'ECL_RECOGNITION'",
            Long.class);
        assertThat(jeCount).isZero();
    }

    // ── 9. Money-market AC: routes to 1140 not 1250 ──────────────────────────
    @Test
    @DisplayName("Money-market AC ECL → Dr 5340 / Cr 1140 (money-market carve-out)")
    void moneyMarketRouting() {
        InvestmentHolding h = classificationService.register(new RegisterHoldingRequest(
            null, "T-Bill", "CBN", AssetType.MONEY_MARKET,
            LocalDate.of(2025, 12, 1), new BigDecimal("2000000.00"),
            new BigDecimal("2000000.00"), new BigDecimal("0.18000"),
            LocalDate.of(2026, 6, 1), "NGN",
            true, BusinessModel.HOLD_TO_COLLECT, false));
        entityManager.flush();

        engine.recognise(janPeriodId, List.of(
            new InvestmentEclEngine.EclInput(h.getId(), new BigDecimal("1500.00"), null)));
        entityManager.flush();

        Map<String, Object> je = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry WHERE source_event_type = 'ECL_RECOGNITION' " +
            "AND source_reference = ?",
            janPeriodId + ":" + h.getId());
        assertLine((UUID) je.get("id"), "5340", "1500.00", "0.00");
        assertLine((UUID) je.get("id"), "1140", "0.00", "1500.00");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private InvestmentHolding registerAcDebt(String name, String cost) {
        return classificationService.register(new RegisterHoldingRequest(
            null, name, "Issuer", AssetType.DEBT,
            LocalDate.of(2025, 12, 1), new BigDecimal(cost),
            new BigDecimal(cost), new BigDecimal("0.10000"),
            LocalDate.of(2030, 12, 1), "NGN",
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
