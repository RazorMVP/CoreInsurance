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
    //      caught up, bounded entirely into the given OPEN period ─────────────
    @Test
    @DisplayName("runCutover groups ungrouped in-force FAC (inward + outward) and posts the "
        + "inception-to-period-start catch-up into the OPEN period only")
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
            .divide(BigDecimal.valueOf(365), 2, java.math.RoundingMode.HALF_UP);
        assertThat(expectedInwardCatchUp).isEqualByComparingTo("193.97");
        // 1000 x 59 / 365 = 161.6438... -> 161.64
        BigDecimal expectedOutwardCatchUp = new BigDecimal("1000.00")
            .multiply(BigDecimal.valueOf(59))
            .divide(BigDecimal.valueOf(365), 2, java.math.RoundingMode.HALF_UP);
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

        // ── paa_lrc rows for the cutover period ──
        Map<String, Object> inwardLrc = jdbcTemplate.queryForMap(
            "SELECT premium_earned, closing_balance FROM paa_lrc WHERE group_id = ? AND period_id = ?",
            inwardGroupId, marchPeriodId);
        assertThat((BigDecimal) inwardLrc.get("premium_earned")).isEqualByComparingTo(expectedInwardCatchUp);
        assertThat((BigDecimal) inwardLrc.get("closing_balance"))
            .isEqualByComparingTo(new BigDecimal("1200.00").subtract(expectedInwardCatchUp));

        Map<String, Object> outwardLrc = jdbcTemplate.queryForMap(
            "SELECT premium_earned, closing_balance FROM paa_lrc WHERE group_id = ? AND period_id = ?",
            outwardGroupId, marchPeriodId);
        assertThat((BigDecimal) outwardLrc.get("premium_earned")).isEqualByComparingTo(expectedOutwardCatchUp);
        assertThat((BigDecimal) outwardLrc.get("closing_balance"))
            .isEqualByComparingTo(new BigDecimal("1000.00").subtract(expectedOutwardCatchUp));
    }

    // ── 2. Guard: a CLOSED period rejects BEFORE any grouping or posting ─────
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
