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
    SubledgerPostingService.class,
    PeriodLockService.class,
    TrialBalanceService.class,
    ReconciliationGateIT.TestSupportConfig.class
})
class ReconciliationGateIT {

    private static final String FIXTURE_RESOURCE = "/reconciliation/events.json";
    private static final Path SNAPSHOT_PATH =
        Paths.get("src/test/resources/reconciliation/expected-trial-balance.json");
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
        registry.add("spring.flyway.target", () -> "34");
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

        ObjectNode actualSnapshot = serialise(actual);

        if (Boolean.getBoolean("snapshot.update")) {
            ObjectMapper writer = newMapper().enable(SerializationFeature.INDENT_OUTPUT);
            Files.writeString(SNAPSHOT_PATH, writer.writeValueAsString(actualSnapshot) + "\n");
            System.out.println("[snapshot.update] wrote " + SNAPSHOT_PATH.toAbsolutePath());
            return;
        }

        ObjectNode expectedSnapshot = (ObjectNode) newMapper()
            .readTree(new ClassPathResource("/reconciliation/expected-trial-balance.json").getInputStream());

        assertThat(actualSnapshot.get("accounts"))
            .as("per-account net balances must match the checked-in snapshot — "
                + "if this is an intentional change, regenerate with -Dsnapshot.update=true "
                + "and explain why in the PR description")
            .isEqualTo(expectedSnapshot.get("accounts"));
        assertThat(actualSnapshot.get("totalDebits"))
            .as("totalDebits must match the checked-in snapshot")
            .isEqualTo(expectedSnapshot.get("totalDebits"));
        assertThat(actualSnapshot.get("totalCredits"))
            .as("totalCredits must match the checked-in snapshot")
            .isEqualTo(expectedSnapshot.get("totalCredits"));
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
        assertThat(events.size()).as("Slice 1.9a fixture invariant: 50 events").isEqualTo(50);

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
            "Slice 1.9a — expected trial balance after playing the 50-event canonical fixture. "
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

    @TestConfiguration
    static class TestSupportConfig {
        @Bean
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
