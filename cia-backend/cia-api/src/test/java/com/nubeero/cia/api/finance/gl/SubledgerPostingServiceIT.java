package com.nubeero.cia.api.finance.gl;

import com.nubeero.cia.common.event.ClaimApprovedEvent;
import com.nubeero.cia.common.event.ClaimExpenseApprovedEvent;
import com.nubeero.cia.common.event.ClaimSettledEvent;
import com.nubeero.cia.common.event.EndorsementApprovedEvent;
import com.nubeero.cia.common.event.FacPremiumCededEvent;
import com.nubeero.cia.common.event.PolicyApprovedEvent;
import com.nubeero.cia.finance.gl.ChartOfAccountService;
import com.nubeero.cia.finance.gl.FiscalPeriodResolver;
import com.nubeero.cia.finance.gl.JournalEntryService;
import com.nubeero.cia.finance.gl.PostingRuleNotFoundException;
import com.nubeero.cia.finance.gl.PostingRuleService;
import com.nubeero.cia.finance.gl.SubledgerPostingService;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end Testcontainers IT for {@link SubledgerPostingService}. Every
 * test publishes one of the six sub-ledger events directly into the service
 * (bypassing Spring's ApplicationEventPublisher — same code path the
 * publisher would trigger) and verifies a balanced journal entry landed in
 * the {@code journal_entry} + {@code journal_entry_line} tables with the
 * shape declared by V33's posting rules (or, for FAC, the hardcoded 3-line
 * contract documented in the service).
 *
 * <p>Schema: Flyway runs through V33 so {@code posting_rule} carries the
 * six seed rows. The {@code fiscal_year} + MONTH {@code fiscal_period}
 * needed by {@link FiscalPeriodResolver} are inserted via JDBC in
 * {@code @BeforeEach}.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    ChartOfAccountService.class,
    FiscalPeriodResolver.class,
    JournalEntryService.class,
    PostingRuleService.class,
    com.nubeero.cia.finance.gl.PolicyClassResolver.class,
    SubledgerPostingService.class,
    SubledgerPostingServiceIT.TestSupportConfig.class
})
class SubledgerPostingServiceIT {

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

    @Autowired private SubledgerPostingService service;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    private LocalDate businessDate;

    @BeforeEach
    void seedFiscalPeriod() {
        businessDate = LocalDate.of(2026, 5, 15);
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

    // ── PolicyApproved ───────────────────────────────────────────────────────

    @Test
    @DisplayName("PolicyApproved → JE with Dr 1310 + Cr 2110 visible in journal_entry_line")
    void policyApproved() {
        UUID policyId = UUID.randomUUID();
        service.onPolicyApproved(new PolicyApprovedEvent(
            policyId, "POL-IT-001", UUID.randomUUID(), "Acme", null, null,
            "Motor", new BigDecimal("500000.00"), "NGN",
            LocalDate.of(2027, 5, 14), UUID.randomUUID(), UUID.randomUUID(),
            new BigDecimal("10000000.00"), businessDate));
        entityManager.flush();

        Map<String, Object> je = loadJe("policy", "POLICY_APPROVED", policyId.toString());
        assertThat(je).isNotEmpty();
        assertThat(je.get("narrative")).isEqualTo("Premium booking for policy POL-IT-001");

        assertLine((UUID) je.get("id"), "1310", "500000.00", "0.00");
        assertLine((UUID) je.get("id"), "2110", "0.00", "500000.00");
    }

    // ── ClaimApproved ────────────────────────────────────────────────────────

    @Test
    @DisplayName("ClaimApproved → JE with Dr 5110 + Cr 2140")
    void claimApproved() {
        UUID claimId = UUID.randomUUID();
        service.onClaimApproved(new ClaimApprovedEvent(
            claimId, "CLM-IT-007", UUID.randomUUID(), "POL-IT-007",
            UUID.randomUUID(), "Insured", null, null, "Motor",
            new BigDecimal("120000.00"), "NGN"));
        entityManager.flush();

        Map<String, Object> je = loadJe("claim", "CLAIM_APPROVED", claimId.toString());
        assertThat(je.get("narrative")).isEqualTo("Claim approval for CLM-IT-007 on policy POL-IT-007");
        assertLine((UUID) je.get("id"), "5110", "120000.00", "0.00");
        assertLine((UUID) je.get("id"), "2140", "0.00", "120000.00");
    }

    // ── ClaimSettled ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("ClaimSettled → JE with Dr 2140 + Cr 1120 at settledAt.toLocalDate (UTC)")
    void claimSettled() {
        UUID claimId = UUID.randomUUID();
        service.onClaimSettled(new ClaimSettledEvent(
            claimId, "CLM-IT-099", UUID.randomUUID(), "POL-IT-099",
            UUID.randomUUID(), "Insured", new BigDecimal("85000.00"), "NGN",
            Instant.parse("2026-05-15T11:30:00Z")));
        entityManager.flush();

        Map<String, Object> je = loadJe("claim", "CLAIM_SETTLED", claimId.toString());
        assertThat(je.get("business_date")).isEqualTo(java.sql.Date.valueOf("2026-05-15"));
        assertLine((UUID) je.get("id"), "2140", "85000.00", "0.00");
        assertLine((UUID) je.get("id"), "1120", "0.00", "85000.00");
    }

    // ── ClaimExpenseApproved ─────────────────────────────────────────────────

    @Test
    @DisplayName("ClaimExpenseApproved → JE with Dr 5140 + Cr 2350")
    void claimExpenseApproved() {
        UUID expenseId = UUID.randomUUID();
        service.onClaimExpenseApproved(new ClaimExpenseApprovedEvent(
            expenseId, "EXP-IT-1", UUID.randomUUID(), "CLM-IT-1",
            UUID.randomUUID(), "Surveyor", "SURVEY",
            new BigDecimal("15000.00"), "NGN"));
        entityManager.flush();

        Map<String, Object> je = loadJe("claim", "CLAIM_EXPENSE_APPROVED", expenseId.toString());
        assertThat(je.get("narrative")).isEqualTo("Claim expense EXP-IT-1 on claim CLM-IT-1");
        assertLine((UUID) je.get("id"), "5140", "15000.00", "0.00");
        assertLine((UUID) je.get("id"), "2350", "0.00", "15000.00");
    }

    // ── EndorsementApproved — sign dispatch ──────────────────────────────────

    @Test
    @DisplayName("EndorsementApproved positive → ADDITIONAL rule; JE Dr 1310 / Cr 2110")
    void endorsementAdditional() {
        UUID endoId = UUID.randomUUID();
        service.onEndorsementApproved(new EndorsementApprovedEvent(
            endoId, "ENDO-ADD-1", UUID.randomUUID(), "POL-ADD-1",
            UUID.randomUUID(), "Cust", null, null, "Motor",
            new BigDecimal("25000.00"), "NGN"));
        entityManager.flush();

        Map<String, Object> je = loadJe("endorsement", "ENDORSEMENT_PREMIUM_ADDITIONAL", endoId.toString());
        assertLine((UUID) je.get("id"), "1310", "25000.00", "0.00");
        assertLine((UUID) je.get("id"), "2110", "0.00", "25000.00");
    }

    @Test
    @DisplayName("EndorsementApproved negative → REFUND rule; JE Dr 2110 / Cr 1310 with absolute amount")
    void endorsementRefund() {
        UUID endoId = UUID.randomUUID();
        service.onEndorsementApproved(new EndorsementApprovedEvent(
            endoId, "ENDO-REF-1", UUID.randomUUID(), "POL-REF-1",
            UUID.randomUUID(), "Cust", null, null, "Motor",
            new BigDecimal("-7500.00"), "NGN"));
        entityManager.flush();

        Map<String, Object> je = loadJe("endorsement", "ENDORSEMENT_PREMIUM_REFUND", endoId.toString());
        assertLine((UUID) je.get("id"), "2110", "7500.00", "0.00");
        assertLine((UUID) je.get("id"), "1310", "0.00", "7500.00");
    }

    @Test
    @DisplayName("EndorsementApproved with zero adjustment leaves the GL untouched")
    void endorsementZero() {
        UUID endoId = UUID.randomUUID();
        service.onEndorsementApproved(new EndorsementApprovedEvent(
            endoId, "ENDO-NOOP", UUID.randomUUID(), "POL-1",
            UUID.randomUUID(), "Cust", null, null, "Motor",
            BigDecimal.ZERO, "NGN"));

        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM journal_entry WHERE source_reference = ?",
            Long.class, endoId.toString());
        assertThat(count).isZero();
    }

    // ── FacPremiumCeded ──────────────────────────────────────────────────────

    @Test
    @DisplayName("FacPremiumCeded → 3-line JE: Dr 5210, Cr 4300, Cr 2310 (sum invariant holds)")
    void facPremiumCeded() {
        UUID facCoverId = UUID.randomUUID();
        service.onFacPremiumCeded(new FacPremiumCededEvent(
            facCoverId, "FAC-IT-001", UUID.randomUUID(), "POL-FAC",
            UUID.randomUUID(), "Munich Re",
            new BigDecimal("100000.00"), new BigDecimal("20000.00"),
            new BigDecimal("80000.00"), "NGN"));
        entityManager.flush();

        Map<String, Object> je = loadJe("reinsurance", "FAC_PREMIUM_CEDED", facCoverId.toString());
        assertThat(je.get("narrative")).isEqualTo("Outward FAC FAC-IT-001 ceded to Munich Re");

        Long lineCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM journal_entry_line WHERE journal_entry_id = ?",
            Long.class, je.get("id"));
        assertThat(lineCount).isEqualTo(3L);

        assertLine((UUID) je.get("id"), "5210", "100000.00", "0.00");
        assertLine((UUID) je.get("id"), "4300", "0.00", "20000.00");
        assertLine((UUID) je.get("id"), "2310", "0.00", "80000.00");

        // Balance invariant — the GL service enforces it, but verify here for clarity.
        BigDecimal net = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(debit_amount), 0) - COALESCE(SUM(credit_amount), 0) " +
            "FROM journal_entry_line WHERE journal_entry_id = ?",
            BigDecimal.class, je.get("id"));
        assertThat(net).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── Missing posting rule ─────────────────────────────────────────────────

    @Test
    @DisplayName("Deactivating a rule causes the matching event to throw PostingRuleNotFoundException")
    void missingRuleFailsLoud() {
        jdbcTemplate.update("UPDATE posting_rule SET is_active = FALSE WHERE source_event_type = 'POLICY_APPROVED'");

        UUID policyId = UUID.randomUUID();
        assertThatThrownBy(() -> service.onPolicyApproved(new PolicyApprovedEvent(
            policyId, "POL-NO-RULE", UUID.randomUUID(), "x", null, null,
            "x", new BigDecimal("100.00"), "NGN",
            LocalDate.of(2027, 5, 14), UUID.randomUUID(), UUID.randomUUID(),
            BigDecimal.ZERO, businessDate)))
            .isInstanceOf(PostingRuleNotFoundException.class)
            .hasMessageContaining("POLICY_APPROVED");

        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM journal_entry WHERE source_reference = ?",
            Long.class, policyId.toString());
        assertThat(count).isZero();
    }

    // ── Idempotency (DB UNIQUE on source triple) ─────────────────────────────

    @Test
    @DisplayName("Re-publishing the same PolicyApproved event throws JournalEntryDuplicateException (DB UNIQUE)")
    void idempotencyOnReplay() {
        UUID policyId = UUID.randomUUID();
        PolicyApprovedEvent event = new PolicyApprovedEvent(
            policyId, "POL-IDP", UUID.randomUUID(), "x", null, null,
            "x", new BigDecimal("100.00"), "NGN",
            LocalDate.of(2027, 5, 14), UUID.randomUUID(), UUID.randomUUID(),
            BigDecimal.ZERO, businessDate);
        service.onPolicyApproved(event);
        entityManager.flush();

        assertThatThrownBy(() -> service.onPolicyApproved(event))
            .hasMessageContaining("POLICY_APPROVED");

        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM journal_entry WHERE source_reference = ?",
            Long.class, policyId.toString());
        assertThat(count).isEqualTo(1L);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> loadJe(String module, String eventType, String reference) {
        return jdbcTemplate.queryForMap(
            "SELECT id, business_date, source_module, source_event_type, source_reference, narrative, status " +
            "FROM journal_entry " +
            "WHERE source_module = ? AND source_event_type = ? AND source_reference = ?",
            module, eventType, reference);
    }

    private void assertLine(UUID journalEntryId, String accountCode, String expectedDebit, String expectedCredit) {
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
     * Slice-test support: a system {@link Clock} and a {@link CacheManager}
     * pre-populated with the four COA cache regions plus the new posting-rule
     * region introduced in Slice 1.5.
     */
    @TestConfiguration
    @EnableCaching
    static class TestSupportConfig {

        @Bean
        Clock clock() {
            return Clock.systemDefaultZone();
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
