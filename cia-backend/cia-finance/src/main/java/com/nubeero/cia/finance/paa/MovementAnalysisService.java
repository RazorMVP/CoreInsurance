package com.nubeero.cia.finance.paa;

import com.nubeero.cia.finance.gl.FiscalPeriod;
import com.nubeero.cia.finance.gl.FiscalPeriodNotFoundException;
import com.nubeero.cia.finance.gl.FiscalPeriodRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computes the IFRS 17 §103 {@link MovementAnalysis} for one fiscal
 * period. Reads exclusively from the {@code paa_movement_analysis} view
 * (V38) — no joins or computation logic lives here; the view is the
 * single source of truth.
 *
 * <p>Module 12 Phase 2 Slice 2.8. Read-only — never writes anything,
 * never posts a JE. Phase 4's NAICOM submission tooling will also consume
 * the view directly without going through this service.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MovementAnalysisService {

    private static final int MONEY_SCALE = 2;

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final JdbcTemplate jdbcTemplate;

    public MovementAnalysis compute(UUID periodId) {
        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT * FROM paa_movement_analysis WHERE period_id = ? " +
            "ORDER BY portfolio_code, cohort_year, onerousness",
            periodId);

        List<MovementAnalysis.GroupMovementEntry> byGroup = new ArrayList<>(rows.size());

        // LRC totals
        BigDecimal lrcOpening = BigDecimal.ZERO;
        BigDecimal premiumsReceived = BigDecimal.ZERO;
        BigDecimal premiumEarned = BigDecimal.ZERO;
        BigDecimal acqDeferred = BigDecimal.ZERO;
        BigDecimal acqAmortised = BigDecimal.ZERO;
        BigDecimal lossComponent = BigDecimal.ZERO;
        BigDecimal lossComponentChange = BigDecimal.ZERO;
        BigDecimal lrcClosing = BigDecimal.ZERO;

        // LIC totals
        BigDecimal licOpening = BigDecimal.ZERO;
        BigDecimal claimsIncurred = BigDecimal.ZERO;
        BigDecimal claimsPaid = BigDecimal.ZERO;
        BigDecimal caseReserveChange = BigDecimal.ZERO;
        BigDecimal ibnrEstimate = BigDecimal.ZERO;
        BigDecimal ibnrChange = BigDecimal.ZERO;
        BigDecimal riskAdjustment = BigDecimal.ZERO;
        BigDecimal riskAdjustmentChange = BigDecimal.ZERO;
        BigDecimal discountUnwind = BigDecimal.ZERO;
        BigDecimal licClosing = BigDecimal.ZERO;

        BigDecimal totalOpening = BigDecimal.ZERO;
        BigDecimal totalClosing = BigDecimal.ZERO;

        for (Map<String, Object> r : rows) {
            BigDecimal rLrcOpening = bd(r.get("lrc_opening"));
            BigDecimal rPremReceived = bd(r.get("premium_received"));
            BigDecimal rPremEarned = bd(r.get("premium_earned"));
            BigDecimal rAcqDeferred = bd(r.get("acquisition_costs_deferred"));
            BigDecimal rAcqAmortised = bd(r.get("acquisition_costs_amortised"));
            BigDecimal rLossComp = bd(r.get("loss_component"));
            BigDecimal rLossCompChange = bd(r.get("loss_component_change"));
            BigDecimal rLrcClosing = bd(r.get("lrc_closing"));

            BigDecimal rLicOpening = bd(r.get("lic_opening"));
            BigDecimal rClaimsIncurred = bd(r.get("claims_incurred"));
            BigDecimal rClaimsPaid = bd(r.get("claims_paid"));
            BigDecimal rCaseReserveChange = bd(r.get("case_reserve_change"));
            BigDecimal rIbnrEstimate = bd(r.get("ibnr_estimate"));
            BigDecimal rIbnrChange = bd(r.get("ibnr_change"));
            BigDecimal rRiskAdjustment = bd(r.get("risk_adjustment"));
            BigDecimal rRiskAdjustmentChange = bd(r.get("risk_adjustment_change"));
            BigDecimal rDiscountUnwind = bd(r.get("discount_unwind"));
            BigDecimal rLicClosing = bd(r.get("lic_closing"));

            BigDecimal rTotalOpening = bd(r.get("total_opening"));
            BigDecimal rTotalClosing = bd(r.get("total_closing"));

            // Aggregate totals
            lrcOpening = lrcOpening.add(rLrcOpening);
            premiumsReceived = premiumsReceived.add(rPremReceived);
            premiumEarned = premiumEarned.add(rPremEarned);
            acqDeferred = acqDeferred.add(rAcqDeferred);
            acqAmortised = acqAmortised.add(rAcqAmortised);
            lossComponent = lossComponent.add(rLossComp);
            lossComponentChange = lossComponentChange.add(rLossCompChange);
            lrcClosing = lrcClosing.add(rLrcClosing);

            licOpening = licOpening.add(rLicOpening);
            claimsIncurred = claimsIncurred.add(rClaimsIncurred);
            claimsPaid = claimsPaid.add(rClaimsPaid);
            caseReserveChange = caseReserveChange.add(rCaseReserveChange);
            ibnrEstimate = ibnrEstimate.add(rIbnrEstimate);
            ibnrChange = ibnrChange.add(rIbnrChange);
            riskAdjustment = riskAdjustment.add(rRiskAdjustment);
            riskAdjustmentChange = riskAdjustmentChange.add(rRiskAdjustmentChange);
            discountUnwind = discountUnwind.add(rDiscountUnwind);
            licClosing = licClosing.add(rLicClosing);

            totalOpening = totalOpening.add(rTotalOpening);
            totalClosing = totalClosing.add(rTotalClosing);

            byGroup.add(new MovementAnalysis.GroupMovementEntry(
                (UUID) r.get("group_id"),
                (String) r.get("portfolio_code"),
                (String) r.get("portfolio_name"),
                (Integer) r.get("cohort_year"),
                (String) r.get("onerousness"),
                (String) r.get("group_status"),
                rLrcOpening, rPremReceived, rPremEarned,
                rAcqDeferred, rAcqAmortised,
                rLossComp, rLossCompChange, rLrcClosing,
                rLicOpening, rClaimsIncurred, rClaimsPaid,
                rCaseReserveChange, rIbnrEstimate, rIbnrChange,
                rRiskAdjustment, rRiskAdjustmentChange,
                rDiscountUnwind, rLicClosing,
                rTotalOpening, rTotalClosing,
                (String) r.get("currency_code")));
        }

        log.info("Movement analysis computed for period {} — {} groups; "
                + "LRC opening {} → closing {}; LIC opening {} → closing {}",
            periodId, byGroup.size(),
            scale(lrcOpening), scale(lrcClosing),
            scale(licOpening), scale(licClosing));

        return new MovementAnalysis(
            period.getId(),
            period.getStartDate(),
            period.getEndDate(),
            new MovementAnalysis.LrcMovementTotals(
                scale(lrcOpening), scale(premiumsReceived), scale(premiumEarned),
                scale(acqDeferred), scale(acqAmortised),
                scale(lossComponent), scale(lossComponentChange),
                scale(lrcClosing)),
            new MovementAnalysis.LicMovementTotals(
                scale(licOpening), scale(claimsIncurred), scale(claimsPaid),
                scale(caseReserveChange), scale(ibnrEstimate), scale(ibnrChange),
                scale(riskAdjustment), scale(riskAdjustmentChange),
                scale(discountUnwind), scale(licClosing)),
            scale(totalOpening),
            scale(totalClosing),
            byGroup);
    }

    private static BigDecimal bd(Object o) {
        return o == null ? BigDecimal.ZERO : (BigDecimal) o;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
