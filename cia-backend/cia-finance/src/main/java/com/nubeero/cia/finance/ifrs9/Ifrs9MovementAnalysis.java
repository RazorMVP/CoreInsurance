package com.nubeero.cia.finance.ifrs9;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * IFRS 9 §B5.5.39 / IFRS 7 §35M movement analysis for one fiscal period —
 * two disclosure sections combined:
 *
 * <ol>
 *   <li>{@link InvestmentSection} — per-holding roll-forward across all
 *       Slice 3.3–3.5 engine writes, plus aggregate totals by classification.</li>
 *   <li>{@link PremiumReceivableSection} — single aggregate roll-forward
 *       for the simplified-approach premium-receivable ECL allowance
 *       (Slice 3.6).</li>
 * </ol>
 *
 * <p>Module 12 Phase 3 Slice 3.7 — pure read-side projection. No state,
 * no JE. Derived from the {@code ifrs9_investment_movement_analysis} SQL
 * view (V40) and JE aggregates on accounts 5350 / 1340.
 */
public record Ifrs9MovementAnalysis(

    UUID periodId,
    LocalDate periodStart,
    LocalDate periodEnd,
    InvestmentSection investments,
    PremiumReceivableSection premiumReceivableEcl

) {

    /**
     * Per-holding roll-forward + classification totals. Holdings with no
     * carrying-value row this period are excluded (matches the underlying
     * view's WHERE filter).
     */
    public record InvestmentSection(
        InvestmentTotals totals,
        List<HoldingEntry> byHolding
    ) {}

    /**
     * Aggregate movement totals across all investment holdings. Component
     * columns null for classifications where they don't apply.
     */
    public record InvestmentTotals(
        BigDecimal openingBalance,
        BigDecimal effectiveInterestIncome,
        BigDecimal couponReceived,
        BigDecimal fairValueChangePnl,
        BigDecimal fairValueChangeOci,
        BigDecimal eclMovement,
        BigDecimal impairmentLoss,
        BigDecimal disposals,
        BigDecimal closingBalance,
        BigDecimal totalPnlIncome,
        BigDecimal totalOciMovement
    ) {}

    public record HoldingEntry(
        UUID holdingId,
        String isin,
        String securityName,
        String issuer,
        AssetType assetType,
        InvestmentClassification classification,
        HoldingStatus holdingStatus,
        String currencyCode,
        LocalDate maturityDate,

        BigDecimal openingBalance,
        BigDecimal effectiveInterestIncome,
        BigDecimal couponReceived,
        BigDecimal fairValueChangePnl,
        BigDecimal fairValueChangeOci,
        BigDecimal eclMovement,
        BigDecimal impairmentLoss,
        BigDecimal disposals,
        BigDecimal closingBalance,
        BigDecimal closingFairValue,
        Integer eclStage,

        BigDecimal totalPnlIncome,
        BigDecimal totalOciMovement
    ) {}

    /**
     * Premium-receivable ECL roll-forward — derived from JE aggregates on
     * accounts 5350 / 1340.
     */
    public record PremiumReceivableSection(
        BigDecimal openingAllowance,
        BigDecimal periodMovement,
        BigDecimal closingAllowance,
        /** "INCREASE", "REVERSAL", or "NO_CHANGE" for the period. */
        String direction
    ) {}
}
