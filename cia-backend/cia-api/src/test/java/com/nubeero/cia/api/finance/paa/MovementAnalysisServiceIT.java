package com.nubeero.cia.api.finance.paa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nubeero.cia.common.audit.AuditLogRepository;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.event.FacDerecognisedEvent;
import com.nubeero.cia.finance.gl.ChartOfAccountService;
import com.nubeero.cia.finance.gl.FiscalPeriodLookupCache;
import com.nubeero.cia.finance.gl.FiscalPeriodResolver;
import com.nubeero.cia.finance.gl.JournalEntryService;
import com.nubeero.cia.finance.gl.PeriodLockService;
import com.nubeero.cia.finance.gl.PolicyClassResolver;
import com.nubeero.cia.finance.gl.PostingRuleService;
import com.nubeero.cia.finance.paa.ContractGroupingService;
import com.nubeero.cia.finance.paa.CutoverResult;
import com.nubeero.cia.finance.paa.DiscountUnwindEngine;
import com.nubeero.cia.finance.paa.FacDerecognitionListener;
import com.nubeero.cia.finance.paa.FacPaaCutoverService;
import com.nubeero.cia.finance.paa.LicEngine;
import com.nubeero.cia.finance.paa.LrcEngine;
import com.nubeero.cia.finance.paa.MovementAnalysis;
import com.nubeero.cia.finance.paa.MovementAnalysisService;
import com.nubeero.cia.finance.paa.OnerousContractTestEngine;
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
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * End-to-end Testcontainers IT for {@link MovementAnalysisService} —
 * Slice 2.8. Each test seeds a complete period-close fixture, runs the
 * upstream engines (LRC + LIC + Unwind + Onerous), then asserts the
 * §103 movement analysis output.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Empty period: no rows in paa_lrc/paa_lic → byGroup empty, all totals zero.</li>
 *   <li>Single-policy, no claims: LRC totals populated, LIC zero.</li>
 *   <li>Single-policy with claim: LRC + LIC both populated.</li>
 *   <li>Onerous group: loss component appears in LRC totals.</li>
 *   <li>Per-group breakdown ordered by (portfolio_code, cohort_year, onerousness).</li>
 *   <li>Aggregate totals = sum of per-group rows.</li>
 *   <li>Total opening / closing liability = LRC + LIC.</li>
 *   <li>FAC / IFRS-17 PAA workstream Task 6: DIRECT, FAC_INWARD, and
 *       FAC_OUTWARD groups each surface their {@code contractNature} in
 *       the movement analysis, sourced from {@code portfolio.contract_nature}
 *       via the V78-recreated {@code paa_movement_analysis} view.</li>
 *   <li>FAC / IFRS-17 PAA workstream Task 6b: a cancelled FAC group's
 *       GL-only derecognition release is composed into the §103 roll-forward
 *       as accelerated premium earning — inward, outward, the
 *       recognise-then-cancel-same-period merge edge, a cutover'd-then-
 *       recognised group's non-double-count, and a co-existing DIRECT
 *       group's non-contamination.</li>
 * </ol>
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
    PostingRuleService.class,
    PolicyClassResolver.class,
    ContractGroupingService.class,
    PeriodLockService.class,
    LrcEngine.class,
    LicEngine.class,
    DiscountUnwindEngine.class,
    OnerousContractTestEngine.class,
    FacDerecognitionListener.class,
    FacPaaCutoverService.class,
    MovementAnalysisService.class,
    MovementAnalysisServiceIT.TestSupportConfig.class
})
class MovementAnalysisServiceIT {

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
        registry.add("spring.flyway.target", () -> "78");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired private MovementAnalysisService service;
    @Autowired private LrcEngine lrcEngine;
    @Autowired private LicEngine licEngine;
    @Autowired private OnerousContractTestEngine onerousEngine;
    @Autowired private FacPaaCutoverService cutoverService;
    @Autowired private ApplicationEventPublisher publisher;
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
            fiscalYearId, "FY-MA-2026",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ACTIVE", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            janPeriodId, fiscalYearId, "MONTH",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "OPEN", "test");
    }

    /** Seeds an additional OPEN month period under the shared {@link #fiscalYearId}. */
    private UUID seedPeriod(LocalDate start, LocalDate end) {
        UUID periodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            periodId, fiscalYearId, "MONTH", start, end, "OPEN", "test");
        return periodId;
    }

    // ── 1. Empty period: all zeros, empty byGroup ────────────────────────────
    @Test
    @DisplayName("period with no paa_lrc / paa_lic rows: empty byGroup, all totals zero")
    void emptyPeriod() {
        MovementAnalysis ma = service.compute(janPeriodId);

        assertThat(ma.byGroup()).isEmpty();
        assertThat(ma.lrcTotals().opening()).isEqualByComparingTo("0.00");
        assertThat(ma.lrcTotals().closing()).isEqualByComparingTo("0.00");
        assertThat(ma.licTotals().opening()).isEqualByComparingTo("0.00");
        assertThat(ma.licTotals().closing()).isEqualByComparingTo("0.00");
        assertThat(ma.totalOpeningLiability()).isEqualByComparingTo("0.00");
        assertThat(ma.totalClosingLiability()).isEqualByComparingTo("0.00");
        assertThat(ma.periodStart()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(ma.periodEnd()).isEqualTo(LocalDate.of(2026, 1, 31));
    }

    // ── 2. Single policy, no claims: LRC populated, LIC zero ────────────────
    @Test
    @DisplayName("single policy with no claims: LRC totals reflect earnings; LIC zero")
    void singlePolicyNoClaims() {
        UUID groupId = seedGroup("PORT-NC", 2026);
        seedPolicyAndAssignment(groupId, "POL-NC",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "365000.00");
        runMeasurementUpstream();

        MovementAnalysis ma = service.compute(janPeriodId);

        assertThat(ma.byGroup()).hasSize(1);
        assertThat(ma.lrcTotals().premiumsReceived()).isEqualByComparingTo("365000.00");
        // 365000 × 31/365 = 31000
        assertThat(ma.lrcTotals().premiumEarned()).isEqualByComparingTo("31000.00");
        assertThat(ma.licTotals().opening()).isEqualByComparingTo("0.00");
        assertThat(ma.licTotals().closing()).isEqualByComparingTo("0.00");
    }

    // ── 3. Single policy with claim: LRC + LIC both populated ───────────────
    @Test
    @DisplayName("single policy with claim: LRC + LIC both populated, total = sum")
    void singlePolicyWithClaim() {
        UUID groupId = seedGroup("PORT-WC", 2026);
        UUID policyId = seedPolicyAndAssignment(groupId, "POL-WC",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "365000.00");
        seedClaim(policyId, "CLM-WC", "APPROVED",
            ts(2026, 1, 15, 10, 0), null, "50000.00", null);
        runMeasurementUpstream();

        MovementAnalysis ma = service.compute(janPeriodId);

        assertThat(ma.byGroup()).hasSize(1);
        assertThat(ma.licTotals().claimsIncurred()).isEqualByComparingTo("50000.00");
        // Total closing liability = lrcClosing + licClosing
        assertThat(ma.totalClosingLiability())
            .isEqualByComparingTo(ma.lrcTotals().closing().add(ma.licTotals().closing()));
    }

    // ── 4. Onerous group: loss component flows through to totals ────────────
    @Test
    @DisplayName("onerous group: loss_component + change appear in LRC totals")
    void onerousGroupLossComponent() {
        UUID groupId = seedGroup("PORT-ONEROUS-MA", 2026);
        UUID policyId = seedPolicyAndAssignment(groupId, "POL-ONEROUS-MA",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "365000.00");
        // earn 31000, claim 100000 → onerous test recognises LC = 69000
        seedClaim(policyId, "CLM-ONEROUS-MA", "APPROVED",
            ts(2026, 1, 15, 10, 0), null, "100000.00", null);
        runMeasurementUpstream();
        onerousEngine.test(janPeriodId);
        entityManager.flush();

        MovementAnalysis ma = service.compute(janPeriodId);

        assertThat(ma.byGroup()).hasSize(1);
        assertThat(ma.lrcTotals().lossComponent()).isEqualByComparingTo("69000.00");
        assertThat(ma.lrcTotals().lossComponentChange()).isEqualByComparingTo("69000.00");
    }

    // ── 5. Per-group ordering: by portfolio_code, cohort_year, onerousness ─
    @Test
    @DisplayName("byGroup ordered by (portfolio_code, cohort_year, onerousness)")
    void byGroupOrdering() {
        UUID groupZ = seedGroup("PORT-Z-MA", 2026);
        UUID groupA = seedGroup("PORT-A-MA", 2026);
        UUID groupM = seedGroup("PORT-M-MA", 2027);
        seedPolicyAndAssignment(groupZ, "POL-Z-MA",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "100000.00");
        seedPolicyAndAssignment(groupA, "POL-A-MA",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "100000.00");
        // groupM (2027) — policy starts in Jan 2026 but the group's cohort_year
        // is set to 2027 just for ordering — the engine doesn't care about the
        // semantic mismatch for this ordering test.
        seedPolicyAndAssignment(groupM, "POL-M-MA",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "100000.00");
        runMeasurementUpstream();

        MovementAnalysis ma = service.compute(janPeriodId);

        assertThat(ma.byGroup())
            .extracting(MovementAnalysis.GroupMovementEntry::portfolioCode)
            .containsExactly("PORT-A-MA", "PORT-M-MA", "PORT-Z-MA");
    }

    // ── 6. Aggregate totals = sum of per-group rows ─────────────────────────
    @Test
    @DisplayName("aggregate totals equal the sum across per-group rows")
    void aggregateTotalsMatch() {
        UUID groupA = seedGroup("PORT-AGG-A", 2026);
        UUID groupB = seedGroup("PORT-AGG-B", 2026);
        UUID policyA = seedPolicyAndAssignment(groupA, "POL-AGG-A",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "365000.00");
        UUID policyB = seedPolicyAndAssignment(groupB, "POL-AGG-B",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "730000.00");
        seedClaim(policyA, "CLM-AGG-A", "APPROVED",
            ts(2026, 1, 10, 10, 0), null, "10000.00", null);
        seedClaim(policyB, "CLM-AGG-B", "APPROVED",
            ts(2026, 1, 12, 10, 0), null, "20000.00", null);
        runMeasurementUpstream();

        MovementAnalysis ma = service.compute(janPeriodId);

        // Per-group sum equals total
        BigDecimal sumOpening = ma.byGroup().stream()
            .map(MovementAnalysis.GroupMovementEntry::totalOpening)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumClosing = ma.byGroup().stream()
            .map(MovementAnalysis.GroupMovementEntry::totalClosing)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(ma.totalOpeningLiability()).isEqualByComparingTo(sumOpening);
        assertThat(ma.totalClosingLiability()).isEqualByComparingTo(sumClosing);

        // Insurance liability = LRC + LIC
        assertThat(ma.totalOpeningLiability())
            .isEqualByComparingTo(ma.lrcTotals().opening().add(ma.licTotals().opening()));
        assertThat(ma.totalClosingLiability())
            .isEqualByComparingTo(ma.lrcTotals().closing().add(ma.licTotals().closing()));
    }

    // ── 7. Group dimensions preserved (cohort_year, onerousness, currency) ─
    @Test
    @DisplayName("group entries carry cohort_year, onerousness, currency dimensions")
    void groupDimensionsPreserved() {
        UUID groupId = seedGroup("PORT-DIMS-MA", 2026);
        seedPolicyAndAssignment(groupId, "POL-DIMS-MA",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "365000.00");
        runMeasurementUpstream();

        MovementAnalysis ma = service.compute(janPeriodId);

        assertThat(ma.byGroup()).hasSize(1);
        var entry = ma.byGroup().get(0);
        assertThat(entry.cohortYear()).isEqualTo(2026);
        assertThat(entry.onerousness()).isEqualTo("NOT_ONEROUS");
        assertThat(entry.groupStatus()).isEqualTo("OPEN");
        assertThat(entry.currencyCode()).isEqualTo("NGN");
        assertThat(entry.portfolioCode()).isEqualTo("PORT-DIMS-MA");
        assertThat(entry.portfolioName()).isEqualTo("Test PORT-DIMS-MA");
    }

    // ── 8. FAC / IFRS-17 PAA workstream Task 6: contract_nature surfacing ───
    @Test
    @DisplayName("DIRECT, FAC_INWARD, and FAC_OUTWARD groups each surface their contractNature")
    void facGroupsSurfaceContractNature() {
        UUID directGroupId = seedGroup("PORT-CN-DIRECT", 2026);
        seedPolicyAndAssignment(directGroupId, "POL-CN-DIRECT",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "365000.00");

        UUID inwardGroupId = seedFacInwardGroup("FIN-CN", 2026);
        seedFacInwardAssignment(inwardGroupId, "FAC-IN-CN-001",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            "1200.00", "1000.00", "200.00");

        UUID outwardGroupId = seedFacOutwardGroup("FOU-CN", 2026);
        seedFacOutwardAssignment(outwardGroupId, "FAC-OUT-CN-001",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            "1200.00", "1000.00");

        runMeasurementUpstream();

        MovementAnalysis ma = service.compute(janPeriodId);

        assertThat(ma.byGroup()).hasSize(3);
        var byPortfolioCode = ma.byGroup().stream()
            .collect(java.util.stream.Collectors.toMap(
                MovementAnalysis.GroupMovementEntry::portfolioCode,
                MovementAnalysis.GroupMovementEntry::contractNature));

        assertThat(byPortfolioCode.get("PORT-CN-DIRECT")).isEqualTo("DIRECT");
        assertThat(byPortfolioCode.get("FIN-CN")).isEqualTo("FAC_INWARD");
        assertThat(byPortfolioCode.get("FOU-CN")).isEqualTo("FAC_OUTWARD");
    }

    // ── 9. Task 6b: inward cancel-after-recognise surfaces the release as a
    //      synthetic entry in the period the cancellation lands in ──────────
    @Test
    @DisplayName("Task 6b: inward FAC recognised in P1, cancelled in P2 — P2's movement analysis "
        + "surfaces the release: premiumEarned == released, lrcClosing == 0, contractNature == FAC_INWARD, "
        + "and LRC totals include it")
    void inwardCancelAfterRecognise_surfacesReleaseInLaterPeriod() {
        UUID febPeriodId = seedPeriod(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

        UUID groupId = seedFacInwardGroup("FIN-DERC-A", 2026);
        UUID facInwardId = UUID.randomUUID();
        seedFacInwardAssignmentWithId(groupId, facInwardId, "FAC-IN-DERC-A",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            "1200.00", "1000.00", "200.00");
        entityManager.flush();

        lrcEngine.recognise(janPeriodId);
        entityManager.flush();

        // Baseline (matches FacLifecycleLrcIT): 1200 x 31/365 = 101.92 earned in Jan; remaining = 1098.08.
        BigDecimal janEarned = new BigDecimal("1200.00")
            .multiply(BigDecimal.valueOf(31)).divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);
        BigDecimal released = new BigDecimal("1200.00").subtract(janEarned);
        assertThat(released).isEqualByComparingTo("1098.08");

        jdbcTemplate.update("UPDATE ri_fac_inwards SET status = 'CANCELLED' WHERE id = ?", facInwardId);
        publisher.publishEvent(new FacDerecognisedEvent(
            FacDerecognisedEvent.ContractType.FAC_INWARD, facInwardId, LocalDate.of(2026, 2, 15)));
        entityManager.flush();

        MovementAnalysis ma = service.compute(febPeriodId);

        assertThat(ma.byGroup()).hasSize(1);
        var entry = ma.byGroup().get(0);
        assertThat(entry.groupId()).isEqualTo(groupId);
        assertThat(entry.contractNature()).isEqualTo("FAC_INWARD");
        assertThat(entry.premiumEarned()).isEqualByComparingTo(released);
        assertThat(entry.lrcClosing()).isEqualByComparingTo("0.00");
        assertThat(entry.lrcOpening()).isEqualByComparingTo(released);

        assertThat(ma.lrcTotals().premiumEarned()).isEqualByComparingTo(released);
        assertThat(ma.lrcTotals().opening()).isEqualByComparingTo(released);
        assertThat(ma.lrcTotals().closing()).isEqualByComparingTo("0.00");
    }

    // ── 10. Task 6b: outward symmetric ───────────────────────────────────────
    @Test
    @DisplayName("Task 6b: outward FAC recognised in P1, cancelled in P2 — P2's movement analysis "
        + "surfaces the release: contractNature == FAC_OUTWARD, released == the 1410 movement")
    void outwardCancelAfterRecognise_surfacesReleaseInLaterPeriod() {
        UUID febPeriodId = seedPeriod(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

        UUID groupId = seedFacOutwardGroup("FOU-DERC-B", 2026);
        UUID facCoverId = UUID.randomUUID();
        seedFacOutwardAssignmentWithId(groupId, facCoverId, "FAC-OUT-DERC-B",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "1200.00", "1000.00");
        entityManager.flush();

        lrcEngine.recognise(janPeriodId);
        entityManager.flush();

        // Baseline (matches FacLifecycleLrcIT): 1000 x 31/365 = 84.93 earned in Jan; remaining = 915.07.
        BigDecimal janEarned = new BigDecimal("1000.00")
            .multiply(BigDecimal.valueOf(31)).divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);
        BigDecimal released = new BigDecimal("1000.00").subtract(janEarned);
        assertThat(released).isEqualByComparingTo("915.07");

        jdbcTemplate.update("UPDATE ri_fac_covers SET status = 'CANCELLED' WHERE id = ?", facCoverId);
        publisher.publishEvent(new FacDerecognisedEvent(
            FacDerecognisedEvent.ContractType.FAC_OUTWARD, facCoverId, LocalDate.of(2026, 2, 15)));
        entityManager.flush();

        MovementAnalysis ma = service.compute(febPeriodId);

        assertThat(ma.byGroup()).hasSize(1);
        var entry = ma.byGroup().get(0);
        assertThat(entry.groupId()).isEqualTo(groupId);
        assertThat(entry.contractNature()).isEqualTo("FAC_OUTWARD");
        assertThat(entry.premiumEarned()).isEqualByComparingTo(released);
        assertThat(entry.lrcClosing()).isEqualByComparingTo("0.00");
    }

    // ── 11. Task 6b: recognise-then-cancel in the SAME period merges into ONE
    //      entry rather than emitting a duplicate row ────────────────────────
    @Test
    @DisplayName("Task 6b: recognise-then-cancel in the SAME period — ONE merged entry, "
        + "premiumEarned = period slice + released, lrcClosing == 0")
    void recogniseThenCancelSamePeriod_mergesIntoOneEntry() {
        UUID groupId = seedFacInwardGroup("FIN-DERC-C", 2026);
        UUID facInwardId = UUID.randomUUID();
        seedFacInwardAssignmentWithId(groupId, facInwardId, "FAC-IN-DERC-C",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            "1200.00", "1000.00", "200.00");
        entityManager.flush();

        lrcEngine.recognise(janPeriodId);
        entityManager.flush();

        BigDecimal janSlice = new BigDecimal("1200.00")
            .multiply(BigDecimal.valueOf(31)).divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);
        BigDecimal released = new BigDecimal("1200.00").subtract(janSlice);
        assertThat(janSlice).isEqualByComparingTo("101.92");
        assertThat(released).isEqualByComparingTo("1098.08");

        // M1 (Task 6b review): test-pin the invariant the merge branch relies on —
        // FacDerecognitionListener releases the group's latest persisted
        // paa_lrc.closing_balance, which is exactly what "released" (computed
        // independently above from the day-count formula) must equal. Read the
        // ACTUAL persisted row rather than re-deriving the same formula in Java,
        // so a future engine change that breaks the tie shows up here.
        Map<String, Object> janLrcRow = jdbcTemplate.queryForMap(
            "SELECT closing_balance FROM paa_lrc WHERE group_id = ? AND period_id = ?", groupId, janPeriodId);
        assertThat((BigDecimal) janLrcRow.get("closing_balance"))
            .as("M1 invariant: the derecognition's released amount must equal the group's persisted "
                + "paa_lrc.closing_balance for the period — this is what the merge branch's hardcoded "
                + "lrcClosing = 0 assumes")
            .isEqualByComparingTo(released);

        // Cancellation effective WITHIN the same January period.
        jdbcTemplate.update("UPDATE ri_fac_inwards SET status = 'CANCELLED' WHERE id = ?", facInwardId);
        publisher.publishEvent(new FacDerecognisedEvent(
            FacDerecognisedEvent.ContractType.FAC_INWARD, facInwardId, LocalDate.of(2026, 1, 20)));
        entityManager.flush();

        MovementAnalysis ma = service.compute(janPeriodId);

        long matching = ma.byGroup().stream().filter(g -> g.groupId().equals(groupId)).count();
        assertThat(matching).as("no duplicate row for the recognise-then-cancel-same-period group").isEqualTo(1);

        var entry = ma.byGroup().stream().filter(g -> g.groupId().equals(groupId)).findFirst().orElseThrow();
        assertThat(entry.premiumEarned())
            .as("period slice + released")
            .isEqualByComparingTo(janSlice.add(released));
        assertThat(entry.premiumEarned()).isEqualByComparingTo("1200.00");
        assertThat(entry.lrcClosing()).isEqualByComparingTo("0.00");
    }

    // ── 12. Task 6b: cutover'd-then-recognised group shows once — the
    //      composition must NOT pick up a PAA_CUTOVER JE as a derecognition ──
    @Test
    @DisplayName("Task 6b: a cutover'd-then-recognised group shows ONCE in the movement analysis — "
        + "the PAA_CUTOVER catch-up JE is not mistaken for a FAC_DERECOGNITION release")
    void cutoverThenRecognise_noDoubleCount() {
        UUID marchPeriodId = seedPeriod(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        // Ungrouped in-force inward FAC (no contract_group_assignment row yet) — cutover candidate.
        UUID facInwardId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO ri_fac_inwards (id, fac_inward_reference, ceding_company_id, ceding_company_name, " +
            "class_of_business_id, class_of_business_name, status, " +
            "sum_insured, our_share_pct, accepted_sum_insured, premium_rate, " +
            "gross_premium, commission_rate, commission_amount, net_premium, " +
            "currency_code, cover_from, cover_to, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            facInwardId, "FAC-IN-CUTOVER-MA", UUID.randomUUID(), "Legacy Ceding Co",
            UUID.randomUUID(), "Test COB", "ACTIVE",
            new BigDecimal("10000000.00"), new BigDecimal("0.5000"), new BigDecimal("5000000.00"),
            new BigDecimal("0.024000"),
            new BigDecimal("1200.00"), new BigDecimal("0.2000"), new BigDecimal("200.00"),
            new BigDecimal("1000.00"),
            "NGN", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "test");
        entityManager.flush();

        CutoverResult cutoverResult = cutoverService.runCutover(marchPeriodId);
        entityManager.flush();
        assertThat(cutoverResult.contractsGrouped()).isEqualTo(1);

        Map<String, Object> assignment = jdbcTemplate.queryForMap(
            "SELECT group_id FROM contract_group_assignment WHERE contract_type = 'FAC_INWARD' AND contract_id = ?",
            facInwardId);
        UUID groupId = (UUID) assignment.get("group_id");

        lrcEngine.recognise(marchPeriodId);
        entityManager.flush();

        // March-only slice (independent of the cutover's Jan1-Feb28 backlog catch-up).
        BigDecimal marchSlice = new BigDecimal("1200.00")
            .multiply(BigDecimal.valueOf(31)).divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);
        assertThat(marchSlice).isEqualByComparingTo("101.92");

        MovementAnalysis ma = service.compute(marchPeriodId);

        long matching = ma.byGroup().stream().filter(g -> g.groupId().equals(groupId)).count();
        assertThat(matching).as("cutover'd-then-recognised group appears exactly once, via the view").isEqualTo(1);

        var entry = ma.byGroup().stream().filter(g -> g.groupId().equals(groupId)).findFirst().orElseThrow();
        assertThat(entry.premiumEarned())
            .as("only the March slice — the PAA_CUTOVER catch-up must not be folded in as a release")
            .isEqualByComparingTo(marchSlice);
    }

    // ── 13. Task 6b: a co-existing, normally-recognised DIRECT group is
    //      unaffected by another group's derecognition composition in the
    //      SAME period compute() call ─────────────────────────────────────
    @Test
    @DisplayName("Task 6b: a normally-recognised DIRECT group is unaffected when another group's "
        + "derecognition release is composed into the SAME period")
    void directGroupUnaffectedByCoExistingDerecognitionComposition() {
        UUID febPeriodId = seedPeriod(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

        UUID directGroupId = seedGroup("PORT-DERC-DIRECT", 2026);
        seedPolicyAndAssignment(directGroupId, "POL-DERC-DIRECT",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "365000.00");

        UUID facGroupId = seedFacInwardGroup("FIN-DERC-MIX", 2026);
        UUID facInwardId = UUID.randomUUID();
        seedFacInwardAssignmentWithId(facGroupId, facInwardId, "FAC-IN-DERC-MIX",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            "1200.00", "1000.00", "200.00");
        entityManager.flush();

        // Both groups recognised in Jan; then both recognised again in Feb — the DIRECT
        // policy is still in force, the FAC contract will be cancelled mid-Feb and
        // therefore earns nothing further once its status flips (in-force filter).
        lrcEngine.recognise(janPeriodId);
        entityManager.flush();

        jdbcTemplate.update("UPDATE ri_fac_inwards SET status = 'CANCELLED' WHERE id = ?", facInwardId);
        publisher.publishEvent(new FacDerecognisedEvent(
            FacDerecognisedEvent.ContractType.FAC_INWARD, facInwardId, LocalDate.of(2026, 2, 15)));
        entityManager.flush();

        lrcEngine.recognise(febPeriodId);
        entityManager.flush();

        // 365000 x 28(Feb, non-leap 2026)/365 = 28000.00 exactly.
        BigDecimal directFebEarned = new BigDecimal("365000.00")
            .multiply(BigDecimal.valueOf(28)).divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);
        assertThat(directFebEarned).isEqualByComparingTo("28000.00");

        MovementAnalysis ma = service.compute(febPeriodId);

        assertThat(ma.byGroup()).hasSize(2);

        // M2 (Task 6b review): "FIN-DERC-MIX" (the synthetic derecognition-only
        // entry) sorts alphabetically BEFORE "PORT-DERC-DIRECT" (the ordinary
        // view row). Before the fix, synthetic entries were appended after the
        // loop regardless of sort key, so this group would have landed at the
        // TAIL (position 1) instead of the front (position 0) — asserting the
        // exact sequence pins the whole-list re-sort, not just presence.
        assertThat(ma.byGroup())
            .as("synthetic derecognition-only entry is sorted alongside view rows by portfolio_code, "
                + "not appended at the tail")
            .extracting(MovementAnalysis.GroupMovementEntry::portfolioCode)
            .containsExactly("FIN-DERC-MIX", "PORT-DERC-DIRECT");

        var directEntry = ma.byGroup().stream()
            .filter(g -> g.groupId().equals(directGroupId)).findFirst().orElseThrow();
        assertThat(directEntry.contractNature()).isEqualTo("DIRECT");
        assertThat(directEntry.premiumEarned())
            .as("DIRECT group's Feb earning is untouched by the FAC group's derecognition composition")
            .isEqualByComparingTo(directFebEarned);

        var facEntry = ma.byGroup().stream()
            .filter(g -> g.groupId().equals(facGroupId)).findFirst().orElseThrow();
        assertThat(facEntry.contractNature()).isEqualTo("FAC_INWARD");
        assertThat(facEntry.lrcClosing()).isEqualByComparingTo("0.00");
    }

    // ── 14. Task 6b review M3: a REVERSED derecognition JE contributes ZERO —
    //      the aggregate is a magnitude sum (SUM(debit+credit)), which does
    //      not self-net like Ifrs9's SUM(credit-debit), so the reversal
    //      exclusion filter (status = 'POSTED' AND reversal_of IS NULL) is
    //      load-bearing ─────────────────────────────────────────────────────
    @Test
    @DisplayName("Task 6b (M3): a REVERSED FAC_DERECOGNITION JE contributes ZERO to the movement analysis — "
        + "no synthetic entry for the group")
    void reversedDerecognition_contributesZeroToMovementAnalysis() {
        UUID febPeriodId = seedPeriod(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

        UUID groupId = seedFacInwardGroup("FIN-DERC-REV", 2026);
        UUID facInwardId = UUID.randomUUID();
        seedFacInwardAssignmentWithId(groupId, facInwardId, "FAC-IN-DERC-REV",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            "1200.00", "1000.00", "200.00");
        entityManager.flush();

        lrcEngine.recognise(janPeriodId);
        entityManager.flush();

        jdbcTemplate.update("UPDATE ri_fac_inwards SET status = 'CANCELLED' WHERE id = ?", facInwardId);
        publisher.publishEvent(new FacDerecognisedEvent(
            FacDerecognisedEvent.ContractType.FAC_INWARD, facInwardId, LocalDate.of(2026, 2, 15)));
        entityManager.flush();

        // Sanity: before reversal, the release surfaces normally (mirrors test #9).
        MovementAnalysis before = service.compute(febPeriodId);
        assertThat(before.byGroup()).as("release surfaces before reversal").hasSize(1);

        Map<String, Object> je = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry " +
            "WHERE source_module = 'paa' AND source_event_type = 'FAC_DERECOGNITION' " +
            "AND source_reference = ?",
            "FAC_INWARD:" + facInwardId);
        UUID jeId = (UUID) je.get("id");

        // Simulate JournalEntryService.reverse(jeId, reason) at the DB level —
        // flips the original to REVERSED and inserts a mirror-image JE with
        // reversalOf set and sourceEventType = REVERSAL (JournalEntryService
        // .REVERSAL_EVENT_TYPE, never FAC_DERECOGNITION), exactly what the real
        // service persists (see JournalEntryService.reverse). Done via raw JDBC
        // rather than the real service call because reverse() resolves the
        // reversal JE's period from the WALL CLOCK (LocalDate.now()) via
        // FiscalPeriodResolver, which this fixture's Jan/Feb-2026-only periods
        // don't cover — the DB-level simulation is deterministic regardless of
        // when the suite runs, and exercises exactly the two facts this test
        // cares about: original status flips to REVERSED, and a sibling
        // REVERSAL-typed JE exists.
        jdbcTemplate.update("UPDATE journal_entry SET status = 'REVERSED' WHERE id = ?", jeId);

        UUID reversalJeId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO journal_entry (id, posting_date, business_date, period_id, source_module, " +
            "source_event_type, source_reference, narrative, posted_by, status, reversal_of, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'POSTED', ?, ?)",
            reversalJeId, LocalDate.of(2026, 2, 20), LocalDate.of(2026, 2, 20), febPeriodId,
            "paa", "REVERSAL", jeId.toString(),
            "REVERSAL of JE " + jeId + ": Task 6b M3 test", "test", jeId, "test");

        List<Map<String, Object>> originalLines = jdbcTemplate.queryForList(
            "SELECT account_id, debit_amount, credit_amount, currency_code, cohort_year, portfolio_id, " +
            "contract_group_id, class_of_business_id FROM journal_entry_line WHERE journal_entry_id = ?", jeId);
        int lineNo = 1;
        for (Map<String, Object> line : originalLines) {
            jdbcTemplate.update(
                "INSERT INTO journal_entry_line (id, journal_entry_id, line_no, account_id, debit_amount, " +
                "credit_amount, currency_code, cohort_year, portfolio_id, contract_group_id, " +
                "class_of_business_id, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), reversalJeId, lineNo++, line.get("account_id"),
                line.get("credit_amount"), line.get("debit_amount"), // swapped — mirrors reverse()
                line.get("currency_code"), line.get("cohort_year"), line.get("portfolio_id"),
                line.get("contract_group_id"), line.get("class_of_business_id"), "test");
        }
        entityManager.flush();

        String originalStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM journal_entry WHERE id = ?", String.class, jeId);
        assertThat(originalStatus).isEqualTo("REVERSED");

        MovementAnalysis after = service.compute(febPeriodId);

        assertThat(after.byGroup())
            .as("a reversed derecognition must not surface as a synthetic release — the magnitude-sum "
                + "aggregate excludes it via status = 'POSTED' AND reversal_of IS NULL")
            .noneMatch(g -> g.groupId().equals(groupId));
        assertThat(after.lrcTotals().premiumEarned())
            .as("the reversed release must not inflate LRC totals either")
            .isEqualByComparingTo("0.00");
    }

    // ── 15. Final-review (Minor 3 + Critical downstream): recognise-then-cancel
    //      ONE contract of a MULTI-CONTRACT group in the SAME period merges the
    //      per-contract release WITHOUT zeroing the surviving contract's closing ─
    @Test
    @DisplayName("multi-contract group, recognise-then-cancel A in the SAME period: the §103 merge "
        + "folds only A's release into premiumEarned and REDUCES lrcClosing to B's remaining "
        + "(survivor preserved), not zero")
    void multiContractRecogniseThenCancelSamePeriod_survivorClosingPreserved() {
        // Clean daily rates: A 3650 (10/day), B 7300 (20/day) over the full 2026 year.
        UUID groupId = seedFacInwardGroup("FIN-DERC-MULTI", 2026);
        UUID aId = UUID.randomUUID();
        UUID bId = UUID.randomUUID();
        seedFacInwardAssignmentWithId(groupId, aId, "FAC-IN-DERC-MULTI-A",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "3650.00", "3650.00", "0.00");
        seedFacInwardAssignmentWithId(groupId, bId, "FAC-IN-DERC-MULTI-B",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "7300.00", "7300.00", "0.00");
        entityManager.flush();

        lrcEngine.recognise(janPeriodId);
        entityManager.flush();

        // Jan slice = A(310) + B(620) = 930; A remaining = 3340 (released); B remaining = 6680.
        BigDecimal groupJanSlice = new BigDecimal("930.00");
        BigDecimal releasedA = new BigDecimal("3340.00");
        BigDecimal bRemaining = new BigDecimal("6680.00");

        // Cancel A effective WITHIN January (same period as the recognise) → merge branch.
        jdbcTemplate.update("UPDATE ri_fac_inwards SET status = 'CANCELLED' WHERE id = ?", aId);
        publisher.publishEvent(new FacDerecognisedEvent(
            FacDerecognisedEvent.ContractType.FAC_INWARD, aId, LocalDate.of(2026, 1, 20)));
        entityManager.flush();

        MovementAnalysis ma = service.compute(janPeriodId);

        var entry = ma.byGroup().stream().filter(g -> g.groupId().equals(groupId)).findFirst().orElseThrow();
        assertThat(entry.premiumEarned())
            .as("period slice (A+B) + only A's release folded in")
            .isEqualByComparingTo(groupJanSlice.add(releasedA)); // 930 + 3340 = 4270
        assertThat(entry.lrcClosing())
            .as("survivor B's closing preserved — NOT zeroed (the pre-fix bug)")
            .isEqualByComparingTo(bRemaining);
        assertThat(ma.lrcTotals().closing())
            .as("LRC totals closing == B's remaining, not zero")
            .isEqualByComparingTo(bRemaining);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Timestamp ts(int y, int m, int d, int hour, int min) {
        return Timestamp.valueOf(LocalDateTime.of(LocalDate.of(y, m, d), LocalTime.of(hour, min)));
    }

    private UUID seedGroup(String portfolioCode, int cohortYear) {
        UUID portfolioId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO portfolio (id, code, name, created_by) VALUES (?, ?, ?, ?)",
            portfolioId, portfolioCode, "Test " + portfolioCode, "test");
        jdbcTemplate.update(
            "INSERT INTO group_of_contracts (id, portfolio_id, cohort_year, onerousness, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            groupId, portfolioId, cohortYear, "NOT_ONEROUS", "OPEN", "test");
        return groupId;
    }

    private UUID seedPolicyAndAssignment(UUID groupId, String policyNumber,
                                          LocalDate startDate, LocalDate endDate, String netPremium) {
        UUID policyId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO policies (id, policy_number, customer_id, customer_name, " +
            "product_id, product_name, product_code, product_rate, " +
            "class_of_business_id, class_of_business_name, class_of_business_code, " +
            "policy_start_date, policy_end_date, net_premium, currency_code, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            policyId, policyNumber, UUID.randomUUID(), "Test Customer",
            UUID.randomUUID(), "Test Product", "PROD-MA", new BigDecimal("0.0500"),
            UUID.randomUUID(), "Test COB", "COB-MA",
            startDate, endDate, new BigDecimal(netPremium), "NGN", "APPROVED", "test");
        jdbcTemplate.update(
            "INSERT INTO contract_group_assignment (id, contract_type, contract_id, group_id, assigned_at, created_by) " +
            "VALUES (?, 'POLICY', ?, ?, now(), ?)",
            UUID.randomUUID(), policyId, groupId, "test");
        return policyId;
    }

    /** Mirrors {@code InwardFacLrcIT#seedFacInwardGroup} — a portfolio with contract_nature = FAC_INWARD. */
    private UUID seedFacInwardGroup(String portfolioCode, int cohortYear) {
        UUID portfolioId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO portfolio (id, code, name, contract_nature, created_by) VALUES (?, ?, ?, 'FAC_INWARD', ?)",
            portfolioId, portfolioCode, "Test " + portfolioCode, "test");
        jdbcTemplate.update(
            "INSERT INTO group_of_contracts (id, portfolio_id, cohort_year, onerousness, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            groupId, portfolioId, cohortYear, "NOT_ONEROUS", "OPEN", "test");
        return groupId;
    }

    /** Mirrors {@code InwardFacLrcIT#seedFacInwardAssignment} — an ACTIVE ri_fac_inwards row + assignment. */
    private void seedFacInwardAssignment(UUID groupId, String facReference,
                                          LocalDate coverFrom, LocalDate coverTo,
                                          String grossPremium, String netPremium, String commissionAmount) {
        seedFacInwardAssignmentWithId(groupId, UUID.randomUUID(), facReference,
            coverFrom, coverTo, grossPremium, netPremium, commissionAmount);
    }

    /**
     * Same as {@link #seedFacInwardAssignment} but takes the {@code ri_fac_inwards} id as a
     * parameter (Task 6b) — the derecognition tests need it to flip status to CANCELLED and to
     * reference it in the published {@link FacDerecognisedEvent}. Mirrors {@code
     * FacLifecycleLrcIT#seedFacInwardAssignment}.
     */
    private void seedFacInwardAssignmentWithId(UUID groupId, UUID facInwardId, String facReference,
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

    /** A portfolio with contract_nature = FAC_OUTWARD. */
    private UUID seedFacOutwardGroup(String portfolioCode, int cohortYear) {
        UUID portfolioId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO portfolio (id, code, name, contract_nature, created_by) VALUES (?, ?, ?, 'FAC_OUTWARD', ?)",
            portfolioId, portfolioCode, "Test " + portfolioCode, "test");
        jdbcTemplate.update(
            "INSERT INTO group_of_contracts (id, portfolio_id, cohort_year, onerousness, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            groupId, portfolioId, cohortYear, "NOT_ONEROUS", "OPEN", "test");
        return groupId;
    }

    /**
     * A CONFIRMED ri_fac_covers row + FAC_OUTWARD assignment — the LRC basis is
     * ri_fac_covers.net_premium (§65 commission-netting; see LrcEngine#loadFacOutwardPricing).
     * policy_id carries no FK (V10), so no policies row needs seeding.
     */
    private void seedFacOutwardAssignment(UUID groupId, String facReference,
                                           LocalDate coverFrom, LocalDate coverTo,
                                           String premiumCeded, String netPremium) {
        seedFacOutwardAssignmentWithId(groupId, UUID.randomUUID(), facReference,
            coverFrom, coverTo, premiumCeded, netPremium);
    }

    /**
     * Same as {@link #seedFacOutwardAssignment} but takes the {@code ri_fac_covers} id as a
     * parameter (Task 6b) — the derecognition tests need it to flip status to CANCELLED and to
     * reference it in the published {@link FacDerecognisedEvent}.
     */
    private void seedFacOutwardAssignmentWithId(UUID groupId, UUID facCoverId, String facReference,
                                                 LocalDate coverFrom, LocalDate coverTo,
                                                 String premiumCeded, String netPremium) {
        jdbcTemplate.update(
            "INSERT INTO ri_fac_covers (id, fac_reference, policy_id, policy_number, " +
            "reinsurance_company_id, reinsurance_company_name, status, " +
            "sum_insured_ceded, premium_rate, premium_ceded, commission_rate, commission_amount, net_premium, " +
            "currency_code, cover_from, cover_to, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            facCoverId, facReference, UUID.randomUUID(), "POL-FOR-" + facReference,
            UUID.randomUUID(), "Test Reinsurer", "CONFIRMED",
            new BigDecimal("5000000.00"), new BigDecimal("0.024000"), new BigDecimal(premiumCeded),
            new BigDecimal("0.2000"), new BigDecimal(premiumCeded).subtract(new BigDecimal(netPremium)),
            new BigDecimal(netPremium),
            "NGN", coverFrom, coverTo, "test");
        jdbcTemplate.update(
            "INSERT INTO contract_group_assignment (id, contract_type, contract_id, group_id, assigned_at, created_by) " +
            "VALUES (?, 'FAC_OUTWARD', ?, ?, now(), ?)",
            UUID.randomUUID(), facCoverId, groupId, "test");
    }

    private void seedClaim(UUID policyId, String claimNumber, String status,
                            Timestamp approvedAt, Timestamp settledAt,
                            String approvedAmount, String dvAmount) {
        UUID claimId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO claims (id, claim_number, status, policy_id, policy_number, " +
            "policy_start_date, policy_end_date, customer_id, customer_name, product_id, product_name, " +
            "class_of_business_id, class_of_business_name, incident_date, reported_date, description, " +
            "reserve_amount, approved_amount, currency_code, approved_at, settled_at, dv_amount, " +
            "approved_by, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            claimId, claimNumber, status, policyId, "POL-FOR-" + claimNumber,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            UUID.randomUUID(), "Test Customer",
            UUID.randomUUID(), "Test Product",
            UUID.randomUUID(), "Test COB",
            LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 6), "Incident description",
            new BigDecimal("0"),
            approvedAmount == null ? null : new BigDecimal(approvedAmount),
            "NGN",
            approvedAt, settledAt,
            dvAmount == null ? null : new BigDecimal(dvAmount),
            "test", "test");
    }

    private void runMeasurementUpstream() {
        entityManager.flush();
        lrcEngine.recognise(janPeriodId);
        licEngine.recognise(janPeriodId);
        entityManager.flush();
    }

    /**
     * Supplies the auxiliary beans {@code @DataJpaTest} doesn't auto-wire: an
     * {@link ObjectMapper} configured for Java time types + the {@link
     * AuditService} that {@link PeriodLockService} depends on (Task 6b's
     * cutover scenario needs {@code FacPaaCutoverService}, which needs {@code
     * PeriodLockService} — mirrors {@code FacPaaCutoverIT.TestSupportConfig}),
     * plus the COA/posting-rule cache regions the pre-existing services expect
     * pre-registered.
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
                ChartOfAccountService.CACHE_TREE,
                PostingRuleService.CACHE_BY_EVENT_TYPE);
        }
    }
}
