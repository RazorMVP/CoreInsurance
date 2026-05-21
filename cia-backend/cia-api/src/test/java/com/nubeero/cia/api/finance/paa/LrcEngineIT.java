package com.nubeero.cia.api.finance.paa;

import com.nubeero.cia.finance.gl.ChartOfAccountService;
import com.nubeero.cia.finance.gl.FiscalPeriodResolver;
import com.nubeero.cia.finance.gl.JournalEntryService;
import com.nubeero.cia.finance.gl.PostingRuleService;
import com.nubeero.cia.finance.paa.LrcEngine;
import com.nubeero.cia.finance.paa.LrcRecognitionAlreadyDoneException;
import com.nubeero.cia.finance.paa.LrcRecognitionResult;
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
 * End-to-end Testcontainers IT for {@link LrcEngine} — Slice 2.3.
 *
 * <p>Each test seeds the necessary fixtures (fiscal year + month, COB,
 * portfolio, group, policy, policy_group_assignment) via JdbcTemplate,
 * invokes {@link LrcEngine#recognise(UUID)}, and verifies the resulting
 * {@code paa_lrc} row + journal entry shape.
 *
 * <p>Schema target = 37: V36 (PAA tables) + V37 (policy_group_assignment).
 * Tests can therefore rely on uq_paa_lrc_group_period, FKs to fiscal_period,
 * and the V31/V32 JE gateway + COA seed.
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
    LrcEngineIT.TestSupportConfig.class
})
class LrcEngineIT {

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

    @Autowired private LrcEngine engine;
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
            fiscalYearId, "FY-LRC-2026",
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

    // ── 1. Happy path: one group, one policy, full period coverage ───────────
    @Test
    @DisplayName("single-policy group recognises 31/365 of premium in January")
    void singlePolicyJanuaryRecognition() {
        UUID groupId = seedGroup("PORT-IT-001", 2026, "NOT_ONEROUS");
        seedPolicyAssignment(groupId, "POL-LRC-001",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "120000.00");
        entityManager.flush();

        LrcRecognitionResult result = engine.recognise(janPeriodId);
        entityManager.flush();

        // 120000 × 31/365 = 10191.78
        assertThat(result.totalPremiumEarned()).isEqualByComparingTo("10191.78");
        assertThat(result.groupsProcessed()).isEqualTo(1);
        assertThat(result.groupsWithJournalEntry()).isEqualTo(1);

        // paa_lrc row
        Map<String, Object> lrc = jdbcTemplate.queryForMap(
            "SELECT opening_balance, premium_received, premium_earned, closing_balance " +
            "FROM paa_lrc WHERE group_id = ? AND period_id = ?",
            groupId, janPeriodId);
        assertThat((BigDecimal) lrc.get("opening_balance")).isEqualByComparingTo("120000.00");
        assertThat((BigDecimal) lrc.get("premium_received")).isEqualByComparingTo("120000.00");
        assertThat((BigDecimal) lrc.get("premium_earned")).isEqualByComparingTo("10191.78");
        assertThat((BigDecimal) lrc.get("closing_balance")).isEqualByComparingTo("109808.22");

        // JE shape — Dr 2110 / Cr 4110
        Map<String, Object> je = jdbcTemplate.queryForMap(
            "SELECT id, business_date FROM journal_entry " +
            "WHERE source_module = 'paa' AND source_event_type = 'LRC_RECOGNITION' " +
            "AND source_reference = ?",
            janPeriodId + ":" + groupId);
        assertThat(je.get("business_date")).isEqualTo(java.sql.Date.valueOf("2026-01-31"));

        assertLine((UUID) je.get("id"), "2110", "10191.78", "0.00");
        assertLine((UUID) je.get("id"), "4110", "0.00", "10191.78");
    }

    // ── 2. Idempotency on re-run ─────────────────────────────────────────────
    @Test
    @DisplayName("re-running the engine for the same period raises LrcRecognitionAlreadyDoneException")
    void rerunRaisesAlreadyDone() {
        UUID groupId = seedGroup("PORT-IT-002", 2026, "NOT_ONEROUS");
        seedPolicyAssignment(groupId, "POL-LRC-002",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "120000.00");
        entityManager.flush();

        engine.recognise(janPeriodId);
        entityManager.flush();

        assertThatThrownBy(() -> engine.recognise(janPeriodId))
            .isInstanceOf(LrcRecognitionAlreadyDoneException.class);

        // Original paa_lrc + JE are intact — no partial write from the second attempt.
        Long lrcRows = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM paa_lrc WHERE group_id = ? AND period_id = ?",
            Long.class, groupId, janPeriodId);
        assertThat(lrcRows).isEqualTo(1L);
    }

    // ── 3. Multi-group: each group writes its own roll-forward + JE ─────────
    @Test
    @DisplayName("two groups in the same period each get their own paa_lrc + JE")
    void twoGroupsTwoJournalEntries() {
        UUID groupA = seedGroup("PORT-IT-003A", 2026, "NOT_ONEROUS");
        UUID groupB = seedGroup("PORT-IT-003B", 2026, "ONEROUS");
        seedPolicyAssignment(groupA, "POL-MGRP-001",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "120000.00");
        seedPolicyAssignment(groupB, "POL-MGRP-002",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "60000.00");
        entityManager.flush();

        LrcRecognitionResult result = engine.recognise(janPeriodId);
        entityManager.flush();

        assertThat(result.groupsProcessed()).isEqualTo(2);
        assertThat(result.groupsWithJournalEntry()).isEqualTo(2);
        // 120000 × 31/365 = 10191.78; 60000 × 31/365 = 5095.89
        assertThat(result.totalPremiumEarned()).isEqualByComparingTo("15287.67");

        Long jeCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM journal_entry " +
            "WHERE source_module = 'paa' AND source_event_type = 'LRC_RECOGNITION' " +
            "AND business_date = ?",
            Long.class, java.sql.Date.valueOf("2026-01-31"));
        assertThat(jeCount).isEqualTo(2L);
    }

    // ── 4. Skip groups with zero activity (no JE posted) ─────────────────────
    @Test
    @DisplayName("group whose only policy doesn't overlap the period produces no JE")
    void groupWithZeroActivityProducesNoJe() {
        UUID groupId = seedGroup("PORT-IT-004", 2026, "NOT_ONEROUS");
        // Policy starts June — no overlap with January period.
        seedPolicyAssignment(groupId, "POL-LATE-001",
            LocalDate.of(2026, 6, 1), LocalDate.of(2027, 5, 31), "120000.00");
        entityManager.flush();

        LrcRecognitionResult result = engine.recognise(janPeriodId);
        entityManager.flush();

        assertThat(result.groupsWithJournalEntry()).isZero();
        assertThat(result.totalPremiumEarned()).isEqualByComparingTo("0.00");

        Long jeCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM journal_entry " +
            "WHERE source_module = 'paa' AND source_event_type = 'LRC_RECOGNITION'",
            Long.class);
        assertThat(jeCount).isZero();

        Long lrcCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM paa_lrc WHERE group_id = ?",
            Long.class, groupId);
        // The group has a non-zero opening (full premium pending) — paa_lrc is still
        // written for audit visibility even though earnings = 0, so the LRC asset is
        // observable in the trial-balance view. The JE is skipped only because the
        // earned amount is zero.
        // (Actually opening = full premium since period is before policy.start, so
        //  the row exists. allZero() check ensures the row exists with non-zero opening.)
        assertThat(lrcCount).isEqualTo(1L);
    }

    // ── 5. February run after January — independent computation ─────────────
    @Test
    @DisplayName("running for February (after January) computes independently — stateless engine")
    void febIndependentOfJan() {
        UUID groupId = seedGroup("PORT-IT-005", 2026, "NOT_ONEROUS");
        seedPolicyAssignment(groupId, "POL-FEB-001",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "365000.00");
        entityManager.flush();

        engine.recognise(janPeriodId);
        entityManager.flush();

        LrcRecognitionResult feb = engine.recognise(febPeriodId);
        entityManager.flush();

        // Feb 28 days → 365000 × 28/365 = 28000.00
        assertThat(feb.totalPremiumEarned()).isEqualByComparingTo("28000.00");

        // Verify paa_lrc.opening for Feb period = closing of Jan period (within rounding)
        BigDecimal janClosing = jdbcTemplate.queryForObject(
            "SELECT closing_balance FROM paa_lrc WHERE group_id = ? AND period_id = ?",
            BigDecimal.class, groupId, janPeriodId);
        BigDecimal febOpening = jdbcTemplate.queryForObject(
            "SELECT opening_balance FROM paa_lrc WHERE group_id = ? AND period_id = ?",
            BigDecimal.class, groupId, febPeriodId);
        // Stateless engine: both computed independently from policy data, so they
        // should equal (within at most 1 kobo from independent rounding).
        assertThat(febOpening.subtract(janClosing).abs())
            .as("Feb opening should equal Jan closing within 0.01")
            .isLessThanOrEqualTo(new BigDecimal("0.01"));
    }

    // ── 6. Multi-policy single group: amounts aggregate ─────────────────────
    @Test
    @DisplayName("two policies in same group aggregate into one paa_lrc + one JE")
    void twoPoliciesAggregate() {
        UUID groupId = seedGroup("PORT-IT-006", 2026, "NOT_ONEROUS");
        seedPolicyAssignment(groupId, "POL-AGG-1",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "365000.00");
        seedPolicyAssignment(groupId, "POL-AGG-2",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "73000.00");
        entityManager.flush();

        LrcRecognitionResult result = engine.recognise(janPeriodId);

        // 365000 × 31/365 = 31000; 73000 × 31/365 = 6200; total = 37200
        assertThat(result.totalPremiumEarned()).isEqualByComparingTo("37200.00");
        assertThat(result.groupsWithJournalEntry()).isEqualTo(1);
    }

    // ── 7. JE line dimensions are tagged with portfolio + group + cohort ────
    @Test
    @DisplayName("JE lines carry portfolio_id + contract_group_id + cohort_year for IFRS-17 roll-ups")
    void jeLineDimensionsTagged() {
        UUID groupId = seedGroup("PORT-IT-007", 2026, "NOT_ONEROUS");
        seedPolicyAssignment(groupId, "POL-DIM-001",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "365000.00");
        entityManager.flush();

        engine.recognise(janPeriodId);
        entityManager.flush();

        Map<String, Object> dims = jdbcTemplate.queryForMap(
            "SELECT portfolio_id, contract_group_id, cohort_year " +
            "FROM journal_entry_line l " +
            "JOIN journal_entry je ON je.id = l.journal_entry_id " +
            "JOIN chart_of_account a ON a.id = l.account_id " +
            "WHERE je.source_event_type = 'LRC_RECOGNITION' AND a.code = '2110'");
        assertThat(dims.get("contract_group_id")).isEqualTo(groupId);
        assertThat(dims.get("cohort_year")).isEqualTo(2026);
        assertThat(dims.get("portfolio_id")).isNotNull();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID seedGroup(String portfolioCode, int cohortYear, String onerousness) {
        UUID portfolioId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO portfolio (id, code, name, created_by) VALUES (?, ?, ?, ?)",
            portfolioId, portfolioCode, "Test " + portfolioCode, "test");
        jdbcTemplate.update(
            "INSERT INTO group_of_contracts (id, portfolio_id, cohort_year, onerousness, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            groupId, portfolioId, cohortYear, onerousness, "OPEN", "test");
        return groupId;
    }

    private void seedPolicyAssignment(UUID groupId, String policyNumber,
                                       LocalDate startDate, LocalDate endDate, String netPremium) {
        UUID policyId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO policies (id, policy_number, customer_id, customer_name, " +
            "product_id, product_name, product_code, product_rate, " +
            "class_of_business_id, class_of_business_name, class_of_business_code, " +
            "policy_start_date, policy_end_date, net_premium, currency_code, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            policyId, policyNumber, UUID.randomUUID(), "Test Customer",
            UUID.randomUUID(), "Test Product", "PROD-LRC", new BigDecimal("0.0500"),
            UUID.randomUUID(), "Test COB", "COB-LRC",
            startDate, endDate, new BigDecimal(netPremium), "NGN", "APPROVED", "test");
        jdbcTemplate.update(
            "INSERT INTO policy_group_assignment (id, policy_id, group_id, assigned_at, created_by) " +
            "VALUES (?, ?, ?, now(), ?)",
            UUID.randomUUID(), policyId, groupId, "test");
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

    /**
     * Cache regions ChartOfAccountService and PostingRuleService expect to find
     * pre-registered; otherwise their @Cacheable methods throw at first call.
     */
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
