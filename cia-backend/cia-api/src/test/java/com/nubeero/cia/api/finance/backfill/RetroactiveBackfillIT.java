package com.nubeero.cia.api.finance.backfill;

import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.finance.backfill.RetroactiveJournalBackfillActivitiesImpl;
import com.nubeero.cia.finance.gl.ChartOfAccountService;
import com.nubeero.cia.finance.gl.FiscalPeriodLookupCache;
import com.nubeero.cia.finance.gl.FiscalPeriodResolver;
import com.nubeero.cia.finance.gl.JournalEntryService;
import com.nubeero.cia.finance.gl.PeriodLockService;
import com.nubeero.cia.finance.gl.PostingRuleService;
import com.nubeero.cia.finance.gl.SubledgerPostingService;
import com.nubeero.cia.workflow.backfill.BackfillChunkRequest;
import com.nubeero.cia.workflow.backfill.BackfillChunkResult;
import com.nubeero.cia.workflow.backfill.BackfillEventType;
import com.nubeero.cia.workflow.backfill.BackfillPreflightResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for Slice 1.8a — retroactive JE backfill.
 *
 * <p>Verifies the canonical exit criteria from the slice design pass:
 * <ol>
 *   <li><b>Balanced trial-balance after backfill</b> — POLICY_APPROVED rows
 *       fed in via the {@code policies} table produce one JE per source row
 *       with the V33 posting rule's Dr/Cr shape.</li>
 *   <li><b>Re-run yields zero new JEs</b> — running the same chunk request a
 *       second time reports {@code alreadyExists == fixtureCount} and
 *       {@code posted == 0}; idempotency is enforced at the DB level by the
 *       {@code journal_entry} UNIQUE constraint on the source triple.</li>
 *   <li><b>Pre-flight refuses HARD-closed range</b> — when the date range
 *       includes a HARD-closed period, {@code previewPeriodLocks} returns
 *       {@code hasBlockingLocks = true} with the offending period label so
 *       the workflow can short-circuit before writing anything.</li>
 * </ol>
 *
 * <p>Schema scope: Flyway runs through V33 (GL foundation + COA + posting
 * rules). Tenant tables (policies, fiscal_year, fiscal_period) are seeded
 * via JdbcTemplate so the test doesn't need cia-policy entities on the
 * classpath.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    ChartOfAccountService.class,
    FiscalPeriodResolver.class,
    FiscalPeriodLookupCache.class,
    JournalEntryService.class,
    PostingRuleService.class,
    SubledgerPostingService.class,
    PeriodLockService.class,
    RetroactiveJournalBackfillActivitiesImpl.class,
    RetroactiveBackfillIT.TestSupportConfig.class
})
class RetroactiveBackfillIT {

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
        registry.add("spring.flyway.target", () -> "33");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    private static final LocalDate FROM = LocalDate.of(2026, 5, 1);
    private static final LocalDate TO = LocalDate.of(2026, 5, 31);

    @Autowired private RetroactiveJournalBackfillActivitiesImpl activities;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID fiscalYearId;
    private UUID periodId;

    @BeforeEach
    void seedFiscalPeriod() {
        fiscalYearId = UUID.randomUUID();
        periodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            fiscalYearId, "FY2026",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ACTIVE", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            periodId, fiscalYearId, "MONTH",
            LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), "OPEN", "test");
    }

    @Test
    @DisplayName("processChunk for POLICY_APPROVED posts one JE per source row, second run reports all alreadyExists")
    void policyApprovedBackfillIsIdempotent() {
        // Arrange: three approved policies inside the May 2026 period.
        seedApprovedPolicy("POL-IT-001", LocalDate.of(2026, 5,  5), new BigDecimal("100000.00"));
        seedApprovedPolicy("POL-IT-002", LocalDate.of(2026, 5, 12), new BigDecimal("200000.00"));
        seedApprovedPolicy("POL-IT-003", LocalDate.of(2026, 5, 20), new BigDecimal("300000.00"));

        BackfillChunkRequest request = new BackfillChunkRequest(
                "test-tenant", "admin@example.com",
                BackfillEventType.POLICY_APPROVED, FROM, TO,
                0, 100, false);

        // Act 1 — first run.
        BackfillChunkResult first = activities.processChunk(request);

        // Assert: three JEs landed; one per source row.
        assertThat(first.attempted()).isEqualTo(3);
        assertThat(first.posted()).isEqualTo(3);
        assertThat(first.alreadyExists()).isZero();
        assertThat(first.failed()).isZero();
        assertThat(first.exhausted()).isTrue();

        long jeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entry " +
                "WHERE source_module = 'policy' AND source_event_type = 'POLICY_APPROVED'",
                Long.class);
        assertThat(jeCount).isEqualTo(3);

        // Trial balance: total debits must equal total credits across the new JEs.
        BigDecimal totalDebits = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(jel.debit), 0) " +
                "FROM journal_entry_line jel " +
                "JOIN journal_entry je ON je.id = jel.journal_entry_id " +
                "WHERE je.source_event_type = 'POLICY_APPROVED'",
                BigDecimal.class);
        BigDecimal totalCredits = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(jel.credit), 0) " +
                "FROM journal_entry_line jel " +
                "JOIN journal_entry je ON je.id = jel.journal_entry_id " +
                "WHERE je.source_event_type = 'POLICY_APPROVED'",
                BigDecimal.class);
        assertThat(totalDebits).isEqualByComparingTo(new BigDecimal("600000.00"));
        assertThat(totalCredits).isEqualByComparingTo(totalDebits);

        // Act 2 — second run with the same request.
        BackfillChunkResult second = activities.processChunk(request);

        // Assert: nothing new posted; idempotency held.
        assertThat(second.attempted()).isEqualTo(3);
        assertThat(second.alreadyExists()).isEqualTo(3);
        assertThat(second.posted()).isZero();
        assertThat(second.failed()).isZero();

        long jeCountAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entry " +
                "WHERE source_event_type = 'POLICY_APPROVED'",
                Long.class);
        assertThat(jeCountAfter).isEqualTo(3);
    }

    @Test
    @DisplayName("Slice 1.8b: a partially completed backfill resumes cleanly — no duplicates, only missing rows posted")
    void backfillIsResumableAfterPartialRun() {
        // Arrange: five policies, each on a distinct day so the activity's
        // ORDER BY (policy_start_date, id) produces a stable sequence.
        seedApprovedPolicy("POL-RES-001", LocalDate.of(2026, 5, 1), new BigDecimal("100000.00"));
        seedApprovedPolicy("POL-RES-002", LocalDate.of(2026, 5, 2), new BigDecimal("200000.00"));
        seedApprovedPolicy("POL-RES-003", LocalDate.of(2026, 5, 3), new BigDecimal("300000.00"));
        seedApprovedPolicy("POL-RES-004", LocalDate.of(2026, 5, 4), new BigDecimal("400000.00"));
        seedApprovedPolicy("POL-RES-005", LocalDate.of(2026, 5, 5), new BigDecimal("500000.00"));

        // Phase 1 — partial run with limit=2. Models the situation where the
        // worker crashed after rows 1 and 2 succeeded but before row 3 could
        // be attempted. exhausted() is false: the workflow would have asked
        // for another chunk.
        BackfillChunkRequest partial = new BackfillChunkRequest(
                "test-tenant", "admin@example.com",
                BackfillEventType.POLICY_APPROVED, FROM, TO,
                0, 2, false);
        BackfillChunkResult phase1 = activities.processChunk(partial);
        assertThat(phase1.attempted()).isEqualTo(2);
        assertThat(phase1.posted()).isEqualTo(2);
        assertThat(phase1.alreadyExists()).isZero();
        assertThat(phase1.exhausted()).isFalse();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entry WHERE source_event_type = 'POLICY_APPROVED'",
                Long.class))
                .as("two JEs landed before the simulated crash")
                .isEqualTo(2);

        // Phase 2 — full resume from offset 0. The DB-level UNIQUE constraint
        // on (source_module, source_event_type, source_reference) routes
        // POL-RES-001 / 002 down the alreadyExists branch; 003-005 post for
        // the first time.
        BackfillChunkRequest resume = new BackfillChunkRequest(
                "test-tenant", "admin@example.com",
                BackfillEventType.POLICY_APPROVED, FROM, TO,
                0, 100, false);
        BackfillChunkResult phase2 = activities.processChunk(resume);
        assertThat(phase2.attempted()).isEqualTo(5);
        assertThat(phase2.alreadyExists()).isEqualTo(2);
        assertThat(phase2.posted()).isEqualTo(3);
        assertThat(phase2.failed()).isZero();
        assertThat(phase2.exhausted()).isTrue();

        // Total JE rows = 5; trial balance still holds at ₦1.5M Dr=Cr.
        long totalJEs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entry WHERE source_event_type = 'POLICY_APPROVED'",
                Long.class);
        assertThat(totalJEs).as("exactly one JE per source row — no duplicates").isEqualTo(5);

        BigDecimal totalDebits = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(jel.debit), 0) " +
                "FROM journal_entry_line jel " +
                "JOIN journal_entry je ON je.id = jel.journal_entry_id " +
                "WHERE je.source_event_type = 'POLICY_APPROVED'",
                BigDecimal.class);
        BigDecimal totalCredits = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(jel.credit), 0) " +
                "FROM journal_entry_line jel " +
                "JOIN journal_entry je ON je.id = jel.journal_entry_id " +
                "WHERE je.source_event_type = 'POLICY_APPROVED'",
                BigDecimal.class);
        assertThat(totalDebits).isEqualByComparingTo(new BigDecimal("1500000.00"));
        assertThat(totalCredits).isEqualByComparingTo(totalDebits);
    }

    @Test
    @DisplayName("Slice 1.8b benchmark: 10,000 POLICY_APPROVED rows complete inside the budget")
    @EnabledIfSystemProperty(named = "backfill.benchmark", matches = "true")
    void backfillOf10kEventsCompletesUnderBudget() {
        // Wall-clock budget for the activity loop over 10k rows. The current
        // path is one JE post per row → bounded by SubledgerPostingService +
        // Hibernate flush cost. On a developer laptop with Testcontainers
        // Postgres we observe ~30 ms/row; on CI we add slack for cold JIT.
        final int rowCount = 10_000;
        final long budgetMillis = 5L * 60_000L; // 5 minutes upper bound
        final int chunkSize = 200;

        long seedStart = System.currentTimeMillis();
        seedApprovedPoliciesInBulk(rowCount);
        long seedElapsed = System.currentTimeMillis() - seedStart;
        System.out.printf("[benchmark] seeded %d policies in %d ms%n", rowCount, seedElapsed);

        long runStart = System.currentTimeMillis();
        int offset = 0;
        long totalAttempted = 0;
        long totalPosted = 0;
        long totalAlreadyExists = 0;
        long totalFailed = 0;
        while (true) {
            BackfillChunkResult chunk = activities.processChunk(new BackfillChunkRequest(
                    "test-tenant", "bench@example.com",
                    BackfillEventType.POLICY_APPROVED, FROM, TO,
                    offset, chunkSize, false));
            totalAttempted += chunk.attempted();
            totalPosted += chunk.posted();
            totalAlreadyExists += chunk.alreadyExists();
            totalFailed += chunk.failed();
            if (chunk.exhausted()) break;
            offset += chunkSize;
        }
        long runElapsed = System.currentTimeMillis() - runStart;
        double rowsPerSec = rowCount * 1000.0 / Math.max(runElapsed, 1);
        System.out.printf("[benchmark] posted %d JEs in %d ms (%.0f rows/sec, chunk=%d)%n",
                totalPosted, runElapsed, rowsPerSec, chunkSize);

        assertThat(totalAttempted).isEqualTo(rowCount);
        assertThat(totalPosted).isEqualTo(rowCount);
        assertThat(totalAlreadyExists).isZero();
        assertThat(totalFailed).isZero();
        assertThat(runElapsed)
                .as("backfill of %d rows must complete within %d ms (observed %d ms)",
                        rowCount, budgetMillis, runElapsed)
                .isLessThan(budgetMillis);
    }

    @Test
    @DisplayName("previewPeriodLocks returns hasBlockingLocks=true when the range crosses a HARD-closed period")
    void preflightRefusesWhenHardClosedInRange() {
        // Arrange: hard-close the May 2026 period via direct period_lock insert.
        jdbcTemplate.update(
            "UPDATE fiscal_period SET status = 'HARD_CLOSED', " +
            "soft_closed_at = NOW() - INTERVAL '7 days', hard_closed_at = NOW() " +
            "WHERE id = ?",
            periodId);
        jdbcTemplate.update(
            "INSERT INTO period_lock (id, fiscal_period_id, lock_type, locked_at, locked_by, created_by) " +
            "VALUES (?, ?, 'HARD', NOW(), 'test', 'test')",
            UUID.randomUUID(), periodId);

        BackfillPreflightResult result = activities.previewPeriodLocks("test-tenant", FROM, TO);

        assertThat(result.hasBlockingLocks()).isTrue();
        assertThat(result.blockingPeriodLabels()).contains("May 2026");
    }

    // ── Fixture helpers ───────────────────────────────────────────────────────

    /**
     * Bulk-seed N approved policies via a single batched insert — used by the
     * 10k benchmark to keep arrange-phase cost out of the measured window.
     * Distributes {@code policy_start_date} across {@link #FROM}..{@link #TO}
     * so all rows fall inside the resolvable fiscal period.
     */
    private void seedApprovedPoliciesInBulk(int count) {
        long rangeDays = TO.toEpochDay() - FROM.toEpochDay() + 1;
        java.util.List<Object[]> batch = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            LocalDate start = FROM.plusDays(i % rangeDays);
            batch.add(new Object[] {
                    UUID.randomUUID(), "POL-BENCH-" + String.format("%06d", i),
                    UUID.randomUUID(), "Customer " + i,
                    UUID.randomUUID(), "Motor Comprehensive", "MOT-C", new BigDecimal("0.025"),
                    UUID.randomUUID(), "Motor", "MOT",
                    start, start.plusYears(1),
                    new BigDecimal("5000000.00"),
                    new BigDecimal("100000.00"),
                    new BigDecimal("100000.00"),
                    "admin", java.sql.Timestamp.from(Instant.now()), "test"
            });
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO policies (id, policy_number, status, " +
                "customer_id, customer_name, " +
                "product_id, product_name, product_code, product_rate, " +
                "class_of_business_id, class_of_business_name, class_of_business_code, " +
                "policy_start_date, policy_end_date, " +
                "total_sum_insured, total_premium, net_premium, " +
                "approved_by, approved_at, created_by) " +
                "VALUES (?, 'APPROVED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                batch);
    }

    private void seedApprovedPolicy(String policyNumber, LocalDate policyStart, BigDecimal netPremium) {
        jdbcTemplate.update(
            "INSERT INTO policies (id, policy_number, status, " +
            "customer_id, customer_name, " +
            "product_id, product_name, product_code, product_rate, " +
            "class_of_business_id, class_of_business_name, class_of_business_code, " +
            "policy_start_date, policy_end_date, " +
            "total_sum_insured, total_premium, net_premium, " +
            "approved_by, approved_at, created_by) " +
            "VALUES (?, ?, 'APPROVED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), policyNumber,
            UUID.randomUUID(), "Customer " + policyNumber,
            UUID.randomUUID(), "Motor Comprehensive", "MOT-C", new BigDecimal("0.025"),
            UUID.randomUUID(), "Motor", "MOT",
            policyStart, policyStart.plusYears(1),
            new BigDecimal("5000000.00"), netPremium, netPremium,
            "admin", java.sql.Timestamp.from(Instant.now()), "test");
    }

    @TestConfiguration
    static class TestSupportConfig {
        @Bean
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-05-15T10:00:00Z"), ZoneOffset.UTC);
        }

        /**
         * AuditService stub — the IT path that exercises PeriodLockService
         * could call it on close/reopen; we don't exercise that in the tests
         * above, but the bean is needed for the context to wire.
         */
        @Bean
        AuditService auditService() {
            return new AuditService(null, null, null) {
                @Override
                public void log(String entityType, String entityId,
                                com.nubeero.cia.common.audit.AuditAction action,
                                Object oldValue, Object newValue) {
                    // no-op
                }
            };
        }

        @Bean
        org.springframework.context.ApplicationEventPublisher events() {
            return event -> { /* no-op */ };
        }

        @Bean
        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }
    }
}
