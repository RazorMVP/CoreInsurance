package com.nubeero.cia.reinsurance;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class RiFacInwardPremiumMathTest {

    @Test
    void computesAcceptedSiGrossCommissionNet() {
        // SI 20,000,000 × 30% = 6,000,000 accepted; rate 0.75% → 45,000 gross;
        // commission 10% → 4,500; net 40,500.
        var a = RiFacInwardService.computeAmounts(
            new BigDecimal("20000000"), new BigDecimal("30"),
            new BigDecimal("0.75"), new BigDecimal("10"));
        assertThat(a.acceptedSumInsured()).isEqualByComparingTo("6000000.00");
        assertThat(a.grossPremium()).isEqualByComparingTo("45000.00");
        assertThat(a.commissionAmount()).isEqualByComparingTo("4500.00");
        assertThat(a.netPremium()).isEqualByComparingTo("40500.00");
    }

    @Test
    void nullCommissionRateTreatedAsZero() {
        var a = RiFacInwardService.computeAmounts(
            new BigDecimal("1000000"), new BigDecimal("50"),
            new BigDecimal("1.0"), null);
        assertThat(a.commissionAmount()).isEqualByComparingTo("0.00");
        assertThat(a.netPremium()).isEqualByComparingTo(a.grossPremium());
    }
}
