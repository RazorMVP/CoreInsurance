package com.nubeero.cia.api.finance.paa;

import com.nubeero.cia.common.event.FacDerecognisedEvent;
import com.nubeero.cia.finance.dto.JournalEntryLineRequest;
import com.nubeero.cia.finance.dto.PostJournalEntryRequest;
import com.nubeero.cia.finance.gl.ChartOfAccountService;
import com.nubeero.cia.finance.gl.FiscalPeriodResolver;
import com.nubeero.cia.finance.gl.JournalEntryService;
import com.nubeero.cia.finance.gl.PostingRuleService;
import com.nubeero.cia.finance.paa.FacDerecognitionListener;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Testcontainers IT for FAC / IFRS-17 PAA workstream Task 5's
 * lifecycle handling: cancellation derecognises the remaining LRC/asset
 * balance via a GL journal entry ONLY, and an {@code extend} recomputes the
 * roll-forward over the new cover window with zero special posting code.
 *
 * <h2>Fix round 2 — GL is the sole source of truth</h2>
 * <p>An earlier version of {@code FacDerecognitionListener} also wrote an
 * ad-hoc {@code paa_lrc} row for the derecognition. That collided with
 * {@link LrcEngine}'s own row for the SAME {@code (group, period)} the next
 * time {@code recognise} ran for that period — the engine's idempotency
 * pre-check runs BEFORE its zero-activity skip, so the collision threw and
 * rolled back recognition for EVERY group in the tenant, not just the
 * cancelled FAC's. This suite now asserts the corrected shape: derecognition
 * posts a GL journal entry ONLY (no {@code paa_lrc} row), and a subsequent
 * {@code recognise()} call for a later period neither throws nor re-earns
 * the cancelled contract — the in-force status filter in {@link
 * LrcEngine#loadFacInwardPricing} / {@code loadFacOutwardPricing} (Task 5
 * fix round 2) stops it from being found at all.
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
 * this workstream. Each test manually flips the underlying {@code
 * ri_fac_inwards}/{@code ri_fac_covers} row's status to {@code CANCELLED}
 * before publishing — simulating exactly what {@code cancel()} does before
 * it publishes the event in production.
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
    @Autowired private JournalEntryService journalEntryService;
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    private UUID fiscalYearId;
    private UUID janPeriodId;
    private UUID febPeriodId;
    private UUID marchPeriodId;

    @BeforeEach
    void seedFiscalYearAndPeriods() {
        fiscalYearId = UUID.randomUUID();
        janPeriodId = UUID.randomUUID();
        febPeriodId = UUID.randomUUID();
        marchPeriodId = UUID.randomUUID();
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
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            marchPeriodId, fiscalYearId, "MONTH",
            LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), "OPEN", "test");
    }

    // ── 1. Inward FAC cancel mid-Feb derecognises the remaining LRC liability,
    //      posts GL ONLY (no paa_lrc row), and a later recognise() neither
    //      throws nor re-earns the cancelled contract ─────────────────────────
    @Test
    @DisplayName("cancel mid-Feb: Dr 2210 / Cr 4330 for the remaining unearned LRC, GL only; "
        + "a later recognise() does not throw and does not re-earn the cancelled contract")
    void inwardCancel_derecognisesRemainingLrc_andStopsFutureEarning() {
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

        // Mirrors RiFacInwardService.cancel(): status -> CANCELLED BEFORE the event publishes.
        jdbcTemplate.update("UPDATE ri_fac_inwards SET status = 'CANCELLED' WHERE id = ?", facInwardId);
        publisher.publishEvent(new FacDerecognisedEvent(
            FacDerecognisedEvent.ContractType.FAC_INWARD, facInwardId, LocalDate.of(2026, 2, 15)));
        entityManager.flush();

        // ── (a) JE Dr 2210 / Cr 4330 for the remaining balance ──
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

        // ── (b) GL is the sole source of truth: no ad-hoc paa_lrc row anywhere beyond the Jan recognise() ──
        Long postJanLrcCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM paa_lrc WHERE group_id = ? AND period_id <> ?",
            Long.class, groupId, janPeriodId);
        assertThat(postJanLrcCount)
            .as("derecognition must not write a paa_lrc row — LrcEngine.recognise is the sole writer")
            .isZero();

        // ── (c) a later recognise() neither throws NOR re-earns the cancelled contract ──
        LrcRecognitionResult marchResult = engine.recognise(marchPeriodId);
        entityManager.flush();
        assertThat(marchResult.groupsWithJournalEntry())
            .as("cancelled contract's group earns nothing in a later period")
            .isZero();
        Long marchLrcCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM paa_lrc WHERE group_id = ? AND period_id = ?",
            Long.class, groupId, marchPeriodId);
        assertThat(marchLrcCount).as("no paa_lrc row for the cancelled contract's group in March").isZero();
        Long marchJeCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM journal_entry WHERE source_reference = ?",
            Long.class, marchPeriodId + ":" + groupId);
        assertThat(marchJeCount).as("no double-earn JE posted for March").isZero();
    }

    // ── 2. Outward FAC cancel mid-Feb derecognises the remaining reinsurance-held
    //      asset, posts GL ONLY, and a later recognise() does not re-earn it ────
    @Test
    @DisplayName("cancel mid-Feb: Dr 5210 / Cr 1410 for the remaining unamortised asset, GL only; "
        + "a later recognise() does not throw and does not re-earn the cancelled contract")
    void outwardCancel_derecognisesRemainingAsset_andStopsFutureEarning() {
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

        jdbcTemplate.update("UPDATE ri_fac_covers SET status = 'CANCELLED' WHERE id = ?", facCoverId);
        publisher.publishEvent(new FacDerecognisedEvent(
            FacDerecognisedEvent.ContractType.FAC_OUTWARD, facCoverId, LocalDate.of(2026, 2, 15)));
        entityManager.flush();

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

        Long postJanLrcCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM paa_lrc WHERE group_id = ? AND period_id <> ?",
            Long.class, groupId, janPeriodId);
        assertThat(postJanLrcCount)
            .as("derecognition must not write a paa_lrc row")
            .isZero();

        LrcRecognitionResult marchResult = engine.recognise(marchPeriodId);
        entityManager.flush();
        assertThat(marchResult.groupsWithJournalEntry())
            .as("cancelled contract's group earns nothing in a later period")
            .isZero();
        Long marchLrcCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM paa_lrc WHERE group_id = ? AND period_id = ?",
            Long.class, groupId, marchPeriodId);
        assertThat(marchLrcCount).isZero();
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

        jdbcTemplate.update("UPDATE ri_fac_inwards SET status = 'CANCELLED' WHERE id = ?", facInwardId);
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

    // ── 5. Fix 3 — cancel BEFORE any recognise() ever ran releases the FULL
    //      LRC-basis premium (a status-agnostic direct read, since loadPricing's
    //      in-force filter would otherwise return nothing for a CANCELLED row) ──
    @Test
    @DisplayName("inward: cancel before any recognise() ever ran releases the FULL gross premium "
        + "(the accept-time 2210 liability would otherwise linger forever)")
    void inwardCancelBeforeAnyRecognise_releasesFullGrossPremium() {
        UUID groupId = seedFacInwardGroup("FIN-LC-NOPRIOR");
        UUID facInwardId = UUID.randomUUID();
        seedFacInwardAssignment(groupId, facInwardId, "FAC-IN-LC-NOPRIOR",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            "1200.00", "1000.00", "200.00");
        entityManager.flush();
        // Deliberately NO engine.recognise(...) call before cancelling — no paa_lrc history exists.

        jdbcTemplate.update("UPDATE ri_fac_inwards SET status = 'CANCELLED' WHERE id = ?", facInwardId);
        publisher.publishEvent(new FacDerecognisedEvent(
            FacDerecognisedEvent.ContractType.FAC_INWARD, facInwardId, LocalDate.of(2026, 1, 20)));
        entityManager.flush();

        Map<String, Object> je = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry " +
            "WHERE source_module = 'paa' AND source_event_type = 'FAC_DERECOGNITION' " +
            "AND source_reference = ?",
            "FAC_INWARD:" + facInwardId);
        UUID jeId = (UUID) je.get("id");
        assertLine(jeId, "2210", new BigDecimal("1200.00"), BigDecimal.ZERO);
        assertLine(jeId, "4330", BigDecimal.ZERO, new BigDecimal("1200.00"));

        Long lrcCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM paa_lrc WHERE group_id = ?", Long.class, groupId);
        assertThat(lrcCount).as("still no paa_lrc row ever written for this group").isZero();
    }

    @Test
    @DisplayName("outward: cancel before any recognise() ever ran releases the FULL net premium")
    void outwardCancelBeforeAnyRecognise_releasesFullNetPremium() {
        UUID groupId = seedFacOutwardGroup("FOU-LC-NOPRIOR");
        UUID facCoverId = UUID.randomUUID();
        seedFacOutwardAssignment(groupId, facCoverId,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            "1000.00");
        entityManager.flush();

        jdbcTemplate.update("UPDATE ri_fac_covers SET status = 'CANCELLED' WHERE id = ?", facCoverId);
        publisher.publishEvent(new FacDerecognisedEvent(
            FacDerecognisedEvent.ContractType.FAC_OUTWARD, facCoverId, LocalDate.of(2026, 1, 20)));
        entityManager.flush();

        Map<String, Object> je = jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry " +
            "WHERE source_module = 'paa' AND source_event_type = 'FAC_DERECOGNITION' " +
            "AND source_reference = ?",
            "FAC_OUTWARD:" + facCoverId);
        UUID jeId = (UUID) je.get("id");
        assertLine(jeId, "5210", new BigDecimal("1000.00"), BigDecimal.ZERO);
        assertLine(jeId, "1410", BigDecimal.ZERO, new BigDecimal("1000.00"));

        Long lrcCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM paa_lrc WHERE group_id = ?", Long.class, groupId);
        assertThat(lrcCount).isZero();
    }

    // ── 7. CRITICAL (final-review) — per-contract derecognition in a MULTI-CONTRACT
    //      group: cancelling A releases only A's own remaining, never the group
    //      (A+B) aggregate; B keeps earning; 2210 never goes negative; and each
    //      contract's lifetime recognised income == its own gross EXACTLY ──────
    //
    // Uses a fully-past 2025 fiscal year so every recognise() JE's business_date
    // (period end) stays <= posting_date (today) — the V31 ck_journal_entry_dates
    // invariant — while still exercising a full-year run to expiry.
    @Test
    @DisplayName("inward multi-contract group: cancel A releases only A's remaining (not A+B); "
        + "survivor B keeps earning; account 2210 stays == B's remaining and never negative; "
        + "each contract's lifetime income == its own gross exactly")
    void inwardMultiContractGroup_cancelOne_releasesOnlyThatContract_noOverRelease() {
        // Clean daily rates so every slice is exact (no rounding drift):
        // A 3650/365 = 10.00/day, B 7300/365 = 20.00/day over the full 2025 year.
        List<UUID> months = seedYear2025Periods();
        UUID groupId = seedFacInwardGroup("FIN-LC-MULTI");
        UUID aId = UUID.randomUUID();
        UUID bId = UUID.randomUUID();
        seedFacInwardAssignment(groupId, aId, "FAC-IN-MULTI-A",
            LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), "3650.00", "3650.00", "0.00");
        seedFacInwardAssignment(groupId, bId, "FAC-IN-MULTI-B",
            LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), "7300.00", "7300.00", "0.00");
        entityManager.flush();

        // Accept-time liability: Cr 2210 gross for each (mirrors
        // SubledgerPostingService.replayFacPremiumAccepted's Cr 2210 = gross).
        postInwardAccept(aId, "3650.00");
        postInwardAccept(bId, "7300.00");
        entityManager.flush();

        // Jan close: group earns A(310) + B(620) = 930; 2210 = 10950 − 930 = 10020.
        engine.recognise(months.get(0));
        entityManager.flush();
        assertThat(creditMinusDebit("2210"))
            .as("after Jan: 2210 == A_remaining(3340) + B_remaining(6680)")
            .isEqualByComparingTo("10020.00");

        BigDecimal aRemaining = new BigDecimal("3340.00"); // 3650 − 10×31
        BigDecimal bRemaining = new BigDecimal("6680.00"); // 7300 − 20×31

        // Cancel A mid-Feb.
        jdbcTemplate.update("UPDATE ri_fac_inwards SET status = 'CANCELLED' WHERE id = ?", aId);
        publisher.publishEvent(new FacDerecognisedEvent(
            FacDerecognisedEvent.ContractType.FAC_INWARD, aId, LocalDate.of(2025, 2, 15)));
        entityManager.flush();

        // (1) The derecognition JE releases EXACTLY A's own remaining — NOT the
        //     group (A+B) aggregate of 10020.
        UUID jeId = (UUID) jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry WHERE source_module = 'paa' "
                + "AND source_event_type = 'FAC_DERECOGNITION' AND source_reference = ?",
            "FAC_INWARD:" + aId).get("id");
        assertLine(jeId, "2210", aRemaining, BigDecimal.ZERO);
        assertLine(jeId, "4330", BigDecimal.ZERO, aRemaining);
        BigDecimal releasedByJe = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(l.debit_amount),0) FROM journal_entry_line l "
                + "JOIN chart_of_account a ON a.id = l.account_id "
                + "WHERE l.journal_entry_id = ? AND a.code = '2210'", BigDecimal.class, jeId);
        assertThat(releasedByJe).as("released == A's remaining, not the A+B group aggregate")
            .isEqualByComparingTo(aRemaining);
        assertThat(releasedByJe).as("must NOT release the whole group's LRC closing")
            .isNotEqualByComparingTo("10020.00");

        // (2) Account 2210 after cancellation == B's remaining, strictly > 0.
        assertThat(creditMinusDebit("2210"))
            .as("after cancel A: 2210 == B's remaining, survivor preserved")
            .isEqualByComparingTo(bRemaining);
        assertThat(creditMinusDebit("2210")).isGreaterThan(BigDecimal.ZERO);

        // (3) Next period recognise() earns B's next slice; 2210 never negative.
        engine.recognise(months.get(1));
        entityManager.flush();
        // B Feb slice = 20 × 28 = 560; 2210 = 6680 − 560 = 6120.
        assertThat(creditMinusDebit("2210")).isEqualByComparingTo("6120.00");
        assertThat(creditMinusDebit("2210")).as("2210 never negative").isGreaterThanOrEqualTo(BigDecimal.ZERO);

        // Close the rest of the year — B earns out fully; A stays cancelled.
        for (int i = 2; i < 12; i++) {
            engine.recognise(months.get(i));
            entityManager.flush();
            assertThat(creditMinusDebit("2210"))
                .as("2210 never dips below zero across the full year")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }

        // (4) Lifetime conservation:
        //  • A: Jan periodic slice (310) + derecognition release (3340) == A.gross (3650) exactly.
        BigDecimal aJanSlice = new BigDecimal("3650.00")
            .multiply(BigDecimal.valueOf(31)).divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);
        assertThat(aJanSlice.add(aRemaining))
            .as("A: Σ periodic earned + derecognition release == A.gross exactly")
            .isEqualByComparingTo("3650.00");
        //  • After the full year, 2210 is fully discharged (A released, B earned out) — no strand,
        //    no negative excursion.
        assertThat(creditMinusDebit("2210"))
            .as("end of year: A released + B fully earned ⇒ 2210 == 0 (no strand)")
            .isEqualByComparingTo("0.00");
        //  • Total inward premium income (4330) == A.gross + B.gross exactly — no double-earn,
        //    no under-recognition.
        assertThat(creditMinusDebit("4330"))
            .as("total 4330 income == A.gross + B.gross (no double-earn, no strand)")
            .isEqualByComparingTo("10950.00");
    }

    // ── 8. CRITICAL symmetric (outward): net basis, Dr 5210 / Cr 1410 ─────────
    @Test
    @DisplayName("outward multi-contract group: cancel A releases only A's remaining net asset "
        + "(not A+B); survivor B's 1410 asset preserved and never negative; lifetime nets to zero")
    void outwardMultiContractGroup_cancelOne_releasesOnlyThatContract_noOverRelease() {
        List<UUID> months = seedYear2025Periods();
        UUID groupId = seedFacOutwardGroup("FOU-LC-MULTI");
        UUID aId = UUID.randomUUID();
        UUID bId = UUID.randomUUID();
        // net basis: A 3650 (10/day), B 7300 (20/day).
        seedFacOutwardAssignment(groupId, aId,
            LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), "3650.00");
        seedFacOutwardAssignment(groupId, bId,
            LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), "7300.00");
        entityManager.flush();

        // Accept-time asset: Dr 1410 net for each (mirrors replayFacPremiumCeded's Dr 1410 = net).
        postOutwardAccept(aId, "3650.00");
        postOutwardAccept(bId, "7300.00");
        entityManager.flush();

        engine.recognise(months.get(0));
        entityManager.flush();
        // 1410 asset = 10950 − 930 = 10020.
        assertThat(debitMinusCredit("1410"))
            .as("after Jan: 1410 asset == A_remaining(3340) + B_remaining(6680)")
            .isEqualByComparingTo("10020.00");

        BigDecimal aRemaining = new BigDecimal("3340.00");
        BigDecimal bRemaining = new BigDecimal("6680.00");

        jdbcTemplate.update("UPDATE ri_fac_covers SET status = 'CANCELLED' WHERE id = ?", aId);
        publisher.publishEvent(new FacDerecognisedEvent(
            FacDerecognisedEvent.ContractType.FAC_OUTWARD, aId, LocalDate.of(2025, 2, 15)));
        entityManager.flush();

        UUID jeId = (UUID) jdbcTemplate.queryForMap(
            "SELECT id FROM journal_entry WHERE source_module = 'paa' "
                + "AND source_event_type = 'FAC_DERECOGNITION' AND source_reference = ?",
            "FAC_OUTWARD:" + aId).get("id");
        assertLine(jeId, "5210", aRemaining, BigDecimal.ZERO);
        assertLine(jeId, "1410", BigDecimal.ZERO, aRemaining);
        BigDecimal releasedByJe = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(l.credit_amount),0) FROM journal_entry_line l "
                + "JOIN chart_of_account a ON a.id = l.account_id "
                + "WHERE l.journal_entry_id = ? AND a.code = '1410'", BigDecimal.class, jeId);
        assertThat(releasedByJe).as("released == A's remaining net asset, not A+B aggregate")
            .isEqualByComparingTo(aRemaining);
        assertThat(releasedByJe).isNotEqualByComparingTo("10020.00");

        assertThat(debitMinusCredit("1410"))
            .as("after cancel A: 1410 asset == B's remaining, survivor preserved")
            .isEqualByComparingTo(bRemaining);
        assertThat(debitMinusCredit("1410")).isGreaterThan(BigDecimal.ZERO);

        engine.recognise(months.get(1));
        entityManager.flush();
        assertThat(debitMinusCredit("1410")).isEqualByComparingTo("6120.00");

        for (int i = 2; i < 12; i++) {
            engine.recognise(months.get(i));
            entityManager.flush();
            assertThat(debitMinusCredit("1410"))
                .as("1410 asset never dips below zero across the full year")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }

        assertThat(debitMinusCredit("1410"))
            .as("end of year: A released + B amortised out ⇒ 1410 == 0 (no strand)")
            .isEqualByComparingTo("0.00");
        assertThat(debitMinusCredit("5210"))
            .as("total 5210 expense == A.net + B.net (no double-amortise, no strand)")
            .isEqualByComparingTo("10950.00");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Seeds a fully-past 2025 fiscal year with 12 MONTH periods (Jan…Dec) and
     * returns their ids in month order. 2025 is non-leap (365 days), and every
     * period end (≤ 2025-12-31) is before "today" so recognise()'s business
     * date honours V31's {@code business_date <= posting_date} CHECK.
     */
    private List<UUID> seedYear2025Periods() {
        UUID fyId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
            fyId, "FY-FACLIFECYCLE-2025", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
            "ACTIVE", "test");
        int[] lastDay = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        List<UUID> ids = new java.util.ArrayList<>(12);
        for (int m = 1; m <= 12; m++) {
            UUID pid = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) "
                    + "VALUES (?, ?, 'MONTH', ?, ?, 'OPEN', 'test')",
                pid, fyId, LocalDate.of(2025, m, 1), LocalDate.of(2025, m, lastDay[m - 1]));
            ids.add(pid);
        }
        return ids;
    }

    /** Accept-time inward posting Cr 2210 gross / Dr 1330 gross (commission-free, so net == gross). */
    private void postInwardAccept(UUID facInwardId, String gross) {
        journalEntryService.post(new PostJournalEntryRequest(
            LocalDate.of(2025, 1, 1), "test-accept", "FAC_ACCEPT", "accept:" + facInwardId,
            "test inward accept",
            List.of(
                new JournalEntryLineRequest("1330", new BigDecimal(gross), BigDecimal.ZERO,
                    "NGN", null, null, null, null, null),
                new JournalEntryLineRequest("2210", BigDecimal.ZERO, new BigDecimal(gross),
                    "NGN", null, null, null, null, null))));
    }

    /** Accept-time outward posting Dr 1410 net / Cr 2310 net. */
    private void postOutwardAccept(UUID facCoverId, String net) {
        journalEntryService.post(new PostJournalEntryRequest(
            LocalDate.of(2025, 1, 1), "test-accept", "FAC_CEDE", "cede:" + facCoverId,
            "test outward accept",
            List.of(
                new JournalEntryLineRequest("1410", new BigDecimal(net), BigDecimal.ZERO,
                    "NGN", null, null, null, null, null),
                new JournalEntryLineRequest("2310", BigDecimal.ZERO, new BigDecimal(net),
                    "NGN", null, null, null, null, null))));
    }

    private BigDecimal creditMinusDebit(String accountCode) {
        return jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(l.credit_amount),0) - COALESCE(SUM(l.debit_amount),0) "
                + "FROM journal_entry_line l JOIN chart_of_account a ON a.id = l.account_id "
                + "WHERE a.code = ?", BigDecimal.class, accountCode);
    }

    private BigDecimal debitMinusCredit(String accountCode) {
        return jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(l.debit_amount),0) - COALESCE(SUM(l.credit_amount),0) "
                + "FROM journal_entry_line l JOIN chart_of_account a ON a.id = l.account_id "
                + "WHERE a.code = ?", BigDecimal.class, accountCode);
    }

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
            facCoverId, "FOU-" + facCoverId, UUID.randomUUID(), "POL-" + facCoverId,
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
