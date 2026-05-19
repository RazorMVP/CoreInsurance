package com.nubeero.cia.finance.ifrs9;

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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computes the IFRS 9 §B5.5.39 / IFRS 7 §35M {@link Ifrs9MovementAnalysis}
 * for one fiscal period.
 *
 * <p>Module 12 Phase 3 Slice 3.7. Read-only — never writes anything,
 * never posts a JE. Phase 4 NAICOM submission tooling will consume the
 * underlying {@code ifrs9_investment_movement_analysis} view directly
 * without going through this service.
 *
 * <h2>Two-section composition</h2>
 * <ul>
 *   <li><b>Investments</b> — read from
 *       {@code ifrs9_investment_movement_analysis} (V40 view); aggregated
 *       totals computed in Java from the row set.</li>
 *   <li><b>Premium receivable ECL</b> — derived from JE aggregates on
 *       account 5350 (expense) and 1340 (allowance):
 *       <ul>
 *         <li>opening allowance = SUM of prior periods' delta on 1340</li>
 *         <li>period movement = this period's delta on 1340</li>
 *         <li>closing allowance = opening + movement</li>
 *       </ul>
 *       No new table; the JE table is the source of truth (matches Slice 3.6's
 *       stateless design).</li>
 * </ul>
 */
@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class Ifrs9MovementAnalysisService {

    private static final int MONEY_SCALE = 2;

    private static final String EVENT_PREMIUM_RECEIVABLE_ECL = "PREMIUM_RECEIVABLE_ECL";
    private static final String COA_PREMIUM_RECEIVABLE_ECL_ALLOWANCE = "1340";

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final JdbcTemplate jdbcTemplate;

    public Ifrs9MovementAnalysis compute(UUID periodId) {
        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));

        Ifrs9MovementAnalysis.InvestmentSection investments = computeInvestmentSection(periodId);
        Ifrs9MovementAnalysis.PremiumReceivableSection premium = computePremiumReceivableSection(period);

        log.info("IFRS 9 movement analysis computed for period {} — {} investment holdings; "
                + "premium receivable opening {} → closing {}",
            periodId, investments.byHolding().size(),
            premium.openingAllowance(), premium.closingAllowance());

        return new Ifrs9MovementAnalysis(
            period.getId(),
            period.getStartDate(),
            period.getEndDate(),
            investments,
            premium);
    }

    private Ifrs9MovementAnalysis.InvestmentSection computeInvestmentSection(UUID periodId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT * FROM ifrs9_investment_movement_analysis " +
            "WHERE period_id = ? " +
            "ORDER BY classification, security_name",
            periodId);

        List<Ifrs9MovementAnalysis.HoldingEntry> entries = new ArrayList<>(rows.size());

        BigDecimal totalOpening = BigDecimal.ZERO;
        BigDecimal totalInterest = BigDecimal.ZERO;
        BigDecimal totalCoupon = BigDecimal.ZERO;
        BigDecimal totalFvPnl = BigDecimal.ZERO;
        BigDecimal totalFvOci = BigDecimal.ZERO;
        BigDecimal totalEcl = BigDecimal.ZERO;
        BigDecimal totalImpair = BigDecimal.ZERO;
        BigDecimal totalDisposals = BigDecimal.ZERO;
        BigDecimal totalClosing = BigDecimal.ZERO;
        BigDecimal totalPnlIncome = BigDecimal.ZERO;
        BigDecimal totalOciMovement = BigDecimal.ZERO;

        for (Map<String, Object> r : rows) {
            BigDecimal opening = bd(r.get("opening_balance"));
            BigDecimal interest = bd(r.get("effective_interest_income"));
            BigDecimal coupon = bd(r.get("coupon_received"));
            BigDecimal fvPnl = bd(r.get("fair_value_change_pnl"));
            BigDecimal fvOci = bd(r.get("fair_value_change_oci"));
            BigDecimal ecl = bd(r.get("ecl_movement"));
            BigDecimal impair = bd(r.get("impairment_loss"));
            BigDecimal disposals = bd(r.get("disposals"));
            BigDecimal closing = bd(r.get("closing_balance"));
            BigDecimal pnlIncome = bd(r.get("total_pnl_income"));
            BigDecimal ociMovement = bd(r.get("total_oci_movement"));

            totalOpening = totalOpening.add(opening);
            totalInterest = totalInterest.add(interest);
            totalCoupon = totalCoupon.add(coupon);
            totalFvPnl = totalFvPnl.add(fvPnl);
            totalFvOci = totalFvOci.add(fvOci);
            totalEcl = totalEcl.add(ecl);
            totalImpair = totalImpair.add(impair);
            totalDisposals = totalDisposals.add(disposals);
            totalClosing = totalClosing.add(closing);
            totalPnlIncome = totalPnlIncome.add(pnlIncome);
            totalOciMovement = totalOciMovement.add(ociMovement);

            entries.add(new Ifrs9MovementAnalysis.HoldingEntry(
                (UUID) r.get("holding_id"),
                (String) r.get("isin"),
                (String) r.get("security_name"),
                (String) r.get("issuer"),
                AssetType.valueOf((String) r.get("asset_type")),
                InvestmentClassification.valueOf((String) r.get("classification")),
                HoldingStatus.valueOf((String) r.get("holding_status")),
                (String) r.get("currency_code"),
                toLocalDate(r.get("maturity_date")),

                opening, interest, coupon,
                fvPnl, fvOci,
                ecl, impair, disposals,
                closing,
                (BigDecimal) r.get("closing_fair_value"),
                (Integer) r.get("ecl_stage"),

                pnlIncome, ociMovement));
        }

        Ifrs9MovementAnalysis.InvestmentTotals totals = new Ifrs9MovementAnalysis.InvestmentTotals(
            scale(totalOpening), scale(totalInterest), scale(totalCoupon),
            scale(totalFvPnl), scale(totalFvOci),
            scale(totalEcl), scale(totalImpair), scale(totalDisposals),
            scale(totalClosing),
            scale(totalPnlIncome), scale(totalOciMovement));

        return new Ifrs9MovementAnalysis.InvestmentSection(totals, entries);
    }

    /**
     * Premium-receivable ECL roll-forward derived from journal_entry_line
     * aggregate on account 1340. {@code opening} = net credits on 1340 for
     * periods ending BEFORE this period's start. {@code movement} =
     * net credits on 1340 for journal_entry rows where business_date is in
     * this period.
     */
    private Ifrs9MovementAnalysis.PremiumReceivableSection computePremiumReceivableSection(FiscalPeriod period) {
        BigDecimal opening = sumPremiumReceivableAllowance(null, period.getStartDate());
        BigDecimal closing = sumPremiumReceivableAllowance(null, period.getEndDate().plusDays(1));
        BigDecimal movement = scale(closing.subtract(opening));

        String direction = movement.signum() > 0 ? "INCREASE"
                         : movement.signum() < 0 ? "REVERSAL"
                         : "NO_CHANGE";

        return new Ifrs9MovementAnalysis.PremiumReceivableSection(
            scale(opening), movement, scale(closing), direction);
    }

    /**
     * Sum (credits − debits) on account 1340 from {@code PREMIUM_RECEIVABLE_ECL}
     * JEs whose business_date is &lt; {@code upToExclusive}. The credit-minus-
     * debit ordering matches the contra-asset semantics: credits increase
     * the allowance balance, debits reverse it.
     */
    private BigDecimal sumPremiumReceivableAllowance(Object unused, LocalDate upToExclusive) {
        BigDecimal sum = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(l.credit_amount - l.debit_amount), 0) " +
            "FROM journal_entry_line l " +
            "JOIN journal_entry je ON je.id = l.journal_entry_id " +
            "JOIN chart_of_account coa ON coa.id = l.account_id " +
            "WHERE je.source_event_type = ? " +
            "  AND coa.code = ? " +
            "  AND je.business_date < ? " +
            "  AND l.deleted_at IS NULL " +
            "  AND je.deleted_at IS NULL",
            BigDecimal.class,
            EVENT_PREMIUM_RECEIVABLE_ECL, COA_PREMIUM_RECEIVABLE_ECL_ALLOWANCE,
            java.sql.Date.valueOf(upToExclusive));
        return scale(sum);
    }

    private static BigDecimal bd(Object o) {
        return o == null ? BigDecimal.ZERO : (BigDecimal) o;
    }

    private static LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate ld) return ld;
        if (o instanceof java.sql.Date sql) return sql.toLocalDate();
        return null;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
