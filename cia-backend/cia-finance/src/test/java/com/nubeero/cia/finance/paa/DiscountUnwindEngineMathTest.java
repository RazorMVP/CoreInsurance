package com.nubeero.cia.finance.paa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DiscountUnwindEngine#computeUnwind}. No Spring
 * context, no DB — just BigDecimal arithmetic. Same package as the engine
 * so the package-private static helper is visible, mirroring
 * {@link LrcEngineMathTest}.
 */
class DiscountUnwindEngineMathTest {

    @Test
    @DisplayName("January unwind: ₦50,000 × 6% × 31/365 rounds HALF_UP to 254.79")
    void januaryUnwind() {
        BigDecimal unwind = DiscountUnwindEngine.computeUnwind(
            new BigDecimal("50000.00"), new BigDecimal("0.06"), 31);
        assertThat(unwind).isEqualByComparingTo("254.79");
    }

    @Test
    @DisplayName("February unwind: ₦100,000 × 5% × 28/365 rounds HALF_UP to 383.56")
    void februaryUnwind() {
        // 100000 × 0.05 × 28 / 365 = 383.5616... → 383.56
        BigDecimal unwind = DiscountUnwindEngine.computeUnwind(
            new BigDecimal("100000.00"), new BigDecimal("0.05"), 28);
        assertThat(unwind).isEqualByComparingTo("383.56");
    }

    @Test
    @DisplayName("Annual unwind: ₦1,000,000 × 6% × 365/365 = 60,000.00")
    void annualUnwind() {
        BigDecimal unwind = DiscountUnwindEngine.computeUnwind(
            new BigDecimal("1000000.00"), new BigDecimal("0.06"), 365);
        assertThat(unwind).isEqualByComparingTo("60000.00");
    }

    @Test
    @DisplayName("Zero opening → zero unwind regardless of rate")
    void zeroOpening() {
        assertThat(DiscountUnwindEngine.computeUnwind(BigDecimal.ZERO, new BigDecimal("0.06"), 31))
            .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Zero rate → zero unwind (degenerate case for tenants migrating off discounting)")
    void zeroRate() {
        assertThat(DiscountUnwindEngine.computeUnwind(new BigDecimal("100000"), BigDecimal.ZERO, 31))
            .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Zero period days → zero unwind (defensive — fiscal_period CHECK prevents this in production)")
    void zeroPeriodDays() {
        assertThat(DiscountUnwindEngine.computeUnwind(new BigDecimal("100000"), new BigDecimal("0.06"), 0))
            .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Sub-kobo precision rounds HALF_UP at 2 decimal places")
    void halfUpRounding() {
        // ₦12,341 × 4.5% × 31 / 365 = 47.16635... → 47.17
        BigDecimal unwind = DiscountUnwindEngine.computeUnwind(
            new BigDecimal("12341.00"), new BigDecimal("0.045"), 31);
        assertThat(unwind).isEqualByComparingTo("47.17");
    }

    @Test
    @DisplayName("Quarterly unwind: ₦1,000,000 × 6% × 92/365 ≈ 15,123.29")
    void quarterlyUnwind() {
        // Jan + Feb + Mar = 31 + 28 + 31 = 90 days (non-leap); but let's pick 92 for Q2 (Apr+May+Jun = 30+31+31 = 92)
        // 1000000 × 0.06 × 92 / 365 = 15123.287... → 15123.29
        BigDecimal unwind = DiscountUnwindEngine.computeUnwind(
            new BigDecimal("1000000.00"), new BigDecimal("0.06"), 92);
        assertThat(unwind).isEqualByComparingTo("15123.29");
    }
}
