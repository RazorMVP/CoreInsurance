package com.nubeero.cia.finance.ifrs9;

import com.nubeero.cia.common.exception.CiaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link InvestmentClassificationService#classify} — the
 * pure §4.1 classification function. No Spring context, no DB.
 *
 * <p>Covers the full 4×3×2 decision matrix plus the failure cases for
 * missing SPPI / business-model inputs on debt instruments.
 */
class InvestmentClassificationServiceMathTest {

    @Nested
    @DisplayName("DEBT classification")
    class DebtClassification {

        @Test
        @DisplayName("SPPI pass + HOLD_TO_COLLECT → AMORTISED_COST")
        void sppiPassHoldToCollect() {
            assertThat(InvestmentClassificationService.classify(
                AssetType.DEBT, true, BusinessModel.HOLD_TO_COLLECT, false))
                .isEqualTo(InvestmentClassification.AMORTISED_COST);
        }

        @Test
        @DisplayName("SPPI pass + HOLD_TO_COLLECT_AND_SELL → FVOCI_DEBT")
        void sppiPassHoldAndSell() {
            assertThat(InvestmentClassificationService.classify(
                AssetType.DEBT, true, BusinessModel.HOLD_TO_COLLECT_AND_SELL, false))
                .isEqualTo(InvestmentClassification.FVOCI_DEBT);
        }

        @Test
        @DisplayName("SPPI pass + SELL_FIRST → FVPL")
        void sppiPassSellFirst() {
            assertThat(InvestmentClassificationService.classify(
                AssetType.DEBT, true, BusinessModel.SELL_FIRST, false))
                .isEqualTo(InvestmentClassification.FVPL);
        }

        @Test
        @DisplayName("SPPI fail → FVPL regardless of business model")
        void sppiFailForcesFvpl() {
            for (BusinessModel bm : BusinessModel.values()) {
                assertThat(InvestmentClassificationService.classify(
                    AssetType.DEBT, false, bm, false))
                    .as("SPPI fail + " + bm + " should be FVPL")
                    .isEqualTo(InvestmentClassification.FVPL);
            }
        }

        @Test
        @DisplayName("SPPI null → FVPL (treated as failed test)")
        void sppiNullForcesFvpl() {
            assertThat(InvestmentClassificationService.classify(
                AssetType.DEBT, null, BusinessModel.HOLD_TO_COLLECT, false))
                .isEqualTo(InvestmentClassification.FVPL);
        }

        @Test
        @DisplayName("SPPI pass + null business model throws BUSINESS_MODEL_REQUIRED")
        void sppiPassNullBusinessModelThrows() {
            assertThatThrownBy(() -> InvestmentClassificationService.classify(
                AssetType.DEBT, true, null, false))
                .isInstanceOf(CiaException.class)
                .hasMessageContaining("Business model is required");
        }
    }

    @Nested
    @DisplayName("MONEY_MARKET classification (uses same debt logic)")
    class MoneyMarketClassification {

        @Test
        @DisplayName("SPPI pass + HOLD_TO_COLLECT → AMORTISED_COST (typical T-bill / CP)")
        void typicalMoneyMarket() {
            assertThat(InvestmentClassificationService.classify(
                AssetType.MONEY_MARKET, true, BusinessModel.HOLD_TO_COLLECT, false))
                .isEqualTo(InvestmentClassification.AMORTISED_COST);
        }

        @Test
        @DisplayName("SELL_FIRST → FVPL")
        void traderBookMoneyMarket() {
            assertThat(InvestmentClassificationService.classify(
                AssetType.MONEY_MARKET, true, BusinessModel.SELL_FIRST, false))
                .isEqualTo(InvestmentClassification.FVPL);
        }
    }

    @Nested
    @DisplayName("EQUITY classification")
    class EquityClassification {

        @Test
        @DisplayName("No §5.7.5 election → FVPL (default)")
        void equityDefaultFvpl() {
            assertThat(InvestmentClassificationService.classify(
                AssetType.EQUITY, null, null, false))
                .isEqualTo(InvestmentClassification.FVPL);
        }

        @Test
        @DisplayName("§5.7.5 election active → FVOCI_EQUITY (no recycling)")
        void equityWithElection() {
            assertThat(InvestmentClassificationService.classify(
                AssetType.EQUITY, null, null, true))
                .isEqualTo(InvestmentClassification.FVOCI_EQUITY);
        }

        @Test
        @DisplayName("SPPI + businessModel inputs ignored for equity")
        void equityIgnoresDebtInputs() {
            assertThat(InvestmentClassificationService.classify(
                AssetType.EQUITY, true, BusinessModel.HOLD_TO_COLLECT, false))
                .isEqualTo(InvestmentClassification.FVPL);
            assertThat(InvestmentClassificationService.classify(
                AssetType.EQUITY, false, BusinessModel.SELL_FIRST, true))
                .isEqualTo(InvestmentClassification.FVOCI_EQUITY);
        }
    }

    @Nested
    @DisplayName("DERIVATIVE classification")
    class DerivativeClassification {

        @Test
        @DisplayName("Always FVPL — §5.4.1(b)")
        void derivativesAreAlwaysFvpl() {
            // Even if admin passes nonsense inputs, derivatives must be FVPL.
            assertThat(InvestmentClassificationService.classify(
                AssetType.DERIVATIVE, true, BusinessModel.HOLD_TO_COLLECT, false))
                .isEqualTo(InvestmentClassification.FVPL);
            assertThat(InvestmentClassificationService.classify(
                AssetType.DERIVATIVE, false, BusinessModel.SELL_FIRST, true))
                .isEqualTo(InvestmentClassification.FVPL);
            assertThat(InvestmentClassificationService.classify(
                AssetType.DERIVATIVE, null, null, false))
                .isEqualTo(InvestmentClassification.FVPL);
        }
    }
}
