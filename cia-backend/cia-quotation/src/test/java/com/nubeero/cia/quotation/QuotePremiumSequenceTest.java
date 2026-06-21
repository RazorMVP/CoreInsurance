package com.nubeero.cia.quotation;

import static org.assertj.core.api.Assertions.assertThat;

import com.nubeero.cia.setup.quote.CalcSequence;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the quote per-item premium math in {@link QuoteService}
 * — {@code computeItemNet} (LOADING_FIRST / DISCOUNT_FIRST sequence) and
 * {@code sumAdjustments} (PERCENT vs FLAT). No Spring, no DB: the two methods
 * are stateless and package-private-{@code static}, so they are exercised
 * directly. Every expected value is re-derived by hand in the comments.
 *
 * <p>Part of the {@code money-math-test-coverage} backlog (Slice 2). The
 * production change is visibility-only (private → package-private static) —
 * zero logic change.
 */
class QuotePremiumSequenceTest {

    private static AdjustmentEntry percent(String value) {
        return AdjustmentEntry.builder().format(AdjustmentFormat.PERCENT)
                .value(new BigDecimal(value)).build();
    }

    private static AdjustmentEntry flat(String value) {
        return AdjustmentEntry.builder().format(AdjustmentFormat.FLAT)
                .value(new BigDecimal(value)).build();
    }

    // ── sumAdjustments ─────────────────────────────────────────────────────

    @Test
    void sumAdjustments_percent_isBaseTimesRateOver100_half_up_2dp() {
        // base 100.05 @ 10% = 100.05 × 10 / 100 = 10.005 → HALF_UP 2dp = 10.01
        BigDecimal result = QuoteService.sumAdjustments(List.of(percent("10")), new BigDecimal("100.05"));
        assertThat(result).isEqualByComparingTo("10.01");
    }

    @Test
    void sumAdjustments_flat_isValueScaled2_independentOfBase() {
        // FLAT is base-independent: value 50 → 50.00, regardless of the base
        BigDecimal result = QuoteService.sumAdjustments(List.of(flat("50")), new BigDecimal("999999"));
        assertThat(result).isEqualByComparingTo("50.00");
    }

    @Test
    void sumAdjustments_multipleEntries_areSummed() {
        // base 1000: PERCENT 10% = 100.00, FLAT 50 = 50.00 → 150.00
        BigDecimal result = QuoteService.sumAdjustments(
                List.of(percent("10"), flat("50")), new BigDecimal("1000"));
        assertThat(result).isEqualByComparingTo("150.00");
    }

    @Test
    void sumAdjustments_emptyOrNull_isZero() {
        assertThat(QuoteService.sumAdjustments(List.of(), new BigDecimal("1000")))
                .isEqualByComparingTo("0");
        assertThat(QuoteService.sumAdjustments(null, new BigDecimal("1000")))
                .isEqualByComparingTo("0");
    }

    // ── computeItemNet: sequence semantics ─────────────────────────────────

    @Test
    void loadingFirst_flatLoadingPercentDiscount_appliesDiscountToLoadedPremium() {
        // gross 1000, loading FLAT +200, discount PERCENT 10%
        // LOADING_FIRST: loaded = 1000 + 200 = 1200
        //                discountOnLoaded = 10% of 1200 = 120 → net = 1200 - 120 = 1080
        BigDecimal net = QuoteService.computeItemNet(
                new BigDecimal("1000"), List.of(flat("200")), List.of(percent("10")),
                CalcSequence.LOADING_FIRST);
        assertThat(net).isEqualByComparingTo("1080");
    }

    @Test
    void discountFirst_flatLoadingPercentDiscount_appliesLoadingToDiscountedPremium() {
        // gross 1000, loading FLAT +200, discount PERCENT 10%
        // DISCOUNT_FIRST: discounted = 1000 - 100 = 900
        //                 loadingOnDiscounted = FLAT 200 (base-independent) → net = 900 + 200 = 1100
        BigDecimal net = QuoteService.computeItemNet(
                new BigDecimal("1000"), List.of(flat("200")), List.of(percent("10")),
                CalcSequence.DISCOUNT_FIRST);
        assertThat(net).isEqualByComparingTo("1100");
    }

    @Test
    void sequence_withFlatComponent_ordersMatter() {
        // The same inputs through the two sequences must differ (1080 vs 1100) —
        // proves the FLAT adjustment makes ordering observable (pure-percent would commute).
        BigDecimal loadingFirst = QuoteService.computeItemNet(
                new BigDecimal("1000"), List.of(flat("200")), List.of(percent("10")),
                CalcSequence.LOADING_FIRST);
        BigDecimal discountFirst = QuoteService.computeItemNet(
                new BigDecimal("1000"), List.of(flat("200")), List.of(percent("10")),
                CalcSequence.DISCOUNT_FIRST);
        assertThat(loadingFirst).isNotEqualByComparingTo(discountFirst);
    }

    @Test
    void purePercent_bothSequencesCommute_toSameNet() {
        // gross 1000, loading 20%, discount 10% — pure percent commutes:
        // LOADING_FIRST: 1200 - (10% of 1200 = 120) = 1080
        // DISCOUNT_FIRST: 900 + (20% of 900 = 180) = 1080
        BigDecimal loadingFirst = QuoteService.computeItemNet(
                new BigDecimal("1000"), List.of(percent("20")), List.of(percent("10")),
                CalcSequence.LOADING_FIRST);
        BigDecimal discountFirst = QuoteService.computeItemNet(
                new BigDecimal("1000"), List.of(percent("20")), List.of(percent("10")),
                CalcSequence.DISCOUNT_FIRST);
        assertThat(loadingFirst).isEqualByComparingTo("1080");
        assertThat(discountFirst).isEqualByComparingTo("1080");
    }

    @Test
    void loadingFirst_discountExceedsLoaded_flooredAtZero() {
        // gross 100, no loading, discount PERCENT 150%
        // loaded = 100; discountOnLoaded = 150 → 100 - 150 = -50 → floored to 0
        BigDecimal net = QuoteService.computeItemNet(
                new BigDecimal("100"), List.of(), List.of(percent("150")),
                CalcSequence.LOADING_FIRST);
        assertThat(net).isEqualByComparingTo("0");
    }

    @Test
    void discountFirst_discountExceedsGross_flooredAtZero() {
        // gross 100, discount FLAT 150 → discounted = max(100 - 150, 0) = 0; no loading → 0
        BigDecimal net = QuoteService.computeItemNet(
                new BigDecimal("100"), List.of(), List.of(flat("150")),
                CalcSequence.DISCOUNT_FIRST);
        assertThat(net).isEqualByComparingTo("0");
    }

    @Test
    void noAdjustments_netEqualsGross() {
        assertThat(QuoteService.computeItemNet(
                new BigDecimal("1000"), List.of(), List.of(), CalcSequence.LOADING_FIRST))
                .isEqualByComparingTo("1000");
        assertThat(QuoteService.computeItemNet(
                new BigDecimal("1000"), List.of(), List.of(), CalcSequence.DISCOUNT_FIRST))
                .isEqualByComparingTo("1000");
    }
}
