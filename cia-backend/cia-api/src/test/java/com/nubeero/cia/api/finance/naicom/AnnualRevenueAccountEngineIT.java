package com.nubeero.cia.api.finance.naicom;

import com.nubeero.cia.finance.naicom.AnnualRevenueAccountEngine;
import com.nubeero.cia.finance.naicom.NaicomSubmissionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
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
 * Slice 1.10b IT for {@link AnnualRevenueAccountEngine} — the engine
 * was re-implemented to read class-broken-down totals from
 * {@code journal_entry_line} aggregates (Slice 4.3 used to read from
 * {@code policies} + {@code claims} source tables).
 *
 * <p>Fixture strategy: seed {@code classes_of_business} rows + POSTED
 * JEs with the right {@code source_event_type} ('POLICY_APPROVED' /
 * 'CLAIM_APPROVED'), {@code business_date} within the period, and
 * {@code class_of_business_id} populated on the lines. No real
 * policies / claims tables are touched — the engine no longer reads
 * them.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Empty year — zero totals, empty byClass</li>
 *   <li>Single class with policies + claims — loss ratio computed</li>
 *   <li>Claims-only class — null loss ratio (no divide-by-zero)</li>
 *   <li>Multi-class — totals aggregate; ordered by code ASC</li>
 *   <li>Period boundary — JEs outside year excluded</li>
 *   <li>REVERSED JEs excluded (auditor-grade — status filter)</li>
 *   <li>JE lines without class_of_business_id excluded (Phase 2 / 3
 *       JEs don't pollute the class breakdown)</li>
 *   <li><b>Reconciliation</b> — engine totals match an independent
 *       JE-aggregate via {@link JdbcTemplate}. The auditor-grade
 *       guarantee: every figure in N01 has a JE provenance.</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    AnnualRevenueAccountEngine.class
})
class AnnualRevenueAccountEngineIT {

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
        registry.add("spring.flyway.target", () -> "43");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired private AnnualRevenueAccountEngine engine;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID yearPeriodId;
    private UUID motorClassId;
    private UUID fireClassId;
    private UUID marineClassId;
    private UUID coa2110Id; // LRC BEL — premium credit-side
    private UUID coa1310Id; // Premium receivable — premium debit-side
    private UUID coa2140Id; // LIC OCR — claims credit-side
    private UUID coa5110Id; // Incurred claims — claims debit-side
    private final LocalDate yearStart = LocalDate.of(2026, 1, 1);
    private final LocalDate yearEnd = LocalDate.of(2026, 3, 31);

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM journal_entry_line");
        jdbcTemplate.update("DELETE FROM journal_entry");
        jdbcTemplate.update("DELETE FROM fiscal_period");
        jdbcTemplate.update("DELETE FROM fiscal_year");
        jdbcTemplate.update("DELETE FROM classes_of_business");

        UUID fyId = UUID.randomUUID();
        yearPeriodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            fyId, "FY-AR-2026", yearStart, yearEnd, "ACTIVE", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            yearPeriodId, fyId, "QUARTER", yearStart, yearEnd, "HARD_CLOSED", "test");

        motorClassId = seedClass("MOTOR-COMP", "Motor Comprehensive");
        fireClassId = seedClass("FIRE", "Fire");
        marineClassId = seedClass("MARINE", "Marine");

        // Resolve V32-seeded COA accounts by code.
        coa2110Id = lookupAccount("2110");
        coa1310Id = lookupAccount("1310");
        coa2140Id = lookupAccount("2140");
        coa5110Id = lookupAccount("5110");
    }

    @Test
    @DisplayName("empty year — zero totals + empty byClass")
    void emptyYear() {
        Map<String, Object> payload = engine.computePayload(yearPeriodId);

        assertThat(payload.get("submissionType"))
            .isEqualTo(NaicomSubmissionType.ANNUAL_REVENUE_ACCOUNT.name());
        Map<?, ?> totals = (Map<?, ?>) payload.get("totals");
        assertThat(totals.get("policyCount")).isEqualTo(0);
        assertThat(totals.get("claimCount")).isEqualTo(0);
        assertThat((BigDecimal) totals.get("grossPremium")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(totals.get("lossRatio")).as("zero premium ⇒ null lossRatio").isNull();
        assertThat((List<?>) payload.get("byClass")).isEmpty();
    }

    @Test
    @DisplayName("single class — premium + claim JEs produce per-class row with computed loss ratio")
    void singleClassLossRatio() {
        seedPolicyApprovedJe(motorClassId, new BigDecimal("100000.00"),
            LocalDate.of(2026, 1, 15), "POLICY_APPROVED-1");
        seedClaimApprovedJe(motorClassId, new BigDecimal("65000.00"),
            LocalDate.of(2026, 2, 10), "CLAIM_APPROVED-1");

        Map<String, Object> payload = engine.computePayload(yearPeriodId);

        List<?> byClass = (List<?>) payload.get("byClass");
        assertThat(byClass).hasSize(1);
        Map<?, ?> row = (Map<?, ?>) byClass.get(0);
        assertThat(row.get("classOfBusinessCode")).isEqualTo("MOTOR-COMP");
        assertThat(row.get("policyCount")).isEqualTo(1);
        assertThat((BigDecimal) row.get("grossPremium")).isEqualByComparingTo("100000.00");
        assertThat(row.get("claimCount")).isEqualTo(1);
        assertThat((BigDecimal) row.get("claimsIncurred")).isEqualByComparingTo("65000.00");
        assertThat((BigDecimal) row.get("lossRatio"))
            .as("65000/100000 × 100 = 65.00%")
            .isEqualByComparingTo("65.00");
    }

    @Test
    @DisplayName("claims-only class — lossRatio null, no divide-by-zero")
    void claimsOnlyClassEmitsNullLossRatio() {
        // No POLICY_APPROVED JE — only a CLAIM_APPROVED JE for the FIRE class.
        seedClaimApprovedJe(fireClassId, new BigDecimal("250000.00"),
            LocalDate.of(2026, 2, 1), "CLAIM_APPROVED-FIRE-1");

        Map<String, Object> payload = engine.computePayload(yearPeriodId);

        Map<?, ?> row = (Map<?, ?>) ((List<?>) payload.get("byClass")).get(0);
        assertThat(row.get("classOfBusinessCode")).isEqualTo("FIRE");
        assertThat((BigDecimal) row.get("grossPremium")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat((BigDecimal) row.get("claimsIncurred")).isEqualByComparingTo("250000.00");
        assertThat(row.get("lossRatio"))
            .as("zero premium ⇒ null lossRatio (not 0, not Infinity)")
            .isNull();
    }

    @Test
    @DisplayName("multi-class — per-class rows + totals aggregate correctly + ordered by code ASC")
    void multiClass() {
        seedPolicyApprovedJe(motorClassId, new BigDecimal("400000.00"),
            LocalDate.of(2026, 1, 10), "POL-M-1");
        seedPolicyApprovedJe(fireClassId, new BigDecimal("200000.00"),
            LocalDate.of(2026, 1, 11), "POL-F-1");
        seedPolicyApprovedJe(marineClassId, new BigDecimal("600000.00"),
            LocalDate.of(2026, 1, 12), "POL-MAR-1");
        seedClaimApprovedJe(motorClassId, new BigDecimal("200000.00"),
            LocalDate.of(2026, 2, 1), "CLM-M-1");
        seedClaimApprovedJe(fireClassId, new BigDecimal("80000.00"),
            LocalDate.of(2026, 3, 1), "CLM-F-1");
        seedClaimApprovedJe(marineClassId, new BigDecimal("100000.00"),
            LocalDate.of(2026, 3, 5), "CLM-MAR-1");

        Map<String, Object> payload = engine.computePayload(yearPeriodId);

        // Deterministic order: code ASC → FIRE, MARINE, MOTOR-COMP
        List<?> byClass = (List<?>) payload.get("byClass");
        assertThat(byClass).hasSize(3);
        List<String> codes = byClass.stream()
            .map(r -> (String) ((Map<?, ?>) r).get("classOfBusinessCode"))
            .toList();
        assertThat(codes).containsExactly("FIRE", "MARINE", "MOTOR-COMP");

        Map<?, ?> totals = (Map<?, ?>) payload.get("totals");
        assertThat(totals.get("policyCount")).isEqualTo(3);
        assertThat(totals.get("claimCount")).isEqualTo(3);
        assertThat((BigDecimal) totals.get("grossPremium")).isEqualByComparingTo("1200000.00");
        assertThat((BigDecimal) totals.get("claimsIncurred")).isEqualByComparingTo("380000.00");
        assertThat((BigDecimal) totals.get("lossRatio"))
            .as("380000/1200000 × 100 = 31.67% (HALF_UP)")
            .isEqualByComparingTo("31.67");
    }

    @Test
    @DisplayName("period boundary — JEs outside period excluded by business_date filter")
    void periodBoundary() {
        // In-period
        seedPolicyApprovedJe(motorClassId, new BigDecimal("100000.00"),
            LocalDate.of(2026, 2, 15), "POL-IN");
        // Pre-period (Dec 2025)
        seedPolicyApprovedJe(motorClassId, new BigDecimal("999999.00"),
            LocalDate.of(2025, 12, 5), "POL-PRE");
        // Post-period (April 2026)
        seedPolicyApprovedJe(motorClassId, new BigDecimal("888888.00"),
            LocalDate.of(2026, 4, 5), "POL-POST");

        Map<String, Object> payload = engine.computePayload(yearPeriodId);

        Map<?, ?> totals = (Map<?, ?>) payload.get("totals");
        assertThat(totals.get("policyCount"))
            .as("only the in-period JE is counted")
            .isEqualTo(1);
        assertThat((BigDecimal) totals.get("grossPremium")).isEqualByComparingTo("100000.00");
    }

    @Test
    @DisplayName("REVERSED JEs excluded — status filter on je.status = 'POSTED'")
    void reversedJesExcluded() {
        UUID liveJeId = seedPolicyApprovedJe(motorClassId, new BigDecimal("100000.00"),
            LocalDate.of(2026, 1, 15), "POL-LIVE");
        // Seed a "REVERSED" JE — simulate what JournalEntryService.reverse() would produce.
        UUID reversedJeId = seedPolicyApprovedJe(motorClassId, new BigDecimal("999999.00"),
            LocalDate.of(2026, 1, 20), "POL-REVERSED");
        jdbcTemplate.update("UPDATE journal_entry SET status = 'REVERSED' WHERE id = ?",
            reversedJeId);
        // Sanity — keep the live one POSTED to confirm filter selectivity.
        assertThat(liveJeId).isNotEqualTo(reversedJeId);

        Map<String, Object> payload = engine.computePayload(yearPeriodId);

        Map<?, ?> totals = (Map<?, ?>) payload.get("totals");
        assertThat(totals.get("policyCount")).isEqualTo(1);
        assertThat((BigDecimal) totals.get("grossPremium")).isEqualByComparingTo("100000.00");
    }

    @Test
    @DisplayName("JE lines without class_of_business_id silently excluded")
    void linesWithoutClassExcluded() {
        seedPolicyApprovedJe(motorClassId, new BigDecimal("100000.00"),
            LocalDate.of(2026, 1, 15), "POL-WITH-CLASS");
        // Simulate a Phase 2 / Phase 3 JE — POLICY_APPROVED-typed but null class.
        // (In production, Phase 2/3 JEs don't carry POLICY_APPROVED event types; this
        // edge case proves the engine's class-not-null filter even in adversarial cases.)
        UUID nullClassJeId = insertJe("POLICY_APPROVED", "POL-NULL-CLASS",
            LocalDate.of(2026, 1, 17));
        insertLine(nullClassJeId, coa1310Id, new BigDecimal("88888.00"), BigDecimal.ZERO, null);
        insertLine(nullClassJeId, coa2110Id, BigDecimal.ZERO, new BigDecimal("88888.00"), null);

        Map<String, Object> payload = engine.computePayload(yearPeriodId);

        Map<?, ?> totals = (Map<?, ?>) payload.get("totals");
        assertThat(totals.get("policyCount"))
            .as("only the classed JE is counted")
            .isEqualTo(1);
        assertThat((BigDecimal) totals.get("grossPremium")).isEqualByComparingTo("100000.00");
    }

    @Test
    @DisplayName("RECONCILIATION — engine totals match independent JE aggregate (auditor-grade)")
    void reconciliationAgainstJeAggregate() {
        // Multi-class fixture with both premium and claims spanning the period.
        seedPolicyApprovedJe(motorClassId, new BigDecimal("400000.00"),
            LocalDate.of(2026, 1, 10), "POL-M-RECON");
        seedPolicyApprovedJe(fireClassId, new BigDecimal("200000.00"),
            LocalDate.of(2026, 1, 11), "POL-F-RECON");
        seedClaimApprovedJe(motorClassId, new BigDecimal("150000.00"),
            LocalDate.of(2026, 2, 1), "CLM-M-RECON");
        seedClaimApprovedJe(fireClassId, new BigDecimal("80000.00"),
            LocalDate.of(2026, 2, 5), "CLM-F-RECON");

        Map<String, Object> payload = engine.computePayload(yearPeriodId);
        Map<?, ?> totals = (Map<?, ?>) payload.get("totals");

        // Independent aggregate — different query path, same source.
        // Engine groups by class then sums per-class; this query sums
        // straight across without grouping. Both must arrive at the
        // same total — the auditor's guarantee.
        BigDecimal jeSumPremium = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(jel.credit_amount), 0) " +
            "FROM journal_entry je " +
            "JOIN journal_entry_line jel ON jel.journal_entry_id = je.id " +
            "WHERE je.source_event_type = 'POLICY_APPROVED' " +
            "  AND je.status = 'POSTED' " +
            "  AND je.business_date BETWEEN ? AND ? " +
            "  AND jel.credit_amount > 0 " +
            "  AND jel.class_of_business_id IS NOT NULL " +
            "  AND je.deleted_at IS NULL " +
            "  AND jel.deleted_at IS NULL",
            BigDecimal.class,
            java.sql.Date.valueOf(yearStart), java.sql.Date.valueOf(yearEnd));
        BigDecimal jeSumClaims = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(jel.credit_amount), 0) " +
            "FROM journal_entry je " +
            "JOIN journal_entry_line jel ON jel.journal_entry_id = je.id " +
            "WHERE je.source_event_type = 'CLAIM_APPROVED' " +
            "  AND je.status = 'POSTED' " +
            "  AND je.business_date BETWEEN ? AND ? " +
            "  AND jel.credit_amount > 0 " +
            "  AND jel.class_of_business_id IS NOT NULL " +
            "  AND je.deleted_at IS NULL " +
            "  AND jel.deleted_at IS NULL",
            BigDecimal.class,
            java.sql.Date.valueOf(yearStart), java.sql.Date.valueOf(yearEnd));

        assertThat((BigDecimal) totals.get("grossPremium"))
            .as("N01 grossPremium total must equal independent JE-aggregate over POLICY_APPROVED credits")
            .isEqualByComparingTo(jeSumPremium);
        assertThat((BigDecimal) totals.get("claimsIncurred"))
            .as("N01 claimsIncurred total must equal independent JE-aggregate over CLAIM_APPROVED credits")
            .isEqualByComparingTo(jeSumClaims);

        // Per-class reconciliation too — the engine's per-class breakdown
        // must tie to per-class JE aggregates.
        BigDecimal motorPremium = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(jel.credit_amount), 0) " +
            "FROM journal_entry je " +
            "JOIN journal_entry_line jel ON jel.journal_entry_id = je.id " +
            "WHERE je.source_event_type = 'POLICY_APPROVED' " +
            "  AND je.status = 'POSTED' " +
            "  AND je.business_date BETWEEN ? AND ? " +
            "  AND jel.credit_amount > 0 " +
            "  AND jel.class_of_business_id = ?",
            BigDecimal.class,
            java.sql.Date.valueOf(yearStart), java.sql.Date.valueOf(yearEnd), motorClassId);
        List<?> byClass = (List<?>) payload.get("byClass");
        BigDecimal engineMotorPremium = byClass.stream()
            .map(r -> (Map<?, ?>) r)
            .filter(r -> "MOTOR-COMP".equals(r.get("classOfBusinessCode")))
            .map(r -> (BigDecimal) r.get("grossPremium"))
            .findFirst()
            .orElseThrow();
        assertThat(engineMotorPremium)
            .as("per-class motor premium must tie to JE aggregate")
            .isEqualByComparingTo(motorPremium);
    }

    // ── Fixture helpers ────────────────────────────────────────────────────

    private UUID seedClass(String code, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO classes_of_business (id, code, name, created_by) " +
            "VALUES (?, ?, ?, ?)",
            id, code, name, "test");
        return id;
    }

    private UUID lookupAccount(String code) {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM chart_of_account WHERE code = ?", UUID.class, code);
    }

    /**
     * Posts a POLICY_APPROVED JE with two lines: Dr 1310 (Premium
     * receivable) + Cr 2110 (LRC BEL), both carrying the supplied
     * class_of_business_id. Returns the new JE id.
     */
    private UUID seedPolicyApprovedJe(UUID classId, BigDecimal amount,
                                       LocalDate businessDate, String reference) {
        UUID jeId = insertJe("POLICY_APPROVED", reference, businessDate);
        insertLine(jeId, coa1310Id, amount, BigDecimal.ZERO, classId);
        insertLine(jeId, coa2110Id, BigDecimal.ZERO, amount, classId);
        return jeId;
    }

    /**
     * Posts a CLAIM_APPROVED JE with two lines: Dr 5110 (Incurred
     * claims) + Cr 2140 (LIC OCR), both carrying class. Returns the
     * new JE id.
     */
    private UUID seedClaimApprovedJe(UUID classId, BigDecimal amount,
                                      LocalDate businessDate, String reference) {
        UUID jeId = insertJe("CLAIM_APPROVED", reference, businessDate);
        insertLine(jeId, coa5110Id, amount, BigDecimal.ZERO, classId);
        insertLine(jeId, coa2140Id, BigDecimal.ZERO, amount, classId);
        return jeId;
    }

    private UUID insertJe(String eventType, String reference, LocalDate businessDate) {
        UUID jeId = UUID.randomUUID();
        // posting_date defaults to current_date; business_date must be <= it (ck_journal_entry_dates).
        // All fixture business_dates here are within Q1 2026 — well before today (2026-05-19),
        // so the constraint is satisfied.
        jdbcTemplate.update(
            "INSERT INTO journal_entry (id, business_date, period_id, " +
            "source_module, source_event_type, source_reference, posted_by, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            jeId, businessDate, yearPeriodId,
            "test", eventType, reference, "test", "POSTED", "test");
        return jeId;
    }

    private void insertLine(UUID jeId, UUID accountId, BigDecimal debit, BigDecimal credit,
                             UUID classOfBusinessId) {
        jdbcTemplate.update(
            "INSERT INTO journal_entry_line (id, journal_entry_id, line_no, account_id, " +
            "debit_amount, credit_amount, class_of_business_id, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), jeId,
            // line_no — incrementing across all lines per JE; tests don't care about value
            (int) (Math.random() * 1000) + 1,
            accountId, debit, credit, classOfBusinessId, "test");
    }
}
