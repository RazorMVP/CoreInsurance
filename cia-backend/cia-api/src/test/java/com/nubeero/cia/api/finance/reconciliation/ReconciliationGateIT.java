package com.nubeero.cia.api.finance.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.config.CiaCommonAutoConfiguration;
import com.nubeero.cia.common.event.ClaimApprovedEvent;
import com.nubeero.cia.common.event.ClaimExpenseApprovedEvent;
import com.nubeero.cia.common.event.ClaimSettledEvent;
import com.nubeero.cia.common.event.EndorsementApprovedEvent;
import com.nubeero.cia.common.event.FacPremiumCededEvent;
import com.nubeero.cia.common.event.PolicyApprovedEvent;
import com.nubeero.cia.finance.gl.ChartOfAccountService;
import com.nubeero.cia.finance.gl.FiscalPeriodLookupCache;
import com.nubeero.cia.finance.gl.FiscalPeriodResolver;
import com.nubeero.cia.finance.gl.JournalEntryService;
import com.nubeero.cia.finance.gl.PeriodLockService;
import com.nubeero.cia.finance.gl.PostingRuleService;
import com.nubeero.cia.finance.gl.SubledgerPostingService;
import com.nubeero.cia.finance.gl.TrialBalanceService;
import com.nubeero.cia.finance.dto.TrialBalanceLine;
import com.nubeero.cia.finance.dto.TrialBalanceResponse;
import com.nubeero.cia.workflow.backfill.BackfillEventType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reconciliation Gate IT — Slice 1.9a (Module 12 Period-End Closures).
 *
 * <h2>What this test guarantees</h2>
 * <p>For a fixed event fixture (50 heterogeneous business events across all
 * six {@link BackfillEventType} values), the trial balance computed by
 * {@link TrialBalanceService} matches a checked-in snapshot file
 * <strong>byte-for-byte at the per-account net level</strong>. Any future
 * change to:
 * <ul>
 *   <li>a {@code posting_rule} row (Dr/Cr account-code swap, narrative drift)</li>
 *   <li>a {@code SubledgerPostingService.replay*} method (new line, scale change)</li>
 *   <li>the {@link JournalEntryService#post} contract (e.g. dropping a line)</li>
 *   <li>{@code TrialBalanceService.trialBalanceAsOf} aggregation logic</li>
 * </ul>
 * that shifts even one account's net balance fails CI on the PR that
 * introduces the drift.
 *
 * <h2>Mutation guard</h2>
 * <p>{@link #mutatingPostingRuleBreaksReconciliation()} deliberately swaps
 * the Dr/Cr account codes on the {@code POLICY_APPROVED} posting rule and
 * asserts the snapshot match FAILS — proving the gate actually catches
 * drift rather than being a tautology. Without this guard a gate that
 * accepts everything would still be green and we'd never know.
 *
 * <h2>Snapshot regeneration</h2>
 * <p>When an intentional change shifts the expected balance (new posting
 * rule, new event type, fixture extension), regenerate the snapshot:
 * <pre>{@code
 * mvn test -pl cia-api -Dtest=ReconciliationGateIT \
 *   -Dsnapshot.update=true
 * }</pre>
 * The PR description must explain why the snapshot moved.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    CiaCommonAutoConfiguration.class,
    ChartOfAccountService.class,
    FiscalPeriodResolver.class,
    FiscalPeriodLookupCache.class,
    JournalEntryService.class,
    PostingRuleService.class,
    com.nubeero.cia.finance.gl.PolicyClassResolver.class,
    SubledgerPostingService.class,
    PeriodLockService.class,
    TrialBalanceService.class,
    ReconciliationGateIT.TestSupportConfig.class
})
class ReconciliationGateIT {

    private static final String FIXTURE_RESOURCE = "/reconciliation/events.json";
    private static final Path TRIAL_BALANCE_SNAPSHOT_PATH =
        Paths.get("src/test/resources/reconciliation/expected-trial-balance.json");
    private static final Path JOURNAL_ENTRIES_SNAPSHOT_PATH =
        Paths.get("src/test/resources/reconciliation/expected-journal-entries.json");
    private static final String JOURNAL_ENTRIES_SNAPSHOT_RESOURCE =
        "/reconciliation/expected-journal-entries.json";
    private static final LocalDate AS_OF = LocalDate.of(2026, 5, 31);

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
        registry.add("spring.flyway.target", () -> "48");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private TrialBalanceService trialBalanceService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @PersistenceContext private EntityManager em;

    @BeforeEach
    void seedFiscalPeriod() {
        UUID fiscalYearId = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();
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
    @DisplayName("canonical 50-event fixture produces the expected per-account trial balance")
    void reconciliationGateMatchesSnapshot() throws IOException {
        playFixture();
        em.flush();

        long jeCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM journal_entry", Long.class);
        long lineCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM journal_entry_line", Long.class);
        System.out.printf("[gate] %d journal_entry rows, %d journal_entry_line rows%n",
            jeCount, lineCount);

        TrialBalanceResponse actual = trialBalanceService.trialBalanceAsOf(AS_OF);

        assertThat(actual.footer().balanced())
            .as("trial balance must always balance (totalDebits == totalCredits)")
            .isTrue();

        ObjectNode actualTrialBalance = serialise(actual);
        ObjectNode actualJournalEntries = serialiseJournalEntries();

        if (Boolean.getBoolean("snapshot.update")) {
            ObjectMapper writer = newMapper().enable(SerializationFeature.INDENT_OUTPUT);
            Files.writeString(TRIAL_BALANCE_SNAPSHOT_PATH,
                writer.writeValueAsString(actualTrialBalance) + "\n");
            Files.writeString(JOURNAL_ENTRIES_SNAPSHOT_PATH,
                writer.writeValueAsString(actualJournalEntries) + "\n");
            System.out.println("[snapshot.update] wrote " + TRIAL_BALANCE_SNAPSHOT_PATH.toAbsolutePath());
            System.out.println("[snapshot.update] wrote " + JOURNAL_ENTRIES_SNAPSHOT_PATH.toAbsolutePath());
            return;
        }

        ObjectNode expectedTrialBalance = (ObjectNode) newMapper()
            .readTree(new ClassPathResource("/reconciliation/expected-trial-balance.json").getInputStream());

        assertThat(actualTrialBalance.get("accounts"))
            .as("per-account net balances must match the checked-in trial-balance snapshot — "
                + "if this is an intentional change, regenerate with -Dsnapshot.update=true "
                + "and explain why in the PR description")
            .isEqualTo(expectedTrialBalance.get("accounts"));
        assertThat(actualTrialBalance.get("totalDebits"))
            .as("totalDebits must match the checked-in snapshot")
            .isEqualTo(expectedTrialBalance.get("totalDebits"));
        assertThat(actualTrialBalance.get("totalCredits"))
            .as("totalCredits must match the checked-in snapshot")
            .isEqualTo(expectedTrialBalance.get("totalCredits"));

        // Per-JE evidence snapshot (Slice 1.9b) — finer-grained than the
        // per-account trial balance. Catches drift that re-orders lines
        // within a JE or rewrites a narrative template (cases where account
        // aggregates would still match by coincidence).
        ObjectNode expectedJournalEntries = (ObjectNode) newMapper()
            .readTree(new ClassPathResource(JOURNAL_ENTRIES_SNAPSHOT_RESOURCE).getInputStream());
        assertThat(actualJournalEntries.get("entries"))
            .as("per-JE evidence must match the checked-in journal-entries snapshot — "
                + "regenerate with -Dsnapshot.update=true if the drift is intentional. "
                + "This is the finer-grained gate that catches changes invisible to per-account aggregation.")
            .isEqualTo(expectedJournalEntries.get("entries"));
        assertThat(actualJournalEntries.get("entryCount"))
            .as("entryCount must match the snapshot")
            .isEqualTo(expectedJournalEntries.get("entryCount"));
        assertThat(actualJournalEntries.get("lineCount"))
            .as("lineCount must match the snapshot")
            .isEqualTo(expectedJournalEntries.get("lineCount"));
    }

    @Test
    @DisplayName("MUTATION GUARD: swapping Dr/Cr on POLICY_APPROVED breaks the snapshot match")
    void mutatingPostingRuleBreaksReconciliation() throws IOException {
        // Deliberately corrupt the POLICY_APPROVED rule — swap debit and
        // credit account codes. Dr/Cr swap preserves the global totalDebits
        // == totalCredits invariant (the JE itself is still balanced), but
        // shifts amounts to the WRONG accounts. This is the failure mode
        // a snapshot-based gate must catch — a "balanced trial balance"
        // assertion alone would miss it.
        jdbcTemplate.update(
            "UPDATE posting_rule "
            + "  SET debit_account_code = '2110', "
            + "      credit_account_code = '1310' "
            + "WHERE source_event_type = 'POLICY_APPROVED'");

        playFixture();
        em.flush();

        TrialBalanceResponse mutatedActual = trialBalanceService.trialBalanceAsOf(AS_OF);

        // Sanity check: balance invariant still holds — proves Dr/Cr swap
        // alone doesn't trip a "balanced" gate.
        assertThat(mutatedActual.footer().balanced())
            .as("Dr/Cr swap preserves the global balance invariant")
            .isTrue();

        // The gate is the assertion that catches it: per-account totals
        // must diverge from the snapshot.
        ObjectNode mutatedSnapshot = serialise(mutatedActual);
        ObjectNode expectedSnapshot = (ObjectNode) newMapper()
            .readTree(new ClassPathResource("/reconciliation/expected-trial-balance.json").getInputStream());

        assertThatThrownBy(() -> assertThat(mutatedSnapshot.get("accounts"))
            .isEqualTo(expectedSnapshot.get("accounts")))
            .as("mutation must surface as a snapshot mismatch — if THIS assertion fires, "
                + "the gate is a tautology and not actually catching drift")
            .isInstanceOf(AssertionError.class);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void playFixture() throws IOException {
        ObjectMapper mapper = newMapper();
        JsonNode root = mapper.readTree(new ClassPathResource(FIXTURE_RESOURCE).getInputStream());
        JsonNode events = root.get("events");
        assertThat(events).as("fixture must contain an events array").isNotNull();
        assertThat(events.size()).as("Slice 1.9b fixture invariant: 200 events").isEqualTo(200);

        for (JsonNode envelope : events) {
            BackfillEventType type = BackfillEventType.valueOf(envelope.get("type").asText());
            JsonNode payload = envelope.get("payload");
            Object event = switch (type) {
                case POLICY_APPROVED        -> mapper.treeToValue(payload, PolicyApprovedEvent.class);
                case CLAIM_APPROVED         -> mapper.treeToValue(payload, ClaimApprovedEvent.class);
                case CLAIM_SETTLED          -> mapper.treeToValue(payload, ClaimSettledEvent.class);
                case CLAIM_EXPENSE_APPROVED -> mapper.treeToValue(payload, ClaimExpenseApprovedEvent.class);
                case ENDORSEMENT_APPROVED   -> mapper.treeToValue(payload, EndorsementApprovedEvent.class);
                case FAC_PREMIUM_CEDED      -> mapper.treeToValue(payload, FacPremiumCededEvent.class);
            };
            publisher.publishEvent(event);
        }
    }

    private static ObjectMapper newMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Serialise to a deterministic JSON shape that snapshot-matches by-value
     * regardless of JE row order: an account map keyed by account code.
     * Sort by code so the serialised string is stable.
     */
    private static ObjectNode serialise(TrialBalanceResponse response) {
        ObjectMapper mapper = newMapper().enable(SerializationFeature.INDENT_OUTPUT);
        ObjectNode root = mapper.createObjectNode();
        root.put("_description",
            "Slice 1.9b — expected trial balance after playing the 200-event canonical fixture. "
            + "Regenerate with -Dsnapshot.update=true.");
        root.put("balanced", response.footer().balanced());
        root.put("totalDebits", scale(response.footer().totalDebits()).toPlainString());
        root.put("totalCredits", scale(response.footer().totalCredits()).toPlainString());
        root.put("lineCount", response.footer().lineCount());

        Map<String, TrialBalanceLine> byCode = new LinkedHashMap<>();
        response.lines().stream()
            .sorted((a, b) -> a.accountCode().compareTo(b.accountCode()))
            .forEach(line -> byCode.put(line.accountCode(), line));

        ObjectNode accounts = mapper.createObjectNode();
        byCode.forEach((code, line) -> {
            ObjectNode entry = mapper.createObjectNode();
            entry.put("name", line.accountName());
            entry.put("type", line.accountType().name());
            entry.put("debitBalance", scale(line.debitBalance()).toPlainString());
            entry.put("creditBalance", scale(line.creditBalance()).toPlainString());
            accounts.set(code, entry);
        });
        root.set("accounts", accounts);
        return root;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Per-JE evidence (Slice 1.9b): a finer-grained snapshot than the per-
     * account trial balance. Captures each journal entry as
     * {@code (sourceModule, sourceEventType, sourceReference, businessDate,
     * narrative, lines[])}; lines are {@code (accountCode, debit, credit)}
     * ordered by their original {@code line_no}. The DB UNIQUE constraint
     * on the source triple guarantees stable identity across runs; we
     * deliberately exclude {@code id}, {@code created_at}, {@code updated_at},
     * {@code period_id}, {@code account_id}, {@code posting_date} (all
     * non-deterministic across days, schemas, or test isolation).
     *
     * <p>Drift this catches that the per-account snapshot does not:
     * <ul>
     *   <li>narrative-template rewording in a posting rule</li>
     *   <li>line-order swap within a JE (e.g. credit-then-debit instead of debit-then-credit)</li>
     *   <li>change to which event type maps to which posting rule when the
     *       net per-account effect happens to coincide</li>
     * </ul>
     */
    private ObjectNode serialiseJournalEntries() {
        ObjectMapper mapper = newMapper().enable(SerializationFeature.INDENT_OUTPUT);
        ObjectNode root = mapper.createObjectNode();
        root.put("_description",
            "Slice 1.9b — expected journal-entry shape after playing the 200-event canonical fixture. "
            + "Each entry is keyed by the (source_module, source_event_type, source_reference) DB UNIQUE "
            + "triple; lines preserve the posting-rule's original order. Regenerate with -Dsnapshot.update=true.");

        List<Map<String, Object>> rows = jdbcTemplate.query(
            """
            SELECT je.source_module,
                   je.source_event_type,
                   je.source_reference,
                   je.business_date,
                   je.narrative,
                   jel.line_no,
                   coa.code AS account_code,
                   jel.debit_amount,
                   jel.credit_amount
              FROM journal_entry je
              JOIN journal_entry_line jel ON jel.journal_entry_id = je.id
              JOIN chart_of_account coa   ON coa.id = jel.account_id
             WHERE je.deleted_at IS NULL
               AND jel.deleted_at IS NULL
             ORDER BY je.source_module,
                      je.source_event_type,
                      je.source_reference,
                      jel.line_no
            """,
            (rs, n) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sourceModule", rs.getString("source_module"));
                row.put("sourceEventType", rs.getString("source_event_type"));
                row.put("sourceReference", rs.getString("source_reference"));
                row.put("businessDate", rs.getDate("business_date").toLocalDate().toString());
                row.put("narrative", rs.getString("narrative"));
                row.put("lineNo", rs.getInt("line_no"));
                row.put("accountCode", rs.getString("account_code"));
                row.put("debit", scale(rs.getBigDecimal("debit_amount")).toPlainString());
                row.put("credit", scale(rs.getBigDecimal("credit_amount")).toPlainString());
                return row;
            });

        // Group flat rows back into nested (entry → lines) shape.
        Map<String, ObjectNode> entriesByKey = new LinkedHashMap<>();
        // int (not long) so Jackson serialises as IntNode — the snapshot file's
        // numeric literal parses as IntNode too, and Jackson's node equality
        // distinguishes Int from Long even when the value is identical.
        int lineCount = 0;
        for (Map<String, Object> row : rows) {
            String key = row.get("sourceModule") + "|" + row.get("sourceEventType") + "|" + row.get("sourceReference");
            ObjectNode entry = entriesByKey.computeIfAbsent(key, k -> {
                ObjectNode e = mapper.createObjectNode();
                e.put("sourceModule", (String) row.get("sourceModule"));
                e.put("sourceEventType", (String) row.get("sourceEventType"));
                e.put("sourceReference", (String) row.get("sourceReference"));
                e.put("businessDate", (String) row.get("businessDate"));
                e.put("narrative", (String) row.get("narrative"));
                e.set("lines", mapper.createArrayNode());
                return e;
            });
            ObjectNode line = mapper.createObjectNode();
            line.put("accountCode", (String) row.get("accountCode"));
            line.put("debit", (String) row.get("debit"));
            line.put("credit", (String) row.get("credit"));
            ((com.fasterxml.jackson.databind.node.ArrayNode) entry.get("lines")).add(line);
            lineCount++;
        }

        root.put("entryCount", entriesByKey.size());
        root.put("lineCount", lineCount);
        com.fasterxml.jackson.databind.node.ArrayNode entries = mapper.createArrayNode();
        entriesByKey.values().forEach(entries::add);
        root.set("entries", entries);
        return root;
    }

    @TestConfiguration
    static class TestSupportConfig {
        // @Primary so this clock wins over CiaCommonAutoConfiguration.clock()
        // (which is @ConditionalOnMissingBean by type — but the conditional
        // evaluation order for @Import'd configs vs auto-discovered ones is
        // not reliable; without @Primary the system clock can sneak in and
        // any event handler that calls `today()` to derive business_date
        // produces a non-deterministic snapshot value). 2026-05-31 is end
        // of fiscal period and on-or-after every fixture event date, so
        // the V31 ck_journal_entry_dates constraint (business_date <=
        // posting_date) is satisfied for backfilled events too.
        @Bean
        @org.springframework.context.annotation.Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-05-31T10:00:00Z"), ZoneOffset.UTC);
        }

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
    }
}
