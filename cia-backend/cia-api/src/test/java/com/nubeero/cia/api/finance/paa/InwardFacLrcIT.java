package com.nubeero.cia.api.finance.paa;

import com.nubeero.cia.finance.gl.ChartOfAccountService;
import com.nubeero.cia.finance.gl.FiscalPeriodResolver;
import com.nubeero.cia.finance.gl.JournalEntryService;
import com.nubeero.cia.finance.gl.PostingRuleService;
import com.nubeero.cia.finance.paa.LrcEngine;
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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Testcontainers IT for {@link LrcEngine}'s FAC_INWARD dispatch —
 * FAC / IFRS-17 PAA workstream Task 3.
 *
 * <p>Proves the two things Task 3 adds on top of the already-merged Task 1
 * (data model) / Task 2 (FAC grouping) slices:
 * <ol>
 *   <li>a {@code contract_group_assignment} row of type {@code FAC_INWARD}
 *       is now priced (via {@code ri_fac_inwards.gross_premium} — the LRC
 *       basis for inward is <em>gross</em>, matching the accept-time
 *       liability {@code SubledgerPostingService.replayFacPremiumAccepted}
 *       sets up) instead of being skipped as "policy not found";</li>
 *   <li>the resulting JE posts to the FAC_INWARD nature's accounts
 *       (Dr 2210 / Cr 4330) rather than the DIRECT nature's (Dr 2110 /
 *       Cr 4110) — proving {@code LrcEngine}'s new per-group
 *       {@code NatureAccounts} dispatch, not just the pricing dispatch.</li>
 * </ol>
 *
 * <p>Harness mirrors {@code LrcEngineIT} exactly (same {@code @Import} list,
 * same Testcontainers Postgres + {@code @DataJpaTest} shape, same
 * {@code spring.flyway.target=77} — V76 adds {@code portfolio.contract_nature},
 * V77 generalises the assignment table to {@code (contract_type, contract_id)}).
 * {@code LrcEngineIT} itself is re-run unmodified alongside this IT to prove
 * the DIRECT/POLICY path stays byte-identical.
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
    InwardFacLrcIT.TestSupportConfig.class
})
class InwardFacLrcIT {

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
        registry.add("spring.flyway.target", () -> "77");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired private LrcEngine engine;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    private UUID fiscalYearId;
    private UUID janPeriodId;

    @BeforeEach
    void seedFiscalYearAndPeriod() {
        fiscalYearId = UUID.randomUUID();
        janPeriodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            fiscalYearId, "FY-FACLRC-2026",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ACTIVE", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            janPeriodId, fiscalYearId, "MONTH",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "OPEN", "test");
    }

    // ── 1. Inward FAC group earns gross premium straight-line, Dr 2210 / Cr 4330 ──
    @Test
    @DisplayName("FAC_INWARD group recognises 31/365 of GROSS premium in January, posting Dr 2210 / Cr 4330")
    void singleFacInwardGroupJanuaryRecognition() {
        UUID groupId = seedFacInwardGroup("FIN-IT-001", 2026, "NOT_ONEROUS");
        seedFacInwardAssignment(groupId, "FAC-IN-LRC-001",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            "1200.00", "1000.00", "200.00");
        entityManager.flush();

        LrcRecognitionResult result = engine.recognise(janPeriodId);
        entityManager.flush();

        // 1200 × 31/365 = 101.9178... → 101.92
        BigDecimal expectedEarned = new BigDecimal("1200.00")
            .multiply(BigDecimal.valueOf(31))
            .divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);
        assertThat(expectedEarned).isEqualByComparingTo("101.92");
        assertThat(result.totalPremiumEarned()).isEqualByComparingTo(expectedEarned);
        assertThat(result.groupsWithJournalEntry()).isEqualTo(1);

        // (a) paa_lrc row for the FAC_INWARD group.
        Map<String, Object> lrc = jdbcTemplate.queryForMap(
            "SELECT opening_balance, premium_received, premium_earned, closing_balance " +
            "FROM paa_lrc WHERE group_id = ? AND period_id = ?",
            groupId, janPeriodId);
        BigDecimal opening = (BigDecimal) lrc.get("opening_balance");
        BigDecimal earned = (BigDecimal) lrc.get("premium_earned");
        BigDecimal closing = (BigDecimal) lrc.get("closing_balance");
        assertThat(earned).isEqualByComparingTo(expectedEarned);
        // LRC basis is GROSS for inward — opening at the January (cover-inception)
        // period is the full gross premium, same shape LrcEngineIT asserts for a
        // DIRECT policy's inception-period opening.
        assertThat(opening).isEqualByComparingTo("1200.00");
        // (c) roll-forward conservation this engine actually implements:
        // closing = opening − earned. (premium_received is an independent
        // cash-received signal — at the FAC's own cover-inception period it
        // equals opening by construction, same as LrcEngineIT's single-policy
        // January test; it is not additive into the balance identity.)
        assertThat(closing).isEqualByComparingTo(opening.subtract(earned));

        // (b) JE Dr 2210 / Cr 4330 for the earned amount.
        Map<String, Object> je = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry " +
            "WHERE source_module = 'paa' AND source_event_type = 'LRC_RECOGNITION' " +
            "AND source_reference = ?",
            janPeriodId + ":" + groupId);
        UUID jeId = (UUID) je.get("id");
        assertLine(jeId, "2210", expectedEarned, BigDecimal.ZERO);
        assertLine(jeId, "4330", BigDecimal.ZERO, expectedEarned);

        BigDecimal net = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(debit_amount), 0) - COALESCE(SUM(credit_amount), 0) " +
            "FROM journal_entry_line WHERE journal_entry_id = ?",
            BigDecimal.class, jeId);
        assertThat(net).as("Σdebit == Σcredit").isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── 2. Dispatch is per-group: a DIRECT group and a FAC_INWARD group in the
    //      same recognise() call each post to their own nature's accounts ────
    @Test
    @DisplayName("mixed DIRECT + FAC_INWARD groups in one recognise() call post to their own accounts, no cross-contamination")
    void mixedDirectAndFacInwardGroups_eachPostsToOwnAccounts() {
        UUID directGroupId = seedDirectGroup("PORT-IT-MIX", 2026, "NOT_ONEROUS");
        seedPolicyAssignment(directGroupId, "POL-MIX-001",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "120000.00");

        UUID facGroupId = seedFacInwardGroup("FIN-IT-MIX", 2026, "NOT_ONEROUS");
        seedFacInwardAssignment(facGroupId, "FAC-IN-LRC-MIX",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            "1200.00", "1000.00", "200.00");
        entityManager.flush();

        LrcRecognitionResult result = engine.recognise(janPeriodId);
        entityManager.flush();

        assertThat(result.groupsWithJournalEntry()).isEqualTo(2);

        Map<String, Object> directJe = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry WHERE source_reference = ?",
            janPeriodId + ":" + directGroupId);
        Map<String, Object> facJe = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry WHERE source_reference = ?",
            janPeriodId + ":" + facGroupId);

        // DIRECT unchanged: Dr 2110 / Cr 4110. 120000 × 31/365 = 10191.78.
        assertLine((UUID) directJe.get("id"), "2110", new BigDecimal("10191.78"), BigDecimal.ZERO);
        assertLine((UUID) directJe.get("id"), "4110", BigDecimal.ZERO, new BigDecimal("10191.78"));
        // The DIRECT group's JE must NOT touch the inward accounts.
        assertNoLine((UUID) directJe.get("id"), "2210");
        assertNoLine((UUID) directJe.get("id"), "4330");

        // FAC_INWARD: Dr 2210 / Cr 4330. 1200 × 31/365 = 101.92.
        assertLine((UUID) facJe.get("id"), "2210", new BigDecimal("101.92"), BigDecimal.ZERO);
        assertLine((UUID) facJe.get("id"), "4330", BigDecimal.ZERO, new BigDecimal("101.92"));
        // The FAC_INWARD group's JE must NOT touch the direct accounts.
        assertNoLine((UUID) facJe.get("id"), "2110");
        assertNoLine((UUID) facJe.get("id"), "4110");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID seedDirectGroup(String portfolioCode, int cohortYear, String onerousness) {
        UUID portfolioId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO portfolio (id, code, name, contract_nature, created_by) VALUES (?, ?, ?, 'DIRECT', ?)",
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
            UUID.randomUUID(), "Test Product", "PROD-FACLRC", new BigDecimal("0.0500"),
            UUID.randomUUID(), "Test COB", "COB-FACLRC",
            startDate, endDate, new BigDecimal(netPremium), "NGN", "APPROVED", "test");
        jdbcTemplate.update(
            "INSERT INTO contract_group_assignment (id, contract_type, contract_id, group_id, assigned_at, created_by) " +
            "VALUES (?, 'POLICY', ?, ?, now(), ?)",
            UUID.randomUUID(), policyId, groupId, "test");
    }

    private UUID seedFacInwardGroup(String portfolioCode, int cohortYear, String onerousness) {
        UUID portfolioId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO portfolio (id, code, name, contract_nature, created_by) VALUES (?, ?, ?, 'FAC_INWARD', ?)",
            portfolioId, portfolioCode, "Test " + portfolioCode, "test");
        jdbcTemplate.update(
            "INSERT INTO group_of_contracts (id, portfolio_id, cohort_year, onerousness, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            groupId, portfolioId, cohortYear, onerousness, "OPEN", "test");
        return groupId;
    }

    private void seedFacInwardAssignment(UUID groupId, String facReference,
                                          LocalDate coverFrom, LocalDate coverTo,
                                          String grossPremium, String netPremium, String commissionAmount) {
        UUID facInwardId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO ri_fac_inwards (id, fac_inward_reference, ceding_company_id, ceding_company_name, " +
            "class_of_business_id, class_of_business_name, status, " +
            "sum_insured, our_share_pct, accepted_sum_insured, premium_rate, " +
            "gross_premium, commission_rate, commission_amount, net_premium, " +
            "currency_code, cover_from, cover_to, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            facInwardId, facReference, UUID.randomUUID(), "Test Ceding Co",
            UUID.randomUUID(), "Test COB", "ACTIVE",
            new BigDecimal("10000000.00"), new BigDecimal("0.5000"), new BigDecimal("5000000.00"),
            new BigDecimal("0.024000"),
            new BigDecimal(grossPremium), new BigDecimal("0.2000"), new BigDecimal(commissionAmount),
            new BigDecimal(netPremium),
            "NGN", coverFrom, coverTo, "test");
        jdbcTemplate.update(
            "INSERT INTO contract_group_assignment (id, contract_type, contract_id, group_id, assigned_at, created_by) " +
            "VALUES (?, 'FAC_INWARD', ?, ?, now(), ?)",
            UUID.randomUUID(), facInwardId, groupId, "test");
    }

    private void assertLine(UUID journalEntryId, String accountCode,
                             BigDecimal expectedDebit, BigDecimal expectedCredit) {
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

    private void assertNoLine(UUID journalEntryId, String accountCode) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM journal_entry_line l " +
            "JOIN chart_of_account a ON a.id = l.account_id " +
            "WHERE l.journal_entry_id = ? AND a.code = ?",
            Long.class, journalEntryId, accountCode);
        assertThat(count).as("no line for account " + accountCode).isZero();
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
