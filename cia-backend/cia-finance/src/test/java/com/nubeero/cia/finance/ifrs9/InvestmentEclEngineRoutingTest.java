package com.nubeero.cia.finance.ifrs9;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link InvestmentEclEngine#routeEclJe} and
 * {@link InvestmentEclEngine#isEclEligible} — the pure §5.5 / §5.7.10A
 * routing matrix. No Spring, no DB.
 */
class InvestmentEclEngineRoutingTest {

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
    @DisplayName("AC debt routing — ECL reduces carrying amount directly")
    class AcDebtRouting {

        @Test
        @DisplayName("AC debt ECL increase → Dr 5340 / Cr 1250")
        void increase() {
            var routing = InvestmentEclEngine.routeEclJe(
                holding(AssetType.DEBT, InvestmentClassification.AMORTISED_COST),
                new BigDecimal("500.00"));
            assertThat(routing.debit()).isEqualTo("5340");
            assertThat(routing.credit()).isEqualTo("1250");
        }

        @Test
        @DisplayName("AC debt ECL reversal → Dr 1250 / Cr 5340")
        void reversal() {
            var routing = InvestmentEclEngine.routeEclJe(
                holding(AssetType.DEBT, InvestmentClassification.AMORTISED_COST),
                new BigDecimal("-500.00"));
            assertThat(routing.debit()).isEqualTo("1250");
            assertThat(routing.credit()).isEqualTo("5340");
        }
    }

    @Nested
    @DisplayName("AC money-market routing — uses 1140 carve-out")
    class AcMoneyMarketRouting {

        @Test
        @DisplayName("AC money-market ECL increase → Dr 5340 / Cr 1140")
        void increase() {
            var routing = InvestmentEclEngine.routeEclJe(
                holding(AssetType.MONEY_MARKET, InvestmentClassification.AMORTISED_COST),
                new BigDecimal("800.00"));
            assertThat(routing.debit()).isEqualTo("5340");
            assertThat(routing.credit()).isEqualTo("1140");
        }

        @Test
        @DisplayName("AC money-market ECL reversal → Dr 1140 / Cr 5340")
        void reversal() {
            var routing = InvestmentEclEngine.routeEclJe(
                holding(AssetType.MONEY_MARKET, InvestmentClassification.AMORTISED_COST),
                new BigDecimal("-800.00"));
            assertThat(routing.debit()).isEqualTo("1140");
            assertThat(routing.credit()).isEqualTo("5340");
        }
    }

    @Nested
    @DisplayName("FVOCI_DEBT routing — §5.7.10A: OCI reserve, not BS reduction")
    class FvociDebtRouting {

        @Test
        @DisplayName("FVOCI_DEBT ECL increase → Dr 5340 / Cr 3410 (OCI)")
        void increase() {
            var routing = InvestmentEclEngine.routeEclJe(
                holding(AssetType.DEBT, InvestmentClassification.FVOCI_DEBT),
                new BigDecimal("300.00"));
            assertThat(routing.debit()).isEqualTo("5340");
            assertThat(routing.credit()).isEqualTo("3410");
        }

        @Test
        @DisplayName("FVOCI_DEBT ECL reversal → Dr 3410 / Cr 5340")
        void reversal() {
            var routing = InvestmentEclEngine.routeEclJe(
                holding(AssetType.DEBT, InvestmentClassification.FVOCI_DEBT),
                new BigDecimal("-300.00"));
            assertThat(routing.debit()).isEqualTo("3410");
            assertThat(routing.credit()).isEqualTo("5340");
        }
    }

    @Nested
    @DisplayName("FVPL / FVOCI_EQUITY are not ECL-eligible")
    class NotEligible {

        @Test
        @DisplayName("FVPL throws IllegalArgumentException")
        void fvplThrows() {
            assertThatThrownBy(() -> InvestmentEclEngine.routeEclJe(
                holding(AssetType.DEBT, InvestmentClassification.FVPL),
                new BigDecimal("100")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ECL is not applicable to FVPL");
        }

        @Test
        @DisplayName("FVOCI_EQUITY throws IllegalArgumentException")
        void fvociEquityThrows() {
            assertThatThrownBy(() -> InvestmentEclEngine.routeEclJe(
                holding(AssetType.EQUITY, InvestmentClassification.FVOCI_EQUITY),
                new BigDecimal("100")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ECL is not applicable to FVOCI_EQUITY");
        }

        @Test
        @DisplayName("isEclEligible filter — AC + FVOCI_DEBT only")
        void filterMatchesStandard() {
            assertThat(InvestmentEclEngine.isEclEligible(InvestmentClassification.AMORTISED_COST)).isTrue();
            assertThat(InvestmentEclEngine.isEclEligible(InvestmentClassification.FVOCI_DEBT)).isTrue();
            assertThat(InvestmentEclEngine.isEclEligible(InvestmentClassification.FVPL)).isFalse();
            assertThat(InvestmentEclEngine.isEclEligible(InvestmentClassification.FVOCI_EQUITY)).isFalse();
        }
    }
}
