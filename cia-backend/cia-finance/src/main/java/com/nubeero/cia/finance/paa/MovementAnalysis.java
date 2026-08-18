package com.nubeero.cia.finance.paa;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * IFRS 17 §103 movement analysis for one fiscal period — the full LRC and
 * LIC roll-forward presented in the disclosure shape auditors expect.
 *
 * <p>Module 12 Phase 2 Slice 2.8 — pure read-side projection over
 * {@code paa_lrc} + {@code paa_lic} via the {@code paa_movement_analysis}
 * SQL view (V38). No state, no JE — just a stable disclosure shape.
 *
 * <h2>§103 invariants encoded in this DTO</h2>
 * <pre>
 *   LRC: opening + received − earned + lossComponentChange = closing
 *        (+ acquisition cost movements, all zero in v1)
 *
 *   LIC: opening + claimsIncurred − claimsPaid + caseReserveChange
 *        + ibnrChange + raChange + discountUnwind = closing
 *
 *   Insurance contract liability = LRC + LIC
 * </pre>
 *
 * <h2>v1 fields populated</h2>
 * <ul>
 *   <li>LRC: opening, premium received, premium earned, loss component
 *       (Slice 2.7) → closing</li>
 *   <li>LIC: opening, claims incurred, claims paid, discount unwind
 *       (Slice 2.6) → closing</li>
 *   <li>Acquisition costs deferred/amortised, IBNR, RA: all zero in v1
 *       (DB columns ready for Slice 2.7b's actuarial extensions)</li>
 *   <li>Contract nature (FAC / IFRS-17 PAA workstream Task 6): DIRECT /
 *       FAC_INWARD / FAC_OUTWARD, sourced from {@code portfolio.contract_nature}
 *       via the V78-recreated {@code paa_movement_analysis} view</li>
 * </ul>
 */
public record MovementAnalysis(

    UUID periodId,
    LocalDate periodStart,
    LocalDate periodEnd,
    LrcMovementTotals lrcTotals,
    LicMovementTotals licTotals,
    BigDecimal totalOpeningLiability,
    BigDecimal totalClosingLiability,
    List<GroupMovementEntry> byGroup

) {

    /** Aggregate LRC movement per §103(a) — group-of-contracts level summed across the period. */
    public record LrcMovementTotals(
        BigDecimal opening,
        BigDecimal premiumsReceived,
        BigDecimal premiumEarned,
        BigDecimal acquisitionCostsDeferred,
        BigDecimal acquisitionCostsAmortised,
        BigDecimal lossComponent,
        BigDecimal lossComponentChange,
        BigDecimal closing
    ) {}

    /** Aggregate LIC movement per §103(b). */
    public record LicMovementTotals(
        BigDecimal opening,
        BigDecimal claimsIncurred,
        BigDecimal claimsPaid,
        BigDecimal caseReserveChange,
        BigDecimal ibnrEstimate,
        BigDecimal ibnrChange,
        BigDecimal riskAdjustment,
        BigDecimal riskAdjustmentChange,
        BigDecimal discountUnwind,
        BigDecimal closing
    ) {}

    /**
     * Per-(portfolio × cohort × onerousness) row — preserves the §22
     * grouping in the disclosure. Auditors require the breakdown.
     */
    public record GroupMovementEntry(
        UUID groupId,
        String portfolioCode,
        String portfolioName,
        Integer cohortYear,
        String onerousness,
        String groupStatus,

        // LRC side
        BigDecimal lrcOpening,
        BigDecimal premiumReceived,
        BigDecimal premiumEarned,
        BigDecimal acquisitionCostsDeferred,
        BigDecimal acquisitionCostsAmortised,
        BigDecimal lossComponent,
        BigDecimal lossComponentChange,
        BigDecimal lrcClosing,

        // LIC side
        BigDecimal licOpening,
        BigDecimal claimsIncurred,
        BigDecimal claimsPaid,
        BigDecimal caseReserveChange,
        BigDecimal ibnrEstimate,
        BigDecimal ibnrChange,
        BigDecimal riskAdjustment,
        BigDecimal riskAdjustmentChange,
        BigDecimal discountUnwind,
        BigDecimal licClosing,

        // Combined
        BigDecimal totalOpening,
        BigDecimal totalClosing,
        String currencyCode,

        // Contract nature (V76/V78) — DIRECT / FAC_INWARD / FAC_OUTWARD.
        // Distinguishes direct-policy groups from facultative reinsurance
        // (inward/outward) groups in the §103 disclosure.
        String contractNature
    ) {}
}
