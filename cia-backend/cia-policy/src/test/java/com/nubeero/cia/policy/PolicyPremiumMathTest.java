package com.nubeero.cia.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the policy direct-entry premium + commission math in
 * {@link PolicyService} — {@code recalculateTotals} (Σ sum-insured, Σ premium,
 * the {@code discount.min(totalPremium)} cap, net premium) and
 * {@code computeCommissionAmount} ({@code net × rate / 100}, HALF_UP 2dp).
 * Both are stateless and package-private-{@code static}, so they are exercised
 * directly with hand-built entities. Every expected value is re-derived in the
 * comments.
 *
 * <p>Part of the {@code money-math-test-coverage} backlog (Slice 2). The
 * production change is visibility-only (private → package-private static).
 */
class PolicyPremiumMathTest {

    private static PolicyRisk risk(String sumInsured, String premium) {
        return PolicyRisk.builder()
                .sumInsured(new BigDecimal(sumInsured))
                .premium(new BigDecimal(premium))
                .build();
    }

    private static Policy policyWithRisks(List<PolicyRisk> risks) {
        return Policy.builder().risks(new ArrayList<>(risks)).build();
    }

    // ── recalculateTotals ──────────────────────────────────────────────────

    @Test
    void recalculateTotals_sumsRisks_andAppliesDiscount() {
        // risk A: SI 1,000,000 premium 50,000 ; risk B: SI 2,000,000 premium 100,000
        // totalSI = 3,000,000 ; totalPremium = 150,000
        // discount 30,000 ≤ premium → effective 30,000 ; net = 150,000 - 30,000 = 120,000
        Policy policy = policyWithRisks(List.of(
                risk("1000000", "50000"), risk("2000000", "100000")));

        PolicyService.recalculateTotals(policy, new BigDecimal("30000"));

        assertThat(policy.getTotalSumInsured()).isEqualByComparingTo("3000000");
        assertThat(policy.getTotalPremium()).isEqualByComparingTo("150000");
        assertThat(policy.getDiscount()).isEqualByComparingTo("30000");
        assertThat(policy.getNetPremium()).isEqualByComparingTo("120000");
    }

    @Test
    void recalculateTotals_discountCappedAtTotalPremium() {
        // totalPremium = 150,000 ; discount 200,000 > premium → effective capped at 150,000
        // net = 150,000 - 150,000 = 0 (discount can never make net negative)
        Policy policy = policyWithRisks(List.of(
                risk("1000000", "50000"), risk("2000000", "100000")));

        PolicyService.recalculateTotals(policy, new BigDecimal("200000"));

        assertThat(policy.getDiscount()).isEqualByComparingTo("150000");
        assertThat(policy.getNetPremium()).isEqualByComparingTo("0");
    }

    @Test
    void recalculateTotals_zeroDiscount_netEqualsTotalPremium() {
        Policy policy = policyWithRisks(List.of(risk("500000", "25000")));

        PolicyService.recalculateTotals(policy, BigDecimal.ZERO);

        assertThat(policy.getTotalPremium()).isEqualByComparingTo("25000");
        assertThat(policy.getDiscount()).isEqualByComparingTo("0");
        assertThat(policy.getNetPremium()).isEqualByComparingTo("25000");
    }

    @Test
    void recalculateTotals_noRisks_zeroPremium_discountClampedToZero() {
        // no risks → totalPremium 0 ; a positive discount is min'd to 0, net = 0
        Policy policy = policyWithRisks(List.of());

        PolicyService.recalculateTotals(policy, new BigDecimal("50000"));

        assertThat(policy.getTotalSumInsured()).isEqualByComparingTo("0");
        assertThat(policy.getTotalPremium()).isEqualByComparingTo("0");
        assertThat(policy.getDiscount()).isEqualByComparingTo("0");
        assertThat(policy.getNetPremium()).isEqualByComparingTo("0");
    }

    // ── computeCommissionAmount ────────────────────────────────────────────

    @Test
    void computeCommissionAmount_netTimesRateOver100() {
        // net 120,000 @ 10% = 120000 × 10 / 100 = 12,000.00
        Policy policy = Policy.builder()
                .netPremium(new BigDecimal("120000")).commissionRate(new BigDecimal("10")).build();
        assertThat(PolicyService.computeCommissionAmount(policy)).isEqualByComparingTo("12000.00");
    }

    @Test
    void computeCommissionAmount_roundsHalfUpTo2dp() {
        // net 333.33 @ 7.5% = 333.33 × 7.5 / 100 = 24.99975 → HALF_UP 2dp = 25.00
        Policy policy = Policy.builder()
                .netPremium(new BigDecimal("333.33")).commissionRate(new BigDecimal("7.5")).build();
        assertThat(PolicyService.computeCommissionAmount(policy)).isEqualByComparingTo("25.00");
    }

    @Test
    void computeCommissionAmount_nullRate_isNull() {
        Policy policy = Policy.builder().netPremium(new BigDecimal("120000")).commissionRate(null).build();
        assertThat(PolicyService.computeCommissionAmount(policy)).isNull();
    }

    @Test
    void computeCommissionAmount_nullNetPremium_isNull() {
        Policy policy = Policy.builder().netPremium(null).commissionRate(new BigDecimal("10")).build();
        assertThat(PolicyService.computeCommissionAmount(policy)).isNull();
    }
}
