package com.nubeero.cia.api.finance.naicom;

import com.nubeero.cia.finance.naicom.Ifrs17DisclosureEngine;
import com.nubeero.cia.finance.naicom.NaicomSubmissionType;
import com.nubeero.cia.finance.paa.MovementAnalysisService;
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
 * Slice 4.6 IT for {@link Ifrs17DisclosureEngine}. Exercises the engine
 * end-to-end against Testcontainers Postgres with directly-seeded
 * {@code paa_lrc} + {@code paa_lic} rows (the upstream LRC / LIC engines
 * are already IT-covered by {@code MovementAnalysisServiceIT}, so this
 * fixture cuts through them to focus on the DTO-to-payload adapter
 * contract).
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Empty period — no paa_lrc/paa_lic rows; all totals zero,
 *       byGroup empty.</li>
 *   <li>Single group with LRC only — totals reflect LRC, LIC zero.</li>
 *   <li>Single group with both LRC + LIC — total liability = LRC + LIC.</li>
 *   <li>Multiple groups — totals aggregate per-group rows; ordering by
 *       (portfolio_code, cohort_year, onerousness).</li>
 *   <li>Group dimensions preserved (cohort_year, onerousness, currency).</li>
 *   <li>FiscalPeriodNotFoundException for missing / deleted period.</li>
 *   <li>Payload envelope matches NAICOM contract (submissionType,
 *       period, generatedAt, notes).</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    MovementAnalysisService.class,
    Ifrs17DisclosureEngine.class
})
class Ifrs17DisclosureEngineIT {

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
        registry.add("spring.flyway.target", () -> "41");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired private Ifrs17DisclosureEngine engine;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID fy2026PeriodId;
    private final LocalDate periodStart = LocalDate.of(2026, 1, 1);
    private final LocalDate periodEnd = LocalDate.of(2026, 12, 31);

    @BeforeEach
    void seedFiscalPeriod() {
        jdbcTemplate.update("DELETE FROM paa_lrc");
        jdbcTemplate.update("DELETE FROM paa_lic");
        jdbcTemplate.update("DELETE FROM policy_group_assignment");
        jdbcTemplate.update("DELETE FROM policies");
        jdbcTemplate.update("DELETE FROM group_of_contracts");
        jdbcTemplate.update("DELETE FROM portfolio");
        jdbcTemplate.update("DELETE FROM fiscal_period");
        jdbcTemplate.update("DELETE FROM fiscal_year");

        UUID fyId = UUID.randomUUID();
        fy2026PeriodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            fyId, "FY-IFRS17-2026", periodStart, periodEnd, "ACTIVE", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            fy2026PeriodId, fyId, "YEAR", periodStart, periodEnd, "HARD_CLOSED", "test");
    }

    @Test
    @DisplayName("empty period — empty byGroup, all totals zero, envelope populated")
    void emptyPeriod() {
        Map<String, Object> payload = engine.computePayload(fy2026PeriodId);

        assertThat(payload.get("submissionType"))
            .isEqualTo(NaicomSubmissionType.IFRS17_DISCLOSURE.name());
        assertThat(payload.get("generatedAt")).isInstanceOf(String.class);
        assertThat(payload.get("notes")).asString().contains("§103");

        @SuppressWarnings("unchecked")
        Map<String, Object> period = (Map<String, Object>) payload.get("period");
        assertThat(period.get("id")).isEqualTo(fy2026PeriodId.toString());
        assertThat(period.get("start")).isEqualTo(periodStart.toString());
        assertThat(period.get("end")).isEqualTo(periodEnd.toString());

        @SuppressWarnings("unchecked")
        Map<String, Object> lrc = (Map<String, Object>) payload.get("lrcMovement");
        assertThat((BigDecimal) lrc.get("opening")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) lrc.get("closing")).isEqualByComparingTo("0.00");
        @SuppressWarnings("unchecked")
        Map<String, Object> lic = (Map<String, Object>) payload.get("licMovement");
        assertThat((BigDecimal) lic.get("opening")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) lic.get("closing")).isEqualByComparingTo("0.00");

        @SuppressWarnings("unchecked")
        Map<String, Object> liability = (Map<String, Object>) payload.get("insuranceContractLiability");
        assertThat((BigDecimal) liability.get("totalOpening")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) liability.get("totalClosing")).isEqualByComparingTo("0.00");

        assertThat((List<?>) payload.get("byGroup")).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) payload.get("totals");
        assertThat(totals.get("groupCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("LRC only — totals reflect LRC, LIC totals zero")
    void singleGroupLrcOnly() {
        UUID groupId = seedGroup("PORT-LRC-A", 2026, "NOT_ONEROUS");
        seedLrc(groupId,
            "0.00", "1000000.00", "100000.00",
            "0.00", "0.00",
            "0.00", "0.00",
            "900000.00");

        Map<String, Object> payload = engine.computePayload(fy2026PeriodId);

        @SuppressWarnings("unchecked")
        Map<String, Object> lrc = (Map<String, Object>) payload.get("lrcMovement");
        assertThat((BigDecimal) lrc.get("premiumsReceived")).isEqualByComparingTo("1000000.00");
        assertThat((BigDecimal) lrc.get("premiumEarned")).isEqualByComparingTo("100000.00");
        assertThat((BigDecimal) lrc.get("closing")).isEqualByComparingTo("900000.00");

        @SuppressWarnings("unchecked")
        Map<String, Object> lic = (Map<String, Object>) payload.get("licMovement");
        assertThat((BigDecimal) lic.get("opening")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) lic.get("closing")).isEqualByComparingTo("0.00");

        @SuppressWarnings("unchecked")
        Map<String, Object> liability = (Map<String, Object>) payload.get("insuranceContractLiability");
        assertThat((BigDecimal) liability.get("totalClosing")).isEqualByComparingTo("900000.00");

        assertThat((List<?>) payload.get("byGroup")).hasSize(1);
    }

    @Test
    @DisplayName("LRC + LIC populated — total liability = LRC + LIC closing")
    void singleGroupBothSides() {
        UUID groupId = seedGroup("PORT-BOTH-A", 2026, "NOT_ONEROUS");
        seedLrc(groupId,
            "0.00", "1000000.00", "100000.00",
            "0.00", "0.00",
            "0.00", "0.00",
            "900000.00");
        seedLic(groupId,
            "0.00", "50000.00", "20000.00",
            "30000.00",
            "0.00", "0.00",
            "0.00", "0.00",
            "0.00",
            "30000.00");

        Map<String, Object> payload = engine.computePayload(fy2026PeriodId);

        @SuppressWarnings("unchecked")
        Map<String, Object> liability = (Map<String, Object>) payload.get("insuranceContractLiability");
        // LRC closing 900000 + LIC closing 30000 = 930000
        assertThat((BigDecimal) liability.get("totalClosing"))
            .as("insurance contract liability = LRC closing + LIC closing")
            .isEqualByComparingTo("930000.00");

        @SuppressWarnings("unchecked")
        Map<String, Object> lic = (Map<String, Object>) payload.get("licMovement");
        assertThat((BigDecimal) lic.get("claimsIncurred")).isEqualByComparingTo("50000.00");
        assertThat((BigDecimal) lic.get("claimsPaid")).isEqualByComparingTo("20000.00");
        assertThat((BigDecimal) lic.get("closing")).isEqualByComparingTo("30000.00");
    }

    @Test
    @DisplayName("multiple groups — totals aggregate per-group rows; ordering by portfolio_code")
    void multipleGroupsAggregateAndOrdering() {
        UUID groupZ = seedGroup("PORT-Z-IFRS17", 2026, "NOT_ONEROUS");
        UUID groupA = seedGroup("PORT-A-IFRS17", 2026, "NOT_ONEROUS");
        UUID groupM = seedGroup("PORT-M-IFRS17", 2026, "NOT_ONEROUS");
        seedLrc(groupZ, "0.00", "300000.00", "30000.00", "0.00", "0.00", "0.00", "0.00", "270000.00");
        seedLrc(groupA, "0.00", "100000.00", "10000.00", "0.00", "0.00", "0.00", "0.00", "90000.00");
        seedLrc(groupM, "0.00", "200000.00", "20000.00", "0.00", "0.00", "0.00", "0.00", "180000.00");

        Map<String, Object> payload = engine.computePayload(fy2026PeriodId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byGroup = (List<Map<String, Object>>) payload.get("byGroup");
        assertThat(byGroup)
            .extracting(g -> g.get("portfolioCode"))
            .as("byGroup ordered by portfolio_code ASC")
            .containsExactly("PORT-A-IFRS17", "PORT-M-IFRS17", "PORT-Z-IFRS17");

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) payload.get("totals");
        assertThat(totals.get("groupCount")).isEqualTo(3);

        @SuppressWarnings("unchecked")
        Map<String, Object> lrc = (Map<String, Object>) payload.get("lrcMovement");
        // 300000 + 100000 + 200000 = 600000 premiums; 270000 + 90000 + 180000 = 540000 closing
        assertThat((BigDecimal) lrc.get("premiumsReceived")).isEqualByComparingTo("600000.00");
        assertThat((BigDecimal) lrc.get("closing")).isEqualByComparingTo("540000.00");
    }

    @Test
    @DisplayName("group entries carry cohort_year, onerousness, status, currency, portfolio name")
    void groupDimensionsPreserved() {
        UUID groupId = seedGroup("PORT-DIMS-IFRS17", 2026, "ONEROUS");
        seedLrc(groupId,
            "0.00", "100000.00", "10000.00",
            "0.00", "0.00",
            "5000.00", "5000.00",
            "95000.00");

        Map<String, Object> payload = engine.computePayload(fy2026PeriodId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byGroup = (List<Map<String, Object>>) payload.get("byGroup");
        assertThat(byGroup).hasSize(1);
        Map<String, Object> entry = byGroup.get(0);
        assertThat(entry.get("portfolioCode")).isEqualTo("PORT-DIMS-IFRS17");
        assertThat(entry.get("portfolioName")).isEqualTo("Test PORT-DIMS-IFRS17");
        assertThat(entry.get("cohortYear")).isEqualTo(2026);
        assertThat(entry.get("onerousness")).isEqualTo("ONEROUS");
        assertThat(entry.get("groupStatus")).isEqualTo("OPEN");
        assertThat(entry.get("currencyCode")).isEqualTo("NGN");
        assertThat(entry.get("groupId")).isInstanceOf(String.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> lrc = (Map<String, Object>) entry.get("lrc");
        assertThat((BigDecimal) lrc.get("lossComponent")).isEqualByComparingTo("5000.00");
        assertThat((BigDecimal) lrc.get("lossComponentChange")).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("missing fiscal period throws FiscalPeriodNotFoundException")
    void unknownPeriodIdThrows() {
        UUID ghost = UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> engine.computePayload(ghost))
            .isInstanceOf(com.nubeero.cia.finance.gl.FiscalPeriodNotFoundException.class);
    }

    @Test
    @DisplayName("payload envelope: submissionType + period + generatedAt + notes present")
    void payloadEnvelope() {
        Map<String, Object> payload = engine.computePayload(fy2026PeriodId);

        assertThat(payload.keySet()).containsExactly(
            "submissionType", "period", "generatedAt",
            "lrcMovement", "licMovement", "insuranceContractLiability",
            "byGroup", "totals", "notes");
        assertThat(payload.get("notes")).asString()
            .contains("paa_movement_analysis")
            .contains("V38");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID seedGroup(String portfolioCode, int cohortYear, String onerousness) {
        UUID portfolioId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO portfolio (id, code, name, created_by) VALUES (?, ?, ?, ?)",
            portfolioId, portfolioCode, "Test " + portfolioCode, "test");
        jdbcTemplate.update(
            "INSERT INTO group_of_contracts (id, portfolio_id, cohort_year, onerousness, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            groupId, portfolioId, cohortYear, onerousness, "OPEN", "test");
        return groupId;
    }

    /**
     * Seeds a paa_lrc row. Column order matches the LRC table per V36.
     */
    private void seedLrc(UUID groupId,
                          String opening, String premiumReceived, String premiumEarned,
                          String acqDeferred, String acqAmortised,
                          String lossComponent, String lossComponentChange,
                          String closing) {
        jdbcTemplate.update(
            "INSERT INTO paa_lrc (id, group_id, period_id, " +
            "opening_balance, premium_received, premium_earned, " +
            "acquisition_costs_deferred, acquisition_costs_amortised, " +
            "loss_component, loss_component_change, closing_balance, " +
            "currency_code, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), groupId, fy2026PeriodId,
            new BigDecimal(opening), new BigDecimal(premiumReceived), new BigDecimal(premiumEarned),
            new BigDecimal(acqDeferred), new BigDecimal(acqAmortised),
            new BigDecimal(lossComponent), new BigDecimal(lossComponentChange),
            new BigDecimal(closing),
            "NGN", "test");
    }

    /**
     * Seeds a paa_lic row. Column order matches the LIC table per V36.
     */
    private void seedLic(UUID groupId,
                          String opening, String claimsIncurred, String claimsPaid,
                          String caseReserveChange,
                          String ibnrEstimate, String ibnrChange,
                          String riskAdjustment, String riskAdjustmentChange,
                          String discountUnwind,
                          String closing) {
        jdbcTemplate.update(
            "INSERT INTO paa_lic (id, group_id, period_id, " +
            "opening_balance, claims_incurred, claims_paid, " +
            "case_reserve_change, " +
            "ibnr_estimate, ibnr_change, " +
            "risk_adjustment, risk_adjustment_change, " +
            "discount_unwind, closing_balance, " +
            "currency_code, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), groupId, fy2026PeriodId,
            new BigDecimal(opening), new BigDecimal(claimsIncurred), new BigDecimal(claimsPaid),
            new BigDecimal(caseReserveChange),
            new BigDecimal(ibnrEstimate), new BigDecimal(ibnrChange),
            new BigDecimal(riskAdjustment), new BigDecimal(riskAdjustmentChange),
            new BigDecimal(discountUnwind), new BigDecimal(closing),
            "NGN", "test");
    }
}
