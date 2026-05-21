package com.nubeero.cia.api.finance.naicom;

import com.nubeero.cia.finance.gl.TrialBalanceService;
import com.nubeero.cia.finance.naicom.BalanceSheetEngine;
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
 * Slice 4.3 IT for {@link BalanceSheetEngine}. Seeds a small set of JEs
 * across ASSET / LIABILITY / EQUITY / INCOME / EXPENSE accounts, then
 * verifies the engine projects them into the NAICOM BS sections correctly.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Empty period — every section's total = 0, balanced</li>
 *   <li>Single asset booking — Assets &gt; 0, Liabilities/Equity = 0,
 *       retainedEarningsToDate = 0 (no I/E activity)</li>
 *   <li>Asset funded by equity — A = E, balanced = true</li>
 *   <li>Income posted intra-year — retainedEarningsToDate = income,
 *       balanceCheck includes it</li>
 *   <li>Zero-balance accounts excluded — accounts that net to 0 don't
 *       appear in their section's {@code lines}</li>
 *   <li>asOf cut-off — JEs with business_date AFTER period_end are
 *       excluded (TrialBalanceService discipline carries through)</li>
 * </ol>
 *
 * <p>Reuses the V32-seeded chart_of_account; tests reference real account
 * codes ('1110' bank, '3110' share capital, etc.) so no schema munging
 * is required.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    TrialBalanceService.class,
    BalanceSheetEngine.class
})
class BalanceSheetEngineIT {

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
        registry.add("spring.flyway.target", () -> "47");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired private BalanceSheetEngine engine;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID yearPeriodId;
    private UUID afterPeriodId;
    private final LocalDate yearStart = LocalDate.of(2026, 1, 1);
    private final LocalDate yearEnd = LocalDate.of(2026, 12, 31);

    // Resolved at @BeforeEach from V32 seed
    private UUID bankAccountId;       // 1110 — Bank current account (ASSET)
    private UUID receivableAccountId; // 1310 — Premium receivable (ASSET)
    private UUID payableAccountId;    // 2310 — Accounts payable (LIABILITY)
    private UUID shareCapitalId;      // 3110 — Share capital (EQUITY)
    private UUID premiumIncomeId;     // 4110 — Premium income (INCOME)
    private UUID claimsExpenseId;     // 5150 — Claims incurred (EXPENSE)

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM journal_entry_line");
        jdbcTemplate.update("DELETE FROM journal_entry");
        jdbcTemplate.update("DELETE FROM fiscal_period");
        jdbcTemplate.update("DELETE FROM fiscal_year");

        UUID fyId = UUID.randomUUID();
        yearPeriodId = UUID.randomUUID();
        afterPeriodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            fyId, "FY-BS-2026", yearStart, yearEnd, "ACTIVE", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            yearPeriodId, fyId, "YEAR", yearStart, yearEnd, "HARD_CLOSED", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            afterPeriodId, fyId, "MONTH",
            LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31), "OPEN", "test");

        bankAccountId = lookupAccount("1110");
        receivableAccountId = lookupAccount("1310");
        payableAccountId = lookupAccount("2310");
        shareCapitalId = lookupAccount("3110");
        premiumIncomeId = lookupAccount("4110");
        claimsExpenseId = lookupAccount("5150");
    }

    @Test
    @DisplayName("empty period — all sections zero, balanced")
    void emptyPeriod() {
        Map<String, Object> payload = engine.computePayload(yearPeriodId);

        assertThat(payload.get("submissionType"))
            .isEqualTo(NaicomSubmissionType.BALANCE_SHEET.name());
        assertThat(((BigDecimal) ((Map<?, ?>) payload.get("assets")).get("total")))
            .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(((BigDecimal) ((Map<?, ?>) payload.get("liabilities")).get("total")))
            .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(((BigDecimal) ((Map<?, ?>) payload.get("equity")).get("total")))
            .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(((Map<?, ?>) payload.get("balanceCheck")).get("balanced"))
            .isEqualTo(true);
    }

    @Test
    @DisplayName("asset funded by equity — A = E, balanced")
    void assetFundedByEquity() {
        // Initial capital injection: Dr Bank ₦1,000,000 / Cr Share Capital ₦1,000,000
        postJe(LocalDate.of(2026, 1, 5), yearPeriodId, "Initial capital",
            bankAccountId, new BigDecimal("1000000.00"), BigDecimal.ZERO,
            shareCapitalId, BigDecimal.ZERO, new BigDecimal("1000000.00"));

        Map<String, Object> payload = engine.computePayload(yearPeriodId);

        BigDecimal totalAssets = (BigDecimal) ((Map<?, ?>) payload.get("assets")).get("total");
        BigDecimal totalEquity = (BigDecimal) ((Map<?, ?>) payload.get("equity")).get("total");
        BigDecimal totalLiab = (BigDecimal) ((Map<?, ?>) payload.get("liabilities")).get("total");
        BigDecimal retained = (BigDecimal) ((Map<?, ?>) payload.get("equity")).get("retainedEarningsToDate");

        assertThat(totalAssets).isEqualByComparingTo("1000000.00");
        assertThat(totalEquity).isEqualByComparingTo("1000000.00");
        assertThat(totalLiab).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(retained).as("no I/E activity yet").isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(((Map<?, ?>) payload.get("balanceCheck")).get("balanced")).isEqualTo(true);
    }

    @Test
    @DisplayName("income posted intra-year — retainedEarningsToDate populated, BS still balances")
    void retainedEarningsFromIntraYearIncome() {
        // Dr Receivable 500k / Cr Premium income 500k  (revenue accrual)
        postJe(LocalDate.of(2026, 3, 15), yearPeriodId, "Premium accrual",
            receivableAccountId, new BigDecimal("500000.00"), BigDecimal.ZERO,
            premiumIncomeId, BigDecimal.ZERO, new BigDecimal("500000.00"));
        // Dr Claims expense 150k / Cr Payable 150k
        postJe(LocalDate.of(2026, 6, 20), yearPeriodId, "Claim expense booking",
            claimsExpenseId, new BigDecimal("150000.00"), BigDecimal.ZERO,
            payableAccountId, BigDecimal.ZERO, new BigDecimal("150000.00"));

        Map<String, Object> payload = engine.computePayload(yearPeriodId);

        BigDecimal totalAssets = (BigDecimal) ((Map<?, ?>) payload.get("assets")).get("total");
        BigDecimal totalLiab = (BigDecimal) ((Map<?, ?>) payload.get("liabilities")).get("total");
        BigDecimal retained = (BigDecimal) ((Map<?, ?>) payload.get("equity")).get("retainedEarningsToDate");

        assertThat(totalAssets).as("receivable 500k on the asset side").isEqualByComparingTo("500000.00");
        assertThat(totalLiab).as("payable 150k on the liability side").isEqualByComparingTo("150000.00");
        assertThat(retained)
            .as("retainedEarningsToDate = income 500k − expense 150k = 350k")
            .isEqualByComparingTo("350000.00");

        // Asset (500k) = Liability (150k) + Equity (0) + RetainedEarnings (350k). BALANCED.
        assertThat(((Map<?, ?>) payload.get("balanceCheck")).get("balanced")).isEqualTo(true);
    }

    @Test
    @DisplayName("zero-balance accounts excluded from section lines")
    void zeroBalanceAccountsExcluded() {
        // Post Dr Bank 100 / Cr Share Capital 100, then a reversing pair Cr Bank 100 / Dr Share Capital 100
        postJe(LocalDate.of(2026, 1, 5), yearPeriodId, "First booking",
            bankAccountId, new BigDecimal("100.00"), BigDecimal.ZERO,
            shareCapitalId, BigDecimal.ZERO, new BigDecimal("100.00"));
        postJe(LocalDate.of(2026, 1, 6), yearPeriodId, "Reversal",
            shareCapitalId, new BigDecimal("100.00"), BigDecimal.ZERO,
            bankAccountId, BigDecimal.ZERO, new BigDecimal("100.00"));

        Map<String, Object> payload = engine.computePayload(yearPeriodId);

        List<?> assetLines = (List<?>) ((Map<?, ?>) payload.get("assets")).get("lines");
        List<?> equityLines = (List<?>) ((Map<?, ?>) payload.get("equity")).get("lines");
        assertThat(assetLines)
            .as("Bank nets to zero — should not appear as a line")
            .isEmpty();
        assertThat(equityLines)
            .as("Share capital nets to zero — should not appear")
            .isEmpty();
    }

    @Test
    @DisplayName("asOf cut-off — JEs after period_end are excluded")
    void asOfCutoff() {
        // In-year booking
        postJe(LocalDate.of(2026, 6, 1), yearPeriodId, "Mid-year",
            bankAccountId, new BigDecimal("500000.00"), BigDecimal.ZERO,
            shareCapitalId, BigDecimal.ZERO, new BigDecimal("500000.00"));
        // Out-of-year booking (after 2026-12-31, in afterPeriodId = Jan 2027)
        postJe(LocalDate.of(2027, 1, 15), afterPeriodId, "Next year",
            bankAccountId, new BigDecimal("999999.00"), BigDecimal.ZERO,
            shareCapitalId, BigDecimal.ZERO, new BigDecimal("999999.00"));

        Map<String, Object> payload = engine.computePayload(yearPeriodId);

        assertThat((BigDecimal) ((Map<?, ?>) payload.get("assets")).get("total"))
            .as("only the in-year ₦500k booking should appear")
            .isEqualByComparingTo("500000.00");
    }

    @Test
    @DisplayName("payload includes period metadata + asOf + balanceCheck shape")
    @SuppressWarnings("unchecked")
    void payloadStructure() {
        postJe(LocalDate.of(2026, 1, 5), yearPeriodId, "Cap",
            bankAccountId, new BigDecimal("1.00"), BigDecimal.ZERO,
            shareCapitalId, BigDecimal.ZERO, new BigDecimal("1.00"));

        Map<String, Object> payload = engine.computePayload(yearPeriodId);

        assertThat(payload).containsKeys(
            "submissionType", "period", "asOf", "generatedAt",
            "assets", "liabilities", "equity", "balanceCheck");
        assertThat(payload.get("asOf")).isEqualTo("2026-12-31");
        Map<String, Object> period = (Map<String, Object>) payload.get("period");
        assertThat(period).containsKeys("id", "start", "end");
        assertThat(period.get("start")).isEqualTo("2026-01-01");
        assertThat(period.get("end")).isEqualTo("2026-12-31");

        Map<String, Object> balanceCheck = (Map<String, Object>) payload.get("balanceCheck");
        assertThat(balanceCheck).containsKeys(
            "totalAssets", "totalLiabilitiesAndEquity", "balanced", "difference");
    }

    // ── Fixture helpers ────────────────────────────────────────────────────
    private UUID lookupAccount(String code) {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM chart_of_account WHERE code = ? AND deleted_at IS NULL",
            (rs, i) -> (UUID) rs.getObject(1), code);
    }

    /**
     * Posts a balanced two-line JE directly via JdbcTemplate (the engines
     * under test are read-only; we don't need to go through JournalEntryService).
     * The {@code periodId} must point at a fiscal_period whose date range
     * contains {@code businessDate} (this is what JournalEntryService would
     * enforce; here we just pass the right one explicitly).
     */
    private void postJe(LocalDate businessDate, UUID periodId, String narrative,
                         UUID drAccountId, BigDecimal drDebit, BigDecimal drCredit,
                         UUID crAccountId, BigDecimal crDebit, BigDecimal crCredit) {
        UUID jeId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO journal_entry (id, narrative, business_date, posting_date, " +
            "period_id, source_module, source_event_type, source_reference, " +
            "posted_by, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            jeId, narrative, businessDate, businessDate,
            periodId, "TEST", "TEST_POSTING", UUID.randomUUID().toString(),
            "test-poster", "test");
        jdbcTemplate.update(
            "INSERT INTO journal_entry_line " +
            "(journal_entry_id, line_no, account_id, debit_amount, credit_amount, created_by) " +
            "VALUES (?, 1, ?, ?, ?, ?)",
            jeId, drAccountId, drDebit, drCredit, "test");
        jdbcTemplate.update(
            "INSERT INTO journal_entry_line " +
            "(journal_entry_id, line_no, account_id, debit_amount, credit_amount, created_by) " +
            "VALUES (?, 2, ?, ?, ?, ?)",
            jeId, crAccountId, crDebit, crCredit, "test");
    }
}
