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
 * NAICOM N06 — Claims Bordereaux.
 *
 * <p>Module 12 Phase 4 Slice 4.2. Reads the {@code claims} table and
 * composes a claim-level loss register for one fiscal period.
 *
 * <h2>Period filter convention</h2>
 * <p>Claims are included via {@code reported_date BETWEEN period_start
 * AND period_end}. The {@code reported_date} is when the insurer was
 * notified of the loss — the analogue of "premium written" for the claims
 * side. Auditors look at "claims reported this period" as the canonical
 * regulator view. Settlement may occur later (sometimes much later), but
 * the claim belongs to the reporting period.
 *
 * <h2>Status filter</h2>
 * <p>Includes every status except WITHDRAWN (a withdrawn notification was
 * never a claim) and REJECTED (claim denied, no loss recorded). The
 * regulator needs to see PROCESSING / PENDING_APPROVAL / APPROVED /
 * SETTLED claims because each represents an open or settled liability.
 *
 * <h2>Financial figures</h2>
 * <ul>
 *   <li>{@code reserveAmount} — current reserve on the claim (case + IBNR
 *       layers if any; v1 reads the {@code reserve_amount} column directly).</li>
 *   <li>{@code paidAmount} — settled disbursement. Equal to {@code dv_amount}
 *       when {@code settled_at IS NOT NULL}, else zero. Once settled this
 *       is the figure paid out of bank.</li>
 *   <li>{@code outstandingAmount} — reserveAmount − paidAmount, floored at
 *       zero. A claim where dv_amount &lt; reserve_amount surfaces a
 *       reserve release (the gap is the LIC roll-forward "reserve true-up"
 *       Phase 2 picks up).</li>
 * </ul>
 *
 * <h2>Payload shape</h2>
 * <pre>
 * {
 *   "submissionType": "CLAIMS_BORDEREAUX",
 *   "period": { "id", "start", "end" },
 *   "generatedAt": ISO-8601 timestamp,
 *   "totals": { "claimCount", "totalReserve", "totalPaid", "totalOutstanding" },
 *   "byClass": [ { "classOfBusinessCode", "classOfBusinessName", "claimCount",
 *                   "totalReserve", "totalPaid", "totalOutstanding" }, ... ],
 *   "claims": [ { "claimNumber", "policyNumber", "customerName",
 *                  "classOfBusinessCode", "classOfBusinessName", "productName",
 *                  "status", "incidentDate", "reportedDate",
 *                  "reserveAmount", "paidAmount", "outstandingAmount",
 *                  "currencyCode", "brokerName" }, ... ]
 * }
 * </pre>
 *
 * <p>Ordering is deterministic: claims sorted by {@code claim_number ASC};
 * byClass sorted by {@code class_of_business_code ASC}. Bit-identical
 * payload across runs given identical input — auditor-replayable.
 *
 * <p>Pure read engine: no DB writes. {@code NaicomSubmissionService}
 * in Slice 4.9 owns the upsert + state machine.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ClaimsBordereauxEngine {

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Composes the N06 Claims Bordereaux payload for one fiscal period.
     * Returned map preserves insertion order ({@link LinkedHashMap}) so
     * serialized artifacts are byte-deterministic across runs.
     *
     * @throws FiscalPeriodNotFoundException if the period does not exist
     *         or has been soft-deleted
     */
    public Map<String, Object> computePayload(UUID periodId) {
        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));

        LocalDate start = period.getStartDate();
        LocalDate end = period.getEndDate();

        List<Map<String, Object>> claims = loadClaimRows(start, end);
        Map<String, Map<String, Object>> byClass = aggregateByClass(claims);
        Map<String, Object> totals = aggregateTotals(claims);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("submissionType", NaicomSubmissionType.CLAIMS_BORDEREAUX.name());
        payload.put("period", periodMeta(period));
        payload.put("generatedAt", Instant.now().toString());
        payload.put("totals", totals);
        payload.put("byClass", new ArrayList<>(byClass.values()));
        payload.put("claims", claims);

        log.info("Claims Bordereaux computed for period {} ({} → {}) — {} claims, total reserve {}, total paid {}",
            periodId, start, end, claims.size(), totals.get("totalReserve"), totals.get("totalPaid"));

        return payload;
    }

    private List<Map<String, Object>> loadClaimRows(LocalDate start, LocalDate end) {
        // The `claims` table snapshots class_of_business_{id,name} but NOT
        // _code (the schema decision predates Phase 4). The code is master
        // data — effectively immutable — so we pull it from the joined
        // policies row, which IS the policy-approval-time snapshot. If a
        // class is ever renamed between policy approval and claim
        // registration, the bordereau uses the policy-time code; auditors
        // would expect this since the regulator's class taxonomy is keyed
        // on the policy contract, not on later operational events.
        return jdbcTemplate.query(
            "SELECT c.claim_number, c.policy_number, c.customer_name, " +
            "       p.class_of_business_code, c.class_of_business_name, c.product_name, " +
            "       c.status, c.broker_name, c.currency_code, " +
            "       c.incident_date, c.reported_date, " +
            "       c.reserve_amount, " +
            "       CASE WHEN c.settled_at IS NOT NULL THEN COALESCE(c.dv_amount, 0) ELSE 0 END AS paid_amount, " +
            "       GREATEST(c.reserve_amount - " +
            "         CASE WHEN c.settled_at IS NOT NULL THEN COALESCE(c.dv_amount, 0) ELSE 0 END, 0) AS outstanding_amount " +
            "FROM claims c " +
            "JOIN policies p ON p.id = c.policy_id " +
            "WHERE c.deleted_at IS NULL " +
            "  AND c.status NOT IN ('WITHDRAWN', 'REJECTED') " +
            "  AND c.reported_date BETWEEN ? AND ? " +
            "ORDER BY c.claim_number ASC",
            (rs, i) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("claimNumber", rs.getString("claim_number"));
                row.put("policyNumber", rs.getString("policy_number"));
                row.put("customerName", rs.getString("customer_name"));
                row.put("classOfBusinessCode", rs.getString("class_of_business_code"));
                row.put("classOfBusinessName", rs.getString("class_of_business_name"));
                row.put("productName", rs.getString("product_name"));
                row.put("status", rs.getString("status"));
                row.put("brokerName", rs.getString("broker_name"));
                row.put("currencyCode", rs.getString("currency_code"));
                row.put("incidentDate", rs.getDate("incident_date").toLocalDate().toString());
                row.put("reportedDate", rs.getDate("reported_date").toLocalDate().toString());
                row.put("reserveAmount", rs.getBigDecimal("reserve_amount"));
                row.put("paidAmount", rs.getBigDecimal("paid_amount"));
                row.put("outstandingAmount", rs.getBigDecimal("outstanding_amount"));
                return row;
            },
            java.sql.Date.valueOf(start), java.sql.Date.valueOf(end));
    }

    private Map<String, Map<String, Object>> aggregateByClass(List<Map<String, Object>> claims) {
        Map<String, Map<String, Object>> byClass = new LinkedHashMap<>();
        for (Map<String, Object> claim : claims) {
            String code = (String) claim.get("classOfBusinessCode");
            byClass.computeIfAbsent(code, k -> {
                Map<String, Object> seed = new LinkedHashMap<>();
                seed.put("classOfBusinessCode", code);
                seed.put("classOfBusinessName", claim.get("classOfBusinessName"));
                seed.put("claimCount", 0);
                seed.put("totalReserve", BigDecimal.ZERO);
                seed.put("totalPaid", BigDecimal.ZERO);
                seed.put("totalOutstanding", BigDecimal.ZERO);
                return seed;
            });
            Map<String, Object> agg = byClass.get(code);
            agg.put("claimCount", (Integer) agg.get("claimCount") + 1);
            agg.put("totalReserve", ((BigDecimal) agg.get("totalReserve"))
                .add((BigDecimal) claim.get("reserveAmount")));
            agg.put("totalPaid", ((BigDecimal) agg.get("totalPaid"))
                .add((BigDecimal) claim.get("paidAmount")));
            agg.put("totalOutstanding", ((BigDecimal) agg.get("totalOutstanding"))
                .add((BigDecimal) claim.get("outstandingAmount")));
        }
        return byClass;
    }

    private Map<String, Object> aggregateTotals(List<Map<String, Object>> claims) {
        Map<String, Object> totals = new LinkedHashMap<>();
        BigDecimal reserve = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        for (Map<String, Object> claim : claims) {
            reserve = reserve.add((BigDecimal) claim.get("reserveAmount"));
            paid = paid.add((BigDecimal) claim.get("paidAmount"));
            outstanding = outstanding.add((BigDecimal) claim.get("outstandingAmount"));
        }
        totals.put("claimCount", claims.size());
        totals.put("totalReserve", reserve);
        totals.put("totalPaid", paid);
        totals.put("totalOutstanding", outstanding);
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
