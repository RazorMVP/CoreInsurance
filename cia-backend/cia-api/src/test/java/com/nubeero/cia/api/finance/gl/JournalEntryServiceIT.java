package com.nubeero.cia.api.finance.gl;

import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.finance.dto.JournalEntryLineRequest;
import com.nubeero.cia.finance.dto.JournalEntryResponse;
import com.nubeero.cia.finance.dto.PostJournalEntryRequest;
import com.nubeero.cia.finance.gl.ChartOfAccountService;
import com.nubeero.cia.finance.gl.FiscalPeriodNotFoundException;
import com.nubeero.cia.finance.gl.FiscalPeriodResolver;
import com.nubeero.cia.finance.gl.InactiveAccountException;
import com.nubeero.cia.finance.gl.JournalEntry;
import com.nubeero.cia.finance.gl.JournalEntryAlreadyReversedException;
import com.nubeero.cia.finance.gl.JournalEntryDuplicateException;
import com.nubeero.cia.finance.gl.JournalEntryRepository;
import com.nubeero.cia.finance.gl.JournalEntryService;
import com.nubeero.cia.finance.gl.JournalEntryStatus;
import com.nubeero.cia.finance.gl.UnbalancedJournalEntryException;
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
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link JournalEntryService} against a real Postgres
 * container with V31 schema + V32 COA seed applied. Verifies the full
 * post/reverse lifecycle end-to-end:
 *
 * <ul>
 *   <li>Happy-path post writes a header + lines and the lines reference the
 *       seeded COA accounts (FK satisfied).</li>
 *   <li>Idempotency (DB UNIQUE on the source triple) — the service-layer
 *       advisory read maps DB conflicts to 409.</li>
 *   <li>{@link FiscalPeriodNotFoundException} when no MONTH period covers
 *       the business date — exercises the resolver under a real schema.</li>
 *   <li>{@link UnbalancedJournalEntryException} — the GL stays empty when
 *       the request fails balance.</li>
 *   <li>Reverse: original transitions to {@code REVERSED}, mirror entry
 *       persists with {@code reversal_of} FK and the trial-balance net
 *       across both rows is zero.</li>
 *   <li>Inactive account rejection on post path; reversal still succeeds
 *       (d7).</li>
 * </ul>
 *
 * <p>The 100-JE reconciliation test lives in
 * {@link TrialBalanceServiceIT} since it's primarily a TB invariant.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    ChartOfAccountService.class,
    FiscalPeriodResolver.class,
    JournalEntryService.class,
    JournalEntryServiceIT.TestSupportConfig.class
})
class JournalEntryServiceIT {

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
        registry.add("spring.flyway.target", () -> "49");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired private JournalEntryService service;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private CacheManager cacheManager;

    private LocalDate businessDate;

    @org.junit.jupiter.api.AfterEach
    void clearCacheAcrossTests() {
        // The ChartOfAccountService @Cacheable cache survives @DataJpaTest's
        // transactional rollback — a test that UPDATEs is_active=FALSE on
        // 1110 (then rolls back) leaves the inactive snapshot in the cache,
        // breaking subsequent tests that need 1110 active. Manual clear at
        // the end of each test method is the surgical fix.
        cacheManager.getCacheNames().forEach(n -> {
            var cache = cacheManager.getCache(n);
            if (cache != null) cache.clear();
        });
    }

    @BeforeEach
    void seedFiscalPeriod() {
        // Tests run inside a @DataJpaTest transaction; insert fiscal year + fiscal
        // period for the business date so the resolver can find a MONTH row.
        // Using JDBC keeps this independent of FiscalYearService (Slice 1.6).
        businessDate = LocalDate.of(2026, 5, 14);
        UUID fyId = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            fyId, "FY2026", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ACTIVE", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            periodId, fyId, "MONTH",
            LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), "OPEN", "test");
    }

    @Test
    @DisplayName("post happy path: header + balanced lines persisted; COA FKs satisfied")
    void postHappyPath() {
        // 1110 = "Cash on hand" (COA seed), 4110 = "Premium income" (COA seed).
        PostJournalEntryRequest request = new PostJournalEntryRequest(
            businessDate, "finance", "MANUAL", "ref-happy", "End-to-end smoke",
            List.of(
                line("1110", "100.00", "0.00"),
                line("4110", "0.00",   "100.00")));

        JournalEntryResponse response = service.post(request);
        entityManager.flush();

        assertThat(response.id()).isNotNull();
        assertThat(response.status()).isEqualTo(JournalEntryStatus.POSTED);
        assertThat(response.lines()).hasSize(2);

        // Verify the row landed in the DB with the FK to fiscal_period valid.
        JournalEntry persisted = journalEntryRepository.findByIdAndDeletedAtIsNull(response.id()).orElseThrow();
        assertThat(persisted.getPeriodId()).isNotNull();
        assertThat(persisted.getLines()).hasSize(2);
        assertThat(persisted.getLines())
            .extracting(line -> line.getAccount().getCode())
            .containsExactly("1110", "4110");
    }

    @Test
    @DisplayName("post is rejected with 422 when no MONTH period covers the business date")
    void postWithoutFiscalPeriod() {
        // 2027 has no fiscal year configured — resolver should fail fast.
        PostJournalEntryRequest request = new PostJournalEntryRequest(
            LocalDate.of(2027, 3, 15), "finance", "MANUAL", "ref-no-fy", "Future",
            List.of(
                line("1110", "100.00", "0.00"),
                line("4110", "0.00",   "100.00")));

        assertThatThrownBy(() -> service.post(request))
            .isInstanceOf(FiscalPeriodNotFoundException.class)
            .hasMessageContaining("2027-03-15");
    }

    @Test
    @DisplayName("post idempotency: second post with same source triple throws 409 (no DB row created)")
    void postIdempotency() {
        PostJournalEntryRequest request = new PostJournalEntryRequest(
            businessDate, "finance", "POLICY_APPROVED", "POL-IDP-1", "First",
            List.of(
                line("1110", "200.00", "0.00"),
                line("4110", "0.00",   "200.00")));
        service.post(request);
        entityManager.flush();

        assertThatThrownBy(() -> service.post(request))
            .isInstanceOf(JournalEntryDuplicateException.class)
            .hasMessageContaining("POLICY_APPROVED")
            .hasMessageContaining("POL-IDP-1");

        Long rowCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM journal_entry WHERE source_module = 'finance' AND source_event_type = 'POLICY_APPROVED' AND source_reference = 'POL-IDP-1'",
            Long.class);
        assertThat(rowCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("post unbalanced: GL stays empty when the request fails the balance check")
    void postUnbalancedLeavesGlEmpty() {
        PostJournalEntryRequest request = new PostJournalEntryRequest(
            businessDate, "finance", "MANUAL", "ref-bad", "Unbalanced",
            List.of(
                line("1110", "100.00", "0.00"),
                line("4110", "0.00",   "50.00")));

        assertThatThrownBy(() -> service.post(request)).isInstanceOf(UnbalancedJournalEntryException.class);

        Long rowCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM journal_entry WHERE source_reference = 'ref-bad'", Long.class);
        assertThat(rowCount).isZero();
    }

    @Test
    @DisplayName("post inactive account: 422 InactiveAccountException, GL untouched")
    void postInactiveAccountRejected() {
        // Inactivate seeded account 1110 to simulate a tenant policy change.
        jdbcTemplate.update("UPDATE chart_of_account SET is_active = FALSE WHERE code = '1110'");

        PostJournalEntryRequest request = new PostJournalEntryRequest(
            businessDate, "finance", "MANUAL", "ref-inactive", "Bad target",
            List.of(
                line("1110", "100.00", "0.00"),
                line("4110", "0.00",   "100.00")));

        assertThatThrownBy(() -> service.post(request))
            .isInstanceOf(InactiveAccountException.class)
            .hasMessageContaining("1110");
    }

    @Test
    @DisplayName("reverse: original flips to REVERSED, mirror entry persists, net = 0 across both rows")
    void reverseHappyPath() {
        PostJournalEntryRequest request = new PostJournalEntryRequest(
            businessDate, "finance", "MANUAL", "ref-to-reverse", "Will be reversed",
            List.of(
                line("1110", "300.00", "0.00"),
                line("4110", "0.00",   "300.00")));
        JournalEntryResponse original = service.post(request);
        entityManager.flush();

        JournalEntryResponse reversal = service.reverse(original.id(), "Misposting cleanup");
        entityManager.flush();
        entityManager.clear(); // detach so the next find reads fresh state

        JournalEntry refetchedOriginal = journalEntryRepository.findByIdAndDeletedAtIsNull(original.id()).orElseThrow();
        JournalEntry refetchedReversal = journalEntryRepository.findByIdAndDeletedAtIsNull(reversal.id()).orElseThrow();
        assertThat(refetchedOriginal.getStatus()).isEqualTo(JournalEntryStatus.REVERSED);
        assertThat(refetchedReversal.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
        assertThat(refetchedReversal.getReversalOf()).isEqualTo(original.id());

        BigDecimal net = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(debit_amount), 0) - COALESCE(SUM(credit_amount), 0) " +
            "FROM journal_entry_line " +
            "WHERE journal_entry_id IN (?, ?)",
            BigDecimal.class, original.id(), reversal.id());
        assertThat(net).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("reverse rejects a second reversal attempt (d11 single-reversal rule)")
    void reverseSecondAttemptRejected() {
        PostJournalEntryRequest request = new PostJournalEntryRequest(
            businessDate, "finance", "MANUAL", "ref-rev-once", "Will be reversed once",
            List.of(
                line("1110", "150.00", "0.00"),
                line("4110", "0.00",   "150.00")));
        JournalEntryResponse original = service.post(request);
        entityManager.flush();
        service.reverse(original.id(), "First reversal");
        entityManager.flush();

        assertThatThrownBy(() -> service.reverse(original.id(), "Second attempt"))
            .isInstanceOf(JournalEntryAlreadyReversedException.class);
    }

    @Test
    @DisplayName("reverse of a reversal is rejected — preserves audit chain")
    void reverseOfReversalRejected() {
        PostJournalEntryRequest request = new PostJournalEntryRequest(
            businessDate, "finance", "MANUAL", "ref-rev-chain", "Chain origin",
            List.of(
                line("1110", "75.00", "0.00"),
                line("4110", "0.00",  "75.00")));
        JournalEntryResponse original = service.post(request);
        entityManager.flush();
        JournalEntryResponse firstReversal = service.reverse(original.id(), "Reverse it");
        entityManager.flush();

        assertThatThrownBy(() -> service.reverse(firstReversal.id(), "Reverse the reversal"))
            .isInstanceOf(JournalEntryAlreadyReversedException.class);
    }

    @Test
    @DisplayName("reverse succeeds even when target accounts have been inactivated (d7)")
    void reverseAgainstInactiveAccountSucceeds() {
        PostJournalEntryRequest request = new PostJournalEntryRequest(
            businessDate, "finance", "MANUAL", "ref-inactive-rev", "Will inactivate later",
            List.of(
                line("1110", "60.00", "0.00"),
                line("4110", "0.00",  "60.00")));
        JournalEntryResponse original = service.post(request);
        entityManager.flush();

        jdbcTemplate.update("UPDATE chart_of_account SET is_active = FALSE WHERE code IN ('1110','4110')");
        entityManager.clear();

        JournalEntryResponse reversal = service.reverse(original.id(), "Year-end cleanup");
        assertThat(reversal.status()).isEqualTo(JournalEntryStatus.POSTED);
        assertThat(reversal.reversalOf()).isEqualTo(original.id());
    }

    @Test
    @DisplayName("post enforces line minimum (Bean Validation): empty lines list rejected upstream")
    void postRejectsEmptyLinesViaBalanceCheck() {
        // PostJournalEntryRequest @NotEmpty/@Size(min=2) is enforced by @Valid at the controller.
        // The service alone treats an empty list as balanced (Σ = 0), so we still expect a
        // BusinessRuleException downstream — but for the service-level contract here we only
        // assert that the service won't silently persist a header with zero lines.
        PostJournalEntryRequest request = new PostJournalEntryRequest(
            businessDate, "finance", "MANUAL", "ref-empty", "Empty",
            List.<JournalEntryLineRequest>of());

        // Two-of-a-kind validation runs after balance; here Σ=0=Σ but no lines means JE persists empty.
        // This is the contract gap @Valid closes at the controller — assert here that whatever
        // path is taken, the resulting state is never a header without lines.
        try {
            service.post(request);
        } catch (BusinessRuleException e) {
            // Acceptable: service can refuse internally (UnbalancedJournalEntryException
            // is itself a BusinessRuleException).
            return;
        }
        entityManager.flush();
        Long emptyHeaders = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM journal_entry je " +
            "WHERE je.source_reference = 'ref-empty' " +
            "  AND NOT EXISTS (SELECT 1 FROM journal_entry_line jel WHERE jel.journal_entry_id = je.id)",
            Long.class);
        assertThat(emptyHeaders).as("no zero-line headers should ever appear in the GL").isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static JournalEntryLineRequest line(String accountCode, String debit, String credit) {
        return new JournalEntryLineRequest(
            accountCode, new BigDecimal(debit), new BigDecimal(credit), null, null, null, null, null, null);
    }

    /**
     * Minimal support beans for the @DataJpaTest slice: a system {@link Clock}
     * (the auto-config bean would arrive via CiaCommonAutoConfiguration in
     * the full context but @DataJpaTest skips application config), and a
     * cache manager so {@link ChartOfAccountService}'s {@code @Cacheable}
     * annotations don't trip the cache-resolver lookup.
     */
    @TestConfiguration
    @EnableCaching
    static class TestSupportConfig {

        @Bean
        Clock systemClock() {  // renamed from clock() — see TrialBalanceServiceIT note
            return Clock.systemDefaultZone();
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
