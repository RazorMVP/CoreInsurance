package com.nubeero.cia.finance.naicom;

import com.nubeero.cia.finance.gl.FiscalPeriod;
import com.nubeero.cia.finance.gl.FiscalPeriodNotFoundException;
import com.nubeero.cia.finance.gl.FiscalPeriodRepository;
import com.nubeero.cia.finance.paa.MovementAnalysis;
import com.nubeero.cia.finance.paa.MovementAnalysisService;
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
 * IFRS 17 §103 disclosure — period-end movement analysis of LRC + LIC
 * presented in the shape NAICOM submission tooling expects.
 *
 * <p>Module 12 Phase 4 Slice 4.6. Pure read engine — no DB writes, no JE
 * postings. Orchestrator (Slice 4.9) owns the submission upsert + state
 * machine.
 *
 * <h2>Substrate: relays {@link MovementAnalysisService}</h2>
 * <p>The §103 invariants and the V38 {@code paa_movement_analysis} view
 * are already centralised inside {@link MovementAnalysisService}. This
 * engine consumes the typed {@link MovementAnalysis} record and adapts
 * it into the {@code Map<String, Object>} envelope shared by every
 * NAICOM submission engine in this phase. Re-querying V38 here would
 * duplicate aggregation logic and risk drift between two SQL surfaces
 * over the same view.
 *
 * <h2>Period semantics</h2>
 * <p>The engine reads movement for the {@code periodId} the caller
 * provides. {@code MovementAnalysisService} pulls LRC + LIC roll-forward
 * rows whose {@code period_id} matches exactly — period-bounded, not
 * cumulative. For an annual IFRS 17 disclosure, callers pass a YEAR-type
 * fiscal period (Jan 1 → Dec 31). The orchestrator (Slice 4.9) gates the
 * appropriate period_type for each {@link NaicomSubmissionType}.
 *
 * <h2>Payload shape</h2>
 * <pre>
 * {
 *   "submissionType": "IFRS17_DISCLOSURE",
 *   "period":         { "id", "start", "end" },
 *   "generatedAt":    ISO-8601,
 *   "lrcMovement":    { "opening", "premiumsReceived", "premiumEarned",
 *                       "acquisitionCostsDeferred", "acquisitionCostsAmortised",
 *                       "lossComponent", "lossComponentChange", "closing" },
 *   "licMovement":    { "opening", "claimsIncurred", "claimsPaid",
 *                       "caseReserveChange", "ibnrEstimate", "ibnrChange",
 *                       "riskAdjustment", "riskAdjustmentChange",
 *                       "discountUnwind", "closing" },
 *   "insuranceContractLiability": { "totalOpening", "totalClosing" },
 *   "byGroup": [
 *     {
 *       "groupId", "portfolioCode", "portfolioName",
 *       "cohortYear", "onerousness", "groupStatus", "currencyCode",
 *       "lrc": { all LRC fields per group },
 *       "lic": { all LIC fields per group },
 *       "totalOpening", "totalClosing"
 *     }, ...
 *   ],
 *   "totals":         { "groupCount" },
 *   "notes":          "v1 disclosure scope / acquisition-cost / IBNR / RA disclosure"
 * }
 * </pre>
 *
 * <p>Per-group rows preserve the ordering chosen by
 * {@link MovementAnalysisService} (portfolio_code, cohort_year,
 * onerousness) — deterministic across runs.
 *
 * <h2>v1 disclosure scope</h2>
 * <p>Slice 2.x's PAA measurement engines populate the columns this
 * disclosure surfaces, with the following v1 limitations carried through
 * to the payload's {@code notes} field:
 * <ul>
 *   <li>Acquisition costs are recognised as expensed-as-incurred under
 *       the default {@code paa_config.acquisition_cashflow_method}, so
 *       {@code acquisitionCostsDeferred} / {@code acquisitionCostsAmortised}
 *       are typically zero.</li>
 *   <li>IBNR + RA columns exist in the schema but are populated by the
 *       Slice 2.7b actuarial extension, not v1's claim-driven LIC
 *       engine. Expect zeros until that ships.</li>
 * </ul>
 */
@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class Ifrs17DisclosureEngine {

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final MovementAnalysisService movementAnalysisService;

    public Map<String, Object> computePayload(UUID periodId) {
        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));

        MovementAnalysis ma = movementAnalysisService.compute(periodId);

        Map<String, Object> lrc = new LinkedHashMap<>();
        lrc.put("opening", ma.lrcTotals().opening());
        lrc.put("premiumsReceived", ma.lrcTotals().premiumsReceived());
        lrc.put("premiumEarned", ma.lrcTotals().premiumEarned());
        lrc.put("acquisitionCostsDeferred", ma.lrcTotals().acquisitionCostsDeferred());
        lrc.put("acquisitionCostsAmortised", ma.lrcTotals().acquisitionCostsAmortised());
        lrc.put("lossComponent", ma.lrcTotals().lossComponent());
        lrc.put("lossComponentChange", ma.lrcTotals().lossComponentChange());
        lrc.put("closing", ma.lrcTotals().closing());

        Map<String, Object> lic = new LinkedHashMap<>();
        lic.put("opening", ma.licTotals().opening());
        lic.put("claimsIncurred", ma.licTotals().claimsIncurred());
        lic.put("claimsPaid", ma.licTotals().claimsPaid());
        lic.put("caseReserveChange", ma.licTotals().caseReserveChange());
        lic.put("ibnrEstimate", ma.licTotals().ibnrEstimate());
        lic.put("ibnrChange", ma.licTotals().ibnrChange());
        lic.put("riskAdjustment", ma.licTotals().riskAdjustment());
        lic.put("riskAdjustmentChange", ma.licTotals().riskAdjustmentChange());
        lic.put("discountUnwind", ma.licTotals().discountUnwind());
        lic.put("closing", ma.licTotals().closing());

        Map<String, Object> liability = new LinkedHashMap<>();
        liability.put("totalOpening", ma.totalOpeningLiability());
        liability.put("totalClosing", ma.totalClosingLiability());

        List<Map<String, Object>> groupRows = new ArrayList<>(ma.byGroup().size());
        for (MovementAnalysis.GroupMovementEntry g : ma.byGroup()) {
            Map<String, Object> groupLrc = new LinkedHashMap<>();
            groupLrc.put("opening", g.lrcOpening());
            groupLrc.put("premiumsReceived", g.premiumReceived());
            groupLrc.put("premiumEarned", g.premiumEarned());
            groupLrc.put("acquisitionCostsDeferred", g.acquisitionCostsDeferred());
            groupLrc.put("acquisitionCostsAmortised", g.acquisitionCostsAmortised());
            groupLrc.put("lossComponent", g.lossComponent());
            groupLrc.put("lossComponentChange", g.lossComponentChange());
            groupLrc.put("closing", g.lrcClosing());

            Map<String, Object> groupLic = new LinkedHashMap<>();
            groupLic.put("opening", g.licOpening());
            groupLic.put("claimsIncurred", g.claimsIncurred());
            groupLic.put("claimsPaid", g.claimsPaid());
            groupLic.put("caseReserveChange", g.caseReserveChange());
            groupLic.put("ibnrEstimate", g.ibnrEstimate());
            groupLic.put("ibnrChange", g.ibnrChange());
            groupLic.put("riskAdjustment", g.riskAdjustment());
            groupLic.put("riskAdjustmentChange", g.riskAdjustmentChange());
            groupLic.put("discountUnwind", g.discountUnwind());
            groupLic.put("closing", g.licClosing());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("groupId", g.groupId().toString());
            row.put("portfolioCode", g.portfolioCode());
            row.put("portfolioName", g.portfolioName());
            row.put("cohortYear", g.cohortYear());
            row.put("onerousness", g.onerousness());
            row.put("groupStatus", g.groupStatus());
            row.put("currencyCode", g.currencyCode());
            row.put("lrc", groupLrc);
            row.put("lic", groupLic);
            row.put("totalOpening", g.totalOpening());
            row.put("totalClosing", g.totalClosing());
            groupRows.add(row);
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("groupCount", ma.byGroup().size());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("submissionType", NaicomSubmissionType.IFRS17_DISCLOSURE.name());
        payload.put("period", periodMeta(period));
        payload.put("generatedAt", Instant.now().toString());
        payload.put("lrcMovement", lrc);
        payload.put("licMovement", lic);
        payload.put("insuranceContractLiability", liability);
        payload.put("byGroup", groupRows);
        payload.put("totals", totals);
        payload.put("notes",
            "v1 disclosure: LRC + LIC roll-forward per IFRS 17 §103 sourced from "
            + "paa_movement_analysis (V38). Acquisition-cost columns reflect "
            + "expense-as-incurred (the default paa_config.acquisition_cashflow_method); "
            + "IBNR and risk-adjustment columns ship in the V36 schema but are "
            + "populated by the Slice 2.7b actuarial extension, not v1's claim-driven "
            + "LIC engine.");

        log.info("IFRS 17 disclosure computed for period {} — {} groups; "
                + "LRC opening {} → closing {}; LIC opening {} → closing {}",
            periodId, ma.byGroup().size(),
            ma.lrcTotals().opening(), ma.lrcTotals().closing(),
            ma.licTotals().opening(), ma.licTotals().closing());

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
