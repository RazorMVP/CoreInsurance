package com.nubeero.cia.api.finance.naicom;

import com.nubeero.cia.finance.ifrs9.Ifrs9MovementAnalysisService;
import com.nubeero.cia.finance.naicom.Ifrs9DisclosureEngine;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 4.7 IT for {@link Ifrs9DisclosureEngine}. Seeds
 * {@code investment_holding} + {@code investment_carrying_value} rows
 * directly and asserts the disclosure payload — the upstream
 * {@link Ifrs9MovementAnalysisService} is independently IT-covered by
 * its own slice, so this fixture skips running the IFRS-9 engines and
 * focuses on the adapter contract.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Empty period — envelope populated, totals zero, byHolding empty.</li>
 *   <li>Single AC holding — totals reflect interest income + coupon.</li>
 *   <li>Mixed classification — totals sum across AC + FVPL + FVOCI_DEBT.</li>
 *   <li>byHolding ordered by classification, security_name.</li>
 *   <li>Holding dimensions preserved (isin, issuer, ecl_stage, maturity).</li>
 *   <li>premiumReceivableEcl section populated from JE aggregates on 1340.</li>
 *   <li>Missing period throws FiscalPeriodNotFoundException.</li>
 *   <li>Payload envelope shape matches NAICOM contract.</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    Ifrs9MovementAnalysisService.class,
    Ifrs9DisclosureEngine.class
})
class Ifrs9DisclosureEngineIT {

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

    @Autowired private Ifrs9DisclosureEngine engine;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID fy2026PeriodId;
    private final LocalDate periodStart = LocalDate.of(2026, 1, 1);
    private final LocalDate periodEnd = LocalDate.of(2026, 12, 31);

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM investment_carrying_value");
        jdbcTemplate.update("DELETE FROM investment_classification_history");
        jdbcTemplate.update("DELETE FROM investment_holding");
        jdbcTemplate.update("DELETE FROM fiscal_period");
        jdbcTemplate.update("DELETE FROM fiscal_year");

        UUID fyId = UUID.randomUUID();
        fy2026PeriodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            fyId, "FY-IFRS9-2026", periodStart, periodEnd, "ACTIVE", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            fy2026PeriodId, fyId, "YEAR", periodStart, periodEnd, "HARD_CLOSED", "test");
    }

    @Test
    @DisplayName("empty period — envelope populated, totals zero, byHolding empty")
    void emptyPeriod() {
        Map<String, Object> payload = engine.computePayload(fy2026PeriodId);

        assertThat(payload.get("submissionType"))
            .isEqualTo(NaicomSubmissionType.IFRS9_DISCLOSURE.name());

        @SuppressWarnings("unchecked")
        Map<String, Object> investments = (Map<String, Object>) payload.get("investments");
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) investments.get("totals");
        assertThat((BigDecimal) totals.get("closingBalance")).isEqualByComparingTo("0.00");
        assertThat((List<?>) investments.get("byHolding")).isEmpty();

        @SuppressWarnings("unchecked")
        Map<String, Object> grand = (Map<String, Object>) payload.get("totals");
        assertThat(grand.get("holdingCount")).isEqualTo(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> premium = (Map<String, Object>) payload.get("premiumReceivableEcl");
        assertThat((BigDecimal) premium.get("openingAllowance")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) premium.get("closingAllowance")).isEqualByComparingTo("0.00");
        assertThat(premium.get("direction")).isEqualTo("NO_CHANGE");
    }

    @Test
    @DisplayName("single AC holding — totals reflect interest income + coupon")
    void singleAcHolding() {
        UUID holdingId = seedHolding("FGNB2030", "FGN Bond 2030", "FGN",
            "DEBT", "AMORTISED_COST", "ACTIVE",
            LocalDate.of(2025, 1, 15), "1000000.00",
            "1000000.00", "0.12000", LocalDate.of(2030, 1, 15), 1);
        seedCarryingValue(holdingId, "1000000.00",
            "60000.00", "30000.00",
            "0.00", "0.00",
            "1000.00", "0.00", "0.00",
            "1029000.00", null, 1);

        Map<String, Object> payload = engine.computePayload(fy2026PeriodId);

        @SuppressWarnings("unchecked")
        Map<String, Object> investments = (Map<String, Object>) payload.get("investments");
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) investments.get("totals");
        assertThat((BigDecimal) totals.get("effectiveInterestIncome")).isEqualByComparingTo("60000.00");
        assertThat((BigDecimal) totals.get("couponReceived")).isEqualByComparingTo("30000.00");
        assertThat((BigDecimal) totals.get("eclMovement")).isEqualByComparingTo("1000.00");
        assertThat((BigDecimal) totals.get("closingBalance")).isEqualByComparingTo("1029000.00");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byHolding = (List<Map<String, Object>>) investments.get("byHolding");
        assertThat(byHolding).hasSize(1);
        Map<String, Object> h = byHolding.get(0);
        assertThat(h.get("isin")).isEqualTo("FGNB2030");
        assertThat(h.get("classification")).isEqualTo("AMORTISED_COST");
        assertThat(h.get("eclStage")).isEqualTo(1);
    }

    @Test
    @DisplayName("mixed classifications — totals sum across AC + FVPL + FVOCI_DEBT")
    void mixedClassifications() {
        UUID acId = seedHolding("AC01", "AC Bond", "FGN",
            "DEBT", "AMORTISED_COST", "ACTIVE",
            LocalDate.of(2025, 1, 1), "500000.00",
            "500000.00", "0.10000", LocalDate.of(2030, 1, 1), 1);
        UUID fvpId = seedHolding("FVPL01", "Equity Trade", "MTN",
            "EQUITY", "FVPL", "ACTIVE",
            LocalDate.of(2025, 6, 1), "200000.00",
            null, null, null, null);
        UUID fvOciId = seedHolding("FVOCID01", "Bond OCI", "Dangote",
            "DEBT", "FVOCI_DEBT", "ACTIVE",
            LocalDate.of(2025, 3, 1), "300000.00",
            "300000.00", "0.08000", LocalDate.of(2029, 3, 1), 1);

        seedCarryingValue(acId, "500000.00",
            "25000.00", "10000.00", "0.00", "0.00",
            "500.00", "0.00", "0.00",
            "534500.00", null, 1);
        seedCarryingValue(fvpId, "200000.00",
            "0.00", "0.00", "15000.00", "0.00",
            "0.00", "0.00", "0.00",
            "215000.00", "215000.00", null);
        seedCarryingValue(fvOciId, "300000.00",
            "20000.00", "12000.00", "0.00", "5000.00",
            "300.00", "0.00", "0.00",
            "337000.00", "337000.00", 1);

        Map<String, Object> payload = engine.computePayload(fy2026PeriodId);

        @SuppressWarnings("unchecked")
        Map<String, Object> investments = (Map<String, Object>) payload.get("investments");
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) investments.get("totals");
        // Closing 534500 + 215000 + 337000 = 1,086,500
        assertThat((BigDecimal) totals.get("closingBalance")).isEqualByComparingTo("1086500.00");
        // Interest 25000 + 20000 = 45000 (FVPL has no EIR)
        assertThat((BigDecimal) totals.get("effectiveInterestIncome")).isEqualByComparingTo("45000.00");
        // FV P&L only on FVPL = 15000
        assertThat((BigDecimal) totals.get("fairValueChangePnl")).isEqualByComparingTo("15000.00");
        // FV OCI only on FVOCI_DEBT = 5000
        assertThat((BigDecimal) totals.get("fairValueChangeOci")).isEqualByComparingTo("5000.00");

        @SuppressWarnings("unchecked")
        Map<String, Object> grand = (Map<String, Object>) payload.get("totals");
        assertThat(grand.get("holdingCount")).isEqualTo(3);
    }

    @Test
    @DisplayName("byHolding ordered by classification ASC, security_name ASC")
    void byHoldingOrdering() {
        UUID zAc = seedHolding("Z-AC", "Z Bond", "Issuer1",
            "DEBT", "AMORTISED_COST", "ACTIVE",
            LocalDate.of(2025, 1, 1), "100000.00",
            "100000.00", "0.10000", LocalDate.of(2030, 1, 1), 1);
        UUID aFvpl = seedHolding("A-FVPL", "A Equity", "Issuer2",
            "EQUITY", "FVPL", "ACTIVE",
            LocalDate.of(2025, 1, 1), "100000.00",
            null, null, null, null);
        UUID aAc = seedHolding("A-AC", "A Bond", "Issuer3",
            "DEBT", "AMORTISED_COST", "ACTIVE",
            LocalDate.of(2025, 1, 1), "100000.00",
            "100000.00", "0.10000", LocalDate.of(2030, 1, 1), 1);

        seedCarryingValue(zAc, "100000.00", "5000.00", "0.00", "0.00", "0.00",
            "0.00", "0.00", "0.00", "105000.00", null, 1);
        seedCarryingValue(aFvpl, "100000.00", "0.00", "0.00", "2000.00", "0.00",
            "0.00", "0.00", "0.00", "102000.00", "102000.00", null);
        seedCarryingValue(aAc, "100000.00", "5000.00", "0.00", "0.00", "0.00",
            "0.00", "0.00", "0.00", "105000.00", null, 1);

        Map<String, Object> payload = engine.computePayload(fy2026PeriodId);

        @SuppressWarnings("unchecked")
        Map<String, Object> investments = (Map<String, Object>) payload.get("investments");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byHolding = (List<Map<String, Object>>) investments.get("byHolding");
        // Order: AMORTISED_COST(A Bond), AMORTISED_COST(Z Bond), FVPL(A Equity)
        assertThat(byHolding)
            .extracting(h -> h.get("securityName"))
            .containsExactly("A Bond", "Z Bond", "A Equity");
    }

    @Test
    @DisplayName("holding dimensions preserved (isin, issuer, ecl_stage, maturity)")
    void holdingDimensions() {
        UUID id = seedHolding("DIM01", "Dim Bond", "Dim Issuer",
            "DEBT", "AMORTISED_COST", "ACTIVE",
            LocalDate.of(2025, 6, 1), "500000.00",
            "500000.00", "0.10000", LocalDate.of(2030, 6, 1), 2);
        seedCarryingValue(id, "500000.00", "10000.00", "5000.00", "0.00", "0.00",
            "200.00", "0.00", "0.00", "514800.00", null, 2);

        Map<String, Object> payload = engine.computePayload(fy2026PeriodId);

        @SuppressWarnings("unchecked")
        Map<String, Object> investments = (Map<String, Object>) payload.get("investments");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byHolding = (List<Map<String, Object>>) investments.get("byHolding");
        Map<String, Object> h = byHolding.get(0);
        assertThat(h.get("isin")).isEqualTo("DIM01");
        assertThat(h.get("issuer")).isEqualTo("Dim Issuer");
        assertThat(h.get("maturityDate")).isEqualTo("2030-06-01");
        assertThat(h.get("currencyCode")).isEqualTo("NGN");
        assertThat(h.get("eclStage")).isEqualTo(2);
        assertThat(h.get("holdingStatus")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("premiumReceivableEcl section populated from JE aggregates on 1340")
    void premiumReceivableEclFromJe() {
        // Seed an allowance increase via JE on account 1340 within the period.
        // business_date must be <= posting_date (now); pick March 15 in-period.
        seedPremiumEclJe(LocalDate.of(2026, 3, 15), new BigDecimal("250000.00"));

        Map<String, Object> payload = engine.computePayload(fy2026PeriodId);

        @SuppressWarnings("unchecked")
        Map<String, Object> premium = (Map<String, Object>) payload.get("premiumReceivableEcl");
        assertThat((BigDecimal) premium.get("openingAllowance")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) premium.get("closingAllowance")).isEqualByComparingTo("250000.00");
        assertThat((BigDecimal) premium.get("periodMovement")).isEqualByComparingTo("250000.00");
        assertThat(premium.get("direction")).isEqualTo("INCREASE");
    }

    @Test
    @DisplayName("missing fiscal period throws FiscalPeriodNotFoundException")
    void unknownPeriodThrows() {
        assertThatThrownBy(() -> engine.computePayload(UUID.randomUUID()))
            .isInstanceOf(com.nubeero.cia.finance.gl.FiscalPeriodNotFoundException.class);
    }

    @Test
    @DisplayName("payload envelope keys + notes content")
    void payloadEnvelope() {
        Map<String, Object> payload = engine.computePayload(fy2026PeriodId);
        assertThat(payload.keySet()).containsExactly(
            "submissionType", "period", "generatedAt",
            "investments", "premiumReceivableEcl", "totals", "notes");
        assertThat(payload.get("notes")).asString()
            .contains("ifrs9_investment_movement_analysis")
            .contains("V40");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID seedHolding(String isin, String name, String issuer,
                              String assetType, String classification, String status,
                              LocalDate acqDate, String acqCost,
                              String faceValue, String couponRate, LocalDate maturity,
                              Integer eclStage) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO investment_holding (id, isin, security_name, issuer, " +
            "asset_type, classification, acquisition_date, acquisition_cost, " +
            "face_value, coupon_rate, maturity_date, currency_code, status, " +
            "sppi_test_passed, ecl_stage, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, isin, name, issuer,
            assetType, classification, acqDate, new BigDecimal(acqCost),
            faceValue == null ? null : new BigDecimal(faceValue),
            couponRate == null ? null : new BigDecimal(couponRate),
            maturity,
            "NGN", status,
            assetType.equals("DEBT") ? Boolean.TRUE : null,
            eclStage,
            "test");
        return id;
    }

    private void seedCarryingValue(UUID holdingId,
                                    String opening,
                                    String effectiveInterest, String coupon,
                                    String fvPnl, String fvOci,
                                    String eclMovement, String impairment, String disposals,
                                    String closing, String closingFairValue, Integer eclStage) {
        jdbcTemplate.update(
            "INSERT INTO investment_carrying_value (id, holding_id, period_id, " +
            "opening_balance, effective_interest_income, coupon_received, " +
            "fair_value_change_pnl, fair_value_change_oci, " +
            "ecl_movement, impairment_loss, disposals, closing_balance, " +
            "closing_fair_value, ecl_stage, currency_code, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), holdingId, fy2026PeriodId,
            new BigDecimal(opening), new BigDecimal(effectiveInterest), new BigDecimal(coupon),
            new BigDecimal(fvPnl), new BigDecimal(fvOci),
            new BigDecimal(eclMovement), new BigDecimal(impairment), new BigDecimal(disposals),
            new BigDecimal(closing),
            closingFairValue == null ? null : new BigDecimal(closingFairValue),
            eclStage, "NGN", "test");
    }

    /**
     * Seeds a journal_entry + journal_entry_line on account 1340 (credit, =
     * allowance increase). PREMIUM_RECEIVABLE_ECL source_event_type matches
     * Ifrs9MovementAnalysisService's filter.
     */
    private void seedPremiumEclJe(LocalDate businessDate, BigDecimal creditAmount) {
        // Resolve the 1340 account id from the seeded COA (V32).
        UUID accountId = jdbcTemplate.queryForObject(
            "SELECT id FROM chart_of_account WHERE code = '1340'",
            UUID.class);
        // Resolve any income/expense account for the debit side — 5350 ECL expense.
        UUID expenseAccountId = jdbcTemplate.queryForObject(
            "SELECT id FROM chart_of_account WHERE code = '5350'",
            UUID.class);

        UUID jeId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO journal_entry (id, business_date, posting_date, period_id, " +
            "source_module, source_event_type, source_reference, narrative, " +
            "posted_by, status, created_by) " +
            "VALUES (?, ?, now(), ?, ?, ?, ?, ?, ?, ?, ?)",
            jeId, businessDate, fy2026PeriodId,
            "IFRS9", "PREMIUM_RECEIVABLE_ECL", "test-ref-1",
            "Premium-receivable ECL provision",
            "test", "POSTED", "test");
        // Debit 5350 (expense), Credit 1340 (allowance)
        jdbcTemplate.update(
            "INSERT INTO journal_entry_line (id, journal_entry_id, line_no, account_id, " +
            "debit_amount, credit_amount, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), jeId, 1, expenseAccountId,
            creditAmount, BigDecimal.ZERO, "test");
        jdbcTemplate.update(
            "INSERT INTO journal_entry_line (id, journal_entry_id, line_no, account_id, " +
            "debit_amount, credit_amount, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), jeId, 2, accountId,
            BigDecimal.ZERO, creditAmount, "test");
    }
}
