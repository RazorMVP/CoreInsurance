package com.nubeero.cia.finance.ifrs9;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AmortisedCostEngine#computeInterest} — the pure
 * IFRS 9 §5.4.1 effective-interest-method formula. No Spring, no DB.
 */
class AmortisedCostEngineMathTest {

    @Test
    @DisplayName("January interest: ₦1,000,000 × 12% × 31/365 = 10191.78")
    void januaryInterestParBond() {
        BigDecimal interest = AmortisedCostEngine.computeInterest(
            new BigDecimal("1000000.00"), new BigDecimal("0.12000"), 31);
        // 1000000 × 0.12 × 31 / 365 = 10191.7808... → 10191.78
        assertThat(interest).isEqualByComparingTo("10191.78");
    }

    @Test
    @DisplayName("February interest: ₦1,000,000 × 12% × 28/365 = 9205.48")
    void februaryInterestParBond() {
        BigDecimal interest = AmortisedCostEngine.computeInterest(
            new BigDecimal("1000000.00"), new BigDecimal("0.12000"), 28);
        // 1000000 × 0.12 × 28 / 365 = 9205.4794... → 9205.48
        assertThat(interest).isEqualByComparingTo("9205.48");
    }

    @Test
    @DisplayName("Full-year interest equals the nominal annual amount")
    void annualInterestParBond() {
        BigDecimal interest = AmortisedCostEngine.computeInterest(
            new BigDecimal("1000000.00"), new BigDecimal("0.12000"), 365);
        // 1000000 × 0.12 × 365 / 365 = 120000.00
        assertThat(interest).isEqualByComparingTo("120000.00");
    }

    @Test
    @DisplayName("Mid-period acquisition (17 active days in January)")
    void midPeriodAcquisition() {
        // Holding acquired Jan 15; period Jan 1-31; active days = Jan 15..Jan 31 = 17
        BigDecimal interest = AmortisedCostEngine.computeInterest(
            new BigDecimal("500000.00"), new BigDecimal("0.10000"), 17);
        // 500000 × 0.10 × 17 / 365 = 2328.7671... → 2328.77
        assertThat(interest).isEqualByComparingTo("2328.77");
    }

    @Test
    @DisplayName("Zero opening → zero interest")
    void zeroOpening() {
        assertThat(AmortisedCostEngine.computeInterest(
            BigDecimal.ZERO, new BigDecimal("0.12"), 31))
            .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Zero rate (e.g. interest-free placement) → zero interest")
    void zeroRate() {
        assertThat(AmortisedCostEngine.computeInterest(
            new BigDecimal("100000"), BigDecimal.ZERO, 31))
            .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Zero days → zero interest")
    void zeroDays() {
        assertThat(AmortisedCostEngine.computeInterest(
            new BigDecimal("100000"), new BigDecimal("0.12"), 0))
            .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Null inputs → zero (defensive)")
    void nullInputs() {
        assertThat(AmortisedCostEngine.computeInterest(null, new BigDecimal("0.12"), 31))
            .isEqualByComparingTo("0.00");
        assertThat(AmortisedCostEngine.computeInterest(new BigDecimal("100000"), null, 31))
            .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Treasury bill: ₦5,000,000 × 18% × 92/365 (Q1)")
    void treasuryBillQuarterlyInterest() {
        BigDecimal interest = AmortisedCostEngine.computeInterest(
            new BigDecimal("5000000.00"), new BigDecimal("0.18000"), 92);
        // 5000000 × 0.18 × 92 / 365 = 226849.32
        assertThat(interest).isEqualByComparingTo("226849.32");
    }

    @Test
    @DisplayName("Sub-kobo rounds HALF_UP at 2dp")
    void halfUpRounding() {
        // 12341 × 4.5% × 31 / 365 = 47.166... → 47.17
        BigDecimal interest = AmortisedCostEngine.computeInterest(
            new BigDecimal("12341.00"), new BigDecimal("0.04500"), 31);
        assertThat(interest).isEqualByComparingTo("47.17");
    }
}
