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
 * <p>Module 12 Phase 4 Slice 4.3, re-implemented over the GL in Slice
 * 1.10b. Per-class view of premium written, claims incurred, and loss
 * ratio for the period.
 *
 * <h2>Substrate: GL-driven (Slice 1.10b)</h2>
 * <p>This engine reads aggregates over {@code journal_entry_line}
 * filtered to {@code POLICY_APPROVED} (premium) and {@code CLAIM_APPROVED}
 * (claims). Class-of-business comes from the
 * {@code journal_entry_line.class_of_business_id} column that Slice
 * 1.10a promoted out of {@code dimension_tags}; the class label
 * ({@code code}, {@code name}) is joined from {@code classes_of_business}
 * for display. Auditor-canonical by construction — every figure has a
 * JE provenance.
 *
 * <p>This replaces the Slice 4.3 source-table read over {@code policies}
 * + {@code claims}. The change is invisible at the payload-shape level
 * (downstream PDF / CSV renderers and the NAICOM e-portal integration
 * see the same JSON); the semantic shift is on the period-filter anchor:
 *
 * <h2>Period filter convention</h2>
 * <ul>
 *   <li>Premium side: JEs with
 *       {@code source_event_type = 'POLICY_APPROVED'} AND
 *       {@code business_date BETWEEN period_start AND period_end}.
 *       {@code business_date} on a POLICY_APPROVED JE is the policy's
 *       {@code policy_start_date} (see Slice 1.5
 *       {@code SubledgerPostingService}). A policy approved Feb 2026
 *       with inception Jan 2026 lands in the JANUARY period under this
 *       view, not the FEBRUARY one. This is the cover-inception model
 *       — IFRS-consistent and what the Phase 4 BalanceSheetEngine /
 *       PrudentialReturnEngine also see.</li>
 *   <li>Claims side: JEs with
 *       {@code source_event_type = 'CLAIM_APPROVED'} AND
 *       {@code business_date BETWEEN period_start AND period_end}. The
 *       business_date on a CLAIM_APPROVED JE is today() at posting time
 *       (or the supplied historical date when backfilling).</li>
 * </ul>
 *
 * <p>Both source events are filtered to POSTED (REVERSED JEs excluded
 * and reversal-of-reversal JEs are not double-counted because they have
 * status REVERSED on the original, POSTED on the new line). Soft-deleted
 * JEs and lines are skipped via the usual {@code deleted_at IS NULL}
 * filter.
 *
 * <p>Lines without {@code class_of_business_id} (Phase 2 / Phase 3 JEs,
 * or historical pre-V42 rows that the V43 backfill didn't reach) are
 * silently excluded from per-class aggregation. The orchestrator (Slice
 * 4.9) gates the period_type; an N01 caller typically passes a
 * YEAR-type period.
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
public class AnnualRevenueAccountEngine implements NaicomSubmissionEngine {

    private static final int LOSS_RATIO_SCALE = 2;

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public NaicomSubmissionType type() {
        return NaicomSubmissionType.ANNUAL_REVENUE_ACCOUNT;
    }

    @Override
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

    /**
     * Premium = SUM(credit_amount) on the credit-side line of every
     * POSTED POLICY_APPROVED JE in the period. Counts each JE once
     * (one JE per policy) via the credit-side line filter.
     *
     * <p>Joins {@code classes_of_business} for the display {@code code}
     * / {@code name}. The JOIN is read-only against the same tenant
     * schema; it doesn't break the cia-finance ↔ cia-setup module
     * boundary (cia-finance has no entity-level dependency on cia-setup,
     * only a SQL JOIN at projection time — equivalent to how the
     * BalanceSheet engine joins to {@code chart_of_account}).
     */
    private void accumulatePremium(Map<String, ClassAggregate> byClass, LocalDate start, LocalDate end) {
        jdbcTemplate.query(
            "SELECT cob.code AS class_of_business_code, " +
            "       cob.name AS class_of_business_name, " +
            "       COUNT(*) AS policy_count, " +
            "       COALESCE(SUM(jel.credit_amount), 0) AS gross_premium " +
            "FROM journal_entry je " +
            "JOIN journal_entry_line jel ON jel.journal_entry_id = je.id " +
            "JOIN classes_of_business cob ON cob.id = jel.class_of_business_id " +
            "WHERE je.source_event_type = 'POLICY_APPROVED' " +
            "  AND je.status = 'POSTED' " +
            "  AND je.business_date BETWEEN ? AND ? " +
            "  AND jel.credit_amount > 0 " +
            "  AND jel.class_of_business_id IS NOT NULL " +
            "  AND je.deleted_at IS NULL " +
            "  AND jel.deleted_at IS NULL " +
            "  AND cob.deleted_at IS NULL " +
            "GROUP BY cob.code, cob.name",
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

    /**
     * Claims incurred = SUM(credit_amount) on the credit-side line of
     * every POSTED CLAIM_APPROVED JE in the period (the credit lands on
     * the LIC OCR liability account, equal in magnitude to the debit on
     * the incurred-claims expense account). Counts each JE once via the
     * credit-side line filter.
     *
     * <p>Note: this captures the initial loss recognition at claim
     * approval. Subsequent reserve adjustments / IBNR layers / discount
     * unwind are Phase 2 PAA engine territory and are reported via the
     * IFRS-17 disclosure pack (Slice 4.6), not N01.
     */
    private void accumulateClaims(Map<String, ClassAggregate> byClass, LocalDate start, LocalDate end) {
        jdbcTemplate.query(
            "SELECT cob.code AS class_of_business_code, " +
            "       cob.name AS class_of_business_name, " +
            "       COUNT(*) AS claim_count, " +
            "       COALESCE(SUM(jel.credit_amount), 0) AS claims_incurred " +
            "FROM journal_entry je " +
            "JOIN journal_entry_line jel ON jel.journal_entry_id = je.id " +
            "JOIN classes_of_business cob ON cob.id = jel.class_of_business_id " +
            "WHERE je.source_event_type = 'CLAIM_APPROVED' " +
            "  AND je.status = 'POSTED' " +
            "  AND je.business_date BETWEEN ? AND ? " +
            "  AND jel.credit_amount > 0 " +
            "  AND jel.class_of_business_id IS NOT NULL " +
            "  AND je.deleted_at IS NULL " +
            "  AND jel.deleted_at IS NULL " +
            "  AND cob.deleted_at IS NULL " +
            "GROUP BY cob.code, cob.name",
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
