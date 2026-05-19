package com.nubeero.cia.api.finance.gl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nubeero.cia.finance.dto.JournalEntryLineRequest;
import com.nubeero.cia.finance.dto.JournalEntryResponse;
import com.nubeero.cia.finance.dto.PostJournalEntryRequest;
import com.nubeero.cia.finance.dto.TrialBalanceResponse;
import com.nubeero.cia.finance.gl.ChartOfAccountService;
import com.nubeero.cia.finance.gl.FiscalPeriodResolver;
import com.nubeero.cia.finance.gl.JournalEntryService;
import com.nubeero.cia.finance.gl.TrialBalanceService;
import jakarta.persistence.EntityManager;
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

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link TrialBalanceService} — the GATEWAY invariant
 * every later closure slice depends on. The headline acceptance check is
 * the 100-JE reconciliation case: after randomly posting 100 balanced
 * journal entries with realistic chart-of-account distribution and a
 * deterministic seed, the trial balance:
 *
 * <ul>
 *   <li>nets to exactly {@link BigDecimal#ZERO} (footer.balanced=true);</li>
 *   <li>has {@code totalDebits == totalCredits} per the footer summary;</li>
 *   <li>emits a deterministic JSON evidence file under
 *       {@code cia-api/src/test/resources/trial-balance/reconciliation-evidence.json}
 *       so the proof can be reviewed by finance and stored alongside the
 *       commit.</li>
 * </ul>
 *
 * <p>The seed is fixed ({@code Random(42L)}) so any future drift in the
 * posting logic that affects per-account aggregation will surface as a
 * concrete diff in the evidence file rather than as a brittle "balanced or
 * not" boolean.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    // CiaCommonAutoConfiguration enables @EnableJpaAuditing so @CreatedDate
    // on BaseEntity populates journal_entry.created_at — without it the JE
    // INSERTs hit a NOT NULL violation (see CLAUDE.md / Module 12 IT
    // wiring note).
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    ChartOfAccountService.class,
    FiscalPeriodResolver.class,
    JournalEntryService.class,
    TrialBalanceService.class,
    TrialBalanceServiceIT.TestSupportConfig.class
})
class TrialBalanceServiceIT {

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
        registry.add("spring.flyway.target", () -> "35");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    /**
     * Reasonable debit / credit account pairings that respect double-entry
     * sign conventions (assets/expenses on the debit side; liabilities /
     * equity / income on the credit side). All eight codes are seeded
     * leaves in V32.
     */
    private static final String[][] ACCOUNT_PAIRS = {
        { "1110", "4110" }, // cash receipts ↔ insurance revenue
        { "1120", "4120" }, // bank ↔ acquisition cost recovery
        { "1130", "4130" }, // call deposits ↔ change in risk adjustment
        { "1310", "4140" }, // premium receivable ↔ experience adjustment
        { "5110", "1110" }, // claims paid ↔ cash
        { "5410", "2310" }, // operating expense ↔ trade payable
        { "5210", "1110" }, // outward RI expense ↔ cash
        { "5310", "1110" }, // investment expense ↔ cash
    };

    @Autowired private JournalEntryService journalEntryService;
    @Autowired private TrialBalanceService trialBalanceService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("100-JE reconciliation: total debits == total credits, net == 0, evidence JSON emitted")
    void hundredJournalEntriesReconcile() throws IOException {
        LocalDate businessDate = seedMay2026Period();
        Random random = new Random(42L);
        BigDecimal grandTotalPosted = BigDecimal.ZERO;
        List<Map<String, Object>> manifest = new ArrayList<>(100);

        for (int i = 1; i <= 100; i++) {
            String[] pair = ACCOUNT_PAIRS[random.nextInt(ACCOUNT_PAIRS.length)];
            // Random amount between 100.00 and 10,000.00 with 2-decimal scale.
            BigDecimal amount = BigDecimal.valueOf(100L + random.nextInt(990_001), 2);
            PostJournalEntryRequest request = new PostJournalEntryRequest(
                businessDate,
                "finance",
                "RECONCILIATION_TEST",
                "RT-" + i,
                "Reconciliation JE " + i,
                List.of(
                    line(pair[0], amount.toPlainString(), "0.00"),
                    line(pair[1], "0.00",                 amount.toPlainString())));
            journalEntryService.post(request);
            grandTotalPosted = grandTotalPosted.add(amount);
            // LinkedHashMap so JSON key order is deterministic across runs.
            // The JE id (a fresh UUID per run) is deliberately omitted — the
            // evidence file is a check-in snapshot, so every value must be
            // deterministic. The seq + (debit, credit, amount) tuple is
            // enough to identify a JE within a fixed-seed run.
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("seq", i);
            entry.put("debitAccount", pair[0]);
            entry.put("creditAccount", pair[1]);
            entry.put("amount", amount.toPlainString());
            manifest.add(entry);
        }
        entityManager.flush();
        entityManager.clear();

        TrialBalanceResponse trialBalance = trialBalanceService.trialBalanceAsOf(businessDate);
        assertThat(trialBalance.footer().balanced())
            .as("100-JE trial balance must net to zero")
            .isTrue();
        assertThat(trialBalance.footer().totalDebits())
            .as("Σ debits across all lines")
            .isEqualByComparingTo(grandTotalPosted);
        assertThat(trialBalance.footer().totalCredits())
            .as("Σ credits across all lines")
            .isEqualByComparingTo(grandTotalPosted);
        // Drift sentinel — the committed reconciliation-evidence.json pins this value.
        // If you change the seed, ACCOUNT_PAIRS, amount formula, or anything else that
        // affects aggregation, this assertion fails and you must regenerate the
        // evidence file (the IT itself writes the new one each run).
        assertThat(grandTotalPosted)
            .as("seed-deterministic grand total (see reconciliation-evidence.json)")
            .isEqualByComparingTo("505263.29");
        assertThat(trialBalance.footer().lineCount()).isEqualTo(200L); // 2 lines × 100 JEs

        // Verify directly at the SQL level — independent path through the data, defends
        // against an aggregation bug in the JPQL.
        BigDecimal sqlNet = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(debit_amount), 0) - COALESCE(SUM(credit_amount), 0) FROM journal_entry_line",
            BigDecimal.class);
        assertThat(sqlNet).isEqualByComparingTo(BigDecimal.ZERO);

        Path evidencePath = writeEvidence(trialBalance, manifest, grandTotalPosted);
        assertThat(evidencePath).exists();
        assertThat(Files.size(evidencePath)).isGreaterThan(0L);
    }

    @Test
    @DisplayName("Trial balance is cumulative since inception — asOf filters on business_date (D4=A)")
    void asOfFiltersOnBusinessDate() {
        LocalDate aprilDate = seedMonthlyPeriod(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), LocalDate.of(2026, 4, 15));
        LocalDate mayDate = seedMonthlyPeriod(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), LocalDate.of(2026, 5, 14));

        post(aprilDate, "RT-april", "500.00", "1110", "4110");
        post(mayDate,   "RT-may",   "300.00", "1110", "4110");
        entityManager.flush();
        entityManager.clear();

        TrialBalanceResponse aprilEnd = trialBalanceService.trialBalanceAsOf(LocalDate.of(2026, 4, 30));
        assertThat(aprilEnd.footer().totalDebits()).isEqualByComparingTo("500.00");

        TrialBalanceResponse mayEnd = trialBalanceService.trialBalanceAsOf(LocalDate.of(2026, 5, 31));
        assertThat(mayEnd.footer().totalDebits()).isEqualByComparingTo("800.00");
    }

    @Test
    @DisplayName("Reversal pairs net to zero in the trial balance (D2=A model)")
    void reversalNetsToZero() {
        LocalDate businessDate = seedMay2026Period();
        JournalEntryResponse original = post(businessDate, "RT-rev-1", "750.00", "1110", "4110");
        journalEntryService.reverse(original.id(), "Trial balance test");
        entityManager.flush();
        entityManager.clear();

        TrialBalanceResponse trialBalance = trialBalanceService.trialBalanceAsOf(businessDate);
        assertThat(trialBalance.footer().balanced()).isTrue();
        // Both rows contribute: 750 debit on 1110 (original) and 750 credit on 1110 (reversal)
        // → 1110 nets to zero, omitted from per-account lines because debit_balance =
        // credit_balance = 0 still appears as a row but with zero balances. The
        // aggregation query DOES return such rows because the GROUP BY isn't filtered
        // by net. That's the desired behaviour: auditors want to see touched accounts.
        assertThat(trialBalance.footer().totalDebits())
            .isEqualByComparingTo(trialBalance.footer().totalCredits());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private LocalDate seedMay2026Period() {
        return seedMonthlyPeriod(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), LocalDate.of(2026, 5, 14));
    }

    private LocalDate seedMonthlyPeriod(LocalDate start, LocalDate end, LocalDate businessDate) {
        UUID fyId = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();
        Integer existingFy = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM fiscal_year WHERE start_date <= ? AND end_date >= ?",
            Integer.class, start, end);
        UUID fiscalYearId = fyId;
        if (existingFy == 0) {
            jdbcTemplate.update(
                "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
                fyId,
                "FY-" + start.getYear() + "-" + start.getMonthValue(),
                start.withDayOfYear(1),
                start.withDayOfYear(1).plusYears(1).minusDays(1),
                "ACTIVE",
                "test");
        } else {
            fiscalYearId = jdbcTemplate.queryForObject(
                "SELECT id FROM fiscal_year WHERE start_date <= ? AND end_date >= ? LIMIT 1",
                UUID.class, start, end);
        }
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            periodId, fiscalYearId, "MONTH", start, end, "OPEN", "test");
        return businessDate;
    }

    private JournalEntryResponse post(LocalDate businessDate, String reference, String amount,
                                       String debitAccount, String creditAccount) {
        PostJournalEntryRequest request = new PostJournalEntryRequest(
            businessDate, "finance", "TB_TEST", reference, "Trial balance fixture",
            List.of(
                line(debitAccount, amount, "0.00"),
                line(creditAccount, "0.00", amount)));
        return journalEntryService.post(request);
    }

    private static JournalEntryLineRequest line(String accountCode, String debit, String credit) {
        return new JournalEntryLineRequest(
            accountCode, new BigDecimal(debit), new BigDecimal(credit), null, null, null, null, null, null);
    }

    private Path writeEvidence(TrialBalanceResponse trialBalance, List<Map<String, Object>> manifest,
                                BigDecimal grandTotalPosted) throws IOException {
        ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("description", "Slice 1.4 GATEWAY reconciliation evidence: 100 randomly-posted balanced JEs " +
            "drawn from a fixed seed (Random(42L)), submitted through JournalEntryService.post, " +
            "aggregated by TrialBalanceService.trialBalanceAsOf.");
        evidence.put("seed", 42);
        evidence.put("journalEntryCount", manifest.size());
        evidence.put("lineCount", trialBalance.footer().lineCount());
        evidence.put("grandTotalPosted", grandTotalPosted.toPlainString());
        // LinkedHashMap (not Map.of) so JSON key order is deterministic across
        // runs — Map.of returns an iteration-unordered map and the evidence file
        // is checked in as a snapshot, so any spurious reordering shows up as
        // a noisy diff with no semantic content.
        Map<String, Object> trialBalanceMap = new LinkedHashMap<>();
        trialBalanceMap.put("asOf", trialBalance.asOf().toString());
        trialBalanceMap.put("totalDebits", trialBalance.footer().totalDebits().toPlainString());
        trialBalanceMap.put("totalCredits", trialBalance.footer().totalCredits().toPlainString());
        trialBalanceMap.put("balanced", trialBalance.footer().balanced());
        trialBalanceMap.put("lineCount", trialBalance.footer().lineCount());
        trialBalanceMap.put("perAccount", trialBalance.lines().stream().map(line -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", line.accountCode());
            row.put("name", line.accountName());
            row.put("type", line.accountType().name());
            row.put("debitBalance", line.debitBalance().toPlainString());
            row.put("creditBalance", line.creditBalance().toPlainString());
            return row;
        }).toList());
        evidence.put("trialBalance", trialBalanceMap);
        evidence.put("journalEntries", manifest);

        Path target = Paths.get("src/test/resources/trial-balance/reconciliation-evidence.json");
        Files.createDirectories(target.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), evidence);
        return target;
    }

    /**
     * Slice-test support: a system {@link Clock} and a {@link CacheManager}
     * pre-populated with the four chart-of-accounts cache regions so
     * {@code @Cacheable} resolves.
     */
    @TestConfiguration
    @EnableCaching
    static class TestSupportConfig {

        @Bean
        // Renamed from `clock` to avoid a bean-name collision with
        // CiaCommonAutoConfiguration.clock() — that bean is
        // @ConditionalOnMissingBean by TYPE (Clock), so a Clock bean under
        // any name suppresses it, but two beans with the same name throw
        // BeanDefinitionOverrideException.
        Clock systemClock() {
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
