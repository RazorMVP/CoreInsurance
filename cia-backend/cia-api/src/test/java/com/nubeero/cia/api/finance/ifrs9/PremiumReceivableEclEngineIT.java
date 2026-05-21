package com.nubeero.cia.api.finance.ifrs9;

import com.nubeero.cia.finance.gl.ChartOfAccountService;
import com.nubeero.cia.finance.gl.FiscalPeriodResolver;
import com.nubeero.cia.finance.gl.JournalEntryService;
import com.nubeero.cia.finance.gl.PostingRuleService;
import com.nubeero.cia.finance.ifrs9.PremiumReceivableEclEngine;
import com.nubeero.cia.finance.ifrs9.PremiumReceivableEclResult;
import com.nubeero.cia.finance.ifrs9.RecognisePremiumReceivableEclRequest.AgingBucket;
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
 * End-to-end Testcontainers IT for {@link PremiumReceivableEclEngine} —
 * Slice 3.6.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>First-period ECL recognition: Dr 5350 / Cr 1340 for total lifetime ECL</li>
 *   <li>Subsequent period delta: Feb target − Jan cumulative = movement</li>
 *   <li>Reversal: Feb target < Jan cumulative flips direction</li>
 *   <li>No-change: same target → no JE</li>
 *   <li>Idempotency: re-run returns NO_CHANGE result with original JE id</li>
 *   <li>Narrative carries per-bucket breakdown for §B5.5.36 disclosure</li>
 *   <li>Empty matrix → zero ECL, no JE on first period</li>
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
    PremiumReceivableEclEngine.class,
    PremiumReceivableEclEngineIT.TestSupportConfig.class
})
class PremiumReceivableEclEngineIT {

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

    @Autowired private PremiumReceivableEclEngine engine;
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
            fyId, "FY-PR-ECL-2026",
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

    // ── 1. First-period ECL: Dr 5350 / Cr 1340 ──────────────────────────────
    @Test
    @DisplayName("First-period lifetime ECL: Dr 5350 / Cr 1340 for the full target amount")
    void firstPeriodRecognition() {
        PremiumReceivableEclResult result = engine.recognise(janPeriodId, List.of(
            bucket("0-30 days",   "10000000", "0.005"),  //  50,000
            bucket("31-60 days",   "2000000", "0.020"),  //  40,000
            bucket("61-90 days",    "500000", "0.050")   //  25,000
        ));
        entityManager.flush();

        // 50000 + 40000 + 25000 = 115000
        assertThat(result.targetLifetimeEcl()).isEqualByComparingTo("115000.00");
        assertThat(result.priorCumulativeEcl()).isEqualByComparingTo("0.00");
        assertThat(result.eclMovement()).isEqualByComparingTo("115000.00");
        assertThat(result.direction()).isEqualTo("INCREASE");
        assertThat(result.journalEntryId()).isNotNull();
        assertThat(result.buckets()).hasSize(3);

        Map<String, Object> je = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry WHERE source_module = 'ifrs9' " +
            "AND source_event_type = 'PREMIUM_RECEIVABLE_ECL' AND source_reference = ?",
            janPeriodId + ":PREMIUM_RECEIVABLE");
        assertLine((UUID) je.get("id"), "5350", "115000.00", "0.00");
        assertLine((UUID) je.get("id"), "1340", "0.00", "115000.00");
    }

    // ── 2. Subsequent period delta ──────────────────────────────────────────
    @Test
    @DisplayName("Feb target 150,000 vs Jan cumulative 115,000 → +35,000 movement")
    void multiPeriodDelta() {
        engine.recognise(janPeriodId, List.of(
            bucket("0-30 days",   "10000000", "0.005"),
            bucket("31-60 days",   "2000000", "0.020"),
            bucket("61-90 days",    "500000", "0.050")
        ));
        entityManager.flush();

        PremiumReceivableEclResult feb = engine.recognise(febPeriodId, List.of(
            bucket("0-30 days",   "12000000", "0.005"),  //  60,000
            bucket("31-60 days",   "3000000", "0.020"),  //  60,000
            bucket("61-90 days",    "600000", "0.050")   //  30,000
        ));
        entityManager.flush();

        // Feb target = 150,000; prior = 115,000; delta = +35,000
        assertThat(feb.targetLifetimeEcl()).isEqualByComparingTo("150000.00");
        assertThat(feb.priorCumulativeEcl()).isEqualByComparingTo("115000.00");
        assertThat(feb.eclMovement()).isEqualByComparingTo("35000.00");
        assertThat(feb.direction()).isEqualTo("INCREASE");

        Map<String, Object> febJe = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry WHERE source_reference = ?",
            febPeriodId + ":PREMIUM_RECEIVABLE");
        assertLine((UUID) febJe.get("id"), "5350", "35000.00", "0.00");
        assertLine((UUID) febJe.get("id"), "1340", "0.00", "35000.00");
    }

    // ── 3. Reversal ─────────────────────────────────────────────────────────
    @Test
    @DisplayName("Receivables paid down: Feb target 50,000 vs Jan 115,000 → −65,000 reversal")
    void reversal() {
        engine.recognise(janPeriodId, List.of(
            bucket("0-30 days",   "10000000", "0.005"),
            bucket("31-60 days",   "2000000", "0.020"),
            bucket("61-90 days",    "500000", "0.050")
        ));
        entityManager.flush();

        PremiumReceivableEclResult feb = engine.recognise(febPeriodId, List.of(
            bucket("0-30 days",   "5000000", "0.005"),   //  25,000
            bucket("31-60 days",  "1000000", "0.020"),   //  20,000
            bucket("61-90 days",   "100000", "0.050")    //   5,000
        ));
        entityManager.flush();

        // Feb target = 50,000; prior = 115,000; delta = -65,000
        assertThat(feb.eclMovement()).isEqualByComparingTo("-65000.00");
        assertThat(feb.direction()).isEqualTo("REVERSAL");

        Map<String, Object> febJe = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry WHERE source_reference = ?",
            febPeriodId + ":PREMIUM_RECEIVABLE");
        // Reversal: Dr 1340 (allowance reduced) / Cr 5350 (expense reversed)
        assertLine((UUID) febJe.get("id"), "1340", "65000.00", "0.00");
        assertLine((UUID) febJe.get("id"), "5350", "0.00", "65000.00");
    }

    // ── 4. No-change ────────────────────────────────────────────────────────
    @Test
    @DisplayName("Feb matrix produces same target as Jan → no JE on Feb")
    void noChange() {
        var matrix = List.of(
            bucket("0-30 days",   "10000000", "0.005"),
            bucket("31-60 days",   "2000000", "0.020")
        );
        engine.recognise(janPeriodId, matrix);
        entityManager.flush();

        PremiumReceivableEclResult feb = engine.recognise(febPeriodId, matrix);
        entityManager.flush();

        assertThat(feb.eclMovement()).isEqualByComparingTo("0.00");
        assertThat(feb.direction()).isEqualTo("NO_CHANGE");
        assertThat(feb.journalEntryId()).isNull();

        Long febJeCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM journal_entry WHERE source_reference = ?",
            Long.class, febPeriodId + ":PREMIUM_RECEIVABLE");
        assertThat(febJeCount).isZero();
    }

    // ── 5. Idempotency: re-run returns NO_CHANGE with original JE id ────────
    @Test
    @DisplayName("Re-run for same period returns NO_CHANGE result + existing JE id")
    void idempotentRerun() {
        PremiumReceivableEclResult first = engine.recognise(janPeriodId, List.of(
            bucket("0-30 days", "1000000", "0.01")
        ));
        entityManager.flush();
        PremiumReceivableEclResult second = engine.recognise(janPeriodId, List.of(
            bucket("0-30 days", "9999999", "0.99")  // wildly different inputs
        ));
        entityManager.flush();

        assertThat(first.direction()).isEqualTo("INCREASE");
        assertThat(second.direction()).isEqualTo("NO_CHANGE");
        assertThat(second.journalEntryId()).isEqualTo(first.journalEntryId());

        Long jeCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM journal_entry WHERE source_reference = ?",
            Long.class, janPeriodId + ":PREMIUM_RECEIVABLE");
        assertThat(jeCount).isEqualTo(1L);
    }

    // ── 6. Narrative carries per-bucket breakdown for §B5.5.36 ──────────────
    @Test
    @DisplayName("JE narrative includes per-bucket breakdown for §B5.5.36 disclosure")
    void narrativeCarriesBreakdown() {
        engine.recognise(janPeriodId, List.of(
            bucket("0-30 days",  "1000000", "0.005"),
            bucket(">365 days",   "200000", "1.000")
        ));
        entityManager.flush();

        String narrative = jdbcTemplate.queryForObject(
            "SELECT narrative FROM journal_entry WHERE source_reference = ?",
            String.class, janPeriodId + ":PREMIUM_RECEIVABLE");
        assertThat(narrative).contains("0-30 days=1000000.00@0.005→5000.00");
        assertThat(narrative).contains(">365 days=200000.00@1.000→200000.00");
        assertThat(narrative).contains("increase of 205000.00");
    }

    // ── 7. Empty matrix → zero ECL, no JE on first period ───────────────────
    @Test
    @DisplayName("Single zero-amount bucket: target 0, prior 0, no JE")
    void zeroFirstPeriod() {
        // Engine requires non-empty buckets per @NotEmpty; pass one zero bucket.
        PremiumReceivableEclResult result = engine.recognise(janPeriodId, List.of(
            bucket("0-30 days", "0", "0.005")
        ));
        entityManager.flush();

        assertThat(result.targetLifetimeEcl()).isEqualByComparingTo("0.00");
        assertThat(result.eclMovement()).isEqualByComparingTo("0.00");
        assertThat(result.direction()).isEqualTo("NO_CHANGE");
        assertThat(result.journalEntryId()).isNull();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static AgingBucket bucket(String label, String amount, String rate) {
        return new AgingBucket(label, new BigDecimal(amount), new BigDecimal(rate));
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
