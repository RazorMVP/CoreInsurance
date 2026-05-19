package com.nubeero.cia.finance.naicom;

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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NAICOM N01 — Annual Revenue Account.
 *
 * <p>Module 12 Phase 4 Slice 4.3. Per-class underwriting view of premium
 * written, claims incurred, and loss ratio for the period. Matches the
 * shape of the existing Module-11 N01 SYSTEM report.
 *
 * <h2>Underwriting view, not GL view</h2>
 * <p>This engine reads {@code policies} (for premium) and {@code claims}
 * (for claims incurred) directly — NOT {@code journal_entry_line}. The
 * reason: Slice-1.5 {@code SubledgerPostingService} doesn't tag JE lines
 * with {@code class_of_business}, so a class-broken-down GL view is not
 * available at the JE-aggregate level. N01's regulator-required
 * presentation is per-class, so the engine sources from the underwriting
 * tables that DO carry class. Headline totals from this engine will tie
 * to the GL only to the extent that {@code SubledgerPostingService}
 * posted JEs for every policy / claim event in the period; in practice
 * the two should agree but a true reconciliation requires either
 * promoting class into {@code dimension_tags} (v2) or running the engine
 * alongside the GL-driven {@link BalanceSheetEngine} and asserting
 * totals match.
 *
 * <h2>Period filter convention</h2>
 * <p>Same as the bordereau engines:
 * <ul>
 *   <li>Premium side: policies with {@code approved_at::date BETWEEN
 *       period_start AND period_end} (booking discipline).</li>
 *   <li>Claims side: claims with {@code reported_date BETWEEN
 *       period_start AND period_end}.</li>
 * </ul>
 * <p>The period is typically YEAR-type for an N01 submission but the
 * engine accepts any period; the orchestrator (Slice 4.9) gates
 * appropriate period_type per submission_type.
 *
 * <h2>Loss ratio</h2>
 * <p>Per-class {@code loss_ratio = total_claims_incurred / gross_premium × 100}.
 * Computed in Java (BigDecimal HALF_UP @ 2dp), guarded against division by
 * zero — a class with claims but no premium emits a {@code null} loss
 * ratio rather than a divide-by-zero error or a nonsensical figure.
 *
 * <h2>Payload shape</h2>
 * <pre>
 * {
 *   "submissionType": "ANNUAL_REVENUE_ACCOUNT",
 *   "period": { "id", "start", "end" },
 *   "generatedAt": ISO-8601,
 *   "byClass": [
 *     {
 *       "classOfBusinessCode", "classOfBusinessName",
 *       "policyCount", "grossPremium",
 *       "claimCount", "claimsIncurred",
 *       "lossRatio"   // percent, 2dp, OR null if grossPremium=0
 *     }, ...
 *   ],
 *   "totals": { "policyCount", "grossPremium", "claimCount",
 *                "claimsIncurred", "lossRatio" }
 * }
 * </pre>
 *
 * <p>Per-class rows sorted by {@code classOfBusinessCode ASC} —
 * deterministic across runs.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AnnualRevenueAccountEngine {

    private static final int LOSS_RATIO_SCALE = 2;

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final JdbcTemplate jdbcTemplate;

    public Map<String, Object> computePayload(UUID periodId) {
        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));

        LocalDate start = period.getStartDate();
        LocalDate end = period.getEndDate();

        Map<String, ClassAggregate> byClass = new LinkedHashMap<>();
        accumulatePremium(byClass, start, end);
        accumulateClaims(byClass, start, end);

        List<Map<String, Object>> classRows = new ArrayList<>();
        BigDecimal totalGrossPremium = BigDecimal.ZERO;
        BigDecimal totalClaimsIncurred = BigDecimal.ZERO;
        int totalPolicyCount = 0;
        int totalClaimCount = 0;

        // Deterministic ordering by classOfBusinessCode ASC.
        List<String> orderedCodes = byClass.keySet().stream().sorted().toList();
        for (String code : orderedCodes) {
            ClassAggregate agg = byClass.get(code);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("classOfBusinessCode", code);
            row.put("classOfBusinessName", agg.name);
            row.put("policyCount", agg.policyCount);
            row.put("grossPremium", agg.grossPremium);
            row.put("claimCount", agg.claimCount);
            row.put("claimsIncurred", agg.claimsIncurred);
            row.put("lossRatio", lossRatio(agg.claimsIncurred, agg.grossPremium));
            classRows.add(row);

            totalGrossPremium = totalGrossPremium.add(agg.grossPremium);
            totalClaimsIncurred = totalClaimsIncurred.add(agg.claimsIncurred);
            totalPolicyCount += agg.policyCount;
            totalClaimCount += agg.claimCount;
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("policyCount", totalPolicyCount);
        totals.put("grossPremium", totalGrossPremium);
        totals.put("claimCount", totalClaimCount);
        totals.put("claimsIncurred", totalClaimsIncurred);
        totals.put("lossRatio", lossRatio(totalClaimsIncurred, totalGrossPremium));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("submissionType", NaicomSubmissionType.ANNUAL_REVENUE_ACCOUNT.name());
        payload.put("period", periodMeta(period));
        payload.put("generatedAt", Instant.now().toString());
        payload.put("byClass", classRows);
        payload.put("totals", totals);

        log.info("Annual Revenue Account computed for period {} ({} → {}) — gross premium {}, claims incurred {}, loss ratio {}",
            periodId, start, end, totalGrossPremium, totalClaimsIncurred, totals.get("lossRatio"));

        return payload;
    }

    private void accumulatePremium(Map<String, ClassAggregate> byClass, LocalDate start, LocalDate end) {
        jdbcTemplate.query(
            "SELECT class_of_business_code, class_of_business_name, " +
            "       COUNT(*) AS policy_count, " +
            "       COALESCE(SUM(total_premium), 0) AS gross_premium " +
            "FROM policies " +
            "WHERE deleted_at IS NULL " +
            "  AND status NOT IN ('DRAFT', 'PENDING_APPROVAL', 'REJECTED') " +
            "  AND approved_at IS NOT NULL " +
            "  AND approved_at::date BETWEEN ? AND ? " +
            "GROUP BY class_of_business_code, class_of_business_name",
            (rs, i) -> {
                String code = rs.getString("class_of_business_code");
                String name = rs.getString("class_of_business_name");
                ClassAggregate agg = byClass.computeIfAbsent(code, k -> new ClassAggregate(name));
                agg.policyCount += rs.getInt("policy_count");
                agg.grossPremium = agg.grossPremium.add(rs.getBigDecimal("gross_premium"));
                return null;
            },
            java.sql.Date.valueOf(start), java.sql.Date.valueOf(end));
    }

    private void accumulateClaims(Map<String, ClassAggregate> byClass, LocalDate start, LocalDate end) {
        // class_of_business_code from joined policies (claims schema has only
        // _id and _name) — same rationale as ClaimsBordereauxEngine.
        // claims_incurred = reserve_amount (case reserve currently held;
        // when settled, this remains the figure originally reserved). v2
        // may add IBNR and other liability layers.
        jdbcTemplate.query(
            "SELECT p.class_of_business_code AS class_of_business_code, " +
            "       c.class_of_business_name AS class_of_business_name, " +
            "       COUNT(*) AS claim_count, " +
            "       COALESCE(SUM(c.reserve_amount), 0) AS claims_incurred " +
            "FROM claims c " +
            "JOIN policies p ON p.id = c.policy_id " +
            "WHERE c.deleted_at IS NULL " +
            "  AND c.status NOT IN ('WITHDRAWN', 'REJECTED') " +
            "  AND c.reported_date BETWEEN ? AND ? " +
            "GROUP BY p.class_of_business_code, c.class_of_business_name",
            (rs, i) -> {
                String code = rs.getString("class_of_business_code");
                String name = rs.getString("class_of_business_name");
                ClassAggregate agg = byClass.computeIfAbsent(code, k -> new ClassAggregate(name));
                agg.claimCount += rs.getInt("claim_count");
                agg.claimsIncurred = agg.claimsIncurred.add(rs.getBigDecimal("claims_incurred"));
                return null;
            },
            java.sql.Date.valueOf(start), java.sql.Date.valueOf(end));
    }

    /**
     * Returns the loss ratio as a percent ({@code 100 × claims / premium}),
     * rounded HALF_UP to 2dp. Returns {@code null} when premium is zero —
     * a class with claims but no premium emits {@code "lossRatio": null}
     * in the JSON rather than divide-by-zero or an infinite figure.
     */
    private static BigDecimal lossRatio(BigDecimal claimsIncurred, BigDecimal grossPremium) {
        if (grossPremium.signum() == 0) return null;
        return claimsIncurred
            .multiply(new BigDecimal("100"))
            .divide(grossPremium, LOSS_RATIO_SCALE, RoundingMode.HALF_UP);
    }

    private static Map<String, Object> periodMeta(FiscalPeriod period) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", period.getId().toString());
        meta.put("start", period.getStartDate().toString());
        meta.put("end", period.getEndDate().toString());
        return meta;
    }

    /** Mutable per-class accumulator used during payload assembly. */
    private static final class ClassAggregate {
        final String name;
        int policyCount = 0;
        int claimCount = 0;
        BigDecimal grossPremium = BigDecimal.ZERO;
        BigDecimal claimsIncurred = BigDecimal.ZERO;

        ClassAggregate(String name) { this.name = name; }
    }
}
