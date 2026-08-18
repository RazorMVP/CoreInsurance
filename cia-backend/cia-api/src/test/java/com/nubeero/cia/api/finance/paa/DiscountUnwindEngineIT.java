package com.nubeero.cia.api.finance.paa;

import com.nubeero.cia.finance.gl.ChartOfAccountService;
import com.nubeero.cia.finance.gl.FiscalPeriodResolver;
import com.nubeero.cia.finance.gl.JournalEntryService;
import com.nubeero.cia.finance.gl.PostingRuleService;
import com.nubeero.cia.finance.paa.DiscountUnwindEngine;
import com.nubeero.cia.finance.paa.DiscountUnwindResult;
import com.nubeero.cia.finance.paa.LicEngine;
import com.nubeero.cia.finance.paa.LrcEngine;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Testcontainers IT for {@link DiscountUnwindEngine} — Slice 2.6.
 *
 * <p>Each test seeds: fiscal year + month, portfolio + group, policy +
 * assignment, claim (to drive a non-zero LIC opening balance), then runs
 * LrcEngine + LicEngine to populate paa_lic, then exercises the unwind
 * engine under various paa_config elections.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>{@code discount_lic = FALSE} (v1 default) → no-op, no JE, paa_lic
 *       untouched.</li>
 *   <li>{@code discount_lic = TRUE}, OCI election FALSE → P&amp;L route
 *       (Dr 5520 / Cr 2140), paa_lic.discount_unwind + closing updated.</li>
 *   <li>{@code discount_lic = TRUE}, OCI election TRUE → OCI route
 *       (Dr 3430 / Cr 2140).</li>
 *   <li>Group with zero opening balance → unwind = 0, no JE posted,
 *       discount_unwind stays 0.</li>
 *   <li>Idempotency — re-running skips rows that already carry a non-zero
 *       discount_unwind.</li>
 *   <li>Math correctness — unwind = opening × rate × days/365 within 1 kobo.</li>
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
    DiscountUnwindEngineIT.TestSupportConfig.class
})
class DiscountUnwindEngineIT {

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

    @Autowired private DiscountUnwindEngine unwindEngine;
    @Autowired private LicEngine licEngine;
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
            fiscalYearId, "FY-UNW-2026",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ACTIVE", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            janPeriodId, fiscalYearId, "MONTH",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "OPEN", "test");
    }

    // ── 1. discount_lic = FALSE → engine is a no-op ──────────────────────────
    @Test
    @DisplayName("discount_lic = FALSE (v1 default): engine is a no-op, no JE posted")
    void discountingDisabledNoop() {
        // Default tenant: PaaConfig is lazy-created with discount_lic = FALSE.
        seedLicWithOpening("PORT-NOOP", "50000.00");

        DiscountUnwindResult result = unwindEngine.recognise(janPeriodId);
        entityManager.flush();

        assertThat(result.discountingDisabled()).isTrue();
        assertThat(result.routing()).isNull();
        assertThat(result.groupsProcessed()).isZero();
        assertThat(result.totalUnwind()).isEqualByComparingTo("0.00");

        // No JE posted.
        Long jeCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM journal_entry WHERE source_event_type = 'PAA_DISCOUNT_UNWIND'",
            Long.class);
        assertThat(jeCount).isZero();
    }

    // ── 2. P&L route: Dr 5520 / Cr 2140 ──────────────────────────────────────
    @Test
    @DisplayName("discount_lic = TRUE, OCI = FALSE → JE Dr 5520 / Cr 2140 (P&L route)")
    void pnlRoute() {
        // Annual rate 6%; period = January (31 days).
        // opening = 50000; unwind = 50000 × 0.06 × 31/365 = 254.79
        seedPaaConfig(true, new BigDecimal("0.06"), false);
        UUID groupId = seedLicWithOpening("PORT-PNL", "50000.00");

        DiscountUnwindResult result = unwindEngine.recognise(janPeriodId);
        entityManager.flush();

        assertThat(result.discountingDisabled()).isFalse();
        assertThat(result.routing()).isEqualTo("P&L");
        assertThat(result.groupsWithJournalEntry()).isEqualTo(1);
        assertThat(result.totalUnwind()).isEqualByComparingTo("254.79");

        // paa_lic updated
        Map<String, Object> lic = jdbcTemplate.queryForMap(
            "SELECT discount_unwind, closing_balance FROM paa_lic WHERE group_id = ?",
            groupId);
        assertThat((BigDecimal) lic.get("discount_unwind")).isEqualByComparingTo("254.79");
        // closing was 50000 (opening 50000 + incurred 0 - paid 0); after unwind = 50254.79
        assertThat((BigDecimal) lic.get("closing_balance")).isEqualByComparingTo("50254.79");

        // JE shape — Dr 5520 / Cr 2140
        Map<String, Object> je = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry " +
            "WHERE source_module = 'paa' AND source_event_type = 'PAA_DISCOUNT_UNWIND' " +
            "AND source_reference = ?",
            janPeriodId + ":" + groupId);
        assertLine((UUID) je.get("id"), "5520", "254.79", "0.00");
        assertLine((UUID) je.get("id"), "2140", "0.00", "254.79");
    }

    // ── 3. OCI route: Dr 3430 / Cr 2140 ──────────────────────────────────────
    @Test
    @DisplayName("discount_lic = TRUE, OCI = TRUE → JE Dr 3430 / Cr 2140 (OCI route)")
    void ociRoute() {
        seedPaaConfig(true, new BigDecimal("0.06"), true);
        UUID groupId = seedLicWithOpening("PORT-OCI", "100000.00");

        DiscountUnwindResult result = unwindEngine.recognise(janPeriodId);
        entityManager.flush();

        assertThat(result.routing()).isEqualTo("OCI");
        // 100000 × 0.06 × 31/365 = 509.59
        assertThat(result.totalUnwind()).isEqualByComparingTo("509.59");

        Map<String, Object> je = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry " +
            "WHERE source_module = 'paa' AND source_event_type = 'PAA_DISCOUNT_UNWIND' " +
            "AND source_reference = ?",
            janPeriodId + ":" + groupId);
        assertLine((UUID) je.get("id"), "3430", "509.59", "0.00");
        assertLine((UUID) je.get("id"), "2140", "0.00", "509.59");
    }

    // ── 4. Empty group (no claims at all) → engine processes nothing ─────────
    @Test
    @DisplayName("group with no claim activity at all: LicEngine skips writing paa_lic, unwind engine sees no rows")
    void emptyGroupSkippedByLicEngine() {
        seedPaaConfig(true, new BigDecimal("0.06"), false);
        // openingAmount "0.00" → seed helper skips the claim insert → LicEngine's
        // allZero() check filters the group out of paa_lic entirely. The unwind
        // engine therefore finds nothing to process.
        seedLicWithOpening("PORT-EMPTY", "0.00");

        DiscountUnwindResult result = unwindEngine.recognise(janPeriodId);
        entityManager.flush();

        assertThat(result.groupsProcessed()).isZero();
        assertThat(result.groupsWithJournalEntry()).isZero();
        assertThat(result.totalUnwind()).isEqualByComparingTo("0.00");

        Long jeCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM journal_entry WHERE source_event_type = 'PAA_DISCOUNT_UNWIND'",
            Long.class);
        assertThat(jeCount).isZero();
    }

    // ── 5. Idempotency — re-run skips already-unwound rows ───────────────────
    @Test
    @DisplayName("re-running skips rows that already carry a non-zero discount_unwind")
    void idempotentRerun() {
        seedPaaConfig(true, new BigDecimal("0.06"), false);
        UUID groupId = seedLicWithOpening("PORT-IDEM", "50000.00");

        DiscountUnwindResult first = unwindEngine.recognise(janPeriodId);
        entityManager.flush();
        DiscountUnwindResult second = unwindEngine.recognise(janPeriodId);
        entityManager.flush();

        assertThat(first.groupsWithJournalEntry()).isEqualTo(1);
        // Second run: row is skipped because discount_unwind != 0 already; not in entries list.
        assertThat(second.groupsWithJournalEntry()).isZero();
        assertThat(second.totalUnwind()).isEqualByComparingTo("0.00");

        // Single JE in the DB despite two engine calls.
        Long jeCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM journal_entry " +
            "WHERE source_event_type = 'PAA_DISCOUNT_UNWIND' AND source_reference = ?",
            Long.class, janPeriodId + ":" + groupId);
        assertThat(jeCount).isEqualTo(1L);

        // closing_balance not double-counted.
        BigDecimal closing = jdbcTemplate.queryForObject(
            "SELECT closing_balance FROM paa_lic WHERE group_id = ?",
            BigDecimal.class, groupId);
        assertThat(closing).isEqualByComparingTo("50254.79");
    }

    // (Pure-math tests for computeUnwind live in cia-finance's
    // DiscountUnwindEngineMathTest — same package as the engine so it can
    // call the package-private static helper directly, mirroring how
    // LrcEngineMathTest tests LrcEngine's earnings math.)

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Seeds an end-to-end fixture (portfolio → group → policy → assignment →
     * claim → paa_lic via LicEngine) such that the resulting paa_lic row has
     * the specified opening balance.
     *
     * <p>"Opening" is achieved by approving a claim in December 2025 (before
     * the test's January 2026 period) — that approval becomes the opening
     * balance at Jan 1. A zero-opening fixture is achieved by skipping the
     * claim entirely.
     */
    private UUID seedLicWithOpening(String portfolioCode, String openingAmount) {
        UUID portfolioId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO portfolio (id, code, name, created_by) VALUES (?, ?, ?, ?)",
            portfolioId, portfolioCode, "Test " + portfolioCode, "test");
        jdbcTemplate.update(
            "INSERT INTO group_of_contracts (id, portfolio_id, cohort_year, onerousness, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            groupId, portfolioId, 2026, "NOT_ONEROUS", "OPEN", "test");

        UUID policyId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO policies (id, policy_number, customer_id, customer_name, " +
            "product_id, product_name, product_code, product_rate, " +
            "class_of_business_id, class_of_business_name, class_of_business_code, " +
            "policy_start_date, policy_end_date, net_premium, currency_code, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            policyId, "POL-" + portfolioCode, UUID.randomUUID(), "Test Customer",
            UUID.randomUUID(), "Test Product", "PROD-UNW", new BigDecimal("0.0500"),
            UUID.randomUUID(), "Test COB", "COB-UNW",
            LocalDate.of(2025, 12, 1), LocalDate.of(2026, 11, 30),
            new BigDecimal("100000.00"), "NGN", "APPROVED", "test");
        jdbcTemplate.update(
            "INSERT INTO contract_group_assignment (id, contract_type, contract_id, group_id, assigned_at, created_by) " +
            "VALUES (?, 'POLICY', ?, ?, now(), ?)",
            UUID.randomUUID(), policyId, groupId, "test");

        BigDecimal opening = new BigDecimal(openingAmount);
        if (opening.signum() > 0) {
            // Claim approved Dec 2025 → outstanding at Jan 1 → opening balance.
            UUID claimId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO claims (id, claim_number, status, policy_id, policy_number, " +
                "policy_start_date, policy_end_date, customer_id, customer_name, product_id, product_name, " +
                "class_of_business_id, class_of_business_name, incident_date, reported_date, description, " +
                "reserve_amount, approved_amount, currency_code, approved_at, " +
                "approved_by, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                claimId, "CLM-" + portfolioCode, "APPROVED", policyId, "POL-FOR-CLAIM",
                LocalDate.of(2025, 12, 1), LocalDate.of(2026, 11, 30),
                UUID.randomUUID(), "Test Customer",
                UUID.randomUUID(), "Test Product",
                UUID.randomUUID(), "Test COB",
                LocalDate.of(2025, 12, 5), LocalDate.of(2025, 12, 6), "Incident description",
                new BigDecimal("0"), opening, "NGN",
                Timestamp.valueOf(LocalDateTime.of(LocalDate.of(2025, 12, 10), LocalTime.of(10, 0))),
                "test", "test");
        }

        entityManager.flush();
        licEngine.recognise(janPeriodId);
        entityManager.flush();

        return groupId;
    }

    private void seedPaaConfig(boolean discountLic, BigDecimal rate, boolean ociElection) {
        jdbcTemplate.update(
            "INSERT INTO paa_config (id, singleton_marker, discount_lic, discount_rate, oci_election, " +
            "ra_method, acquisition_cashflow_method, created_by) " +
            "VALUES (gen_random_uuid(), TRUE, ?, ?, ?, ?, ?, ?)",
            discountLic, rate, ociElection,
            "CONFIDENCE_LEVEL", "EXPENSE_AS_INCURRED", "test");
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
