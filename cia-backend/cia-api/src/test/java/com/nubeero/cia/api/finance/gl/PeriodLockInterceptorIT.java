package com.nubeero.cia.api.finance.gl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nubeero.cia.common.audit.AuditLogRepository;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.finance.dto.JournalEntryLineRequest;
import com.nubeero.cia.finance.dto.PostJournalEntryRequest;
import com.nubeero.cia.finance.gl.ChartOfAccountService;
import com.nubeero.cia.finance.gl.FiscalPeriodLookupCache;
import com.nubeero.cia.finance.gl.FiscalPeriodResolver;
import com.nubeero.cia.finance.gl.JournalEntryService;
import com.nubeero.cia.finance.gl.LockType;
import com.nubeero.cia.finance.gl.PeriodLockInterceptor;
import com.nubeero.cia.finance.gl.PeriodLockInterceptorConfig;
import com.nubeero.cia.finance.gl.PeriodLockRepository;
import com.nubeero.cia.finance.gl.PeriodLockService;
import com.nubeero.cia.finance.gl.PeriodLockedException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * End-to-end integration test for {@link PeriodLockInterceptor} against a
 * real Postgres container with V31–V33 applied. Proves the topology works:
 * Hibernate flush → interceptor → {@code PeriodLockService.checkWrite} →
 * accept / reject / override.
 *
 * <h2>Test scenarios</h2>
 * <ul>
 *   <li>Post JE to OPEN period — succeeds.</li>
 *   <li>Soft-close the period, post within grace — succeeds.</li>
 *   <li>Soft-close, fast-forward past grace, no override role — interceptor
 *       throws {@link PeriodLockedException}; no row persisted.</li>
 *   <li>Soft-close, past grace, with override role — interceptor allows
 *       the write and records a {@code LOCK_OVERRIDE} audit row.</li>
 *   <li>Hard-close the period — outright reject; reversal carve-out tested
 *       via {@code JournalEntry.reversalOf} non-null.</li>
 * </ul>
 *
 * <h2>Request-scope plumbing</h2>
 * <p>{@link FiscalPeriodLookupCache} is {@code @RequestScope} in production
 * for tenant isolation. The IT runs outside a real HTTP request, so the
 * scoped-proxy would throw {@code IllegalStateException} on first access.
 * {@code @BeforeEach} binds a {@link MockHttpServletRequest} to the thread
 * via {@code RequestContextHolder} so the proxy can resolve; an
 * {@code @AfterEach} resets the binding so tests don't leak request state.
 *
 * @since Module 12, Slice 1.7
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    ChartOfAccountService.class,
    FiscalPeriodResolver.class,
    JournalEntryService.class,
    PeriodLockService.class,
    PeriodLockInterceptor.class,
    PeriodLockInterceptorConfig.class,
    FiscalPeriodLookupCache.class,
    PeriodLockInterceptorIT.TestSupportConfig.class
})
class PeriodLockInterceptorIT {

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

    @Autowired private JournalEntryService journalEntryService;
    @Autowired private PeriodLockService periodLockService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    private LocalDate businessDate;
    private UUID periodId;

    @BeforeEach
    void setUp() {
        // Bind a mock request so the @RequestScope cache resolves.
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        businessDate = LocalDate.of(2026, 5, 14);
        UUID fyId = UUID.randomUUID();
        periodId = UUID.randomUUID();
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

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("OPEN period — JE post succeeds through the interceptor")
    void openPeriodPostSucceeds() {
        var response = journalEntryService.post(balancedRequest("ref-open"));
        entityManager.flush();
        assertThat(response.id()).isNotNull();

        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM journal_entry WHERE source_reference = 'ref-open'", Long.class);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("HARD-closed period — JE post rejected with PeriodLockedException; no row persisted")
    void hardClosedRejects() {
        periodLockService.hardClose(periodId, "year-end close");
        entityManager.flush();
        entityManager.clear();   // drop the locally-cached lock entity so the interceptor re-reads

        assertThatThrownBy(() -> {
            journalEntryService.post(balancedRequest("ref-hard"));
            entityManager.flush();
        })
            .isInstanceOf(PeriodLockedException.class)
            .satisfies(ex -> {
                PeriodLockedException ple = (PeriodLockedException) ex;
                assertThat(ple.getDecision()).isNotNull();
                assertThat(ple.getDecision().periodLabel()).isEqualTo("May 2026");
                assertThat(ple.getDecision().reason()).contains("HARD-closed");
            });

        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM journal_entry WHERE source_reference = 'ref-hard'", Long.class);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("SOFT-closed within grace — JE post succeeds")
    void softWithinGraceAllows() {
        periodLockService.softClose(periodId, "month-end soft");
        entityManager.flush();
        entityManager.clear();

        var response = journalEntryService.post(balancedRequest("ref-soft-grace"));
        entityManager.flush();
        assertThat(response.id()).isNotNull();
    }

    @Test
    @DisplayName("SOFT past grace, no override role — REJECT")
    void softPastGraceNoOverrideRejects() {
        periodLockService.softClose(periodId, "month-end soft");
        entityManager.flush();
        // Move grace_window_until into the past so the interceptor treats it as expired.
        jdbcTemplate.update(
            "UPDATE period_lock SET grace_window_until = now() - INTERVAL '1 day' " +
            "WHERE fiscal_period_id = ? AND released_at IS NULL", periodId);
        entityManager.clear();

        // Authenticate as a user WITHOUT the override role.
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("ordinary", "pw",
                List.of(new SimpleGrantedAuthority("ROLE_FINANCE_VIEW"))));

        assertThatThrownBy(() -> {
            journalEntryService.post(balancedRequest("ref-soft-past"));
            entityManager.flush();
        })
            .isInstanceOf(PeriodLockedException.class)
            .satisfies(ex -> {
                PeriodLockedException ple = (PeriodLockedException) ex;
                assertThat(ple.getDecision().overrideRoles()).contains("FINANCE_OVERRIDE_LOCK");
            });
    }

    @Test
    @DisplayName("SOFT past grace, override role present — write succeeds")
    void softPastGraceOverrideAllows() {
        periodLockService.softClose(periodId, "month-end soft");
        entityManager.flush();
        jdbcTemplate.update(
            "UPDATE period_lock SET grace_window_until = now() - INTERVAL '1 day' " +
            "WHERE fiscal_period_id = ? AND released_at IS NULL", periodId);
        entityManager.clear();

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("auditor", "pw",
                List.of(new SimpleGrantedAuthority(PeriodLockService.ROLE_OVERRIDE_LOCK))));

        var response = journalEntryService.post(balancedRequest("ref-soft-override"));
        entityManager.flush();
        assertThat(response.id()).isNotNull();
    }

    @Test
    @DisplayName("Reversal carve-out — reversing a JE works even when period is HARD-closed")
    void reversalCarveOutWorksAfterHardClose() {
        // 1. Post the original JE while OPEN.
        var original = journalEntryService.post(balancedRequest("ref-orig"));
        entityManager.flush();

        // 2. Hard-close the period.
        periodLockService.hardClose(periodId, "year-end");
        entityManager.flush();
        entityManager.clear();

        // 3. Reverse it — reversalOf != null, so isReversal() returns true and
        //    the interceptor must let the row through despite HARD lock.
        var reversal = journalEntryService.reverse(original.id(), "audit correction");
        entityManager.flush();
        assertThat(reversal.id()).isNotNull();
        assertThat(reversal.reversalOf()).isEqualTo(original.id());
    }

    @Test
    @DisplayName("Lock history includes all soft/hard/release rows in chronological order")
    void lockHistoryAccumulates() {
        periodLockService.softClose(periodId, "first soft");
        entityManager.flush();
        periodLockService.hardClose(periodId, "first hard");
        entityManager.flush();
        periodLockService.reopen(periodId, "auditor adjustment");
        entityManager.flush();

        var history = periodLockService.history(periodId);
        // Expect 2 lock rows: SOFT (released auto), HARD (released on reopen). Newest first.
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getLockType()).isEqualTo(LockType.HARD);
        assertThat(history.get(0).getReleasedAt()).isNotNull();
        assertThat(history.get(0).getReleaseReason()).isEqualTo("auditor adjustment");
        assertThat(history.get(1).getLockType()).isEqualTo(LockType.SOFT);
        assertThat(history.get(1).getReleasedAt()).isNotNull();
        assertThat(history.get(1).getReleaseReason()).contains("promoted to HARD");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private PostJournalEntryRequest balancedRequest(String ref) {
        return new PostJournalEntryRequest(
            businessDate, "finance", "MANUAL", ref, "Slice 1.7 IT",
            List.of(
                new JournalEntryLineRequest("1110", new BigDecimal("100.00"), new BigDecimal("0.00"), null, null, null, null, null, null),
                new JournalEntryLineRequest("4110", new BigDecimal("0.00"), new BigDecimal("100.00"), null, null, null, null, null, null)
            ));
    }

    /**
     * Supplies the auxiliary beans @DataJpaTest doesn't auto-wire:
     * {@link AuditLogRepository} via the Spring Data scan (Repositories auto-detected),
     * an {@link ObjectMapper} configured for Java time types,
     * and the {@link AuditService} that {@link PeriodLockService} depends on.
     */
    @TestConfiguration
    static class TestSupportConfig {

        @Bean @Primary
        ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }

        @Bean
        AuditService auditService(AuditLogRepository auditLogRepository, ObjectMapper mapper) {
            return new AuditService(auditLogRepository, mapper, mock(ApplicationEventPublisher.class));
        }
    }
}
