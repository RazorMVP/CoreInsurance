package com.nubeero.cia.api.finance.naicom;

import com.nubeero.cia.finance.naicom.InvestmentStatementEngine;
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
 * Slice 4.7 IT for {@link InvestmentStatementEngine} (NAICOM N08).
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Empty period — envelope populated, totals zero, byHolding empty.</li>
 *   <li>Single active holding with carrying value — all pivots populated.</li>
 *   <li>Active holding with NO carrying value — listed, but figures null.</li>
 *   <li>MATURED / SOLD / IMPAIRED holdings — excluded from snapshot.</li>
 *   <li>Mixed asset types and classifications — byClassification +
 *       byAssetType pivots aggregate correctly.</li>
 *   <li>byHolding ordered by classification ASC, security_name ASC.</li>
 *   <li>Missing fiscal period throws FiscalPeriodNotFoundException.</li>
 *   <li>Payload envelope shape matches NAICOM contract.</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    InvestmentStatementEngine.class
})
class InvestmentStatementEngineIT {

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
        registry.add("spring.flyway.target", () -> "43");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired private InvestmentStatementEngine engine;
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
            fyId, "FY-N08-2026", periodStart, periodEnd, "ACTIVE", "test");
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
            .isEqualTo(NaicomSubmissionType.INVESTMENT_STATEMENT.name());
        assertThat(payload.get("asOf")).isEqualTo(periodEnd.toString());
        assertThat((List<?>) payload.get("byHolding")).isEmpty();
        assertThat((List<?>) payload.get("byClassification")).isEmpty();
        assertThat((List<?>) payload.get("byAssetType")).isEmpty();

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) payload.get("totals");
        assertThat(totals.get("holdingCount")).isEqualTo(0);
        assertThat((BigDecimal) totals.get("acquisitionCost")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) totals.get("carryingValue")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) totals.get("fairValue")).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("single ACTIVE holding with carrying value — all pivots populated")
    void singleActiveHoldingWithMeasurement() {
        UUID id = seedHolding("FGNB2030", "FGN Bond 2030", "FGN",
            "DEBT", "AMORTISED_COST", "ACTIVE",
            LocalDate.of(2025, 1, 15), "1000000.00",
            "1000000.00", "0.12000", LocalDate.of(2030, 1, 15), 1);
        seedCarryingValue(id, "1029000.00", null);

        Map<String, Object> payload = engine.computePayload(fy2026PeriodId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byHolding = (List<Map<String, Object>>) payload.get("byHolding");
        assertThat(byHolding).hasSize(1);
        Map<String, Object> h = byHolding.get(0);
        assertThat(h.get("isin")).isEqualTo("FGNB2030");
        assertThat((BigDecimal) h.get("acquisitionCost")).isEqualByComparingTo("1000000.00");
        assertThat((BigDecimal) h.get("carryingValue")).isEqualByComparingTo("1029000.00");
        assertThat(h.get("fairValue")).isNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byClass = (List<Map<String, Object>>) payload.get("byClassification");
        assertThat(byClass).hasSize(1);
        assertThat(byClass.get(0).get("classification")).isEqualTo("AMORTISED_COST");
        assertThat(byClass.get(0).get("holdingCount")).isEqualTo(1);
        assertThat((BigDecimal) byClass.get(0).get("carryingValue")).isEqualByComparingTo("1029000.00");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byAsset = (List<Map<String, Object>>) payload.get("byAssetType");
        assertThat(byAsset).hasSize(1);
        assertThat(byAsset.get(0).get("assetType")).isEqualTo("DEBT");
    }

    @Test
    @DisplayName("ACTIVE holding with no carrying-value row — listed with null figures")
    void activeHoldingWithNoMeasurement() {
        seedHolding("UNMEASURED", "Unmeasured Bond", "Bank ABC",
            "DEBT", "AMORTISED_COST", "ACTIVE",
            LocalDate.of(2025, 12, 1), "100000.00",
            "100000.00", "0.10000", LocalDate.of(2030, 12, 1), 1);
        // No carrying value row seeded for this period.

        Map<String, Object> payload = engine.computePayload(fy2026PeriodId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byHolding = (List<Map<String, Object>>) payload.get("byHolding");
        assertThat(byHolding).hasSize(1);
        Map<String, Object> h = byHolding.get(0);
        assertThat(h.get("isin")).isEqualTo("UNMEASURED");
        assertThat((BigDecimal) h.get("acquisitionCost")).isEqualByComparingTo("100000.00");
        assertThat(h.get("carryingValue"))
            .as("null when no carrying value row exists for this period")
            .isNull();
        assertThat(h.get("fairValue")).isNull();

        // Aggregates still include this holding (acquisition cost counted; carrying/fair null → 0).
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) payload.get("totals");
        assertThat(totals.get("holdingCount")).isEqualTo(1);
        assertThat((BigDecimal) totals.get("acquisitionCost")).isEqualByComparingTo("100000.00");
        assertThat((BigDecimal) totals.get("carryingValue")).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("MATURED / SOLD / IMPAIRED holdings excluded from snapshot")
    void inactiveHoldingsExcluded() {
        seedHolding("ACTIVE-1", "Active", "Issuer1",
            "DEBT", "AMORTISED_COST", "ACTIVE",
            LocalDate.of(2025, 1, 1), "100000.00",
            "100000.00", "0.10000", LocalDate.of(2030, 1, 1), 1);
        seedHolding("MATURED-1", "Matured", "Issuer2",
            "DEBT", "AMORTISED_COST", "MATURED",
            LocalDate.of(2020, 1, 1), "100000.00",
            "100000.00", "0.10000", LocalDate.of(2025, 12, 31), 1);
        seedHolding("SOLD-1", "Sold", "Issuer3",
            "EQUITY", "FVPL", "SOLD",
            LocalDate.of(2024, 1, 1), "100000.00",
            null, null, null, null);
        seedHolding("IMPAIRED-1", "Impaired", "Issuer4",
            "DEBT", "AMORTISED_COST", "IMPAIRED",
            LocalDate.of(2022, 1, 1), "100000.00",
            "100000.00", "0.10000", LocalDate.of(2032, 1, 1), 3);

        Map<String, Object> payload = engine.computePayload(fy2026PeriodId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byHolding = (List<Map<String, Object>>) payload.get("byHolding");
        assertThat(byHolding)
            .as("only ACTIVE holdings included; MATURED/SOLD/IMPAIRED excluded")
            .hasSize(1);
        assertThat(byHolding.get(0).get("isin")).isEqualTo("ACTIVE-1");
    }

    @Test
    @DisplayName("mixed asset types and classifications — byClassification + byAssetType pivots correct")
    void mixedPivots() {
        UUID ac1 = seedHolding("AC01", "AC One", "Issuer1",
            "DEBT", "AMORTISED_COST", "ACTIVE",
            LocalDate.of(2025, 1, 1), "500000.00",
            "500000.00", "0.10000", LocalDate.of(2030, 1, 1), 1);
        UUID ac2 = seedHolding("AC02", "AC Two", "Issuer2",
            "DEBT", "AMORTISED_COST", "ACTIVE",
            LocalDate.of(2025, 2, 1), "300000.00",
            "300000.00", "0.10000", LocalDate.of(2030, 2, 1), 1);
        UUID fvpl = seedHolding("EQ01", "Equity", "MTN",
            "EQUITY", "FVPL", "ACTIVE",
            LocalDate.of(2025, 6, 1), "200000.00",
            null, null, null, null);
        UUID mmkt = seedHolding("MM01", "T-Bill", "FGN",
            "MONEY_MARKET", "AMORTISED_COST", "ACTIVE",
            LocalDate.of(2025, 9, 1), "150000.00",
            "150000.00", "0.18000", LocalDate.of(2026, 9, 1), 1);

        seedCarryingValue(ac1, "510000.00", null);
        seedCarryingValue(ac2, "305000.00", null);
        seedCarryingValue(fvpl, "220000.00", "220000.00");
        seedCarryingValue(mmkt, "155000.00", null);

        Map<String, Object> payload = engine.computePayload(fy2026PeriodId);

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) payload.get("totals");
        assertThat(totals.get("holdingCount")).isEqualTo(4);
        // 500000 + 300000 + 200000 + 150000 = 1,150,000
        assertThat((BigDecimal) totals.get("acquisitionCost")).isEqualByComparingTo("1150000.00");
        // 510000 + 305000 + 220000 + 155000 = 1,190,000
        assertThat((BigDecimal) totals.get("carryingValue")).isEqualByComparingTo("1190000.00");
        // Only FVPL has fair value: 220000
        assertThat((BigDecimal) totals.get("fairValue")).isEqualByComparingTo("220000.00");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byClass = (List<Map<String, Object>>) payload.get("byClassification");
        // AMORTISED_COST first (3 rows), FVPL second (1 row)
        assertThat(byClass).extracting(c -> c.get("classification"))
            .containsExactly("AMORTISED_COST", "FVPL");
        assertThat(byClass.get(0).get("holdingCount")).isEqualTo(3);
        // AC: 500000 + 300000 + 150000 = 950,000 acquisition
        assertThat((BigDecimal) byClass.get(0).get("acquisitionCost")).isEqualByComparingTo("950000.00");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byAsset = (List<Map<String, Object>>) payload.get("byAssetType");
        // DEBT, EQUITY, MONEY_MARKET alpha-sorted
        assertThat(byAsset).extracting(a -> a.get("assetType"))
            .containsExactly("DEBT", "EQUITY", "MONEY_MARKET");
        assertThat(byAsset.get(0).get("holdingCount")).isEqualTo(2);
    }

    @Test
    @DisplayName("byHolding ordered by classification ASC, security_name ASC")
    void byHoldingOrdering() {
        seedHolding("Z-AC", "Z Bond", "I1", "DEBT", "AMORTISED_COST", "ACTIVE",
            LocalDate.of(2025, 1, 1), "100000.00",
            "100000.00", "0.10000", LocalDate.of(2030, 1, 1), 1);
        seedHolding("A-FVPL", "A Equity", "I2", "EQUITY", "FVPL", "ACTIVE",
            LocalDate.of(2025, 1, 1), "100000.00",
            null, null, null, null);
        seedHolding("A-AC", "A Bond", "I3", "DEBT", "AMORTISED_COST", "ACTIVE",
            LocalDate.of(2025, 1, 1), "100000.00",
            "100000.00", "0.10000", LocalDate.of(2030, 1, 1), 1);

        Map<String, Object> payload = engine.computePayload(fy2026PeriodId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byHolding = (List<Map<String, Object>>) payload.get("byHolding");
        assertThat(byHolding)
            .extracting(h -> h.get("securityName"))
            .containsExactly("A Bond", "Z Bond", "A Equity");
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
            "submissionType", "period", "asOf", "generatedAt",
            "byHolding", "byClassification", "byAssetType", "totals", "notes");
        assertThat(payload.get("notes")).asString()
            .contains("ACTIVE")
            .contains("MATURED");
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

    private void seedCarryingValue(UUID holdingId, String closing, String closingFairValue) {
        jdbcTemplate.update(
            "INSERT INTO investment_carrying_value (id, holding_id, period_id, " +
            "opening_balance, effective_interest_income, coupon_received, " +
            "fair_value_change_pnl, fair_value_change_oci, " +
            "ecl_movement, impairment_loss, disposals, closing_balance, " +
            "closing_fair_value, currency_code, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), holdingId, fy2026PeriodId,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal(closing),
            closingFairValue == null ? null : new BigDecimal(closingFairValue),
            "NGN", "test");
    }
}
