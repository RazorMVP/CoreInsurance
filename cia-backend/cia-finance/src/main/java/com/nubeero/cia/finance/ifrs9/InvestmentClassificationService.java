package com.nubeero.cia.finance.ifrs9;

import com.nubeero.cia.common.exception.CiaException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * IFRS 9 §4.1 classification + §B4.1.26-§B4.1.29 reclassification service.
 * Module 12 Phase 3 Slice 3.2.
 *
 * <p>Two responsibilities:
 * <ol>
 *   <li>{@link #register} — classify a new holding on acquisition using
 *       (assetType, sppiTestPassed, businessModel, fvociEquityElection).
 *       Sets ECL stage = 1 for AC + FVOCI_DEBT (§5.5.5); null otherwise.</li>
 *   <li>{@link #reclassify} — apply a §B4.1.26 business-model-change
 *       reclassification: update the holding's classification AND insert
 *       an {@link InvestmentClassificationHistory} row as the audit trail.
 *       Refuses to reclassify to the same value (DB CHECK enforces this
 *       as a backstop).</li>
 * </ol>
 *
 * <h2>Pure classification function</h2>
 * <p>The classification logic itself ({@link #classify}) is a static pure
 * function over the four inputs — trivially unit-testable, swappable, and
 * matches the existing pattern from
 * {@code LrcEngine.earnedAmount} / {@code OnerousContractTestEngine.targetLossComponent}.
 *
 * <h2>v1 simplifications</h2>
 * <ul>
 *   <li>SPPI flag is admin-provided. v2 may add a contract-term parser
 *       that infers SPPI from coupon-rate structure, prepayment options,
 *       linked-payments etc.</li>
 *   <li>Business model is per-holding rather than per-portfolio. v2 may
 *       collapse repeated values into a portfolio-level setting per §B4.1.2A.</li>
 *   <li>Reclassification accepts any (from, to) pair — auditor's
 *       responsibility to ensure §B4.1.29 conditions are met. v2 may
 *       enforce the "business model change in the period" rule.</li>
 * </ul>
 */
@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class InvestmentClassificationService {

    private final InvestmentHoldingRepository holdingRepository;
    private final InvestmentClassificationHistoryRepository historyRepository;

    /**
     * Register a new holding. Validates the request, computes the §4.1
     * classification, sets ECL stage at recognition (§5.5.5), and persists.
     */
    public InvestmentHolding register(RegisterHoldingRequest request) {
        validateRegister(request);

        InvestmentClassification classification = classify(
            request.assetType(),
            request.sppiTestPassed(),
            request.businessModel(),
            request.fvociEquityElection());

        InvestmentHolding h = new InvestmentHolding();
        h.setIsin(request.isin());
        h.setSecurityName(request.securityName());
        h.setIssuer(request.issuer());
        h.setAssetType(request.assetType());
        h.setClassification(classification);
        h.setAcquisitionDate(request.acquisitionDate());
        h.setAcquisitionCost(request.acquisitionCost());
        h.setFaceValue(request.faceValue());
        // Equity must not carry coupon/maturity (DB CHECK enforces this);
        // strip the fields to be safe even if the caller passes them.
        if (request.assetType() != AssetType.EQUITY) {
            h.setCouponRate(request.couponRate());
            h.setMaturityDate(request.maturityDate());
        }
        h.setCurrencyCode(request.currencyCode() == null ? "NGN" : request.currencyCode());
        h.setStatus(HoldingStatus.ACTIVE);
        h.setSppiTestPassed(request.sppiTestPassed());
        h.setEclStage(initialEclStage(classification));

        InvestmentHolding saved = holdingRepository.save(h);
        log.info("Registered {} holding {} ({}) — classification {}, ECL stage {}",
            saved.getAssetType(), saved.getSecurityName(), saved.getId(),
            classification, saved.getEclStage());
        return saved;
    }

    /**
     * Apply an IFRS 9 §B4.1.26 reclassification. Updates the holding's
     * classification + ECL stage and writes an audit-trail row.
     */
    public InvestmentHolding reclassify(UUID holdingId, ReclassifyHoldingRequest request) {
        InvestmentHolding holding = holdingRepository.findById(holdingId)
            .filter(h -> h.getDeletedAt() == null)
            .orElseThrow(() -> new InvestmentHoldingNotFoundException(holdingId));

        InvestmentClassification previous = holding.getClassification();
        InvestmentClassification next = request.newClassification();

        if (previous == next) {
            throw new CiaException(
                "CLASSIFICATION_UNCHANGED",
                "Holding " + holdingId + " is already classified as " + next + " — no reclassification needed.",
                HttpStatus.UNPROCESSABLE_ENTITY);
        }

        holding.setClassification(next);
        holding.setEclStage(initialEclStage(next));
        holdingRepository.save(holding);

        InvestmentClassificationHistory history = new InvestmentClassificationHistory();
        history.setHolding(holding);
        history.setPreviousClassification(previous);
        history.setNewClassification(next);
        history.setReclassificationDate(request.reclassificationDate());
        history.setReason(request.reason());
        history.setApprovedBy(request.approvedBy());
        historyRepository.save(history);

        log.info("Reclassified holding {} ({}) from {} to {} effective {}",
            holding.getId(), holding.getSecurityName(), previous, next,
            request.reclassificationDate());
        return holding;
    }

    /**
     * Pure IFRS 9 §4.1 classification logic. Static + unit-testable.
     *
     * @param sppiTestPassed required for DEBT / MONEY_MARKET; ignored for EQUITY / DERIVATIVE
     * @param businessModel required for DEBT / MONEY_MARKET; ignored for EQUITY / DERIVATIVE
     * @param fvociEquityElection §5.7.5 election; honoured only for EQUITY
     */
    public static InvestmentClassification classify(
            AssetType assetType,
            Boolean sppiTestPassed,
            BusinessModel businessModel,
            boolean fvociEquityElection) {

        return switch (assetType) {
            case DEBT, MONEY_MARKET -> classifyDebt(sppiTestPassed, businessModel);
            case EQUITY -> fvociEquityElection ? InvestmentClassification.FVOCI_EQUITY
                                               : InvestmentClassification.FVPL;
            case DERIVATIVE -> InvestmentClassification.FVPL;
        };
    }

    private static InvestmentClassification classifyDebt(Boolean sppi, BusinessModel bm) {
        if (sppi == null || !sppi) return InvestmentClassification.FVPL;
        if (bm == null) {
            throw new CiaException(
                "BUSINESS_MODEL_REQUIRED",
                "Business model is required for DEBT / MONEY_MARKET holdings to determine classification.",
                HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return switch (bm) {
            case HOLD_TO_COLLECT -> InvestmentClassification.AMORTISED_COST;
            case HOLD_TO_COLLECT_AND_SELL -> InvestmentClassification.FVOCI_DEBT;
            case SELL_FIRST -> InvestmentClassification.FVPL;
        };
    }

    /** §5.5.5: AC and FVOCI_DEBT holdings start at stage 1; FVPL and FVOCI_EQUITY have no ECL. */
    private static Integer initialEclStage(InvestmentClassification c) {
        return (c == InvestmentClassification.AMORTISED_COST
             || c == InvestmentClassification.FVOCI_DEBT) ? 1 : null;
    }

    private static void validateRegister(RegisterHoldingRequest request) {
        AssetType t = request.assetType();
        if (t == AssetType.DEBT || t == AssetType.MONEY_MARKET) {
            if (request.sppiTestPassed() == null) {
                throw new CiaException(
                    "SPPI_REQUIRED",
                    "sppiTestPassed is required for " + t + " holdings — admin must determine SPPI from contract terms.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
            }
            if (request.businessModel() == null) {
                throw new CiaException(
                    "BUSINESS_MODEL_REQUIRED",
                    "businessModel is required for " + t + " holdings.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
            }
        }
        if (request.acquisitionCost() == null
                || request.acquisitionCost().signum() < 0) {
            throw new CiaException(
                "ACQUISITION_COST_INVALID",
                "acquisitionCost must be non-negative.",
                HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
}
