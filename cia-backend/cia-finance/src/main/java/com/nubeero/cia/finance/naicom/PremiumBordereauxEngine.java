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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NAICOM N05 — Premium Bordereaux.
 *
 * <p>Module 12 Phase 4 Slice 4.2. Reads the {@code policies} table and
 * composes a policy-level premium register for one fiscal period.
 *
 * <h2>Period filter convention</h2>
 * <p>Policies are included via {@code approved_at::date BETWEEN
 * period_start AND period_end} — the <strong>booking date</strong>, not the
 * inception date. Matches the IFRS-17 / Slice-1.7 {@code getLockDate()}
 * convention: regulator-facing reports use the booking date so closed
 * periods cannot be retroactively altered by approving an old inception
 * policy later. A regulator submission is a snapshot of what was BOOKED in
 * the period, irrespective of business-effective dates.
 *
 * <h2>Status filter</h2>
 * <p>{@code status NOT IN ('DRAFT', 'PENDING_APPROVAL', 'REJECTED')} —
 * exclusion of the three pre-approval states. Every post-approval state
 * (ACTIVE, EXPIRED, CANCELLED, LAPSED, REINSTATED) is included because
 * each had premium WRITTEN at approval-time and carries a JE on account
 * 4110 that the regulator must be able to tie back to a policy row.
 *
 * <p>The exclusion-list shape (not inclusion-list) is deliberate: in a
 * regulator submission, the asymmetric failure mode is "missing real
 * data is worse than including a flagged edge case." A new
 * {@link com.nubeero.cia.policy.PolicyStatus} value added later (e.g.
 * RENEWED) would be silently dropped by an inclusion list — auditors
 * would see GL balance with no matching bordereau row. The
 * {@code approved_at IS NOT NULL} clause below catches anything pathological
 * from a hypothetical pre-approval value that drifts past this filter.
 *
 * <h2>Payload shape</h2>
 * <pre>
 * {
 *   "submissionType": "PREMIUM_BORDEREAUX",
 *   "period": { "id", "start", "end" },
 *   "generatedAt": ISO-8601 timestamp,
 *   "totals": { "policyCount", "totalSumInsured", "totalPremium", "totalNetPremium" },
 *   "byClass": [ { "classOfBusinessCode", "classOfBusinessName", "policyCount",
 *                   "totalSumInsured", "totalPremium", "totalNetPremium" }, ... ],
 *   "policies": [ { "policyNumber", "customerName", "classOfBusinessCode",
 *                    "classOfBusinessName", "productName", "sumInsured", "premium",
 *                    "netPremium", "businessType", "policyStartDate", "policyEndDate",
 *                    "approvedAt", "brokerName" }, ... ]
 * }
 * </pre>
 *
 * <p>Ordering is deterministic: policies sorted by {@code policy_number ASC};
 * byClass sorted by {@code class_of_business_code ASC}. Same input data
 * produces a bit-identical payload across runs — required for auditor
 * reproducibility and the Slice-1.9 reconciliation-evidence pattern.
 *
 * <p>This is a PURE read engine: no DB writes, no JE postings, no
 * submission row creation. {@code NaicomSubmissionService} in Slice 4.9
 * calls {@link #computePayload(UUID)} and handles the upsert + state
 * machine + event emission.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PremiumBordereauxEngine implements NaicomSubmissionEngine {

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public NaicomSubmissionType type() {
        return NaicomSubmissionType.PREMIUM_BORDEREAUX;
    }

    /**
     * Composes the N05 Premium Bordereaux payload for one fiscal period.
     * The returned {@link LinkedHashMap} preserves insertion order so that
     * serializing to JSONB / PDF / CSV produces a deterministic, replayable
     * artifact for the same input data.
     *
     * @throws FiscalPeriodNotFoundException if the period does not exist
     *         or has been soft-deleted
     */
    @Override
    public Map<String, Object> computePayload(UUID periodId) {
        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));

        LocalDate start = period.getStartDate();
        LocalDate end = period.getEndDate();

        List<Map<String, Object>> policies = loadPolicyRows(start, end);
        Map<String, Map<String, Object>> byClass = aggregateByClass(policies);
        Map<String, Object> totals = aggregateTotals(policies);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("submissionType", NaicomSubmissionType.PREMIUM_BORDEREAUX.name());
        payload.put("period", periodMeta(period));
        payload.put("generatedAt", Instant.now().toString());
        payload.put("totals", totals);
        payload.put("byClass", new ArrayList<>(byClass.values()));
        payload.put("policies", policies);

        log.info("Premium Bordereaux computed for period {} ({} → {}) — {} policies, total premium {}",
            periodId, start, end, policies.size(), totals.get("totalPremium"));

        return payload;
    }

    private List<Map<String, Object>> loadPolicyRows(LocalDate start, LocalDate end) {
        return jdbcTemplate.query(
            "SELECT policy_number, customer_name, " +
            "       class_of_business_code, class_of_business_name, " +
            "       product_name, business_type, broker_name, " +
            "       total_sum_insured, total_premium, net_premium, " +
            "       policy_start_date, policy_end_date, approved_at " +
            "FROM policies " +
            "WHERE deleted_at IS NULL " +
            "  AND status NOT IN ('DRAFT', 'PENDING_APPROVAL', 'REJECTED') " +
            "  AND approved_at IS NOT NULL " +
            "  AND approved_at::date BETWEEN ? AND ? " +
            "ORDER BY policy_number ASC",
            (rs, i) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("policyNumber", rs.getString("policy_number"));
                row.put("customerName", rs.getString("customer_name"));
                row.put("classOfBusinessCode", rs.getString("class_of_business_code"));
                row.put("classOfBusinessName", rs.getString("class_of_business_name"));
                row.put("productName", rs.getString("product_name"));
                row.put("businessType", rs.getString("business_type"));
                row.put("brokerName", rs.getString("broker_name"));
                row.put("sumInsured", rs.getBigDecimal("total_sum_insured"));
                row.put("premium", rs.getBigDecimal("total_premium"));
                row.put("netPremium", rs.getBigDecimal("net_premium"));
                row.put("policyStartDate", rs.getDate("policy_start_date").toLocalDate().toString());
                row.put("policyEndDate", rs.getDate("policy_end_date").toLocalDate().toString());
                row.put("approvedAt", rs.getTimestamp("approved_at").toInstant().toString());
                return row;
            },
            java.sql.Date.valueOf(start), java.sql.Date.valueOf(end));
    }

    /**
     * Per-class rollup with deterministic ordering ({@link LinkedHashMap}
     * by class_of_business_code). Empty input yields an empty map; callers
     * should treat that as "no premium written this period."
     */
    private Map<String, Map<String, Object>> aggregateByClass(List<Map<String, Object>> policies) {
        Map<String, Map<String, Object>> byClass = new LinkedHashMap<>();
        for (Map<String, Object> p : policies) {
            String code = (String) p.get("classOfBusinessCode");
            byClass.computeIfAbsent(code, k -> {
                Map<String, Object> seed = new LinkedHashMap<>();
                seed.put("classOfBusinessCode", code);
                seed.put("classOfBusinessName", p.get("classOfBusinessName"));
                seed.put("policyCount", 0);
                seed.put("totalSumInsured", BigDecimal.ZERO);
                seed.put("totalPremium", BigDecimal.ZERO);
                seed.put("totalNetPremium", BigDecimal.ZERO);
                return seed;
            });
            Map<String, Object> agg = byClass.get(code);
            agg.put("policyCount", (Integer) agg.get("policyCount") + 1);
            agg.put("totalSumInsured", ((BigDecimal) agg.get("totalSumInsured"))
                .add((BigDecimal) p.get("sumInsured")));
            agg.put("totalPremium", ((BigDecimal) agg.get("totalPremium"))
                .add((BigDecimal) p.get("premium")));
            agg.put("totalNetPremium", ((BigDecimal) agg.get("totalNetPremium"))
                .add((BigDecimal) p.get("netPremium")));
        }
        return byClass;
    }

    private Map<String, Object> aggregateTotals(List<Map<String, Object>> policies) {
        Map<String, Object> totals = new LinkedHashMap<>();
        BigDecimal si = BigDecimal.ZERO;
        BigDecimal premium = BigDecimal.ZERO;
        BigDecimal netPremium = BigDecimal.ZERO;
        for (Map<String, Object> p : policies) {
            si = si.add((BigDecimal) p.get("sumInsured"));
            premium = premium.add((BigDecimal) p.get("premium"));
            netPremium = netPremium.add((BigDecimal) p.get("netPremium"));
        }
        totals.put("policyCount", policies.size());
        totals.put("totalSumInsured", si);
        totals.put("totalPremium", premium);
        totals.put("totalNetPremium", netPremium);
        return totals;
    }

    private static Map<String, Object> periodMeta(FiscalPeriod period) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", period.getId().toString());
        meta.put("start", period.getStartDate().toString());
        meta.put("end", period.getEndDate().toString());
        return meta;
    }
}
