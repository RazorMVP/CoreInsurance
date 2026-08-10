package com.nubeero.cia.api.finance;

import com.nubeero.cia.common.event.RiFacInwardAcceptedEvent;
import com.nubeero.cia.finance.DebitNote;
import com.nubeero.cia.finance.DebitNoteRepository;
import com.nubeero.cia.finance.DebitNoteService;
import com.nubeero.cia.finance.DebitNoteStatus;
import com.nubeero.cia.finance.FinanceEntityType;
import com.nubeero.cia.finance.FinanceNumberService;
import com.nubeero.cia.finance.RiFacInwardAcceptedEventListener;
import com.nubeero.cia.finance.gl.ChartOfAccountService;
import com.nubeero.cia.finance.gl.FiscalPeriodResolver;
import com.nubeero.cia.finance.gl.JournalEntryDuplicateException;
import com.nubeero.cia.finance.gl.JournalEntryService;
import com.nubeero.cia.finance.gl.PolicyClassResolver;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Inward Facultative Reinsurance (v1) Task 6 — end-to-end finance IT for
 * {@link RiFacInwardAcceptedEventListener}: publishing (driving directly,
 * same code path {@code @EventListener} would take) a {@link
 * RiFacInwardAcceptedEvent} must create a receivable {@link DebitNote} AND
 * a balanced 3-line {@code journal_entry} hitting the V75 inward-FAC COA
 * codes (1330 / 5240 / 4330).
 *
 * <p><strong>Deviation from the task brief's file location</strong> — the
 * brief names {@code cia-finance/src/test/.../RiFacInwardFinanceIT.java},
 * but a real-Flyway-schema IT structurally cannot live there:
 * {@code @DataJpaTest} resolves its {@code @SpringBootConfiguration} (here
 * {@code CiaApplication}, {@code scanBasePackages = "com.nubeero.cia"}) and
 * the Flyway migration scripts by walking up the test class's package on the
 * classpath, and both live only in {@code cia-api} (which depends on
 * cia-finance, never the reverse). Every other real-Flyway subledger IT in
 * this codebase (e.g. {@code SubledgerPostingServiceIT}, {@code
 * PolicyCommissionCreditNoteListenerIT}) already lives under {@code
 * cia-api/src/test/java/com/nubeero/cia/api/finance/**} for the same reason
 * — this file follows that established precedent instead.
 *
 * <p>Harness mirrors {@code SubledgerPostingServiceIT} (Testcontainers
 * Postgres + {@code @DataJpaTest} + {@code AutoConfigureTestDatabase.NONE})
 * but pins {@code spring.flyway.target=75} — the COA rows this posting
 * resolves ({@code 5240}) are seeded by V75; {@code 1330}/{@code 2210}
 * pre-date it (V32).
 *
 * <p><strong>FAC / IFRS-17 PAA workstream Task 3:</strong> the accept
 * posting's credit leg moved from {@code 4330} (immediate income) to
 * {@code 2210} (LRC liability) — accept now sets up the liability at the
 * full gross premium; {@code LrcEngineIT}/{@code InwardFacLrcIT} cover the
 * periodic release of that liability to {@code 4330}.
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
    SubledgerPostingService.class,
    FinanceNumberService.class,
    DebitNoteService.class,
    RiFacInwardAcceptedEventListener.class,
    RiFacInwardFinanceIT.TestSupportConfig.class
})
class RiFacInwardFinanceIT {

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
        // Real Flyway chain builds the schema; V75 seeds the 4330/5240 COA rows
        // this posting resolves (1330 pre-dates V75, from V32).
        registry.add("spring.flyway.target", () -> "75");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired private RiFacInwardAcceptedEventListener listener;
    @Autowired private DebitNoteRepository debitNoteRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        // Both tests post via RiFacInwardAcceptedEventListener ->
        // SubledgerPostingService.replayFacPremiumAccepted(event), which uses
        // the 1-arg overload (today()) — so the only fiscal period any test
        // ever posts against is LocalDate.now()'s month.
        ensureMonthPeriod(LocalDate.now());

        // DebitNoteService.currentUser() reads SecurityContextHolder.
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "alice", "pw",
                List.of(new SimpleGrantedAuthority("FINANCE_CREATE"))));

        var ruleCache = cacheManager.getCache(PostingRuleService.CACHE_BY_EVENT_TYPE);
        if (ruleCache != null) ruleCache.clear();
    }

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

    @Test
    @DisplayName("RiFacInwardAccepted → DebitNote receivable (REINSURANCE, OUTSTANDING, amount=netPremium) "
        + "AND balanced 3-line JE hitting 1330/5240/2210 (Task 3: LRC liability, not income)")
    void inwardFacAccepted_createsDebitNoteAndBalancedJe() {
        UUID facInwardId = UUID.randomUUID();
        UUID cedingCompanyId = UUID.randomUUID();
        UUID classOfBusinessId = UUID.randomUUID();
        BigDecimal grossPremium = new BigDecimal("1000000.00");
        BigDecimal commissionAmount = new BigDecimal("125000.00");
        BigDecimal netPremium = new BigDecimal("875000.00");
        // Invariant the GL leg depends on.
        assertThat(netPremium.add(commissionAmount)).isEqualByComparingTo(grossPremium);

        RiFacInwardAcceptedEvent event = new RiFacInwardAcceptedEvent(
            facInwardId, "FAC-IN-2026-000042", cedingCompanyId, "Leadway Assurance",
            classOfBusinessId, grossPremium, commissionAmount, netPremium, "NGN");

        listener.onInwardFacAccepted(event);
        entityManager.flush();

        // ── DebitNote assertions ────────────────────────────────────────────
        DebitNote dn = debitNoteRepository
            .findByEntityIdAndEntityTypeAndDeletedAtIsNull(facInwardId, FinanceEntityType.REINSURANCE)
            .orElseThrow(() -> new AssertionError("Expected a DebitNote for entityId=" + facInwardId));

        assertThat(dn.getEntityType()).isEqualTo(FinanceEntityType.REINSURANCE);
        assertThat(dn.getEntityId()).isEqualTo(facInwardId);
        assertThat(dn.getEntityReference()).isEqualTo("FAC-IN-2026-000042");
        assertThat(dn.getCustomerId()).isEqualTo(cedingCompanyId);
        assertThat(dn.getCustomerName()).isEqualTo("Leadway Assurance");
        assertThat(dn.getAmount()).isEqualByComparingTo(netPremium);
        assertThat(dn.getTotalAmount()).isEqualByComparingTo(netPremium);
        assertThat(dn.getTaxAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dn.getStatus()).isEqualTo(DebitNoteStatus.OUTSTANDING);
        assertThat(dn.getCurrencyCode()).isEqualTo("NGN");
        assertThat(dn.getDebitNoteNumber()).isNotBlank();
        // NOT "alice": BaseEntity.createdBy is a @CreatedBy JPA-auditing field
        // and CiaCommonAutoConfiguration.auditorProvider() only recognises a
        // JWT principal (falls back to "system" otherwise) — it unconditionally
        // overwrites DebitNoteService's manual currentUser() value on persist.
        // Same behaviour createForPolicy/createForEndorsement already carry.
        assertThat(dn.getCreatedBy()).isEqualTo("system");

        // ── GL assertions ────────────────────────────────────────────────────
        Map<String, Object> je = loadJe("reinsurance", "FAC_PREMIUM_ACCEPTED", facInwardId.toString() + ":" + LocalDate.now());
        assertThat(je).as("expected a journal_entry for FAC_PREMIUM_ACCEPTED / " + facInwardId).isNotEmpty();
        assertThat(je.get("narrative")).isEqualTo("Inward FAC FAC-IN-2026-000042 accepted from Leadway Assurance");

        UUID jeId = (UUID) je.get("id");
        Long lineCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM journal_entry_line WHERE journal_entry_id = ?", Long.class, jeId);
        assertThat(lineCount).isEqualTo(3L);

        assertLine(jeId, "1330", netPremium, BigDecimal.ZERO);
        assertLine(jeId, "5240", commissionAmount, BigDecimal.ZERO);
        assertLine(jeId, "2210", BigDecimal.ZERO, grossPremium);

        BigDecimal net = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(debit_amount), 0) - COALESCE(SUM(credit_amount), 0) " +
            "FROM journal_entry_line WHERE journal_entry_id = ?",
            BigDecimal.class, jeId);
        assertThat(net).as("Σdebit == Σcredit").isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Zero-commission inward FAC accept (commissionRate omitted/0 — the OPTIONAL field's "
        + "default and the default FE form state) → balanced 2-line JE (Dr 1330 net / Cr 2210 gross, "
        + "NO zero-amount Dr 5240 line) + DebitNote. Regression guard: the zero-amount line was "
        + "rejected by JournalEntryService's per-line XOR rule, rolling back the whole accept.")
    void zeroCommissionAccept_createsBalancedTwoLineJe() {
        UUID facInwardId = UUID.randomUUID();
        UUID cedingCompanyId = UUID.randomUUID();
        UUID classOfBusinessId = UUID.randomUUID();
        // No ceding commission → net == gross (commissionRate is optional on
        // CreateFacInwardRequest; the FE defaults it to 0). This is the exact
        // input the final whole-branch review found rolled the accept back.
        BigDecimal grossPremium = new BigDecimal("250000.00");
        BigDecimal commissionAmount = BigDecimal.ZERO.setScale(2);
        BigDecimal netPremium = new BigDecimal("250000.00");
        assertThat(netPremium.add(commissionAmount)).isEqualByComparingTo(grossPremium);

        RiFacInwardAcceptedEvent event = new RiFacInwardAcceptedEvent(
            facInwardId, "FAC-IN-2026-000123", cedingCompanyId, "AIICO Insurance",
            classOfBusinessId, grossPremium, commissionAmount, netPremium, "NGN");

        // Must NOT throw — the pre-fix code posted a Dr 5240 = 0.00 line that
        // JournalEntryService rejects (each line needs exactly one side > 0),
        // which rolled back the entire accept.
        listener.onInwardFacAccepted(event);
        entityManager.flush();

        // DebitNote still created, for the full net (== gross) premium.
        DebitNote dn = debitNoteRepository
            .findByEntityIdAndEntityTypeAndDeletedAtIsNull(facInwardId, FinanceEntityType.REINSURANCE)
            .orElseThrow(() -> new AssertionError("Expected a DebitNote for entityId=" + facInwardId));
        assertThat(dn.getAmount()).isEqualByComparingTo(netPremium);
        assertThat(dn.getStatus()).isEqualTo(DebitNoteStatus.OUTSTANDING);

        Map<String, Object> je = loadJe("reinsurance", "FAC_PREMIUM_ACCEPTED",
            facInwardId.toString() + ":" + LocalDate.now());
        assertThat(je).as("expected a journal_entry even with zero commission").isNotEmpty();
        UUID jeId = (UUID) je.get("id");

        Long lineCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM journal_entry_line WHERE journal_entry_id = ?", Long.class, jeId);
        assertThat(lineCount).as("zero-commission entry omits the Dr 5240 line — 2 lines only").isEqualTo(2L);

        Long line5240 = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM journal_entry_line l JOIN chart_of_account a ON a.id = l.account_id " +
            "WHERE l.journal_entry_id = ? AND a.code = '5240'", Long.class, jeId);
        assertThat(line5240).as("commission-expense line absent when commission is zero").isEqualTo(0L);

        assertLine(jeId, "1330", netPremium, BigDecimal.ZERO);
        assertLine(jeId, "2210", BigDecimal.ZERO, grossPremium);

        BigDecimal net = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(debit_amount), 0) - COALESCE(SUM(credit_amount), 0) " +
            "FROM journal_entry_line WHERE journal_entry_id = ?", BigDecimal.class, jeId);
        assertThat(net).as("Σdebit == Σcredit").isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Two accepts for the SAME facInwardId on the SAME business day: "
        + "the second accept's whole transaction rolls back atomically (JE gateway's "
        + "JournalEntryDuplicateException poisons the listener's @Transactional boundary) "
        + "— exactly 1 DebitNote + 1 journal_entry survive, NO orphan receivable")
    void repeatedAcceptOnSameFacInwardSameDay_secondAcceptRollsBackAtomically() {
        UUID facInwardId = UUID.randomUUID();
        RiFacInwardAcceptedEvent event = new RiFacInwardAcceptedEvent(
            facInwardId, "FAC-IN-2026-000099", UUID.randomUUID(), "Continental Re",
            UUID.randomUUID(), new BigDecimal("500000.00"), new BigDecimal("50000.00"),
            new BigDecimal("450000.00"), "NGN");

        // ── First accept: commit it as its OWN top-level transaction — this
        //    is what RiFacInwardService.create()'s @Transactional boundary
        //    does in production (the listener joins via REQUIRED propagation
        //    and the accept lands durably when that transaction commits). ───
        listener.onInwardFacAccepted(event);
        entityManager.flush();
        TestTransaction.flagForCommit();
        TestTransaction.end();

        TestTransaction.start();
        assertThat(countDebitNotesForEntity(facInwardId))
            .as("first accept's DebitNote committed")
            .isEqualTo(1L);
        assertThat(countJesForReference("reinsurance", "FAC_PREMIUM_ACCEPTED",
                facInwardId + ":" + LocalDate.now()))
            .as("first accept's JournalEntry committed")
            .isEqualTo(1L);
        TestTransaction.end();

        // ── Second accept (e.g. a same-day extend() on the same facInward) —
        //    a FRESH top-level transaction, mirroring the REQUIRED-propagation
        //    boundary RiFacInwardService.extend()'s own @Transactional opens
        //    in production. Inside it: the DebitNote leg inserts a second
        //    (pending, uncommitted) receivable, then the GL leg's idempotency
        //    reference (facInwardId:businessDate) is already taken, so
        //    replayFacPremiumAccepted throws JournalEntryDuplicateException —
        //    an unchecked CiaException. Spring's default rollback-on-unchecked
        //    rule marks THIS transaction rollback-only; because the listener
        //    itself is @Transactional(REQUIRED) and joined (rather than
        //    started) this transaction, only an explicit rollback of the
        //    outer TestTransaction physically undoes the pending DebitNote
        //    insert — proving the real production outcome: clean atomic
        //    failure, no orphan receivable. ───────────────────────────────
        TestTransaction.start();
        assertThatThrownBy(() -> listener.onInwardFacAccepted(event))
            .isInstanceOf(JournalEntryDuplicateException.class)
            .hasMessageContaining("FAC_PREMIUM_ACCEPTED");
        TestTransaction.flagForRollback();
        TestTransaction.end();

        // ── Verify in a fresh transaction, reading only what was physically
        //    committed: the second accept's DebitNote never survived — the
        //    same atomic rollback that undid the second JE attempt undid the
        //    second DebitNote insert too, because both live in the SAME
        //    listener transaction. ─────────────────────────────────────────
        TestTransaction.start();
        assertThat(countDebitNotesForEntity(facInwardId))
            .as("second accept's DebitNote insert rolled back with the rest of its "
                + "transaction — no orphan receivable ever committed")
            .isEqualTo(1L);
        assertThat(countJesForReference("reinsurance", "FAC_PREMIUM_ACCEPTED",
                facInwardId + ":" + LocalDate.now()))
            .as("GL leg never double-posts — exactly 1 journal_entry survives")
            .isEqualTo(1L);
        TestTransaction.end();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private long countDebitNotesForEntity(UUID entityId) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM debit_notes WHERE entity_id = ?", Long.class, entityId);
        return count == null ? 0L : count;
    }

    private long countJesForReference(String module, String eventType, String reference) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM journal_entry WHERE source_module = ? AND source_event_type = ? AND source_reference = ?",
            Long.class, module, eventType, reference);
        return count == null ? 0L : count;
    }

    private Map<String, Object> loadJe(String module, String eventType, String reference) {
        return jdbcTemplate.queryForMap(
            "SELECT id, business_date, source_module, source_event_type, source_reference, narrative, status " +
            "FROM journal_entry " +
            "WHERE source_module = ? AND source_event_type = ? AND source_reference = ?",
            module, eventType, reference);
    }

    private void assertLine(UUID journalEntryId, String accountCode, BigDecimal expectedDebit, BigDecimal expectedCredit) {
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
     * Slice-test support: a {@link CacheManager} pre-populated with the four
     * COA cache regions plus the posting-rule region (mirrors {@code
     * SubledgerPostingServiceIT.TestSupportConfig} — {@code
     * ChartOfAccountService}/{@code PostingRuleService}'s {@code @Cacheable}
     * methods need a {@code CacheManager} bean present even though this
     * posting never hits the rule table).
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
