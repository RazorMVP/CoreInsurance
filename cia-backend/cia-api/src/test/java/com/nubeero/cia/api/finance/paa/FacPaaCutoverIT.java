package com.nubeero.cia.api.finance.paa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nubeero.cia.common.audit.AuditLogRepository;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.finance.gl.ChartOfAccountService;
import com.nubeero.cia.finance.gl.FiscalPeriodLookupCache;
import com.nubeero.cia.finance.gl.FiscalPeriodResolver;
import com.nubeero.cia.finance.gl.JournalEntryService;
import com.nubeero.cia.finance.gl.PeriodLockService;
import com.nubeero.cia.finance.gl.PeriodLockedException;
import com.nubeero.cia.finance.gl.PolicyClassResolver;
import com.nubeero.cia.finance.paa.ContractGroupingService;
import com.nubeero.cia.finance.paa.CutoverResult;
import com.nubeero.cia.finance.paa.FacPaaCutoverService;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * End-to-end Testcontainers IT for {@link FacPaaCutoverService} — FAC /
 * IFRS-17 PAA workstream Task 5's modified-prospective transition for
 * in-force facultative reinsurance that pre-dates this system's PAA
 * measurement.
 *
 * <h2>Fix round 2 — GL is the sole source of truth</h2>
 * <p>{@link FacPaaCutoverService#runCutover(UUID)}'s catch-up posting no
 * longer writes an ad-hoc {@code paa_lrc} row (see {@code
 * FacPaaCutoverService.postCatchUp}'s javadoc) — writing one collided with
 * {@link LrcEngine}'s own row for the SAME {@code (group, period)} the next
 * time {@code recognise} ran for that period, aborting recognition
 * tenant-wide. {@link #cutoverThenRecognise_backlogAndOpenPeriodSliceEachEarnOnce_noDoubleCount()}
 * is this fix's core regression guard: cutover, then an ordinary {@code
 * recognise()} for the SAME period, must not throw and must not
 * double-count.
 *
 * <p>Harness mirrors {@code OutwardFacLrcIT} (real {@code
 * ContractGroupingService} + {@code LrcEngine}, Testcontainers Postgres,
 * {@code spring.flyway.target=77}) plus {@code PeriodLockInterceptorIT}'s
 * {@code TestSupportConfig} pattern for the extra {@code AuditService} /
 * {@code ObjectMapper} beans {@link PeriodLockService} needs but {@code
 * @DataJpaTest} doesn't auto-wire.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    ChartOfAccountService.class,
    FiscalPeriodResolver.class,
    FiscalPeriodLookupCache.class,
    JournalEntryService.class,
    PolicyClassResolver.class,
    ContractGroupingService.class,
    LrcEngine.class,
    PeriodLockService.class,
    FacPaaCutoverService.class,
    FacPaaCutoverIT.TestSupportConfig.class
})
class FacPaaCutoverIT {

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

    @Autowired private FacPaaCutoverService cutoverService;
    @Autowired private LrcEngine lrcEngine;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    private UUID motorCobId;
    private UUID fiscalYearId;

    @BeforeEach
    void seedFixtures() {
        motorCobId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO classes_of_business (id, name, code, description, created_by) " +
            "VALUES (?, ?, ?, ?, ?)",
            motorCobId, "Motor Comprehensive", "MOTOR-CUTOVER", "Motor comp cutover test", "test");

        fiscalYearId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            fiscalYearId, "FY-CUTOVER-2026",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ACTIVE", "test");
    }

    // ── 1. Happy path: ungrouped in-force inward + outward FAC get grouped and
    //      caught up, bounded entirely into the given OPEN period; GL only —
    //      no paa_lrc row exists until the next ordinary recognise() ──────────
    @Test
    @DisplayName("runCutover groups ungrouped in-force FAC (inward + outward) and posts the "
        + "inception-to-period-start catch-up into the OPEN period only, GL only (no paa_lrc row yet)")
    void runCutover_groupsAndPostsCatchUpIntoOpenPeriodOnly() {
        UUID marchPeriodId = seedOpenMonthPeriod(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        UUID facInwardId = seedInwardFac(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "1200.00");

        UUID policyId = seedPolicy("POL-CUTOVER-001", LocalDate.of(2026, 1, 1));
        UUID facCoverId = seedOutwardFac(policyId, "POL-CUTOVER-001",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "1000.00");
        entityManager.flush();

        CutoverResult result = cutoverService.runCutover(marchPeriodId);
        entityManager.flush();

        assertThat(result.periodId()).isEqualTo(marchPeriodId);
        assertThat(result.contractsGrouped()).isEqualTo(2);

        // catchupThrough = March 1 - 1 day = Feb 28; daysBetween(Jan 1, Feb 28) inclusive = 59.
        // 1200 x 59 / 365 = 193.9726... -> 193.97
        BigDecimal expectedInwardCatchUp = new BigDecimal("1200.00")
            .multiply(BigDecimal.valueOf(59))
            .divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);
        assertThat(expectedInwardCatchUp).isEqualByComparingTo("193.97");
        // 1000 x 59 / 365 = 161.6438... -> 161.64
        BigDecimal expectedOutwardCatchUp = new BigDecimal("1000.00")
            .multiply(BigDecimal.valueOf(59))
            .divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);
        assertThat(expectedOutwardCatchUp).isEqualByComparingTo("161.64");

        assertThat(result.totalCatchUpEarned())
            .isEqualByComparingTo(expectedInwardCatchUp.add(expectedOutwardCatchUp));

        // ── grouping side effect: both contracts now have a contract_group_assignment row ──
        Map<String, Object> inwardAssignment = jdbcTemplate.queryForMap(
            "SELECT group_id FROM contract_group_assignment WHERE contract_type = 'FAC_INWARD' AND contract_id = ?",
            facInwardId);
        Map<String, Object> outwardAssignment = jdbcTemplate.queryForMap(
            "SELECT group_id FROM contract_group_assignment WHERE contract_type = 'FAC_OUTWARD' AND contract_id = ?",
            facCoverId);
        UUID inwardGroupId = (UUID) inwardAssignment.get("group_id");
        UUID outwardGroupId = (UUID) outwardAssignment.get("group_id");

        // ── catch-up JE for inward: Dr 2210 / Cr 4330, bounded into marchPeriodId ──
        Map<String, Object> inwardJe = jdbcTemplate.queryForMap(
            "SELECT id, period_id FROM journal_entry " +
            "WHERE source_module = 'paa' AND source_event_type = 'PAA_CUTOVER' AND source_reference = ?",
            "FAC_INWARD:" + facInwardId);
        assertThat((UUID) inwardJe.get("period_id"))
            .as("catch-up JE lands in the OPEN cutover period, not any other period")
            .isEqualTo(marchPeriodId);
        UUID inwardJeId = (UUID) inwardJe.get("id");
        assertLine(inwardJeId, "2210", expectedInwardCatchUp, BigDecimal.ZERO);
        assertLine(inwardJeId, "4330", BigDecimal.ZERO, expectedInwardCatchUp);

        // ── catch-up JE for outward: Dr 5210 / Cr 1410, bounded into marchPeriodId ──
        Map<String, Object> outwardJe = jdbcTemplate.queryForMap(
            "SELECT id, period_id FROM journal_entry " +
            "WHERE source_module = 'paa' AND source_event_type = 'PAA_CUTOVER' AND source_reference = ?",
            "FAC_OUTWARD:" + facCoverId);
        assertThat((UUID) outwardJe.get("period_id")).isEqualTo(marchPeriodId);
        UUID outwardJeId = (UUID) outwardJe.get("id");
        assertLine(outwardJeId, "5210", expectedOutwardCatchUp, BigDecimal.ZERO);
        assertLine(outwardJeId, "1410", BigDecimal.ZERO, expectedOutwardCatchUp);

        // ── GL is the sole source of truth: the catch-up writes NO paa_lrc row ──
        Long inwardLrcCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM paa_lrc WHERE group_id = ?", Long.class, inwardGroupId);
        assertThat(inwardLrcCount).as("cutover catch-up must not write a paa_lrc row").isZero();
        Long outwardLrcCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM paa_lrc WHERE group_id = ?", Long.class, outwardGroupId);
        assertThat(outwardLrcCount).isZero();
    }

    // ── 2. Core fix-round-2 regression guard: cutover THEN an ordinary
    //      recognise() for the SAME period must not throw and must not
    //      double-count — the backlog and the open-period slice are each
    //      earned exactly once, reconciling to the true cumulative total ────
    @Test
    @DisplayName("cutover THEN recognise() for the same OPEN period: no throw, backlog earned once by "
        + "cutover, open-period slice earned once by recognise, no double-count")
    void cutoverThenRecognise_backlogAndOpenPeriodSliceEachEarnOnce_noDoubleCount() {
        UUID marchPeriodId = seedOpenMonthPeriod(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        UUID facInwardId = seedInwardFac(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "1200.00");
        entityManager.flush();

        CutoverResult cutoverResult = cutoverService.runCutover(marchPeriodId);
        entityManager.flush();

        BigDecimal expectedBacklog = new BigDecimal("1200.00")
            .multiply(BigDecimal.valueOf(59))
            .divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);
        assertThat(expectedBacklog).isEqualByComparingTo("193.97");
        assertThat(cutoverResult.totalCatchUpEarned()).isEqualByComparingTo(expectedBacklog);

        Map<String, Object> assignment = jdbcTemplate.queryForMap(
            "SELECT group_id FROM contract_group_assignment WHERE contract_type = 'FAC_INWARD' AND contract_id = ?",
            facInwardId);
        UUID groupId = (UUID) assignment.get("group_id");

        // (a) no throw — the whole point of the fix: no paa_lrc collision with the cutover's catch-up.
        LrcRecognitionResult marchResult = lrcEngine.recognise(marchPeriodId);
        entityManager.flush();
        assertThat(marchResult.groupsWithJournalEntry())
            .as("the group earns its March slice via the ordinary recognise() call")
            .isEqualTo(1);

        // ── (b) the March-only slice, earned by recognise(), independent of the backlog ──
        // 1200 x 31(March) / 365 = 101.9178... -> 101.92
        BigDecimal expectedMarchSlice = new BigDecimal("1200.00")
            .multiply(BigDecimal.valueOf(31))
            .divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);
        assertThat(expectedMarchSlice).isEqualByComparingTo("101.92");

        Map<String, Object> marchLrc = jdbcTemplate.queryForMap(
            "SELECT opening_balance, premium_earned, closing_balance FROM paa_lrc " +
            "WHERE group_id = ? AND period_id = ?", groupId, marchPeriodId);
        assertThat((BigDecimal) marchLrc.get("premium_earned")).isEqualByComparingTo(expectedMarchSlice);
        // opening = premium - backlog = 1200 - 193.97 = 1006.03, which is exactly what a
        // normally-tracked contract's openingAmount(marchStart) would independently compute
        // (daysBetween(Mar1, Dec31) = 306 days: 1200 x 306 / 365 = 1006.0273... -> 1006.03).
        assertThat((BigDecimal) marchLrc.get("opening_balance")).isEqualByComparingTo("1006.03");
        assertThat((BigDecimal) marchLrc.get("closing_balance"))
            .isEqualByComparingTo(new BigDecimal("1006.03").subtract(expectedMarchSlice));

        Map<String, Object> marchJe = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry WHERE source_module = 'paa' AND source_event_type = 'LRC_RECOGNITION' " +
            "AND source_reference = ?", marchPeriodId + ":" + groupId);
        UUID marchJeId = (UUID) marchJe.get("id");
        assertLine(marchJeId, "2210", expectedMarchSlice, BigDecimal.ZERO);
        assertLine(marchJeId, "4330", BigDecimal.ZERO, expectedMarchSlice);

        // ── (c) no double-count: total 2210 debits across BOTH JEs (cutover catch-up +
        //     ordinary recognise) equal the true cumulative earned-through-Mar-31 figure,
        //     independently computed as premium x daysBetween(Jan1,Mar31) / totalDays. ──
        BigDecimal totalDebited2210 = jdbcTemplate.queryForObject(
            "SELECT SUM(l.debit_amount) FROM journal_entry_line l " +
            "JOIN chart_of_account a ON a.id = l.account_id " +
            "JOIN journal_entry je ON je.id = l.journal_entry_id " +
            "WHERE a.code = '2210' AND je.source_module = 'paa' " +
            "AND je.source_reference IN (?, ?)",
            BigDecimal.class,
            "FAC_INWARD:" + facInwardId, marchPeriodId + ":" + groupId);
        BigDecimal expectedCumulative = new BigDecimal("1200.00")
            .multiply(BigDecimal.valueOf(90))   // Jan(31) + Feb(28) + Mar(31) = 90 days
            .divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);
        assertThat(expectedCumulative).isEqualByComparingTo("295.89");
        assertThat(totalDebited2210)
            .as("backlog + open-period slice reconcile to the true cumulative total — no double-count")
            .isEqualByComparingTo(expectedCumulative);
        assertThat(expectedBacklog.add(expectedMarchSlice)).isEqualByComparingTo(expectedCumulative);
    }

    // ── 3. Re-running runCutover against the same period is idempotent per
    //      contract — each contract's catch-up posts exactly once ──────────
    @Test
    @DisplayName("re-running runCutover against the same OPEN period posts each contract's catch-up "
        + "exactly once — the second run groups nothing new")
    void runCutover_reRunAgainstSamePeriod_postsEachContractOnce() {
        UUID marchPeriodId = seedOpenMonthPeriod(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        UUID facInwardId = seedInwardFac(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "1200.00");
        entityManager.flush();

        CutoverResult first = cutoverService.runCutover(marchPeriodId);
        entityManager.flush();
        assertThat(first.contractsGrouped()).isEqualTo(1);

        CutoverResult second = cutoverService.runCutover(marchPeriodId);
        entityManager.flush();
        assertThat(second.contractsGrouped())
            .as("the contract is already grouped — the second run finds nothing ungrouped left")
            .isZero();
        assertThat(second.totalCatchUpEarned()).isEqualByComparingTo(BigDecimal.ZERO);

        Long jeCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM journal_entry WHERE source_module = 'paa' " +
            "AND source_event_type = 'PAA_CUTOVER' AND source_reference = ?",
            Long.class, "FAC_INWARD:" + facInwardId);
        assertThat(jeCount).as("catch-up posted exactly once across both runs").isEqualTo(1L);

        Long assignmentCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM contract_group_assignment WHERE contract_type = 'FAC_INWARD' AND contract_id = ?",
            Long.class, facInwardId);
        assertThat(assignmentCount).as("no duplicate assignment row from the second run").isEqualTo(1L);
    }

    // ── 4. Guard: a CLOSED period rejects BEFORE any grouping or posting ─────
    @Test
    @DisplayName("runCutover against a HARD-closed period throws PeriodLockedException before any work")
    void runCutover_closedPeriod_throwsPeriodLockedExceptionBeforeAnyWork() {
        UUID closedPeriodId = seedOpenMonthPeriod(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        hardCloseDirect(closedPeriodId);

        UUID facInwardId = seedInwardFac(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "1200.00");
        entityManager.flush();

        assertThatThrownBy(() -> cutoverService.runCutover(closedPeriodId))
            .isInstanceOf(PeriodLockedException.class);

        Long assignmentCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM contract_group_assignment WHERE contract_type = 'FAC_INWARD' AND contract_id = ?",
            Long.class, facInwardId);
        assertThat(assignmentCount).as("no grouping happened — the guard ran before any work").isZero();

        Long jeCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM journal_entry WHERE source_event_type = 'PAA_CUTOVER'", Long.class);
        assertThat(jeCount).as("no catch-up JE posted — the guard ran before any work").isZero();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID seedOpenMonthPeriod(LocalDate start, LocalDate end) {
        UUID periodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            periodId, fiscalYearId, "MONTH", start, end, "OPEN", "test");
        return periodId;
    }

    private void hardCloseDirect(UUID periodId) {
        jdbcTemplate.update(
            "INSERT INTO period_lock (id, fiscal_period_id, lock_type, locked_at, locked_by, created_by) " +
            "VALUES (?, ?, 'HARD', now(), ?, ?)",
            UUID.randomUUID(), periodId, "test", "test");
        jdbcTemplate.update("UPDATE fiscal_period SET status = 'HARD_CLOSED' WHERE id = ?", periodId);
    }

    private UUID seedInwardFac(LocalDate coverFrom, LocalDate coverTo, String grossPremium) {
        UUID facInwardId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO ri_fac_inwards (id, fac_inward_reference, ceding_company_id, ceding_company_name, " +
            "class_of_business_id, class_of_business_name, status, " +
            "sum_insured, our_share_pct, accepted_sum_insured, premium_rate, " +
            "gross_premium, commission_rate, commission_amount, net_premium, " +
            "currency_code, cover_from, cover_to, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            facInwardId, "FAC-IN-CUTOVER-001", UUID.randomUUID(), "Legacy Ceding Co",
            motorCobId, "Motor Comprehensive", "ACTIVE",
            new BigDecimal("10000000.00"), new BigDecimal("0.5000"), new BigDecimal("5000000.00"),
            new BigDecimal("0.024000"),
            new BigDecimal(grossPremium), new BigDecimal("0.2000"), new BigDecimal("200.00"),
            new BigDecimal("1000.00"),
            "NGN", coverFrom, coverTo, "test");
        return facInwardId;
    }

    private UUID seedPolicy(String policyNumber, LocalDate startDate) {
        UUID policyId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO policies (id, policy_number, customer_id, customer_name, " +
            "product_id, product_name, product_code, product_rate, " +
            "class_of_business_id, class_of_business_name, class_of_business_code, " +
            "policy_start_date, policy_end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            policyId, policyNumber, UUID.randomUUID(), "Test Customer",
            UUID.randomUUID(), "Test Product", "PROD-CUTOVER", new BigDecimal("0.0500"),
            motorCobId, "Motor Comprehensive", "MOTOR-CUTOVER",
            startDate, startDate.plusYears(1), "APPROVED", "test");
        return policyId;
    }

    private UUID seedOutwardFac(UUID policyId, String policyNumber,
                                 LocalDate coverFrom, LocalDate coverTo, String netPremium) {
        UUID facCoverId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO ri_fac_covers (id, fac_reference, policy_id, policy_number, " +
            "reinsurance_company_id, reinsurance_company_name, status, sum_insured_ceded, premium_rate, " +
            "premium_ceded, commission_rate, commission_amount, net_premium, currency_code, " +
            "cover_from, cover_to, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            facCoverId, "FAC-OUT-CUTOVER-001", policyId, policyNumber,
            UUID.randomUUID(), "Munich Re", "CONFIRMED", new BigDecimal("500000.00"), new BigDecimal("2.500000"),
            new BigDecimal("1200.00"), new BigDecimal("0.166667"), new BigDecimal("200.00"),
            new BigDecimal(netPremium),
            "NGN", coverFrom, coverTo, "test");
        return facCoverId;
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
     * Supplies the auxiliary beans {@code @DataJpaTest} doesn't auto-wire:
     * an {@link ObjectMapper} configured for Java time types, the {@link
     * AuditService} that {@link PeriodLockService} depends on (mirrors
     * {@code PeriodLockInterceptorIT.TestSupportConfig}), and the COA cache
     * regions {@link ChartOfAccountService}'s {@code @Cacheable} methods
     * expect pre-registered.
     */
    @TestConfiguration
    @EnableCaching
    static class TestSupportConfig {

        @Bean
        @Primary
        ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }

        @Bean
        AuditService auditService(AuditLogRepository auditLogRepository, ObjectMapper mapper) {
            return new AuditService(auditLogRepository, mapper, mock(ApplicationEventPublisher.class));
        }

        @Bean
        CacheManager testCacheManager() {
            return new ConcurrentMapCacheManager(
                ChartOfAccountService.CACHE_BY_CODE,
                ChartOfAccountService.CACHE_BY_IFRS17,
                ChartOfAccountService.CACHE_BY_IFRS9,
                ChartOfAccountService.CACHE_TREE);
        }
    }
}
