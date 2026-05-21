package com.nubeero.cia.api.finance.ifrs9;

import com.nubeero.cia.common.exception.CiaException;
import com.nubeero.cia.finance.ifrs9.AssetType;
import com.nubeero.cia.finance.ifrs9.BusinessModel;
import com.nubeero.cia.finance.ifrs9.HoldingStatus;
import com.nubeero.cia.finance.ifrs9.InvestmentClassification;
import com.nubeero.cia.finance.ifrs9.InvestmentClassificationHistoryRepository;
import com.nubeero.cia.finance.ifrs9.InvestmentClassificationService;
import com.nubeero.cia.finance.ifrs9.InvestmentHolding;
import com.nubeero.cia.finance.ifrs9.InvestmentHoldingNotFoundException;
import com.nubeero.cia.finance.ifrs9.InvestmentHoldingRepository;
import com.nubeero.cia.finance.ifrs9.ReclassifyHoldingRequest;
import com.nubeero.cia.finance.ifrs9.RegisterHoldingRequest;
import jakarta.persistence.EntityManager;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end Testcontainers IT for {@link InvestmentClassificationService}
 * — Slice 3.2.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Register AC bond — sets classification + ECL stage 1, persists row.</li>
 *   <li>Register FVOCI debt — classification + ECL stage 1.</li>
 *   <li>Register FVPL equity (default, no §5.7.5 election) — null ECL stage.</li>
 *   <li>Register FVOCI equity (with §5.7.5 election) — null ECL stage,
 *       coupon/maturity stripped even if request passes them.</li>
 *   <li>Register debt with SPPI fail → FVPL regardless of business model.</li>
 *   <li>Register DEBT with missing sppiTestPassed → SPPI_REQUIRED.</li>
 *   <li>Reclassify AC → FVPL: holding updated, history row inserted, ECL
 *       stage flipped to null.</li>
 *   <li>Reclassify to same classification rejected (CLASSIFICATION_UNCHANGED).</li>
 *   <li>Reclassify unknown holding → InvestmentHoldingNotFoundException.</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    InvestmentClassificationService.class
})
class InvestmentClassificationServiceIT {

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

    @Autowired private InvestmentClassificationService service;
    @Autowired private InvestmentHoldingRepository holdingRepository;
    @Autowired private InvestmentClassificationHistoryRepository historyRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    // ── 1. Register AC bond ───────────────────────────────────────────────────
    @Test
    @DisplayName("register debt SPPI+HOLD_TO_COLLECT → AC, ECL stage 1")
    void registerAcBond() {
        InvestmentHolding h = service.register(new RegisterHoldingRequest(
            "NG0001",
            "FGN Bond 2031",
            "Federal Government of Nigeria",
            AssetType.DEBT,
            LocalDate.of(2026, 1, 15),
            new BigDecimal("1000000.00"),
            new BigDecimal("1000000.00"),
            new BigDecimal("0.12000"),
            LocalDate.of(2031, 1, 15),
            "NGN",
            true,
            BusinessModel.HOLD_TO_COLLECT,
            false));
        entityManager.flush();

        assertThat(h.getId()).isNotNull();
        assertThat(h.getClassification()).isEqualTo(InvestmentClassification.AMORTISED_COST);
        assertThat(h.getEclStage()).isEqualTo(1);
        assertThat(h.getStatus()).isEqualTo(HoldingStatus.ACTIVE);

        Long count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM investment_holding WHERE isin = 'NG0001'", Long.class);
        assertThat(count).isEqualTo(1L);
    }

    // ── 2. Register FVOCI debt ────────────────────────────────────────────────
    @Test
    @DisplayName("register debt SPPI+HOLD_AND_SELL → FVOCI_DEBT, ECL stage 1")
    void registerFvociDebt() {
        InvestmentHolding h = service.register(new RegisterHoldingRequest(
            null, "Corp Bond 2028", "Acme Bank",
            AssetType.DEBT, LocalDate.of(2026, 1, 15), new BigDecimal("500000.00"),
            new BigDecimal("500000.00"), new BigDecimal("0.14000"),
            LocalDate.of(2028, 1, 15), "NGN",
            true, BusinessModel.HOLD_TO_COLLECT_AND_SELL, false));
        entityManager.flush();

        assertThat(h.getClassification()).isEqualTo(InvestmentClassification.FVOCI_DEBT);
        assertThat(h.getEclStage()).isEqualTo(1);
    }

    // ── 3. Register FVPL equity (default) ────────────────────────────────────
    @Test
    @DisplayName("register equity no §5.7.5 election → FVPL, null ECL stage")
    void registerFvplEquity() {
        InvestmentHolding h = service.register(new RegisterHoldingRequest(
            "NG-EQ", "Acme Holdings Ords", "Acme Holdings",
            AssetType.EQUITY, LocalDate.of(2026, 1, 15), new BigDecimal("100000.00"),
            null, null, null, "NGN",
            null, null, false));
        entityManager.flush();

        assertThat(h.getClassification()).isEqualTo(InvestmentClassification.FVPL);
        assertThat(h.getEclStage()).isNull();
        assertThat(h.getSppiTestPassed()).isNull();
    }

    // ── 4. Register FVOCI equity (with §5.7.5 election) + coupon/maturity strip
    @Test
    @DisplayName("register equity with §5.7.5 election → FVOCI_EQUITY; coupon/maturity stripped")
    void registerFvociEquityWithElection() {
        InvestmentHolding h = service.register(new RegisterHoldingRequest(
            null, "Strategic Equity Stake", "BigCo",
            AssetType.EQUITY, LocalDate.of(2026, 1, 15), new BigDecimal("250000.00"),
            null,
            // Even though admin passes a couponRate + maturityDate, equity rows must
            // have them null (DB CHECK). The service strips them.
            new BigDecimal("0.05000"), LocalDate.of(2031, 1, 15),
            "NGN",
            null, null, true));
        entityManager.flush();

        assertThat(h.getClassification()).isEqualTo(InvestmentClassification.FVOCI_EQUITY);
        assertThat(h.getEclStage()).isNull();
        assertThat(h.getCouponRate()).isNull();
        assertThat(h.getMaturityDate()).isNull();
    }

    // ── 5. SPPI fail → FVPL regardless of business model ─────────────────────
    @Test
    @DisplayName("debt with SPPI fail → FVPL even with HOLD_TO_COLLECT")
    void sppiFailForcesFvpl() {
        InvestmentHolding h = service.register(new RegisterHoldingRequest(
            null, "Convertible Bond", "ExoticCo",
            AssetType.DEBT, LocalDate.of(2026, 1, 15), new BigDecimal("200000.00"),
            new BigDecimal("200000.00"), new BigDecimal("0.06000"),
            LocalDate.of(2030, 1, 15), "NGN",
            false, BusinessModel.HOLD_TO_COLLECT, false));
        entityManager.flush();

        assertThat(h.getClassification()).isEqualTo(InvestmentClassification.FVPL);
        assertThat(h.getSppiTestPassed()).isFalse();
        // FVPL gets null ECL stage even for debt
        assertThat(h.getEclStage()).isNull();
    }

    // ── 6. Missing SPPI on debt → SPPI_REQUIRED ──────────────────────────────
    @Test
    @DisplayName("register debt without sppiTestPassed → SPPI_REQUIRED CiaException")
    void debtRequiresSppi() {
        assertThatThrownBy(() -> service.register(new RegisterHoldingRequest(
            null, "Bond", "Issuer",
            AssetType.DEBT, LocalDate.of(2026, 1, 15), new BigDecimal("1000.00"),
            null, null, null, "NGN",
            null, BusinessModel.HOLD_TO_COLLECT, false)))
            .isInstanceOf(CiaException.class)
            .hasMessageContaining("sppiTestPassed is required");
    }

    // ── 7. Reclassify with audit trail ───────────────────────────────────────
    @Test
    @DisplayName("reclassify AC → FVPL: holding updated + history row + ECL stage flipped")
    void reclassifyAcToFvpl() {
        InvestmentHolding original = service.register(new RegisterHoldingRequest(
            null, "Bond", "Issuer",
            AssetType.DEBT, LocalDate.of(2026, 1, 15), new BigDecimal("100000.00"),
            new BigDecimal("100000.00"), new BigDecimal("0.10000"),
            LocalDate.of(2030, 1, 15), "NGN",
            true, BusinessModel.HOLD_TO_COLLECT, false));
        entityManager.flush();

        InvestmentHolding reclassified = service.reclassify(original.getId(),
            new ReclassifyHoldingRequest(
                InvestmentClassification.FVPL,
                LocalDate.of(2026, 6, 1),
                "Business model shifted to active trading after liquidity reorganisation.",
                "cfo@example.com"));
        entityManager.flush();

        assertThat(reclassified.getClassification()).isEqualTo(InvestmentClassification.FVPL);
        assertThat(reclassified.getEclStage()).isNull();

        // History row written
        var history = historyRepository.findByHoldingIdAndDeletedAtIsNullOrderByReclassificationDateAsc(
            original.getId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getPreviousClassification()).isEqualTo(InvestmentClassification.AMORTISED_COST);
        assertThat(history.get(0).getNewClassification()).isEqualTo(InvestmentClassification.FVPL);
        assertThat(history.get(0).getApprovedBy()).isEqualTo("cfo@example.com");
    }

    // ── 8. Same-to-same reclassification rejected ────────────────────────────
    @Test
    @DisplayName("reclassify to same classification → CLASSIFICATION_UNCHANGED")
    void reclassifyToSameRejected() {
        InvestmentHolding h = service.register(new RegisterHoldingRequest(
            null, "Bond", "Issuer",
            AssetType.DEBT, LocalDate.of(2026, 1, 15), new BigDecimal("100000.00"),
            new BigDecimal("100000.00"), new BigDecimal("0.10000"),
            LocalDate.of(2030, 1, 15), "NGN",
            true, BusinessModel.HOLD_TO_COLLECT, false));
        entityManager.flush();

        assertThatThrownBy(() -> service.reclassify(h.getId(),
            new ReclassifyHoldingRequest(
                InvestmentClassification.AMORTISED_COST,
                LocalDate.of(2026, 6, 1),
                "Try the same value",
                "cfo@example.com")))
            .isInstanceOf(CiaException.class)
            .hasMessageContaining("already classified");
    }

    // ── 9. Reclassify unknown holding → 404 ───────────────────────────────────
    @Test
    @DisplayName("reclassify unknown holding → InvestmentHoldingNotFoundException")
    void reclassifyUnknownRejected() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> service.reclassify(unknown,
            new ReclassifyHoldingRequest(
                InvestmentClassification.FVPL,
                LocalDate.of(2026, 6, 1),
                "Nothing here",
                "cfo@example.com")))
            .isInstanceOf(InvestmentHoldingNotFoundException.class);
    }

    // ── 10. ISIN can be null + repository round-trip ─────────────────────────
    @Test
    @DisplayName("holding repository round-trip preserves all fields")
    void repositoryRoundTrip() {
        InvestmentHolding original = service.register(new RegisterHoldingRequest(
            null, "Money Market Placement", "Bank XYZ",
            AssetType.MONEY_MARKET, LocalDate.of(2026, 1, 15), new BigDecimal("5000000.00"),
            new BigDecimal("5000000.00"), new BigDecimal("0.18000"),
            LocalDate.of(2026, 7, 15), "NGN",
            true, BusinessModel.HOLD_TO_COLLECT, false));
        entityManager.flush();
        entityManager.clear();

        InvestmentHolding loaded = holdingRepository.findById(original.getId()).orElseThrow();
        assertThat(loaded.getIsin()).isNull();
        assertThat(loaded.getSecurityName()).isEqualTo("Money Market Placement");
        assertThat(loaded.getAssetType()).isEqualTo(AssetType.MONEY_MARKET);
        assertThat(loaded.getClassification()).isEqualTo(InvestmentClassification.AMORTISED_COST);
        assertThat(loaded.getCouponRate()).isEqualByComparingTo("0.18000");
        assertThat(loaded.getEclStage()).isEqualTo(1);
    }
}
