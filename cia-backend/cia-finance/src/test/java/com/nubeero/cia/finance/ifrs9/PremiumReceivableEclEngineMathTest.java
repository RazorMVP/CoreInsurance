package com.nubeero.cia.finance.ifrs9;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PremiumReceivableEclEngine#computeLifetimeEcl} —
 * the pure §5.5.15 provision-matrix sum. No Spring, no DB.
 */
class PremiumReceivableEclEngineMathTest {

    private static RecognisePremiumReceivableEclRequest.AgingBucket bucket(
            String label, String amount, String rate) {
        return new RecognisePremiumReceivableEclRequest.AgingBucket(
            label, new BigDecimal(amount), new BigDecimal(rate));
    }

    @Test
    @DisplayName("Standard provision matrix: ₦10M current × 0.5% + ₦2M 31-60 × 2% + ₦500K 61-90 × 5%")
    void standardMatrix() {
        BigDecimal ecl = PremiumReceivableEclEngine.computeLifetimeEcl(List.of(
            bucket("0-30 days",  "10000000", "0.005"),
            bucket("31-60 days",  "2000000", "0.020"),
            bucket("61-90 days",   "500000", "0.050")
        ));
        // 50000 + 40000 + 25000 = 115000
        assertThat(ecl).isEqualByComparingTo("115000.00");
    }

    @Test
    @DisplayName("Stale long-dated bucket: 100% default on >365-day overdue")
    void stale365() {
        BigDecimal ecl = PremiumReceivableEclEngine.computeLifetimeEcl(List.of(
            bucket(">365 days", "1000000", "1.00")
        ));
        assertThat(ecl).isEqualByComparingTo("1000000.00");
    }

    @Test
    @DisplayName("Zero outstanding → zero ECL")
    void zeroOutstanding() {
        BigDecimal ecl = PremiumReceivableEclEngine.computeLifetimeEcl(List.of(
            bucket("0-30 days", "0", "0.005")
        ));
        assertThat(ecl).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Zero rate → zero ECL")
    void zeroRate() {
        BigDecimal ecl = PremiumReceivableEclEngine.computeLifetimeEcl(List.of(
            bucket("0-30 days", "1000000", "0.000")
        ));
        assertThat(ecl).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Empty list → zero ECL")
    void emptyList() {
        BigDecimal ecl = PremiumReceivableEclEngine.computeLifetimeEcl(List.of());
        assertThat(ecl).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Null list → zero ECL (defensive)")
    void nullList() {
        BigDecimal ecl = PremiumReceivableEclEngine.computeLifetimeEcl(null);
        assertThat(ecl).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Sub-kobo rounds HALF_UP at 2dp: ₦12,341 × 4.5% = 555.345 → 555.35")
    void halfUpRounding() {
        BigDecimal ecl = PremiumReceivableEclEngine.computeLifetimeEcl(List.of(
            bucket("0-30 days", "12341", "0.045")
        ));
        assertThat(ecl).isEqualByComparingTo("555.35");
    }

    @Test
    @DisplayName("Full 6-bucket matrix sums correctly")
    void sixBucketMatrix() {
        BigDecimal ecl = PremiumReceivableEclEngine.computeLifetimeEcl(List.of(
            bucket("0-30 days",   "50000000", "0.005"),  //  250,000
            bucket("31-60 days",  "10000000", "0.020"),  //  200,000
            bucket("61-90 days",   "3000000", "0.050"),  //  150,000
            bucket("91-180 days",  "1500000", "0.250"),  //  375,000
            bucket("181-365 days",  "500000", "0.500"),  //  250,000
            bucket(">365 days",     "200000", "1.000")   //  200,000
        ));
        // Total = 250000 + 200000 + 150000 + 375000 + 250000 + 200000 = 1,425,000
        assertThat(ecl).isEqualByComparingTo("1425000.00");
    }
}
