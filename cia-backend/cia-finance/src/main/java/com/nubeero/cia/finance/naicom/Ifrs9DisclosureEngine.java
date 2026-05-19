package com.nubeero.cia.finance.naicom;

import com.nubeero.cia.finance.gl.FiscalPeriod;
import com.nubeero.cia.finance.gl.FiscalPeriodNotFoundException;
import com.nubeero.cia.finance.gl.FiscalPeriodRepository;
import com.nubeero.cia.finance.ifrs9.Ifrs9MovementAnalysis;
import com.nubeero.cia.finance.ifrs9.Ifrs9MovementAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * IFRS 9 §B5.5.39 / IFRS 7 §35M disclosure — period investment + premium-
 * receivable ECL movement analysis in the NAICOM submission payload shape.
 *
 * <p>Module 12 Phase 4 Slice 4.7. Pure read engine — no DB writes, no JE
 * postings. Orchestrator (Slice 4.9) owns the submission upsert + state
 * machine.
 *
 * <h2>Substrate: relays {@link Ifrs9MovementAnalysisService}</h2>
 * <p>The disclosure aggregation (V40 view + premium-receivable JE
 * aggregate) is already centralised inside
 * {@link Ifrs9MovementAnalysisService}. This engine consumes the typed
 * {@link Ifrs9MovementAnalysis} record and adapts it into the
 * {@code Map<String, Object>} envelope shared by every NAICOM submission
 * engine in this phase. Same pattern as Slice 4.6's
 * {@link Ifrs17DisclosureEngine} over Phase 2's V38 disclosure.
 *
 * <h2>Two-section payload</h2>
 * <ul>
 *   <li><b>investments</b> — per-holding roll-forward + aggregate totals
 *       across asset_type / classification / ECL stage. Sourced from the
 *       V40 view.</li>
 *   <li><b>premiumReceivableEcl</b> — single-figure roll-forward of the
 *       1340 allowance derived from JE aggregates (the Slice 3.6
 *       stateless-engine design).</li>
 * </ul>
 *
 * <h2>Payload shape</h2>
 * <pre>
 * {
 *   "submissionType": "IFRS9_DISCLOSURE",
 *   "period":         { "id", "start", "end" },
 *   "generatedAt":    ISO-8601,
 *   "investments":    {
 *      "totals":      { openingBalance, effectiveInterestIncome, couponReceived,
 *                       fairValueChangePnl, fairValueChangeOci,
 *                       eclMovement, impairmentLoss, disposals,
 *                       closingBalance, totalPnlIncome, totalOciMovement },
 *      "byHolding":   [ { holdingId, isin, securityName, issuer,
 *                         assetType, classification, holdingStatus,
 *                         currencyCode, maturityDate,
 *                         openingBalance, effectiveInterestIncome,
 *                         couponReceived, fairValueChangePnl,
 *                         fairValueChangeOci, eclMovement, impairmentLoss,
 *                         disposals, closingBalance, closingFairValue,
 *                         eclStage, totalPnlIncome, totalOciMovement }, ... ]
 *   },
 *   "premiumReceivableEcl": { openingAllowance, periodMovement,
 *                              closingAllowance, direction },
 *   "totals":         { "holdingCount" },
 *   "notes":          "v1 disclosure scope"
 * }
 * </pre>
 *
 * <p>Per-holding rows preserve the ordering chosen by
 * {@link Ifrs9MovementAnalysisService} (classification, security_name) —
 * deterministic across runs.
 *
 * <h2>v1 disclosure scope</h2>
 * <p>Inherits the same limits as Phase 3 itself:
 * <ul>
 *   <li>Amortised-cost engine uses period-step effective interest (not
 *       continuous-compounding); reported figures match the booked
 *       JE-side numbers exactly.</li>
 *   <li>Premium-receivable provision matrix lives in JE narrative; the
 *       roll-forward here surfaces the cumulative allowance but not the
 *       per-bucket breakdown (Slice 4.10 PDF artifact may parse and
 *       render it).</li>
 *   <li>FVOCI_EQUITY does not recycle gains/losses on disposal (§5.7.5);
 *       this is the standard IFRS 9 behaviour and the disclosure payload
 *       does not split realised vs unrealised OCI for that category.</li>
 * </ul>
 */
@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class Ifrs9DisclosureEngine implements NaicomSubmissionEngine {

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final Ifrs9MovementAnalysisService movementAnalysisService;

    @Override
    public NaicomSubmissionType type() {
        return NaicomSubmissionType.IFRS9_DISCLOSURE;
    }

    @Override
    public Map<String, Object> computePayload(UUID periodId) {
        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));

        Ifrs9MovementAnalysis ma = movementAnalysisService.compute(periodId);

        Map<String, Object> totals = new LinkedHashMap<>();
        Ifrs9MovementAnalysis.InvestmentTotals it = ma.investments().totals();
        totals.put("openingBalance", it.openingBalance());
        totals.put("effectiveInterestIncome", it.effectiveInterestIncome());
        totals.put("couponReceived", it.couponReceived());
        totals.put("fairValueChangePnl", it.fairValueChangePnl());
        totals.put("fairValueChangeOci", it.fairValueChangeOci());
        totals.put("eclMovement", it.eclMovement());
        totals.put("impairmentLoss", it.impairmentLoss());
        totals.put("disposals", it.disposals());
        totals.put("closingBalance", it.closingBalance());
        totals.put("totalPnlIncome", it.totalPnlIncome());
        totals.put("totalOciMovement", it.totalOciMovement());

        List<Map<String, Object>> holdingRows = new ArrayList<>(ma.investments().byHolding().size());
        for (Ifrs9MovementAnalysis.HoldingEntry h : ma.investments().byHolding()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("holdingId", h.holdingId().toString());
            row.put("isin", h.isin());
            row.put("securityName", h.securityName());
            row.put("issuer", h.issuer());
            row.put("assetType", h.assetType().name());
            row.put("classification", h.classification().name());
            row.put("holdingStatus", h.holdingStatus().name());
            row.put("currencyCode", h.currencyCode());
            row.put("maturityDate", h.maturityDate() == null ? null : h.maturityDate().toString());
            row.put("openingBalance", h.openingBalance());
            row.put("effectiveInterestIncome", h.effectiveInterestIncome());
            row.put("couponReceived", h.couponReceived());
            row.put("fairValueChangePnl", h.fairValueChangePnl());
            row.put("fairValueChangeOci", h.fairValueChangeOci());
            row.put("eclMovement", h.eclMovement());
            row.put("impairmentLoss", h.impairmentLoss());
            row.put("disposals", h.disposals());
            row.put("closingBalance", h.closingBalance());
            row.put("closingFairValue", h.closingFairValue());
            row.put("eclStage", h.eclStage());
            row.put("totalPnlIncome", h.totalPnlIncome());
            row.put("totalOciMovement", h.totalOciMovement());
            holdingRows.add(row);
        }

        Map<String, Object> investments = new LinkedHashMap<>();
        investments.put("totals", totals);
        investments.put("byHolding", holdingRows);

        Map<String, Object> premium = new LinkedHashMap<>();
        Ifrs9MovementAnalysis.PremiumReceivableSection p = ma.premiumReceivableEcl();
        premium.put("openingAllowance", p.openingAllowance());
        premium.put("periodMovement", p.periodMovement());
        premium.put("closingAllowance", p.closingAllowance());
        premium.put("direction", p.direction());

        Map<String, Object> grandTotals = new LinkedHashMap<>();
        grandTotals.put("holdingCount", ma.investments().byHolding().size());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("submissionType", NaicomSubmissionType.IFRS9_DISCLOSURE.name());
        payload.put("period", periodMeta(period));
        payload.put("generatedAt", Instant.now().toString());
        payload.put("investments", investments);
        payload.put("premiumReceivableEcl", premium);
        payload.put("totals", grandTotals);
        payload.put("notes",
            "v1 disclosure: per-holding investment roll-forward sourced from "
            + "ifrs9_investment_movement_analysis (V40); premium-receivable ECL "
            + "roll-forward derived from JE aggregates on account 1340 "
            + "(per Slice 3.6 stateless design). FVOCI_EQUITY does not recycle "
            + "on disposal per §5.7.5; OCI split between realised and "
            + "unrealised is not reported in v1. Premium-receivable provision "
            + "matrix lives in JE narrative; per-bucket breakdown is available "
            + "via Slice 4.10 PDF artifact rendering, not in this JSON "
            + "payload.");

        log.info("IFRS 9 disclosure computed for period {} — {} holdings; "
                + "investments closing {}; premium-receivable allowance {} → {}",
            periodId, ma.investments().byHolding().size(),
            it.closingBalance(),
            p.openingAllowance(), p.closingAllowance());

        return payload;
    }

    private static Map<String, Object> periodMeta(FiscalPeriod period) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", period.getId().toString());
        meta.put("start", period.getStartDate().toString());
        meta.put("end", period.getEndDate().toString());
        return meta;
    }
}
