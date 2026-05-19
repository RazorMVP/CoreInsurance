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
 * Computes the IFRS 17 §83 / §84 Insurance Service Result for one fiscal
 * period. Pure read-side aggregation over paa_lrc + paa_lic.
 *
 * <p>Module 12 Phase 2 Slice 2.5. The service is read-only — never writes
 * anything, never posts a JE. Operators call this after running
 * {@link LrcEngine#recognise(UUID)} and {@link LicEngine#recognise(UUID)}
 * (or {@link PaaPeriodCloseService#closePeriod(UUID)} which does both) to
 * see the §80 income-statement classification of the just-closed period.
 *
 * <h2>SQL</h2>
 * <p>One LEFT JOIN query against {@code group_of_contracts} so that a group
 * with paa_lrc but no paa_lic (e.g. a group with new policies but no
 * claims yet) still appears with revenue and zero expense — the §103
 * disclosure should never silently drop a group.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class InsuranceServiceResultService {

    private static final int MONEY_SCALE = 2;

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final JdbcTemplate jdbcTemplate;

    public InsuranceServiceResult compute(UUID periodId) {
        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT " +
            "  g.id                                   AS group_id, " +
            "  p.code                                 AS portfolio_code, " +
            "  g.cohort_year                          AS cohort_year, " +
            "  g.onerousness                          AS onerousness, " +
            "  COALESCE(lrc.premium_earned, 0)        AS revenue, " +
            "  COALESCE(lic.claims_incurred, 0) " +
            "    + COALESCE(lic.case_reserve_change, 0) " +
            "    + COALESCE(lic.ibnr_change, 0) " +
            "    + COALESCE(lic.risk_adjustment_change, 0) AS expense " +
            "FROM group_of_contracts g " +
            "JOIN portfolio p ON p.id = g.portfolio_id " +
            "LEFT JOIN paa_lrc lrc ON lrc.group_id = g.id " +
            "                     AND lrc.period_id = ? " +
            "                     AND lrc.deleted_at IS NULL " +
            "LEFT JOIN paa_lic lic ON lic.group_id = g.id " +
            "                     AND lic.period_id = ? " +
            "                     AND lic.deleted_at IS NULL " +
            "WHERE g.deleted_at IS NULL " +
            "  AND p.deleted_at IS NULL " +
            "  AND (lrc.id IS NOT NULL OR lic.id IS NOT NULL) " +
            "ORDER BY p.code, g.cohort_year, g.onerousness",
            periodId, periodId);

        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;
        List<InsuranceServiceResult.GroupResult> byGroup = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            BigDecimal revenue = scale((BigDecimal) row.get("revenue"));
            BigDecimal expense = scale((BigDecimal) row.get("expense"));
            BigDecimal result = revenue.subtract(expense);

            totalRevenue = totalRevenue.add(revenue);
            totalExpense = totalExpense.add(expense);

            byGroup.add(new InsuranceServiceResult.GroupResult(
                (UUID) row.get("group_id"),
                (String) row.get("portfolio_code"),
                (Integer) row.get("cohort_year"),
                (String) row.get("onerousness"),
                revenue, expense, result));
        }

        return new InsuranceServiceResult(
            period.getId(),
            period.getStartDate(),
            period.getEndDate(),
            scale(totalRevenue),
            scale(totalExpense),
            scale(totalRevenue.subtract(totalExpense)),
            byGroup);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
