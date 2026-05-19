package com.nubeero.cia.finance.ifrs9;

import com.nubeero.cia.finance.dto.JournalEntryLineRequest;
import com.nubeero.cia.finance.dto.JournalEntryResponse;
import com.nubeero.cia.finance.dto.PostJournalEntryRequest;
import com.nubeero.cia.finance.gl.FiscalPeriod;
import com.nubeero.cia.finance.gl.FiscalPeriodNotFoundException;
import com.nubeero.cia.finance.gl.FiscalPeriodRepository;
import com.nubeero.cia.finance.gl.JournalEntryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * IFRS 9 §5.7 fair-value remeasurement engine. Module 12 Phase 3 Slice 3.4.
 *
 * <p>For each holding the admin supplies a fair value for, the engine
 * computes the FV change relative to its prior carrying state and posts
 * the appropriate JE (P&amp;L for FVPL, OCI reserve for FVOCI debt/equity).
 *
 * <h2>Eligible classifications</h2>
 * <ul>
 *   <li>{@link InvestmentClassification#FVPL} — gains to 4250 (Income),
 *       losses to 5330 (Expense)</li>
 *   <li>{@link InvestmentClassification#FVOCI_DEBT} — gains/losses to 3410
 *       (FVOCI debt reserve; recycled to P&amp;L on derecognition per §5.7.10)</li>
 *   <li>{@link InvestmentClassification#FVOCI_EQUITY} — gains/losses to 3420
 *       (FVOCI equity reserve; never recycled per §B5.7.1)</li>
 * </ul>
 *
 * <p>{@link InvestmentClassification#AMORTISED_COST} holdings are not
 * remeasured — they stay at amortised cost; only Slice 3.3 acts on them.
 *
 * <h2>Coexistence with AmortisedCostEngine for FVOCI_DEBT</h2>
 * <p>Slice 3.3's AC engine runs first for FVOCI_DEBT and writes an
 * {@link InvestmentCarryingValue} row with {@code effective_interest_income}.
 * This engine then <em>updates</em> the same row (adding
 * {@code fair_value_change_oci} and {@code closing_fair_value}) rather
 * than inserting a duplicate that would violate
 * {@code uq_investment_carrying_holding_period}. For FVPL + FVOCI_EQUITY
 * where the AC engine doesn't run, this engine inserts a fresh row.
 *
 * <h2>Pre-FV balance</h2>
 * <p>The "prior carrying state" used to compute the FV change is:
 * <ul>
 *   <li>If a carrying-value row already exists for this period: that row's
 *       {@code opening + effective_interest_income} (FVOCI_DEBT case after
 *       AC engine has run);</li>
 *   <li>Otherwise: prior period's closing, falling back to acquisition_cost
 *       for the first-period case.</li>
 * </ul>
 *
 * <h2>Idempotency</h2>
 * <p>{@code closing_fair_value IS NULL} on the carrying-value row is the
 * natural sentinel. AC holdings keep it null forever (not FV-measured);
 * FVPL/FVOCI holdings have it null before this engine runs and set after.
 * Re-runs that find a non-null value skip — no separate exception type
 * needed for the per-row case.
 *
 * <h2>v1 simplifications</h2>
 * <ul>
 *   <li>Fair value is admin-provided per holding. v2 will pull from a
 *       market-data feed.</li>
 *   <li>FX gains/losses on foreign-currency investments not split out.
 *       The total FV change captures both real FV movement and FX.</li>
 *   <li>No SICR stage transition driven by FV change (Slice 3.5 owns ECL
 *       stage logic for AC + FVOCI_DEBT).</li>
 * </ul>
 */
@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class FairValueEngine {

    // ── Investment account by classification ─────────────────────────────────
    /** FVPL - Equity securities (V32). */
    static final String COA_FVPL_EQUITY = "1210";
    /** FVPL - Debt securities (V32). */
    static final String COA_FVPL_DEBT = "1220";
    /** FVOCI - Debt securities (V32). */
    static final String COA_FVOCI_DEBT = "1230";
    /** FVOCI - Equity securities elected (V32). */
    static final String COA_FVOCI_EQUITY = "1240";

    // ── P&L destinations (FVPL routing) ──────────────────────────────────────
    /** Unrealised FV gains - FVPL (V32, ifrs9_role=FVPL_GAINS). */
    static final String COA_FVPL_GAINS = "4250";
    /** Unrealised FV losses - FVPL (V32, ifrs9_role=FVPL_LOSSES). */
    static final String COA_FVPL_LOSSES = "5330";

    // ── OCI destinations (FVOCI routing) ─────────────────────────────────────
    /** FVOCI debt reserve (V32, ifrs9_role=OCI_DEBT_RESERVE). */
    static final String COA_OCI_DEBT_RESERVE = "3410";
    /** FVOCI equity reserve (V32, ifrs9_role=OCI_EQUITY_RESERVE). */
    static final String COA_OCI_EQUITY_RESERVE = "3420";

    static final String MODULE_IFRS9 = "ifrs9";
    static final String EVENT_FAIR_VALUE_REMEASUREMENT = "FAIR_VALUE_REMEASUREMENT";

    private static final int MONEY_SCALE = 2;

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final InvestmentHoldingRepository holdingRepository;
    private final InvestmentCarryingValueRepository carryingValueRepository;
    private final JournalEntryService journalEntryService;

    /**
     * Recognise fair-value remeasurement for {@code periodId} across the
     * provided valuations.
     *
     * @param valuationsByHolding admin-supplied FV per holding id. Holdings
     *                            absent from the map are not processed.
     */
    public FairValueResult recognise(UUID periodId, Map<UUID, BigDecimal> valuationsByHolding) {
        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));

        log.info("Fair-value recognition starting for period {} ({} → {}); {} valuations",
            periodId, period.getStartDate(), period.getEndDate(), valuationsByHolding.size());

        BigDecimal totalPnl = BigDecimal.ZERO;
        BigDecimal totalOci = BigDecimal.ZERO;
        int withJe = 0;
        List<FairValueResult.HoldingFairValueEntry> entries = new ArrayList<>();

        for (Map.Entry<UUID, BigDecimal> e : valuationsByHolding.entrySet()) {
            UUID holdingId = e.getKey();
            BigDecimal newFv = scale(e.getValue());

            InvestmentHolding holding = holdingRepository.findById(holdingId)
                .filter(h -> h.getDeletedAt() == null)
                .orElseThrow(() -> new InvestmentHoldingNotFoundException(holdingId));

            if (!isFvEligible(holding.getClassification())) {
                log.warn("Skipping holding {} — classification {} is not FV-eligible",
                    holdingId, holding.getClassification());
                continue;
            }

            Optional<InvestmentCarryingValue> existing =
                carryingValueRepository.findByHoldingIdAndPeriodIdAndDeletedAtIsNull(holdingId, period.getId());

            // Idempotency: a row already carrying a fair value for this period
            // means FV has been recognised. Skip the re-run.
            if (existing.isPresent() && existing.get().getClosingFairValue() != null) {
                log.debug("Skipping holding {} — closing_fair_value already set for period {}",
                    holdingId, periodId);
                continue;
            }

            BigDecimal preFvBalance = preFvBalance(holding, period, existing);
            BigDecimal fvChange = scale(newFv.subtract(preFvBalance));

            UUID jeId = null;
            if (fvChange.signum() != 0) {
                jeId = postJe(holding, period, fvChange);
                withJe++;
            }

            upsertCarryingValue(holding, period, existing, preFvBalance, newFv, fvChange);

            boolean isPnl = holding.getClassification() == InvestmentClassification.FVPL;
            String routing = isPnl ? "PnL" : "OCI";
            if (isPnl) totalPnl = totalPnl.add(fvChange);
            else totalOci = totalOci.add(fvChange);

            entries.add(new FairValueResult.HoldingFairValueEntry(
                holdingId,
                holding.getSecurityName(),
                holding.getClassification(),
                routing,
                preFvBalance,
                newFv,
                fvChange,
                jeId));
        }

        log.info("Fair-value recognition complete for period {} — {} holdings processed, "
                + "{} JEs posted; PnL Δ {}, OCI Δ {}",
            periodId, entries.size(), withJe, scale(totalPnl), scale(totalOci));

        return new FairValueResult(period.getId(), entries.size(), withJe,
            scale(totalPnl), scale(totalOci), entries);
    }

    /** AC holdings are not FV-remeasured. */
    static boolean isFvEligible(InvestmentClassification c) {
        return c == InvestmentClassification.FVPL
            || c == InvestmentClassification.FVOCI_DEBT
            || c == InvestmentClassification.FVOCI_EQUITY;
    }

    /**
     * Determine the carrying balance immediately before this FV recognition.
     * For an FVOCI_DEBT holding the AC engine has already accrued interest in
     * the existing row; that's the right baseline to measure FV change from.
     */
    private BigDecimal preFvBalance(InvestmentHolding holding, FiscalPeriod period,
                                     Optional<InvestmentCarryingValue> existing) {
        if (existing.isPresent()) {
            InvestmentCarryingValue cv = existing.get();
            return scale(cv.getOpeningBalance().add(cv.getEffectiveInterestIncome()));
        }
        return scale(priorClosingOrAcquisition(holding, period));
    }

    private BigDecimal priorClosingOrAcquisition(InvestmentHolding holding, FiscalPeriod period) {
        return carryingValueRepository.findByHoldingIdAndDeletedAtIsNullOrderByPeriodIdAsc(holding.getId())
            .stream()
            .filter(cv -> cv.getPeriod().getEndDate().isBefore(period.getStartDate()))
            .reduce((first, second) -> second)
            .map(InvestmentCarryingValue::getClosingBalance)
            .orElse(holding.getAcquisitionCost());
    }

    private InvestmentCarryingValue upsertCarryingValue(InvestmentHolding holding, FiscalPeriod period,
                                                        Optional<InvestmentCarryingValue> existing,
                                                        BigDecimal preFvBalance,
                                                        BigDecimal newFv, BigDecimal fvChange) {
        InvestmentCarryingValue cv = existing.orElseGet(() -> {
            InvestmentCarryingValue fresh = new InvestmentCarryingValue();
            fresh.setHolding(holding);
            fresh.setPeriod(period);
            fresh.setOpeningBalance(scale(preFvBalance));
            fresh.setCurrencyCode(holding.getCurrencyCode());
            fresh.setEclStage(holding.getEclStage());
            return fresh;
        });

        // FVPL → P&L bucket; FVOCI_DEBT / FVOCI_EQUITY → OCI bucket
        if (holding.getClassification() == InvestmentClassification.FVPL) {
            cv.setFairValueChangePnl(scale(cv.getFairValueChangePnl().add(fvChange)));
        } else {
            cv.setFairValueChangeOci(scale(cv.getFairValueChangeOci().add(fvChange)));
        }
        cv.setClosingBalance(scale(newFv));
        cv.setClosingFairValue(scale(newFv));
        return carryingValueRepository.save(cv);
    }

    private UUID postJe(InvestmentHolding holding, FiscalPeriod period, BigDecimal fvChange) {
        AccountRouting routing = routeJeFor(holding, fvChange);
        BigDecimal abs = fvChange.abs();
        String idempotencyRef = period.getId() + ":" + holding.getId();

        JournalEntryLineRequest debit = new JournalEntryLineRequest(
            routing.debit(), abs, BigDecimal.ZERO, holding.getCurrencyCode(),
            null, null, null,
            holding.getId(),
            null);

        JournalEntryLineRequest credit = new JournalEntryLineRequest(
            routing.credit(), BigDecimal.ZERO, abs, holding.getCurrencyCode(),
            null, null, null,
            holding.getId(),
            null);

        PostJournalEntryRequest request = new PostJournalEntryRequest(
            period.getEndDate(),
            MODULE_IFRS9,
            EVENT_FAIR_VALUE_REMEASUREMENT,
            idempotencyRef,
            "Fair-value " + (fvChange.signum() > 0 ? "gain" : "loss") + " for holding "
                + holding.getSecurityName() + " (" + holding.getClassification() + ") for period "
                + period.getStartDate() + " to " + period.getEndDate(),
            List.of(debit, credit));

        JournalEntryResponse je = journalEntryService.post(request);
        return je.id();
    }

    /**
     * Pure routing helper: (classification, fvChange sign) → (debit, credit)
     * account codes. Static + unit-testable.
     */
    static AccountRouting routeJe(InvestmentClassification classification, BigDecimal fvChange) {
        boolean gain = fvChange.signum() > 0;
        return switch (classification) {
            case FVPL -> {
                // FVPL_DEBT vs FVPL_EQUITY both share gain/loss accounts;
                // the investment account differs but is the gain-side debit
                // or loss-side credit — picked from the holding's asset type
                // at the caller. This helper assumes FVPL_DEBT routing; the
                // engine's postJe overrides for equity via routeJeFor below.
                throw new IllegalArgumentException(
                    "FVPL needs the asset type — call routeJeFor(holding, fvChange) instead");
            }
            case FVOCI_DEBT -> gain
                ? new AccountRouting(COA_FVOCI_DEBT, COA_OCI_DEBT_RESERVE)
                : new AccountRouting(COA_OCI_DEBT_RESERVE, COA_FVOCI_DEBT);
            case FVOCI_EQUITY -> gain
                ? new AccountRouting(COA_FVOCI_EQUITY, COA_OCI_EQUITY_RESERVE)
                : new AccountRouting(COA_OCI_EQUITY_RESERVE, COA_FVOCI_EQUITY);
            case AMORTISED_COST -> throw new IllegalArgumentException(
                "AMORTISED_COST is not FV-eligible");
        };
    }

    /**
     * Production entry point for JE-account routing. Resolves FVPL via
     * asset type (equity → 1210 / debt → 1220), then delegates to
     * {@link #routeJe} for FVOCI. Static + package-private for direct
     * unit-test access.
     */
    static AccountRouting routeJeFor(InvestmentHolding holding, BigDecimal fvChange) {
        if (holding.getClassification() != InvestmentClassification.FVPL) {
            return routeJe(holding.getClassification(), fvChange);
        }
        boolean gain = fvChange.signum() > 0;
        String investmentAccount = holding.getAssetType() == AssetType.EQUITY
            ? COA_FVPL_EQUITY : COA_FVPL_DEBT;
        return gain
            ? new AccountRouting(investmentAccount, COA_FVPL_GAINS)
            : new AccountRouting(COA_FVPL_LOSSES, investmentAccount);
    }

    /** (debit_account_code, credit_account_code) for one FV-change JE. */
    record AccountRouting(String debit, String credit) {}

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
