package com.nubeero.cia.api.finance.paa;

import com.nubeero.cia.common.event.FacDerecognisedEvent;
import com.nubeero.cia.finance.gl.ChartOfAccountService;
import com.nubeero.cia.finance.gl.FiscalPeriodResolver;
import com.nubeero.cia.finance.gl.JournalEntryService;
import com.nubeero.cia.finance.gl.PostingRuleService;
import com.nubeero.cia.finance.paa.FacDerecognitionListener;
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
import org.springframework.context.ApplicationEventPublisher;
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
 * End-to-end Testcontainers IT for FAC / IFRS-17 PAA workstream Task 5's
 * lifecycle handling: cancellation derecognises the remaining LRC/asset
 * balance, and an {@code extend} recomputes the roll-forward over the new
 * cover window with zero special posting code.
 *
 * <p>Harness mirrors {@code InwardFacLrcIT} / {@code OutwardFacLrcIT} (same
 * {@code @DataJpaTest} + Testcontainers Postgres + explicit {@code @Import}
 * shape, {@code spring.flyway.target=77}). The derecognition event is
 * published directly via the real {@link ApplicationEventPublisher} (rather
 * than driving the full {@code RiFacInwardService.cancel} / {@code
 * FacCoverService.cancel} chain, which would need the heavier
 * cia-reinsurance/cia-documents/cia-setup bean graph) — the same "publish
 * the event, exercise the real {@code @EventListener}" pattern {@code
 * FacContractGroupingIT} and {@code OutwardFacLrcIT} already establish for
 * this workstream. The production wiring that {@code cancel()} actually
 * publishes {@link FacDerecognisedEvent} is a small, directly-readable diff
 * in {@code RiFacInwardService}/{@code FacCoverService} (Task 5 Step 3).
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
    FacDerecognitionListener.class,
    FacLifecycleLrcIT.TestSupportConfig.class
})
class FacLifecycleLrcIT {

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
    @Autowired private ApplicationEventPublisher publisher;
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
            fiscalYearId, "FY-FACLIFECYCLE-2026",
            LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31), "ACTIVE", "test");
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

    // ── 1. Inward FAC cancel mid-Feb derecognises the remaining LRC liability ──
    @Test
    @DisplayName("cancel mid-Feb: Dr 2210 / Cr 4330 for the remaining unearned LRC; group's paa_lrc closing -> zero")
    void inwardCancel_derecognisesRemainingLrc() {
        UUID groupId = seedFacInwardGroup("FIN-LC-001");
        UUID facInwardId = UUID.randomUUID();
        seedFacInwardAssignment(groupId, facInwardId, "FAC-IN-LC-001",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            "1200.00", "1000.00", "200.00");
        entityManager.flush();

        engine.recognise(janPeriodId);
        entityManager.flush();

        // Baseline from InwardFacLrcIT: 1200 x 31/365 = 101.92 earned; closing = 1098.08.
        BigDecimal janEarned = new BigDecimal("1200.00")
            .multiply(BigDecimal.valueOf(31))
            .divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);
        assertThat(janEarned).isEqualByComparingTo("101.92");
        BigDecimal remaining = new BigDecimal("1200.00").subtract(janEarned);
        assertThat(remaining).isEqualByComparingTo("1098.08");

        publisher.publishEvent(new FacDerecognisedEvent(
            FacDerecognisedEvent.ContractType.FAC_INWARD, facInwardId, LocalDate.of(2026, 2, 15)));
        entityManager.flush();

        // ── (a) the new Feb paa_lrc row releases the WHOLE remaining balance ──
        Map<String, Object> febLrc = jdbcTemplate.queryForMap(
            "SELECT opening_balance, premium_earned, closing_balance " +
            "FROM paa_lrc WHERE group_id = ? AND period_id = ?",
            groupId, febPeriodId);
        assertThat((BigDecimal) febLrc.get("opening_balance")).isEqualByComparingTo(remaining);
        assertThat((BigDecimal) febLrc.get("premium_earned")).isEqualByComparingTo(remaining);
        assertThat((BigDecimal) febLrc.get("closing_balance"))
            .as("group's paa_lrc closing goes to zero for remaining coverage")
            .isEqualByComparingTo("0.00");

        // ── (b) JE Dr 2210 / Cr 4330 for the remaining balance ──
        Map<String, Object> je = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry " +
            "WHERE source_module = 'paa' AND source_event_type = 'FAC_DERECOGNITION' " +
            "AND source_reference = ?",
            "FAC_INWARD:" + facInwardId);
        UUID jeId = (UUID) je.get("id");
        assertLine(jeId, "2210", remaining, BigDecimal.ZERO);
        assertLine(jeId, "4330", BigDecimal.ZERO, remaining);

        BigDecimal net = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(debit_amount), 0) - COALESCE(SUM(credit_amount), 0) " +
            "FROM journal_entry_line WHERE journal_entry_id = ?",
            BigDecimal.class, jeId);
        assertThat(net).as("Sigma debit == Sigma credit").isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── 2. Outward FAC cancel mid-Feb derecognises the remaining reinsurance-held asset ──
    @Test
    @DisplayName("cancel mid-Feb: Dr 5210 / Cr 1410 for the remaining unamortised asset; group's paa_lrc closing -> zero")
    void outwardCancel_derecognisesRemainingAsset() {
        UUID groupId = seedFacOutwardGroup("FOU-LC-001");
        UUID facCoverId = UUID.randomUUID();
        seedFacOutwardAssignment(groupId, facCoverId,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            "1000.00");
        entityManager.flush();

        engine.recognise(janPeriodId);
        entityManager.flush();

        // Baseline from OutwardFacLrcIT: 1000 x 31/365 = 84.93 earned; closing = 915.07.
        BigDecimal janEarned = new BigDecimal("1000.00")
            .multiply(BigDecimal.valueOf(31))
            .divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);
        assertThat(janEarned).isEqualByComparingTo("84.93");
        BigDecimal remaining = new BigDecimal("1000.00").subtract(janEarned);
        assertThat(remaining).isEqualByComparingTo("915.07");

        publisher.publishEvent(new FacDerecognisedEvent(
            FacDerecognisedEvent.ContractType.FAC_OUTWARD, facCoverId, LocalDate.of(2026, 2, 15)));
        entityManager.flush();

        Map<String, Object> febLrc = jdbcTemplate.queryForMap(
            "SELECT opening_balance, premium_earned, closing_balance " +
            "FROM paa_lrc WHERE group_id = ? AND period_id = ?",
            groupId, febPeriodId);
        assertThat((BigDecimal) febLrc.get("opening_balance")).isEqualByComparingTo(remaining);
        assertThat((BigDecimal) febLrc.get("premium_earned")).isEqualByComparingTo(remaining);
        assertThat((BigDecimal) febLrc.get("closing_balance"))
            .as("group's paa_lrc closing goes to zero for remaining coverage")
            .isEqualByComparingTo("0.00");

        Map<String, Object> je = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry " +
            "WHERE source_module = 'paa' AND source_event_type = 'FAC_DERECOGNITION' " +
            "AND source_reference = ?",
            "FAC_OUTWARD:" + facCoverId);
        UUID jeId = (UUID) je.get("id");
        assertLine(jeId, "5210", remaining, BigDecimal.ZERO);
        assertLine(jeId, "1410", BigDecimal.ZERO, remaining);

        BigDecimal net = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(debit_amount), 0) - COALESCE(SUM(credit_amount), 0) " +
            "FROM journal_entry_line WHERE journal_entry_id = ?",
            BigDecimal.class, jeId);
        assertThat(net).as("Sigma debit == Sigma credit").isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── 3. A re-fired derecognition posts once (idempotent) ──────────────────
    @Test
    @DisplayName("re-firing FacDerecognisedEvent for an already-derecognised contract is a no-op — posts once")
    void reFiredDerecognition_postsOnce() {
        UUID groupId = seedFacInwardGroup("FIN-LC-IDEMP");
        UUID facInwardId = UUID.randomUUID();
        seedFacInwardAssignment(groupId, facInwardId, "FAC-IN-LC-IDEMP",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            "1200.00", "1000.00", "200.00");
        entityManager.flush();

        engine.recognise(janPeriodId);
        entityManager.flush();

        FacDerecognisedEvent event = new FacDerecognisedEvent(
            FacDerecognisedEvent.ContractType.FAC_INWARD, facInwardId, LocalDate.of(2026, 2, 15));
        publisher.publishEvent(event);
        entityManager.flush();
        publisher.publishEvent(event);
        entityManager.flush();

        Long jeCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM journal_entry WHERE source_module = 'paa' " +
            "AND source_event_type = 'FAC_DERECOGNITION' AND source_reference = ?",
            Long.class, "FAC_INWARD:" + facInwardId);
        assertThat(jeCount).as("derecognition posts exactly once even if the event re-fires").isEqualTo(1L);

        Long lrcCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM paa_lrc WHERE group_id = ? AND period_id = ?",
            Long.class, groupId, febPeriodId);
        assertThat(lrcCount).as("only one paa_lrc row for the derecognition period").isEqualTo(1L);
    }

    // ── 4. extend moves cover_to; a later recognise() recomputes over the new
    //      window with zero special posting code (Task 5 Step 4) ────────────
    @Test
    @DisplayName("extend moves cover_to at the DB level (no service call needed); a later recognise() "
        + "for a different period recomputes the day-count fraction over the NEW window automatically")
    void extend_recomputesOverNewWindow_noSpecialPostingCode() {
        UUID groupId = seedFacInwardGroup("FIN-LC-EXT");
        UUID facInwardId = UUID.randomUUID();
        seedFacInwardAssignment(groupId, facInwardId, "FAC-IN-LC-EXT",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            "1200.00", "1000.00", "200.00");
        entityManager.flush();

        // Baseline under the ORIGINAL 365-day term.
        engine.recognise(janPeriodId);
        entityManager.flush();
        BigDecimal janEarnedOriginal = new BigDecimal("1200.00")
            .multiply(BigDecimal.valueOf(31))
            .divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);
        assertThat(janEarnedOriginal).isEqualByComparingTo("101.92");

        // "extend" at the DB level ONLY — mirrors RiFacInwardService.extend()'s
        // sole persistence effect (cover.setCoverTo(...); repository.save(cover);).
        // No new posting code is added for this — the point of the test.
        jdbcTemplate.update("UPDATE ri_fac_inwards SET cover_to = ? WHERE id = ?",
            LocalDate.of(2027, 12, 31), facInwardId);
        entityManager.flush();

        // A later recognise() for a DIFFERENT period (Feb) reads cover_to LIVE —
        // total days is now 730 (2026 + 2027, both non-leap), not the original 365.
        var febResult = engine.recognise(febPeriodId);
        entityManager.flush();

        // 1200 x 28(Feb, non-leap 2026) / 730(new total) = 46.0273... -> 46.03
        BigDecimal febEarnedExtended = new BigDecimal("1200.00")
            .multiply(BigDecimal.valueOf(28))
            .divide(BigDecimal.valueOf(730), 2, RoundingMode.HALF_UP);
        assertThat(febEarnedExtended).isEqualByComparingTo("46.03");
        assertThat(febResult.totalPremiumEarned()).isEqualByComparingTo(febEarnedExtended);

        Map<String, Object> febLrc = jdbcTemplate.queryForMap(
            "SELECT premium_earned FROM paa_lrc WHERE group_id = ? AND period_id = ?",
            groupId, febPeriodId);
        assertThat((BigDecimal) febLrc.get("premium_earned"))
            .as("Feb earning recomputed over the EXTENDED 730-day window, not the original 365")
            .isEqualByComparingTo(febEarnedExtended);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID seedFacInwardGroup(String portfolioCode) {
        UUID portfolioId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO portfolio (id, code, name, contract_nature, created_by) VALUES (?, ?, ?, 'FAC_INWARD', ?)",
            portfolioId, portfolioCode, "Test " + portfolioCode, "test");
        jdbcTemplate.update(
            "INSERT INTO group_of_contracts (id, portfolio_id, cohort_year, onerousness, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            groupId, portfolioId, 2026, "NOT_ONEROUS", "OPEN", "test");
        return groupId;
    }

    private void seedFacInwardAssignment(UUID groupId, UUID facInwardId, String facReference,
                                          LocalDate coverFrom, LocalDate coverTo,
                                          String grossPremium, String netPremium, String commissionAmount) {
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

    private UUID seedFacOutwardGroup(String portfolioCode) {
        UUID portfolioId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO portfolio (id, code, name, contract_nature, created_by) VALUES (?, ?, ?, 'FAC_OUTWARD', ?)",
            portfolioId, portfolioCode, "Test " + portfolioCode, "test");
        jdbcTemplate.update(
            "INSERT INTO group_of_contracts (id, portfolio_id, cohort_year, onerousness, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            groupId, portfolioId, 2026, "NOT_ONEROUS", "OPEN", "test");
        return groupId;
    }

    private void seedFacOutwardAssignment(UUID groupId, UUID facCoverId,
                                           LocalDate coverFrom, LocalDate coverTo, String netPremium) {
        jdbcTemplate.update(
            "INSERT INTO ri_fac_covers (id, fac_reference, policy_id, policy_number, " +
            "reinsurance_company_id, reinsurance_company_name, status, sum_insured_ceded, premium_rate, " +
            "premium_ceded, commission_rate, commission_amount, net_premium, currency_code, " +
            "cover_from, cover_to, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            facCoverId, "FOU-LC-001", UUID.randomUUID(), "POL-FOU-LC-001",
            UUID.randomUUID(), "Munich Re", "CONFIRMED", new BigDecimal("500000.00"), new BigDecimal("2.500000"),
            new BigDecimal("1200.00"), new BigDecimal("0.166667"), new BigDecimal("200.00"),
            new BigDecimal(netPremium),
            "NGN", coverFrom, coverTo, "test");
        jdbcTemplate.update(
            "INSERT INTO contract_group_assignment (id, contract_type, contract_id, group_id, assigned_at, created_by) " +
            "VALUES (?, 'FAC_OUTWARD', ?, ?, now(), ?)",
            UUID.randomUUID(), facCoverId, groupId, "test");
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
