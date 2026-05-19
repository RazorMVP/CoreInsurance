package com.nubeero.cia.finance.paa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LrcEngine}'s pure per-policy earnings math. No
 * Spring context, no DB — just BigDecimal arithmetic and LocalDate
 * boundary handling.
 *
 * <p>The four functions under test are static package-private and take a
 * policy + (periodStart, periodEnd) tuple:
 * <ul>
 *   <li>{@code openingAmount(policy, periodStart, periodEnd)} — premium remaining at period start</li>
 *   <li>{@code receivedAmount(policy, periodStart, periodEnd)} — full premium iff policy.start lands in period</li>
 *   <li>{@code earnedAmount(policy, periodStart, periodEnd)} — premium recognised during the period</li>
 *   <li>{@code closingAmount(policy, periodStart, periodEnd)} — premium remaining after period.end</li>
 * </ul>
 * Together these encode the LRC roll-forward identity
 * {@code opening + received − earned = closing} that the engine enforces
 * group-wide.
 */
class LrcEngineMathTest {

    private static LrcEngine.PolicyPricing policy(LocalDate start, LocalDate end, String premium) {
        return new LrcEngine.PolicyPricing(start, end, new BigDecimal(premium), "NGN");
    }

    private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate JAN_31 = LocalDate.of(2026, 1, 31);
    private static final LocalDate FEB_1 = LocalDate.of(2026, 2, 1);
    private static final LocalDate FEB_28 = LocalDate.of(2026, 2, 28);
    private static final LocalDate MAR_1 = LocalDate.of(2026, 3, 1);
    private static final LocalDate MAR_31 = LocalDate.of(2026, 3, 31);
    private static final LocalDate DEC_1 = LocalDate.of(2026, 12, 1);
    private static final LocalDate DEC_31 = LocalDate.of(2026, 12, 31);

    @Nested
    @DisplayName("daysBetween (inclusive)")
    class DaysBetween {

        @Test
        @DisplayName("same date counts as 1 day")
        void sameDate() {
            assertThat(LrcEngine.daysBetween(JAN_1, JAN_1)).isEqualTo(1);
        }

        @Test
        @DisplayName("Jan 1 to Jan 31 counts as 31 days")
        void januaryMonth() {
            assertThat(LrcEngine.daysBetween(JAN_1, JAN_31)).isEqualTo(31);
        }

        @Test
        @DisplayName("Jan 1 to Dec 31 counts as 365 days (non-leap year)")
        void annualNonLeap() {
            assertThat(LrcEngine.daysBetween(JAN_1, DEC_31)).isEqualTo(365);
        }

        @Test
        @DisplayName("Jan 1 to Dec 31 counts as 366 days (leap year)")
        void annualLeap() {
            assertThat(LrcEngine.daysBetween(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)))
                .isEqualTo(366);
        }
    }

    @Nested
    @DisplayName("earnedAmount — period fully inside coverage")
    class EarnedFullyActive {

        @Test
        @DisplayName("January earning of an annual policy is 31/365 of premium")
        void januaryFraction() {
            var p = policy(JAN_1, DEC_31, "120000.00");
            // 120000 × 31/365 = 10191.7808... rounds HALF_UP to 10191.78
            assertThat(LrcEngine.earnedAmount(p, JAN_1, JAN_31)).isEqualByComparingTo("10191.78");
        }

        @Test
        @DisplayName("February (28 days) earning of an annual policy")
        void februaryFraction() {
            var p = policy(JAN_1, DEC_31, "120000.00");
            // 120000 × 28/365 = 9205.4794... rounds HALF_UP to 9205.48
            assertThat(LrcEngine.earnedAmount(p, FEB_1, FEB_28)).isEqualByComparingTo("9205.48");
        }
    }

    @Nested
    @DisplayName("earnedAmount — period and policy don't fully overlap")
    class EarnedPartialOverlap {

        @Test
        @DisplayName("policy starts mid-period — earn from policy.start to period.end")
        void policyStartsMidPeriod() {
            var p = policy(LocalDate.of(2026, 1, 15), LocalDate.of(2027, 1, 14), "100000.00");
            // active days = 17 (Jan 15 → Jan 31 inclusive); total days = 365
            // 100000 × 17/365 = 4657.5342... rounds HALF_UP to 4657.53
            assertThat(LrcEngine.earnedAmount(p, JAN_1, JAN_31)).isEqualByComparingTo("4657.53");
        }

        @Test
        @DisplayName("policy ends mid-period — earn from period.start to policy.end")
        void policyEndsMidPeriod() {
            var p = policy(LocalDate.of(2025, 12, 15), LocalDate.of(2026, 1, 14), "31000.00");
            // active = 14 days (Jan 1 → Jan 14); total = 31 days (Dec 15 → Jan 14)
            // 31000 × 14/31 = 14000.0000
            assertThat(LrcEngine.earnedAmount(p, JAN_1, JAN_31)).isEqualByComparingTo("14000.00");
        }

        @Test
        @DisplayName("policy doesn't overlap period at all — zero earnings")
        void noOverlap() {
            var p = policy(LocalDate.of(2026, 6, 1), LocalDate.of(2027, 5, 31), "100000.00");
            assertThat(LrcEngine.earnedAmount(p, JAN_1, JAN_31)).isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("receivedAmount — full premium only if policy.start in period")
    class Received {

        @Test
        @DisplayName("policy starts on period.start → full premium received")
        void startOnPeriodStart() {
            var p = policy(JAN_1, DEC_31, "120000.00");
            assertThat(LrcEngine.receivedAmount(p, JAN_1, JAN_31)).isEqualByComparingTo("120000.00");
        }

        @Test
        @DisplayName("policy starts on period.end → full premium received")
        void startOnPeriodEnd() {
            var p = policy(JAN_31, LocalDate.of(2027, 1, 30), "120000.00");
            assertThat(LrcEngine.receivedAmount(p, JAN_1, JAN_31)).isEqualByComparingTo("120000.00");
        }

        @Test
        @DisplayName("policy starts before period → zero received this period")
        void startBeforePeriod() {
            var p = policy(LocalDate.of(2025, 12, 1), LocalDate.of(2026, 11, 30), "120000.00");
            assertThat(LrcEngine.receivedAmount(p, JAN_1, JAN_31)).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("policy starts after period → zero received this period")
        void startAfterPeriod() {
            var p = policy(FEB_1, LocalDate.of(2027, 1, 31), "120000.00");
            assertThat(LrcEngine.receivedAmount(p, JAN_1, JAN_31)).isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("opening / closing balance identities")
    class OpeningClosingIdentity {

        @Test
        @DisplayName("opening for the inception period = full premium")
        void openingAtInception() {
            var p = policy(JAN_1, DEC_31, "120000.00");
            // Policy starts on period.start, so 365 days remaining at period.start
            // 120000 × 365/365 = 120000.00
            assertThat(LrcEngine.openingAmount(p, JAN_1, JAN_31)).isEqualByComparingTo("120000.00");
        }

        @Test
        @DisplayName("closing after final period = 0")
        void closingAtExpiry() {
            var p = policy(JAN_1, DEC_31, "120000.00");
            assertThat(LrcEngine.closingAmount(p, DEC_1, DEC_31)).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("roll-forward identity holds: opening + received − earned = closing (mid-policy period)")
        void rollForwardIdentity() {
            // Policy: 365 days, ₦120,000 starting Jan 1; March 2026 period
            var p = policy(JAN_1, DEC_31, "120000.00");

            BigDecimal opening = LrcEngine.openingAmount(p, MAR_1, MAR_31);   // days remaining Mar 1
            BigDecimal received = LrcEngine.receivedAmount(p, MAR_1, MAR_31); // 0 (Jan-start policy)
            BigDecimal earned = LrcEngine.earnedAmount(p, MAR_1, MAR_31);     // March's 31 days
            BigDecimal closing = LrcEngine.closingAmount(p, MAR_1, MAR_31);   // days remaining Apr 1

            BigDecimal rollForward = opening.add(received).subtract(earned);
            // Within 1 kobo (HALF_UP rounding spread across opening + earned + closing
            // can drift by at most one penny per term).
            assertThat(rollForward.subtract(closing).abs())
                .as("roll-forward identity must hold within 0.01")
                .isLessThanOrEqualTo(new BigDecimal("0.01"));
        }
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("single-day policy fully active in period earns full premium")
        void singleDayPolicy() {
            var p = policy(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 15), "1000.00");
            assertThat(LrcEngine.earnedAmount(p, JAN_1, JAN_31)).isEqualByComparingTo("1000.00");
            // Premium received during period, opening = full premium (policy hasn't started at period.start),
            // closing = 0 (policy expired before period.end).
            assertThat(LrcEngine.receivedAmount(p, JAN_1, JAN_31)).isEqualByComparingTo("1000.00");
            assertThat(LrcEngine.openingAmount(p, JAN_1, JAN_31)).isEqualByComparingTo("1000.00");
            assertThat(LrcEngine.closingAmount(p, JAN_1, JAN_31)).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("end-of-year period for a partial-overlap policy")
        void yearEndCrossover() {
            // Policy Jul 1 → Jun 30 next year, ₦100,000
            var p = policy(LocalDate.of(2026, 7, 1), LocalDate.of(2027, 6, 30), "100000.00");
            // total days = 365; active in Dec = 31
            // 100000 × 31/365 = 8493.1506... → 8493.15
            assertThat(LrcEngine.earnedAmount(p, DEC_1, DEC_31)).isEqualByComparingTo("8493.15");
        }
    }
}
