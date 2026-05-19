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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * IFRS 9 §5.4.1 effective-interest-method engine. Recognises periodic
 * interest income for holdings classified as
 * {@link InvestmentClassification#AMORTISED_COST} or
 * {@link InvestmentClassification#FVOCI_DEBT}. Module 12 Phase 3 Slice 3.3.
 *
 * <p>For each eligible holding the engine:
 * <ol>
 *   <li>Determines the active window in the period
 *       {@code [max(acquisition, period.start), min(maturity, period.end)]};</li>
 *   <li>Looks up the opening balance — prior period's closing, or
 *       {@code acquisition_cost} when no prior carrying-value row exists;</li>
 *   <li>Computes interest income
 *       {@code opening × effective_rate × active_days / 365};</li>
 *   <li>Posts a JE through the gateway:
 *     <ul>
 *       <li>{@code AC + DEBT}: Dr 1250 / Cr 4210</li>
 *       <li>{@code AC + MONEY_MARKET}: Dr 1140 / Cr 4210</li>
 *       <li>{@code FVOCI_DEBT}: Dr 1230 / Cr 4220</li>
 *     </ul>
 *     Line dimensions are tagged with {@code holding_id}.</li>
 *   <li>Writes the {@link InvestmentCarryingValue} row:
 *       {@code closing = opening + interest_income}.</li>
 * </ol>
 *
 * <h2>v1 simplifications</h2>
 * <ul>
 *   <li><b>Effective Interest Rate = stated coupon rate</b> (par-acquisition
 *       assumption). Premium/discount amortisation requires a Newton-Raphson
 *       solver to back out the true EIR from
 *       {@code (acquisition_cost, face_value, coupon, maturity)}; that's
 *       Slice 3.3b.</li>
 *   <li><b>Interest applied to opening balance</b>, not within-period
 *       compounding. For par bonds (v1 supported case) the two are
 *       equivalent because carrying stays equal to face value.</li>
 *   <li><b>Coupon receipts are out of scope.</b> When a coupon arrives, the
 *       Finance module posts {@code Dr 1120 (Bank) / Cr <investment>}
 *       reducing the investment account back to face value. The carrying
 *       value table is the IFRS 9 measurement view; cash flow is a
 *       separate sub-ledger flow.</li>
 *   <li><b>Disposal at maturity is out of scope.</b> A future slice will
 *       reclassify MATURED holdings and zero out their carrying value.</li>
 *   <li><b>Holdings without a coupon rate are skipped</b> (logged warning).
 *       Zero-coupon bonds + money-market instruments without a stated rate
 *       need a v2 yield-to-maturity-based engine.</li>
 * </ul>
 *
 * <h2>Idempotency</h2>
 * <p>Three layers, same pattern as Phase 2 engines:
 * <ul>
 *   <li>DB: {@code uq_investment_carrying_holding_period} in V39 rejects
 *       duplicate rows.</li>
 *   <li>JE gateway: {@code uq_journal_entry_idempotency} on
 *       (source_module, source_event_type, source_reference). Reference
 *       is {@code period_id:holding_id}.</li>
 *   <li>Service: explicit pre-check raises
 *       {@link AmortisedCostAlreadyDoneException} (409 CONFLICT) before
 *       any partial-write side effect.</li>
 * </ul>
 */
@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class AmortisedCostEngine {

    /** Amortised cost - Debt securities. V32 seed. */
    static final String COA_AC_DEBT = "1250";

    /** Money market instruments (held at AC by default). V32 seed. */
    static final String COA_AC_MONEY_MARKET = "1140";

    /** FVOCI - Debt securities. V32 seed. */
    static final String COA_FVOCI_DEBT = "1230";

    /** Interest income - Amortised cost. V32 seed, ifrs9_role=INTEREST_AC. */
    static final String COA_INTEREST_AC = "4210";

    /** Interest income - FVOCI debt. V32 seed, ifrs9_role=INTEREST_FVOCI. */
    static final String COA_INTEREST_FVOCI = "4220";

    static final String MODULE_IFRS9 = "ifrs9";
    static final String EVENT_AMORTISED_COST_INTEREST = "AMORTISED_COST_INTEREST";

    private static final int MONEY_SCALE = 2;
    private static final int FRACTION_SCALE = 12;
    private static final BigDecimal DAYS_PER_YEAR = BigDecimal.valueOf(365L);

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final InvestmentHoldingRepository holdingRepository;
    private final InvestmentCarryingValueRepository carryingValueRepository;
    private final JournalEntryService journalEntryService;

    /**
     * Recognise effective-interest-method interest income for {@code periodId}
     * across every eligible (AC + FVOCI_DEBT, ACTIVE) holding.
     */
    public AmortisedCostResult recognise(UUID periodId) {
        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));

        log.info("Amortised-cost recognition starting for period {} ({} → {})",
            periodId, period.getStartDate(), period.getEndDate());

        BigDecimal totalInterest = BigDecimal.ZERO;
        int withJe = 0;
        List<AmortisedCostResult.HoldingInterestEntry> entries = new ArrayList<>();

        for (InvestmentHolding holding : eligibleHoldings()) {
            // Idempotency: refuse to re-run if a carrying-value row already
            // exists for this (holding, period).
            if (carryingValueRepository
                    .findByHoldingIdAndPeriodIdAndDeletedAtIsNull(holding.getId(), period.getId())
                    .isPresent()) {
                throw new AmortisedCostAlreadyDoneException(period.getId(), holding.getId());
            }

            long activeDays = activeDays(holding, period);
            if (activeDays == 0) {
                log.debug("Skipping holding {} — no active days in period {}", holding.getId(), periodId);
                continue;
            }
            if (holding.getCouponRate() == null) {
                log.warn("Skipping holding {} — no coupon rate; v1 requires stated rate for interest recognition",
                    holding.getId());
                continue;
            }

            BigDecimal opening = openingBalance(holding, period);
            BigDecimal interest = computeInterest(opening, holding.getCouponRate(), activeDays);
            BigDecimal closing = opening.add(interest);

            UUID jeId = null;
            if (interest.signum() > 0) {
                jeId = postJe(holding, period, interest);
                withJe++;
                totalInterest = totalInterest.add(interest);
            }

            InvestmentCarryingValue cv = persistCarryingValue(holding, period, opening, interest, closing);

            entries.add(new AmortisedCostResult.HoldingInterestEntry(
                holding.getId(),
                holding.getSecurityName(),
                holding.getClassification(),
                cv.getOpeningBalance(),
                cv.getEffectiveInterestIncome(),
                cv.getClosingBalance(),
                jeId));
        }

        log.info("Amortised-cost recognition complete for period {} — {} holdings processed, "
                + "{} JEs posted, total interest {}",
            periodId, entries.size(), withJe, totalInterest);

        return new AmortisedCostResult(period.getId(), entries.size(), withJe,
            scale(totalInterest), entries);
    }

    /**
     * Pure interest computation:
     * {@code opening × annualRate × (activeDays / 365)} rounded HALF_UP at
     * {@code MONEY_SCALE}. Static + unit-testable.
     */
    static BigDecimal computeInterest(BigDecimal opening, BigDecimal annualRate, long activeDays) {
        if (opening == null || opening.signum() == 0
                || annualRate == null || annualRate.signum() == 0
                || activeDays == 0) {
            return BigDecimal.ZERO;
        }
        return opening
            .multiply(annualRate)
            .multiply(BigDecimal.valueOf(activeDays))
            .divide(DAYS_PER_YEAR, FRACTION_SCALE, RoundingMode.HALF_UP)
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Inclusive active-day count: holding contributes interest for days in
     * the intersection of (acquisition_date..maturity_date) and
     * (period.start..period.end). Mirrors {@code LrcEngine.earnedAmount}.
     */
    static long activeDays(InvestmentHolding holding, FiscalPeriod period) {
        LocalDate periodStart = period.getStartDate();
        LocalDate periodEnd = period.getEndDate();
        LocalDate holdingStart = holding.getAcquisitionDate();
        LocalDate holdingEnd = holding.getMaturityDate() != null ? holding.getMaturityDate() : periodEnd;

        LocalDate activeStart = holdingStart.isAfter(periodStart) ? holdingStart : periodStart;
        LocalDate activeEnd = holdingEnd.isBefore(periodEnd) ? holdingEnd : periodEnd;

        if (activeStart.isAfter(activeEnd)) return 0L;
        return ChronoUnit.DAYS.between(activeStart, activeEnd) + 1L;
    }

    /**
     * v1 opening balance: prior period's closing, or acquisition_cost if no
     * prior carrying-value row exists. For par bonds this stays equal to
     * acquisition cost across all periods (interest paid as coupon →
     * cash receipt zeroes the increment outside this engine).
     */
    private BigDecimal openingBalance(InvestmentHolding holding, FiscalPeriod period) {
        Optional<InvestmentCarryingValue> latest =
            carryingValueRepository.findByHoldingIdAndDeletedAtIsNullOrderByPeriodIdAsc(holding.getId())
                .stream()
                .filter(cv -> cv.getPeriod().getEndDate().isBefore(period.getStartDate()))
                .reduce((first, second) -> second);
        return latest.map(InvestmentCarryingValue::getClosingBalance)
            .orElse(holding.getAcquisitionCost());
    }

    private List<InvestmentHolding> eligibleHoldings() {
        List<InvestmentHolding> result = new ArrayList<>();
        result.addAll(holdingRepository
            .findByClassificationAndDeletedAtIsNullOrderBySecurityNameAsc(InvestmentClassification.AMORTISED_COST));
        result.addAll(holdingRepository
            .findByClassificationAndDeletedAtIsNullOrderBySecurityNameAsc(InvestmentClassification.FVOCI_DEBT));
        result.removeIf(h -> h.getStatus() != HoldingStatus.ACTIVE);
        return result;
    }

    private UUID postJe(InvestmentHolding holding, FiscalPeriod period, BigDecimal interest) {
        String debitAccount = debitAccountFor(holding);
        String creditAccount = creditAccountFor(holding);
        String idempotencyRef = period.getId() + ":" + holding.getId();

        JournalEntryLineRequest debit = new JournalEntryLineRequest(
            debitAccount, interest, BigDecimal.ZERO, holding.getCurrencyCode(),
            null, null, null,
            holding.getId(),
            null);

        JournalEntryLineRequest credit = new JournalEntryLineRequest(
            creditAccount, BigDecimal.ZERO, interest, holding.getCurrencyCode(),
            null, null, null,
            holding.getId(),
            null);

        PostJournalEntryRequest request = new PostJournalEntryRequest(
            period.getEndDate(),
            MODULE_IFRS9,
            EVENT_AMORTISED_COST_INTEREST,
            idempotencyRef,
            "Effective-interest-method interest for holding " + holding.getSecurityName()
                + " (" + holding.getClassification() + ") for period "
                + period.getStartDate() + " to " + period.getEndDate(),
            List.of(debit, credit));

        JournalEntryResponse je = journalEntryService.post(request);
        return je.id();
    }

    private static String debitAccountFor(InvestmentHolding holding) {
        return switch (holding.getClassification()) {
            case AMORTISED_COST -> holding.getAssetType() == AssetType.MONEY_MARKET
                ? COA_AC_MONEY_MARKET : COA_AC_DEBT;
            case FVOCI_DEBT -> COA_FVOCI_DEBT;
            // Defensive — eligibleHoldings() filter should exclude these:
            case FVPL, FVOCI_EQUITY -> throw new IllegalStateException(
                "AmortisedCostEngine cannot route " + holding.getClassification() + " holdings");
        };
    }

    private static String creditAccountFor(InvestmentHolding holding) {
        return switch (holding.getClassification()) {
            case AMORTISED_COST -> COA_INTEREST_AC;
            case FVOCI_DEBT -> COA_INTEREST_FVOCI;
            case FVPL, FVOCI_EQUITY -> throw new IllegalStateException(
                "AmortisedCostEngine cannot route " + holding.getClassification() + " holdings");
        };
    }

    private InvestmentCarryingValue persistCarryingValue(InvestmentHolding holding, FiscalPeriod period,
                                                          BigDecimal opening, BigDecimal interest,
                                                          BigDecimal closing) {
        InvestmentCarryingValue cv = new InvestmentCarryingValue();
        cv.setHolding(holding);
        cv.setPeriod(period);
        cv.setOpeningBalance(scale(opening));
        cv.setEffectiveInterestIncome(scale(interest));
        cv.setClosingBalance(scale(closing));
        cv.setCurrencyCode(holding.getCurrencyCode());
        cv.setEclStage(holding.getEclStage());
        return carryingValueRepository.save(cv);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
