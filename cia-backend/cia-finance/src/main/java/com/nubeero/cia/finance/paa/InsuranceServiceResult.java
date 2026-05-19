package com.nubeero.cia.finance.paa;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * IFRS 17 §83 / §84 Insurance Service Result for one fiscal period —
 * derived from paa_lrc (revenue) and paa_lic (service expense).
 *
 * <pre>
 *   Insurance service result = Insurance revenue − Insurance service expense
 * </pre>
 *
 * Module 12 Phase 2 Slice 2.5. Pure read-side aggregation — computed on
 * demand from the paa_lrc + paa_lic roll-forward rows written by
 * {@link LrcEngine} and {@link LicEngine}. The Insurance Service Result
 * is not posted as a JE; the underlying components are already in the GL
 * (Cr 4110 from LrcEngine; Dr 5110 from SubledgerPostingService).
 *
 * <h2>v1 service expense composition</h2>
 * <p>Per §84, insurance service expense comprises:
 * <ul>
 *   <li>(a) incurred claims — {@code paa_lic.claims_incurred} (in v1 mapped 1:1 to Dr 5110)</li>
 *   <li>(b) other directly attributable expenses — out of scope in v1</li>
 *   <li>(c) acquisition cash-flow amortisation — v1 uses EXPENSE_AS_INCURRED</li>
 *   <li>(d) changes in past-service LIC — {@code paa_lic.case_reserve_change +
 *       ibnr_change + risk_adjustment_change} (all zero in v1)</li>
 *   <li>(e) onerous-group losses + reversals — Slice 2.7</li>
 * </ul>
 * So v1's service expense ≈ {@code paa_lic.claims_incurred}. The future
 * slices populate the zero-in-v1 columns and the formula adapts without
 * changing this DTO shape.
 */
public record InsuranceServiceResult(

    UUID periodId,
    LocalDate periodStart,
    LocalDate periodEnd,
    BigDecimal totalInsuranceRevenue,
    BigDecimal totalInsuranceServiceExpense,
    BigDecimal totalInsuranceServiceResult,
    List<GroupResult> byGroup

) {

    /** Per-(portfolio, cohort, onerousness) breakdown for §103 disclosure detail. */
    public record GroupResult(
        UUID groupId,
        String portfolioCode,
        Integer cohortYear,
        String onerousness,
        BigDecimal insuranceRevenue,
        BigDecimal insuranceServiceExpense,
        BigDecimal insuranceServiceResult
    ) {}
}
