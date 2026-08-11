package com.nubeero.cia.api.finance.paa;

import com.nubeero.cia.common.event.FacPremiumCededEvent;
import com.nubeero.cia.finance.gl.ChartOfAccountService;
import com.nubeero.cia.finance.gl.FiscalPeriodResolver;
import com.nubeero.cia.finance.gl.JournalEntryService;
import com.nubeero.cia.finance.gl.PolicyClassResolver;
import com.nubeero.cia.finance.gl.PostingRuleService;
import com.nubeero.cia.finance.gl.SubledgerPostingService;
import com.nubeero.cia.finance.paa.ContractGroupAssignment;
import com.nubeero.cia.finance.paa.ContractGroupAssignmentRepository;
import com.nubeero.cia.finance.paa.ContractGroupingService;
import com.nubeero.cia.finance.paa.ContractType;
import com.nubeero.cia.finance.paa.LrcEngine;
import com.nubeero.cia.finance.paa.LrcRecognitionResult;
import com.nubeero.cia.finance.paa.OnerousContractTestEngine;
import com.nubeero.cia.finance.paa.OnerousTestResult;
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
 * End-to-end Testcontainers IT for the FAC / IFRS-17 PAA workstream Task 4
 * outward-FAC accounting crux: the reinsurance-held <em>asset</em> shape
 * (mirror-image of the DIRECT/FAC_INWARD <em>liability</em> shape) with §65
 * commission-netting, plus the onerous-test exemption for reinsurance held.
 *
 * <p>Exercises the full outward-FAC posting chain by publishing a real
 * {@link FacPremiumCededEvent} through Spring's {@link ApplicationEventPublisher}
 * so both {@link ContractGroupingService#onFacPremiumCeded} (groups the cover
 * into a {@code FAC_OUTWARD} portfolio) and {@link
 * SubledgerPostingService#onFacPremiumCeded} (posts the §65-netted confirm
 * JE) fire — mirroring {@code FacContractGroupingIT}'s outward-seeding
 * pattern (seeded {@code ri_fac_covers} + a linked {@code policies} row).
 *
 * <ol>
 *   <li>{@link #confirmNetsCommissionThenJanuaryReleaseAmortisesTheAsset()} —
 *       the happy path: confirm posts {@code Dr 1410 / Cr 2310} at the NET
 *       ceded premium (no {@code 4300} commission-income line), then
 *       {@link LrcEngine#recognise} releases 31/365 of that NET premium in
 *       January via {@code Dr 5210 / Cr 1410} — the sign-flip mirror of the
 *       DIRECT/FAC_INWARD liability release.</li>
 *   <li>{@link #onerousTestExemptsFacOutwardGroup()} — proves {@link
 *       OnerousContractTestEngine#test} skips {@code FAC_OUTWARD} groups
 *       entirely even when a seeded cumulative-incurred figure would, absent
 *       the guard, trigger a large loss-component JE.</li>
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
    PolicyClassResolver.class,
    ContractGroupingService.class,
    SubledgerPostingService.class,
    LrcEngine.class,
    OnerousContractTestEngine.class,
    OutwardFacLrcIT.TestSupportConfig.class
})
class OutwardFacLrcIT {

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

    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private LrcEngine lrcEngine;
    @Autowired private OnerousContractTestEngine onerousEngine;
    @Autowired private ContractGroupAssignmentRepository assignmentRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    private UUID motorCobId;
    private UUID janPeriodId;

    @BeforeEach
    void seedFixtures() {
        motorCobId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO classes_of_business (id, name, code, description, created_by) " +
            "VALUES (?, ?, ?, ?, ?)",
            motorCobId, "Motor Comprehensive", "MOTOR-COMP", "Motor comp test", "test");

        UUID fiscalYearId = UUID.randomUUID();
        janPeriodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            fiscalYearId, "FY-FOU-LRC-2026",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ACTIVE", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            janPeriodId, fiscalYearId, "MONTH",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "OPEN", "test");

        // SubledgerPostingService.replayFacPremiumCeded (invoked as a live
        // listener via the publisher below) defaults its business date to
        // today() when no explicit date is threaded through — mirrors
        // SubledgerPostingServiceIT's ensureMonthPeriod pattern so the
        // confirm posting always has an OPEN MONTH period to land in,
        // whatever day the suite happens to run.
        ensureMonthPeriod(LocalDate.now());
    }

    /**
     * Find-or-create an ACTIVE fiscal_year for {@code date}'s year and an
     * OPEN MONTH fiscal_period covering its month. No-op if the period
     * already exists (e.g. when "today" falls in January 2026).
     */
    private void ensureMonthPeriod(LocalDate date) {
        int year = date.getYear();
        LocalDate fyStart = LocalDate.of(year, 1, 1);
        LocalDate fyEnd = LocalDate.of(year, 12, 31);
        UUID fyId = jdbcTemplate.query(
            "SELECT id FROM fiscal_year WHERE start_date = ? AND end_date = ? LIMIT 1",
            rs -> rs.next() ? UUID.fromString(rs.getString("id")) : null, fyStart, fyEnd);
        if (fyId == null) {
            fyId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
                fyId, "FY" + year, fyStart, fyEnd, "ACTIVE", "test");
        }
        LocalDate monthStart = date.withDayOfMonth(1);
        LocalDate monthEnd = date.withDayOfMonth(date.lengthOfMonth());
        Integer existing = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM fiscal_period WHERE fiscal_year_id = ? AND period_type = 'MONTH' AND start_date = ?",
            Integer.class, fyId, monthStart);
        if (existing == null || existing == 0) {
            jdbcTemplate.update(
                "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), fyId, "MONTH", monthStart, monthEnd, "OPEN", "test");
        }
    }

    // ── 1. Confirm nets commission (§65); January LRC release amortises the NET asset ──
    @Test
    @DisplayName("confirm posts Dr 1410 / Cr 2310 (net, no 4300); January LRC release posts Dr 5210 / Cr 1410 for 31/365 of net")
    void confirmNetsCommissionThenJanuaryReleaseAmortisesTheAsset() {
        UUID policyId = seedPolicy("POL-FOU-LRC-001", motorCobId, LocalDate.of(2026, 1, 1));
        UUID facCoverId = UUID.randomUUID();
        seedFacCover(facCoverId, policyId, "POL-FOU-LRC-001", "FOU-LRC-001",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            "1200.00", "200.00", "1000.00");

        publisher.publishEvent(premiumCededEvent(facCoverId, policyId, "POL-FOU-LRC-001",
            "1200.00", "200.00", "1000.00"));
        entityManager.flush();

        // ── (c) confirm posting is Dr 1410 / Cr 2310 at NET, no 4300 line ──
        Map<String, Object> confirmJe = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry WHERE source_module = 'reinsurance' " +
            "AND source_event_type = 'FAC_PREMIUM_CEDED' AND source_reference = ?",
            facCoverId.toString());
        UUID confirmJeId = (UUID) confirmJe.get("id");
        assertLine(confirmJeId, "1410", new BigDecimal("1000.00"), BigDecimal.ZERO);
        assertLine(confirmJeId, "2310", BigDecimal.ZERO, new BigDecimal("1000.00"));
        assertNoLine(confirmJeId, "4300");

        Long confirmLineCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM journal_entry_line WHERE journal_entry_id = ?",
            Long.class, confirmJeId);
        assertThat(confirmLineCount).as("§65-netted confirm posting is exactly 2 lines").isEqualTo(2L);

        // ── grouping: FacPremiumCededEvent also assigned the cover to a FAC_OUTWARD group ──
        ContractGroupAssignment assignment = assignmentRepository
            .findByContractTypeAndContractIdAndDeletedAtIsNull(ContractType.FAC_OUTWARD, facCoverId)
            .orElseThrow();
        UUID groupId = assignment.getGroup().getId();

        // ── (a) + (b): January LRC release on NET premium ──
        LrcRecognitionResult result = lrcEngine.recognise(janPeriodId);
        entityManager.flush();

        // 1000 × 31/365 = 84.9315... → 84.93
        BigDecimal expectedEarned = new BigDecimal("1000.00")
            .multiply(BigDecimal.valueOf(31))
            .divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);
        assertThat(expectedEarned).isEqualByComparingTo("84.93");
        assertThat(result.groupsWithJournalEntry()).isEqualTo(1);

        Map<String, Object> lrc = jdbcTemplate.queryForMap(
            "SELECT opening_balance, premium_earned, closing_balance " +
            "FROM paa_lrc WHERE group_id = ? AND period_id = ?",
            groupId, janPeriodId);
        assertThat((BigDecimal) lrc.get("premium_earned")).isEqualByComparingTo(expectedEarned);
        // LRC basis is NET for outward — opening at the January (cover-inception)
        // period is the full net premium, mirroring InwardFacLrcIT's GROSS-basis
        // opening assertion for the inward direction.
        assertThat((BigDecimal) lrc.get("opening_balance")).isEqualByComparingTo("1000.00");

        Map<String, Object> lrcJe = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry " +
            "WHERE source_module = 'paa' AND source_event_type = 'LRC_RECOGNITION' " +
            "AND source_reference = ?",
            janPeriodId + ":" + groupId);
        UUID lrcJeId = (UUID) lrcJe.get("id");
        // Sign-flip: Dr 5210 (expense) / Cr 1410 (asset run down) — the mirror
        // of DIRECT/FAC_INWARD's Dr LRC-liability / Cr revenue shape.
        assertLine(lrcJeId, "5210", expectedEarned, BigDecimal.ZERO);
        assertLine(lrcJeId, "1410", BigDecimal.ZERO, expectedEarned);

        BigDecimal net = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(debit_amount), 0) - COALESCE(SUM(credit_amount), 0) " +
            "FROM journal_entry_line WHERE journal_entry_id = ?",
            BigDecimal.class, lrcJeId);
        assertThat(net).as("Σdebit == Σcredit").isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── 2. Onerous test exemption for reinsurance held ────────────────────────
    @Test
    @DisplayName("cumulative incurred > earned on a FAC_OUTWARD group produces no loss-component JE (onerous-test exemption)")
    void onerousTestExemptsFacOutwardGroup() {
        UUID policyId = seedPolicy("POL-FOU-ONEROUS", motorCobId, LocalDate.of(2026, 1, 1));
        UUID facCoverId = UUID.randomUUID();
        seedFacCover(facCoverId, policyId, "POL-FOU-ONEROUS", "FOU-ONEROUS-001",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            "1200.00", "200.00", "1000.00");

        publisher.publishEvent(premiumCededEvent(facCoverId, policyId, "POL-FOU-ONEROUS",
            "1200.00", "200.00", "1000.00"));
        entityManager.flush();

        ContractGroupAssignment assignment = assignmentRepository
            .findByContractTypeAndContractIdAndDeletedAtIsNull(ContractType.FAC_OUTWARD, facCoverId)
            .orElseThrow();
        UUID groupId = assignment.getGroup().getId();

        lrcEngine.recognise(janPeriodId);
        entityManager.flush();

        // Earned in January ≈ 84.93 (see test 1). Seed a cumulative "incurred"
        // (paa_lic.claims_incurred) far in excess of that — absent the
        // FAC_OUTWARD skip guard, targetLossComponent(earned, incurred) would
        // be max(0, 500 − 84.93) = 415.07 and the engine would post a large
        // Dr 5150 / Cr 2130 loss-component JE for this group.
        jdbcTemplate.update(
            "INSERT INTO paa_lic (id, group_id, period_id, claims_incurred, currency_code, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), groupId, janPeriodId, new BigDecimal("500.00"), "NGN", "test");

        OnerousTestResult result = onerousEngine.test(janPeriodId);
        entityManager.flush();

        assertThat(result.groupsTested())
            .as("FAC_OUTWARD group must be skipped entirely by the onerous test — the only paa_lrc row "
                + "in this period belongs to it, so a correct guard leaves nothing tested")
            .isZero();
        assertThat(result.groupsWithLossComponentChange()).isZero();
        assertThat(result.totalLossComponentIncrease()).isEqualByComparingTo("0.00");

        Long lcJeCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM journal_entry " +
            "WHERE source_event_type = 'PAA_ONEROUS_TEST' AND source_reference = ?",
            Long.class, janPeriodId + ":" + groupId);
        assertThat(lcJeCount).as("no loss-component JE ever posted for a FAC_OUTWARD (reinsurance-held) group").isZero();

        BigDecimal lossComponent = jdbcTemplate.queryForObject(
            "SELECT loss_component FROM paa_lrc WHERE group_id = ? AND period_id = ?",
            BigDecimal.class, groupId, janPeriodId);
        assertThat(lossComponent).as("paa_lrc.loss_component stays at its default zero — never touched").isEqualByComparingTo("0.00");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID seedPolicy(String policyNumber, UUID cobId, LocalDate startDate) {
        UUID policyId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO policies (id, policy_number, customer_id, customer_name, " +
            "product_id, product_name, product_code, product_rate, " +
            "class_of_business_id, class_of_business_name, class_of_business_code, " +
            "policy_start_date, policy_end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            policyId, policyNumber, UUID.randomUUID(), "Test Customer",
            UUID.randomUUID(), "Test Product", "PROD-TEST", new BigDecimal("0.0500"),
            cobId, "Motor Comprehensive", "MOTOR-COMP",
            startDate, startDate.plusYears(1), "APPROVED", "test");
        return policyId;
    }

    private void seedFacCover(UUID id, UUID policyId, String policyNumber, String facReference,
                               LocalDate coverFrom, LocalDate coverTo,
                               String premiumCeded, String commissionAmount, String netPremium) {
        jdbcTemplate.update(
            "INSERT INTO ri_fac_covers (id, fac_reference, policy_id, policy_number, " +
            "reinsurance_company_id, reinsurance_company_name, status, sum_insured_ceded, premium_rate, " +
            "premium_ceded, commission_rate, commission_amount, net_premium, currency_code, " +
            "cover_from, cover_to, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, facReference, policyId, policyNumber,
            UUID.randomUUID(), "Munich Re", "APPROVED", new BigDecimal("500000.00"), new BigDecimal("2.500000"),
            new BigDecimal(premiumCeded), new BigDecimal("0.166667"), new BigDecimal(commissionAmount),
            new BigDecimal(netPremium),
            "NGN", coverFrom, coverTo, "test");
    }

    private FacPremiumCededEvent premiumCededEvent(UUID facCoverId, UUID policyId, String policyNumber,
                                                     String premiumCeded, String commissionAmount, String netPremiumCeded) {
        return new FacPremiumCededEvent(
            facCoverId, "FOU-LRC-REF", policyId, policyNumber,
            UUID.randomUUID(), "Munich Re",
            new BigDecimal(premiumCeded), new BigDecimal(commissionAmount), new BigDecimal(netPremiumCeded), "NGN");
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
