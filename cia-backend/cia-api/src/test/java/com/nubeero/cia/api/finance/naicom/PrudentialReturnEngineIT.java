package com.nubeero.cia.api.finance.naicom;

import com.nubeero.cia.finance.gl.TrialBalanceService;
import com.nubeero.cia.finance.naicom.NaicomSubmissionType;
import com.nubeero.cia.finance.naicom.PrudentialReturnEngine;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 4.4 IT for {@link PrudentialReturnEngine}. Seeds a small set of JEs
 * across asset / liability / equity / income / expense accounts (using real
 * COA codes from V32), then verifies the engine projects them into the
 * NAICOM prudential-return shape correctly.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Empty period — every section zero; solvency ratio null (no
 *       premium to compute against, not divide-by-zero)</li>
 *   <li>Capital injection + premium written — solvent: ratio &gt; 1.0,
 *       solvent=true, all balance-sheet figures populated</li>
 *   <li>Premium without enough capital — insolvent: ratio &lt; 1.0,
 *       solvent=false</li>
 *   <li>Investment + reserve subtotals — `investments` (12xx ASSETs) +
 *       `premiumReserves` (21xx LIABILITYs) sum the right rows</li>
 *   <li>Period vs cumulative semantic — BS figures cumulative since
 *       inception, income figures period-bounded</li>
 *   <li>Payload structure — notes field present, solvency dictionary
 *       complete</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    TrialBalanceService.class,
    PrudentialReturnEngine.class
})
class PrudentialReturnEngineIT {

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

    @Autowired private PrudentialReturnEngine engine;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID quarterPeriodId;
    private UUID priorQuarterPeriodId;
    private final LocalDate quarterStart = LocalDate.of(2026, 7, 1);
    private final LocalDate quarterEnd = LocalDate.of(2026, 9, 30);
    private final LocalDate priorStart = LocalDate.of(2026, 4, 1);
    private final LocalDate priorEnd = LocalDate.of(2026, 6, 30);

    // V32 account codes resolved at @BeforeEach
    private UUID bankAccountId;        // 1110 — Bank (ASSET)
    private UUID fvplDebtAccountId;    // 1220 — FVPL Debt securities (ASSET, investment 12xx)
    private UUID amortisedCostAccountId; // 1250 — Amortised cost Debt securities (ASSET, 12xx)
    private UUID lrcBelAccountId;      // 2110 — LRC BEL (LIABILITY, reserve 21xx)
    private UUID licOcrAccountId;      // 2140 — LIC OCR (LIABILITY, reserve 21xx)
    private UUID payableAccountId;     // 2310 — Payable (LIABILITY, NOT a reserve)
    private UUID shareCapitalId;       // 3110 — Share capital (EQUITY)
    private UUID lrcReleaseIncomeId;   // 4110 — Insurance revenue LRC release (INCOME 41xx)
    private UUID claimsExpenseId;      // 5110 — Incurred claims (EXPENSE 51xx)

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM journal_entry_line");
        jdbcTemplate.update("DELETE FROM journal_entry");
        jdbcTemplate.update("DELETE FROM fiscal_period");
        jdbcTemplate.update("DELETE FROM fiscal_year");

        UUID fyId = UUID.randomUUID();
        quarterPeriodId = UUID.randomUUID();
        priorQuarterPeriodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            fyId, "FY-PR-2026",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ACTIVE", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            priorQuarterPeriodId, fyId, "QUARTER", priorStart, priorEnd, "HARD_CLOSED", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            quarterPeriodId, fyId, "QUARTER", quarterStart, quarterEnd, "HARD_CLOSED", "test");

        bankAccountId = lookupAccount("1110");
        fvplDebtAccountId = lookupAccount("1220");
        amortisedCostAccountId = lookupAccount("1250");
        lrcBelAccountId = lookupAccount("2110");
        licOcrAccountId = lookupAccount("2140");
        payableAccountId = lookupAccount("2310");
        shareCapitalId = lookupAccount("3110");
        lrcReleaseIncomeId = lookupAccount("4110");
        claimsExpenseId = lookupAccount("5110");
    }

    @Test
    @DisplayName("empty period — all figures zero; solvency ratio null (no premium → undefined)")
    void emptyPeriod() {
        Map<String, Object> payload = engine.computePayload(quarterPeriodId);

        assertThat(payload.get("submissionType"))
            .isEqualTo(NaicomSubmissionType.PRUDENTIAL_RETURN.name());

        Map<?, ?> bs = (Map<?, ?>) payload.get("balanceSheet");
        assertThat((BigDecimal) bs.get("totalAssets")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat((BigDecimal) bs.get("totalLiabilities")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat((BigDecimal) bs.get("totalEquity")).isEqualByComparingTo(BigDecimal.ZERO);

        Map<?, ?> solvency = (Map<?, ?>) payload.get("solvency");
        assertThat(solvency.get("solvencyRatio"))
            .as("zero premium ⇒ null ratio (not divide-by-zero, not infinity)")
            .isNull();
        assertThat(solvency.get("solvent"))
            .as("zero premium ⇒ null verdict (cannot judge solvency without exposure)")
            .isNull();
    }

    @Test
    @DisplayName("capital injection + premium written — solvent ratio > 1.0")
    void solventScenario() {
        // ── Q1: opening capital injection (out-of-period, cumulative since inception)
        postJe(LocalDate.of(2026, 1, 5), priorQuarterPeriodId, "Capital",
            bankAccountId, new BigDecimal("100000000.00"), BigDecimal.ZERO,
            shareCapitalId, BigDecimal.ZERO, new BigDecimal("100000000.00"));

        // ── Q3 (the reporting period): premium written ₦20M
        postJe(LocalDate.of(2026, 8, 15), quarterPeriodId, "Premium accrual",
            bankAccountId, new BigDecimal("20000000.00"), BigDecimal.ZERO,
            lrcReleaseIncomeId, BigDecimal.ZERO, new BigDecimal("20000000.00"));

        Map<String, Object> payload = engine.computePayload(quarterPeriodId);

        Map<?, ?> bs = (Map<?, ?>) payload.get("balanceSheet");
        assertThat((BigDecimal) bs.get("totalAssets"))
            .as("100M opening capital + 20M premium = 120M cash")
            .isEqualByComparingTo("120000000.00");
        assertThat((BigDecimal) bs.get("shareholdersFunds"))
            .as("share capital alone")
            .isEqualByComparingTo("100000000.00");
        assertThat((BigDecimal) bs.get("retainedEarningsToDate"))
            .as("20M premium income, no expenses")
            .isEqualByComparingTo("20000000.00");
        assertThat((BigDecimal) bs.get("totalEquity"))
            .as("share capital + retained earnings")
            .isEqualByComparingTo("120000000.00");

        Map<?, ?> income = (Map<?, ?>) payload.get("income");
        assertThat((BigDecimal) income.get("periodPremiumWritten"))
            .as("only Q3 premium counts; the capital injection was Q1")
            .isEqualByComparingTo("20000000.00");

        Map<?, ?> solvency = (Map<?, ?>) payload.get("solvency");
        assertThat((BigDecimal) solvency.get("availableCapital"))
            .isEqualByComparingTo("120000000.00");
        assertThat((BigDecimal) solvency.get("minimumRequiredCapital"))
            .as("15% of 20M = 3M")
            .isEqualByComparingTo("3000000.00");
        assertThat((BigDecimal) solvency.get("solvencyRatio"))
            .as("120M / 3M = 40.0")
            .isEqualByComparingTo("40.0000");
        assertThat(solvency.get("solvent")).isEqualTo(true);
    }

    @Test
    @DisplayName("low capital + high premium — insolvent ratio < 1.0")
    void insolventScenario() {
        // Tiny capital injection
        postJe(LocalDate.of(2026, 1, 5), priorQuarterPeriodId, "Cap",
            bankAccountId, new BigDecimal("1000000.00"), BigDecimal.ZERO,
            shareCapitalId, BigDecimal.ZERO, new BigDecimal("1000000.00"));

        // Premium written that exceeds 15% of capital easily
        postJe(LocalDate.of(2026, 8, 15), quarterPeriodId, "Premium",
            bankAccountId, new BigDecimal("50000000.00"), BigDecimal.ZERO,
            lrcReleaseIncomeId, BigDecimal.ZERO, new BigDecimal("50000000.00"));

        Map<String, Object> payload = engine.computePayload(quarterPeriodId);
        Map<?, ?> solvency = (Map<?, ?>) payload.get("solvency");

        BigDecimal ratio = (BigDecimal) solvency.get("solvencyRatio");
        // 51M equity / (15% × 50M = 7.5M) = 6.8 — still solvent.
        // Need to drive ratio below 1.0: equity 1M + 50M income = 51M;
        // required 7.5M. Hmm.
        // Easier: counter-balance equity via an EXPENSE.
        // Wait — I haven't yet thought through. Let me re-do:
        //  - capital: Dr Bank 1M / Cr Share Capital 1M → equity = 1M
        //  - premium: Dr Bank 50M / Cr Income 50M → retainedEarnings += 50M, equity = 51M
        //  - Need to push equity below required (7.5M).
        // So I need to add 44M+ in expenses to bring equity below 7.5M.

        // Drive equity down with a big expense
        postJe(LocalDate.of(2026, 8, 20), quarterPeriodId, "Claims",
            claimsExpenseId, new BigDecimal("45000000.00"), BigDecimal.ZERO,
            payableAccountId, BigDecimal.ZERO, new BigDecimal("45000000.00"));

        payload = engine.computePayload(quarterPeriodId);
        solvency = (Map<?, ?>) payload.get("solvency");

        // equity = share 1M + (income 50M − expense 45M) = 6M
        // required = 15% × 50M = 7.5M
        // ratio = 6M / 7.5M = 0.8 → insolvent
        ratio = (BigDecimal) solvency.get("solvencyRatio");
        assertThat(ratio).isLessThan(BigDecimal.ONE);
        assertThat(solvency.get("solvent")).isEqualTo(false);
        assertThat((BigDecimal) solvency.get("availableCapital"))
            .as("1M + 50M income − 45M expense = 6M")
            .isEqualByComparingTo("6000000.00");
    }

    @Test
    @DisplayName("investment + reserve subtotals — 12xx and 21xx accounts roll up correctly")
    void investmentAndReserveSubtotals() {
        // Investment positions: FVPL Debt + Amortised Cost Debt
        postJe(LocalDate.of(2026, 8, 1), quarterPeriodId, "Buy FVPL bond",
            fvplDebtAccountId, new BigDecimal("5000000.00"), BigDecimal.ZERO,
            bankAccountId, BigDecimal.ZERO, new BigDecimal("5000000.00"));
        postJe(LocalDate.of(2026, 8, 2), quarterPeriodId, "Buy AC bond",
            amortisedCostAccountId, new BigDecimal("3000000.00"), BigDecimal.ZERO,
            bankAccountId, BigDecimal.ZERO, new BigDecimal("3000000.00"));

        // Reserves: LRC BEL + LIC OCR
        postJe(LocalDate.of(2026, 8, 5), quarterPeriodId, "LRC accrual",
            bankAccountId, new BigDecimal("4000000.00"), BigDecimal.ZERO,
            lrcBelAccountId, BigDecimal.ZERO, new BigDecimal("4000000.00"));
        postJe(LocalDate.of(2026, 8, 6), quarterPeriodId, "LIC accrual",
            bankAccountId, new BigDecimal("2000000.00"), BigDecimal.ZERO,
            licOcrAccountId, BigDecimal.ZERO, new BigDecimal("2000000.00"));

        // Non-reserve liability — should NOT count as premium reserves
        postJe(LocalDate.of(2026, 8, 7), quarterPeriodId, "Vendor payable",
            bankAccountId, new BigDecimal("500000.00"), BigDecimal.ZERO,
            payableAccountId, BigDecimal.ZERO, new BigDecimal("500000.00"));

        Map<String, Object> payload = engine.computePayload(quarterPeriodId);
        Map<?, ?> bs = (Map<?, ?>) payload.get("balanceSheet");

        assertThat((BigDecimal) bs.get("investments"))
            .as("12xx accounts sum = 5M + 3M = 8M")
            .isEqualByComparingTo("8000000.00");
        assertThat((BigDecimal) bs.get("premiumReserves"))
            .as("21xx accounts sum = 4M + 2M = 6M (excludes payable 23xx)")
            .isEqualByComparingTo("6000000.00");
        assertThat((BigDecimal) bs.get("totalLiabilities"))
            .as("all liabilities = reserves 6M + payable 500k = 6.5M")
            .isEqualByComparingTo("6500000.00");
    }

    @Test
    @DisplayName("period vs cumulative — BS cumulative since inception, income period-bounded")
    void periodVsCumulativeSemantic() {
        // Q1 premium (out-of-period for Q3)
        postJe(LocalDate.of(2026, 5, 1), priorQuarterPeriodId, "Q2 premium",
            bankAccountId, new BigDecimal("10000000.00"), BigDecimal.ZERO,
            lrcReleaseIncomeId, BigDecimal.ZERO, new BigDecimal("10000000.00"));
        // Q3 premium (in-period)
        postJe(LocalDate.of(2026, 8, 1), quarterPeriodId, "Q3 premium",
            bankAccountId, new BigDecimal("4000000.00"), BigDecimal.ZERO,
            lrcReleaseIncomeId, BigDecimal.ZERO, new BigDecimal("4000000.00"));

        Map<String, Object> payload = engine.computePayload(quarterPeriodId);

        Map<?, ?> bs = (Map<?, ?>) payload.get("balanceSheet");
        assertThat((BigDecimal) bs.get("totalAssets"))
            .as("BS is cumulative — sees both Q2 and Q3 cash")
            .isEqualByComparingTo("14000000.00");
        assertThat((BigDecimal) bs.get("retainedEarningsToDate"))
            .as("retained earnings cumulative since inception")
            .isEqualByComparingTo("14000000.00");

        Map<?, ?> income = (Map<?, ?>) payload.get("income");
        assertThat((BigDecimal) income.get("periodPremiumWritten"))
            .as("income figure is PERIOD-bounded — Q3 only, NOT YTD")
            .isEqualByComparingTo("4000000.00");
    }

    @Test
    @DisplayName("payload structure — notes field present + solvency dictionary complete")
    void payloadStructure() {
        postJe(LocalDate.of(2026, 7, 5), quarterPeriodId, "Cap",
            bankAccountId, new BigDecimal("1.00"), BigDecimal.ZERO,
            shareCapitalId, BigDecimal.ZERO, new BigDecimal("1.00"));

        Map<String, Object> payload = engine.computePayload(quarterPeriodId);

        assertThat(payload).containsKeys(
            "submissionType", "period", "asOf", "generatedAt",
            "balanceSheet", "income", "solvency", "notes");
        assertThat((String) payload.get("notes"))
            .contains("v1 simplified formula")
            .contains("NAICOM Operational Guideline")
            .contains("v2");

        @SuppressWarnings("unchecked")
        Map<String, Object> solvency = (Map<String, Object>) payload.get("solvency");
        assertThat(solvency).containsKeys(
            "availableCapital", "minimumRequiredCapital",
            "minimumCapitalPercent", "solvencyRatio", "solvent");
        assertThat((BigDecimal) solvency.get("minimumCapitalPercent"))
            .as("v1 uses 15%")
            .isEqualByComparingTo("15.00");
    }

    // ── Fixture helpers ────────────────────────────────────────────────────
    private UUID lookupAccount(String code) {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM chart_of_account WHERE code = ? AND deleted_at IS NULL",
            (rs, i) -> (UUID) rs.getObject(1), code);
    }

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
