package com.nubeero.cia.finance.ifrs9;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link FairValueEngine#routeJeFor} and
 * {@link FairValueEngine#routeJe} — the pure §5.7 account-routing
 * matrix. No Spring, no DB.
 */
class FairValueEngineRoutingTest {

    private static InvestmentHolding holding(AssetType assetType, InvestmentClassification classification) {
        InvestmentHolding h = new InvestmentHolding() {};
        h.setSecurityName("Test");
        h.setAssetType(assetType);
        h.setClassification(classification);
        h.setAcquisitionDate(LocalDate.of(2026, 1, 1));
        h.setAcquisitionCost(new BigDecimal("100000.00"));
        h.setCurrencyCode("NGN");
        return h;
    }

    @Nested
    @DisplayName("FVPL routing")
    class FvplRouting {

        @Test
        @DisplayName("FVPL debt gain → Dr 1220 / Cr 4250")
        void fvplDebtGain() {
            var routing = FairValueEngine.routeJeFor(
                holding(AssetType.DEBT, InvestmentClassification.FVPL),
                new BigDecimal("5000.00"));
            assertThat(routing.debit()).isEqualTo("1220");
            assertThat(routing.credit()).isEqualTo("4250");
        }

        @Test
        @DisplayName("FVPL debt loss → Dr 5330 / Cr 1220")
        void fvplDebtLoss() {
            var routing = FairValueEngine.routeJeFor(
                holding(AssetType.DEBT, InvestmentClassification.FVPL),
                new BigDecimal("-5000.00"));
            assertThat(routing.debit()).isEqualTo("5330");
            assertThat(routing.credit()).isEqualTo("1220");
        }

        @Test
        @DisplayName("FVPL equity gain → Dr 1210 / Cr 4250")
        void fvplEquityGain() {
            var routing = FairValueEngine.routeJeFor(
                holding(AssetType.EQUITY, InvestmentClassification.FVPL),
                new BigDecimal("7000.00"));
            assertThat(routing.debit()).isEqualTo("1210");
            assertThat(routing.credit()).isEqualTo("4250");
        }

        @Test
        @DisplayName("FVPL equity loss → Dr 5330 / Cr 1210")
        void fvplEquityLoss() {
            var routing = FairValueEngine.routeJeFor(
                holding(AssetType.EQUITY, InvestmentClassification.FVPL),
                new BigDecimal("-7000.00"));
            assertThat(routing.debit()).isEqualTo("5330");
            assertThat(routing.credit()).isEqualTo("1210");
        }
    }

    @Nested
    @DisplayName("FVOCI_DEBT routing — OCI reserve 3410")
    class FvociDebtRouting {

        @Test
        @DisplayName("FVOCI debt gain → Dr 1230 / Cr 3410")
        void fvociDebtGain() {
            var routing = FairValueEngine.routeJeFor(
                holding(AssetType.DEBT, InvestmentClassification.FVOCI_DEBT),
                new BigDecimal("4000.00"));
            assertThat(routing.debit()).isEqualTo("1230");
            assertThat(routing.credit()).isEqualTo("3410");
        }

        @Test
        @DisplayName("FVOCI debt loss → Dr 3410 / Cr 1230")
        void fvociDebtLoss() {
            var routing = FairValueEngine.routeJeFor(
                holding(AssetType.DEBT, InvestmentClassification.FVOCI_DEBT),
                new BigDecimal("-4000.00"));
            assertThat(routing.debit()).isEqualTo("3410");
            assertThat(routing.credit()).isEqualTo("1230");
        }
    }

    @Nested
    @DisplayName("FVOCI_EQUITY routing — OCI reserve 3420, no recycling")
    class FvociEquityRouting {

        @Test
        @DisplayName("FVOCI equity gain → Dr 1240 / Cr 3420")
        void fvociEquityGain() {
            var routing = FairValueEngine.routeJeFor(
                holding(AssetType.EQUITY, InvestmentClassification.FVOCI_EQUITY),
                new BigDecimal("3000.00"));
            assertThat(routing.debit()).isEqualTo("1240");
            assertThat(routing.credit()).isEqualTo("3420");
        }

        @Test
        @DisplayName("FVOCI equity loss → Dr 3420 / Cr 1240")
        void fvociEquityLoss() {
            var routing = FairValueEngine.routeJeFor(
                holding(AssetType.EQUITY, InvestmentClassification.FVOCI_EQUITY),
                new BigDecimal("-3000.00"));
            assertThat(routing.debit()).isEqualTo("3420");
            assertThat(routing.credit()).isEqualTo("1240");
        }
    }

    @Nested
    @DisplayName("AMORTISED_COST is not FV-eligible")
    class AcNotEligible {

        @Test
        @DisplayName("routeJeFor(AC, gain) throws IllegalArgumentException")
        void acThrows() {
            assertThatThrownBy(() -> FairValueEngine.routeJeFor(
                holding(AssetType.DEBT, InvestmentClassification.AMORTISED_COST),
                new BigDecimal("1000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AMORTISED_COST is not FV-eligible");
        }

        @Test
        @DisplayName("isFvEligible filter excludes AMORTISED_COST")
        void filterExcludesAc() {
            assertThat(FairValueEngine.isFvEligible(InvestmentClassification.AMORTISED_COST)).isFalse();
            assertThat(FairValueEngine.isFvEligible(InvestmentClassification.FVPL)).isTrue();
            assertThat(FairValueEngine.isFvEligible(InvestmentClassification.FVOCI_DEBT)).isTrue();
            assertThat(FairValueEngine.isFvEligible(InvestmentClassification.FVOCI_EQUITY)).isTrue();
        }
    }
}
