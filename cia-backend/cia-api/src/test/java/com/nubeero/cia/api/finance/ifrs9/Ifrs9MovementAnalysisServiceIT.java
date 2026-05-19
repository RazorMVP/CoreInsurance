package com.nubeero.cia.api.finance.ifrs9;

import com.nubeero.cia.finance.gl.ChartOfAccountService;
import com.nubeero.cia.finance.gl.FiscalPeriodResolver;
import com.nubeero.cia.finance.gl.JournalEntryService;
import com.nubeero.cia.finance.gl.PostingRuleService;
import com.nubeero.cia.finance.ifrs9.AmortisedCostEngine;
import com.nubeero.cia.finance.ifrs9.AssetType;
import com.nubeero.cia.finance.ifrs9.BusinessModel;
import com.nubeero.cia.finance.ifrs9.FairValueEngine;
import com.nubeero.cia.finance.ifrs9.Ifrs9MovementAnalysis;
import com.nubeero.cia.finance.ifrs9.Ifrs9MovementAnalysisService;
import com.nubeero.cia.finance.ifrs9.InvestmentClassification;
import com.nubeero.cia.finance.ifrs9.InvestmentClassificationService;
import com.nubeero.cia.finance.ifrs9.InvestmentEclEngine;
import com.nubeero.cia.finance.ifrs9.InvestmentHolding;
import com.nubeero.cia.finance.ifrs9.PremiumReceivableEclEngine;
import com.nubeero.cia.finance.ifrs9.RecognisePremiumReceivableEclRequest;
import com.nubeero.cia.finance.ifrs9.RegisterHoldingRequest;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Testcontainers IT for {@link Ifrs9MovementAnalysisService} —
 * Slice 3.7. Runs upstream engines (Slice 3.3–3.6), then verifies the
 * §B5.5.39 disclosure aggregation across both sections.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Empty period: zero totals + empty byHolding + premium opening/closing all zero</li>
 *   <li>Single AC holding after interest accrual: present in byHolding with interest income</li>
 *   <li>FVOCI_DEBT after AC + FV + ECL: all three component columns populated correctly</li>
 *   <li>FVPL: only fair_value_change_pnl populated</li>
 *   <li>Premium receivable ECL: opening / closing / movement aggregated</li>
 *   <li>Aggregate totals = sum of per-holding rows</li>
 *   <li>byHolding ordered by (classification, security_name)</li>
 * </ol>
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
    InvestmentClassificationService.class,
    AmortisedCostEngine.class,
    FairValueEngine.class,
    InvestmentEclEngine.class,
    PremiumReceivableEclEngine.class,
    Ifrs9MovementAnalysisService.class,
    Ifrs9MovementAnalysisServiceIT.TestSupportConfig.class
})
class Ifrs9MovementAnalysisServiceIT {

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
        registry.add("spring.flyway.target", () -> "40");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired private Ifrs9MovementAnalysisService service;
    @Autowired private AmortisedCostEngine acEngine;
    @Autowired private FairValueEngine fvEngine;
    @Autowired private InvestmentEclEngine eclEngine;
    @Autowired private PremiumReceivableEclEngine premiumEclEngine;
    @Autowired private InvestmentClassificationService classificationService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    private UUID janPeriodId;
    private UUID febPeriodId;

    @BeforeEach
    void seedFiscalYearAndPeriods() {
        UUID fyId = UUID.randomUUID();
        janPeriodId = UUID.randomUUID();
        febPeriodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            fyId, "FY-MA9-2026",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ACTIVE", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            janPeriodId, fyId, "MONTH",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "OPEN", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            febPeriodId, fyId, "MONTH",
            LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), "OPEN", "test");
    }

    // ── 1. Empty period: zero totals, empty byHolding ────────────────────────
    @Test
    @DisplayName("empty period: zero totals, empty byHolding, premium section all zero")
    void emptyPeriod() {
        Ifrs9MovementAnalysis ma = service.compute(janPeriodId);

        assertThat(ma.investments().byHolding()).isEmpty();
        assertThat(ma.investments().totals().openingBalance()).isEqualByComparingTo("0.00");
        assertThat(ma.investments().totals().closingBalance()).isEqualByComparingTo("0.00");

        assertThat(ma.premiumReceivableEcl().openingAllowance()).isEqualByComparingTo("0.00");
        assertThat(ma.premiumReceivableEcl().closingAllowance()).isEqualByComparingTo("0.00");
        assertThat(ma.premiumReceivableEcl().direction()).isEqualTo("NO_CHANGE");

        assertThat(ma.periodStart()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(ma.periodEnd()).isEqualTo(LocalDate.of(2026, 1, 31));
    }

    // ── 2. Single AC after interest accrual: interest income shown ──────────
    @Test
    @DisplayName("AC holding after AmortisedCostEngine: byHolding row with effective_interest_income")
    void acHoldingWithInterest() {
        InvestmentHolding h = classificationService.register(new RegisterHoldingRequest(
            null, "AC Bond 2031", "Issuer", AssetType.DEBT,
            LocalDate.of(2025, 1, 1), new BigDecimal("1000000.00"),
            new BigDecimal("1000000.00"), new BigDecimal("0.12000"),
            LocalDate.of(2031, 1, 1), "NGN",
            true, BusinessModel.HOLD_TO_COLLECT, false));
        entityManager.flush();

        acEngine.recognise(janPeriodId);
        entityManager.flush();

        Ifrs9MovementAnalysis ma = service.compute(janPeriodId);

        assertThat(ma.investments().byHolding()).hasSize(1);
        var entry = ma.investments().byHolding().get(0);
        assertThat(entry.holdingId()).isEqualTo(h.getId());
        assertThat(entry.classification()).isEqualTo(InvestmentClassification.AMORTISED_COST);
        // 1000000 × 0.12 × 31/365 = 10191.78
        assertThat(entry.effectiveInterestIncome()).isEqualByComparingTo("10191.78");
        assertThat(entry.fairValueChangePnl()).isEqualByComparingTo("0.00");
        assertThat(entry.fairValueChangeOci()).isEqualByComparingTo("0.00");
    }

    // ── 3. FVOCI_DEBT: interest + FV (OCI) + ECL (OCI) all visible ──────────
    @Test
    @DisplayName("FVOCI_DEBT after AC + FV + ECL: byHolding shows all three components")
    void fvociDebtAllComponents() {
        InvestmentHolding h = classificationService.register(new RegisterHoldingRequest(
            null, "FVOCI Bond", "Issuer", AssetType.DEBT,
            LocalDate.of(2025, 1, 1), new BigDecimal("1000000.00"),
            new BigDecimal("1000000.00"), new BigDecimal("0.12000"),
            LocalDate.of(2031, 1, 1), "NGN",
            true, BusinessModel.HOLD_TO_COLLECT_AND_SELL, false));
        entityManager.flush();

        // Run all three engines for January
        acEngine.recognise(janPeriodId);
        entityManager.flush();
        // Post-AC carrying = 1,010,191.78; mark FV at 1,020,000 → +9,808.22 OCI
        fvEngine.recognise(janPeriodId, Map.of(h.getId(), new BigDecimal("1020000.00")));
        entityManager.flush();
        eclEngine.recognise(janPeriodId, List.of(
            new InvestmentEclEngine.EclInput(h.getId(), new BigDecimal("3000.00"), null)));
        entityManager.flush();

        Ifrs9MovementAnalysis ma = service.compute(janPeriodId);

        var entry = ma.investments().byHolding().get(0);
        assertThat(entry.classification()).isEqualTo(InvestmentClassification.FVOCI_DEBT);
        assertThat(entry.effectiveInterestIncome()).isEqualByComparingTo("10191.78");
        assertThat(entry.fairValueChangeOci()).isEqualByComparingTo("9808.22");
        assertThat(entry.eclMovement()).isEqualByComparingTo("3000.00");
        // closing_fair_value set by FV engine
        assertThat(entry.closingFairValue()).isEqualByComparingTo("1020000.00");
        // closing_balance reflects FV (NOT reduced by ECL per §5.7.10A)
        assertThat(entry.closingBalance()).isEqualByComparingTo("1020000.00");
    }

    // ── 4. FVPL: only fair_value_change_pnl populated ───────────────────────
    @Test
    @DisplayName("FVPL holding: only fair_value_change_pnl populated, no interest")
    void fvplOnlyPnl() {
        InvestmentHolding h = classificationService.register(new RegisterHoldingRequest(
            null, "Equity", "Issuer", AssetType.EQUITY,
            LocalDate.of(2025, 12, 1), new BigDecimal("100000.00"),
            null, null, null, "NGN",
            null, null, false));
        entityManager.flush();

        fvEngine.recognise(janPeriodId, Map.of(h.getId(), new BigDecimal("105000.00")));
        entityManager.flush();

        Ifrs9MovementAnalysis ma = service.compute(janPeriodId);

        var entry = ma.investments().byHolding().get(0);
        assertThat(entry.classification()).isEqualTo(InvestmentClassification.FVPL);
        assertThat(entry.effectiveInterestIncome()).isEqualByComparingTo("0.00");
        assertThat(entry.fairValueChangePnl()).isEqualByComparingTo("5000.00");
        assertThat(entry.fairValueChangeOci()).isEqualByComparingTo("0.00");
        assertThat(entry.eclStage()).isNull();
    }

    // ── 5. Premium receivable ECL section: opening + movement + closing ────
    @Test
    @DisplayName("premium receivable ECL: Feb section shows Jan opening + Feb movement + closing")
    void premiumReceivableSection() {
        // Jan: post 115,000 ECL allowance
        premiumEclEngine.recognise(janPeriodId, List.of(
            new RecognisePremiumReceivableEclRequest.AgingBucket(
                "0-30 days", new BigDecimal("10000000"), new BigDecimal("0.005")),
            new RecognisePremiumReceivableEclRequest.AgingBucket(
                "31-60 days", new BigDecimal("2000000"), new BigDecimal("0.020")),
            new RecognisePremiumReceivableEclRequest.AgingBucket(
                "61-90 days", new BigDecimal("500000"), new BigDecimal("0.050"))));
        entityManager.flush();

        // Feb: target 150,000 → +35,000 movement
        premiumEclEngine.recognise(febPeriodId, List.of(
            new RecognisePremiumReceivableEclRequest.AgingBucket(
                "0-30 days", new BigDecimal("12000000"), new BigDecimal("0.005")),
            new RecognisePremiumReceivableEclRequest.AgingBucket(
                "31-60 days", new BigDecimal("3000000"), new BigDecimal("0.020")),
            new RecognisePremiumReceivableEclRequest.AgingBucket(
                "61-90 days", new BigDecimal("600000"), new BigDecimal("0.050"))));
        entityManager.flush();

        Ifrs9MovementAnalysis febMa = service.compute(febPeriodId);

        assertThat(febMa.premiumReceivableEcl().openingAllowance()).isEqualByComparingTo("115000.00");
        assertThat(febMa.premiumReceivableEcl().periodMovement()).isEqualByComparingTo("35000.00");
        assertThat(febMa.premiumReceivableEcl().closingAllowance()).isEqualByComparingTo("150000.00");
        assertThat(febMa.premiumReceivableEcl().direction()).isEqualTo("INCREASE");
    }

    // ── 6. Aggregate totals = sum of per-holding rows ────────────────────────
    @Test
    @DisplayName("aggregate totals equal the sum across per-holding rows")
    void aggregateTotalsMatch() {
        classificationService.register(new RegisterHoldingRequest(
            null, "Bond A", "Issuer", AssetType.DEBT,
            LocalDate.of(2025, 1, 1), new BigDecimal("1000000.00"),
            new BigDecimal("1000000.00"), new BigDecimal("0.12000"),
            LocalDate.of(2031, 1, 1), "NGN",
            true, BusinessModel.HOLD_TO_COLLECT, false));
        classificationService.register(new RegisterHoldingRequest(
            null, "Bond B", "Issuer", AssetType.DEBT,
            LocalDate.of(2025, 1, 1), new BigDecimal("500000.00"),
            new BigDecimal("500000.00"), new BigDecimal("0.10000"),
            LocalDate.of(2030, 1, 1), "NGN",
            true, BusinessModel.HOLD_TO_COLLECT, false));
        entityManager.flush();

        acEngine.recognise(janPeriodId);
        entityManager.flush();

        Ifrs9MovementAnalysis ma = service.compute(janPeriodId);

        assertThat(ma.investments().byHolding()).hasSize(2);
        BigDecimal sumOpening = ma.investments().byHolding().stream()
            .map(Ifrs9MovementAnalysis.HoldingEntry::openingBalance)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumInterest = ma.investments().byHolding().stream()
            .map(Ifrs9MovementAnalysis.HoldingEntry::effectiveInterestIncome)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(ma.investments().totals().openingBalance()).isEqualByComparingTo(sumOpening);
        assertThat(ma.investments().totals().effectiveInterestIncome()).isEqualByComparingTo(sumInterest);
    }

    // ── 7. byHolding ordered by (classification, security_name) ──────────────
    @Test
    @DisplayName("byHolding ordered by (classification, security_name)")
    void byHoldingOrdering() {
        // Z security, AC classification
        classificationService.register(new RegisterHoldingRequest(
            null, "Z-AC", "Issuer", AssetType.DEBT,
            LocalDate.of(2025, 1, 1), new BigDecimal("100000.00"),
            new BigDecimal("100000.00"), new BigDecimal("0.10"),
            LocalDate.of(2030, 1, 1), "NGN",
            true, BusinessModel.HOLD_TO_COLLECT, false));
        // A security, AC classification
        classificationService.register(new RegisterHoldingRequest(
            null, "A-AC", "Issuer", AssetType.DEBT,
            LocalDate.of(2025, 1, 1), new BigDecimal("200000.00"),
            new BigDecimal("200000.00"), new BigDecimal("0.10"),
            LocalDate.of(2030, 1, 1), "NGN",
            true, BusinessModel.HOLD_TO_COLLECT, false));
        // M security, FVOCI_DEBT classification — comes after AC alphabetically
        classificationService.register(new RegisterHoldingRequest(
            null, "M-FVOCI", "Issuer", AssetType.DEBT,
            LocalDate.of(2025, 1, 1), new BigDecimal("300000.00"),
            new BigDecimal("300000.00"), new BigDecimal("0.10"),
            LocalDate.of(2030, 1, 1), "NGN",
            true, BusinessModel.HOLD_TO_COLLECT_AND_SELL, false));
        entityManager.flush();

        acEngine.recognise(janPeriodId);
        entityManager.flush();

        Ifrs9MovementAnalysis ma = service.compute(janPeriodId);

        // ORDER BY classification ASC, security_name ASC
        // AMORTISED_COST < FVOCI_DEBT alphabetically, so AC rows first
        assertThat(ma.investments().byHolding())
            .extracting(Ifrs9MovementAnalysis.HoldingEntry::securityName)
            .containsExactly("A-AC", "Z-AC", "M-FVOCI");
    }

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
