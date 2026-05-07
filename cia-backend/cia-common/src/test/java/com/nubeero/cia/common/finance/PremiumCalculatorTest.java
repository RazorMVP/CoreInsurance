package com.nubeero.cia.common.finance;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PremiumCalculatorTest {

    @Test
    void calculatesGrossPremiumFromPercentageRate() {
        assertThat(PremiumCalculator.grossPremium(
                new BigDecimal("1000000.00"),
                new BigDecimal("2.50")
        )).isEqualByComparingTo(new BigDecimal("25000.00"));
    }

    @Test
    void capsDiscountAtGrossPremium() {
        assertThat(PremiumCalculator.netPremium(
                new BigDecimal("25000.00"),
                new BigDecimal("30000.00")
        )).isEqualByComparingTo(new BigDecimal("0.00"));
    }
}
