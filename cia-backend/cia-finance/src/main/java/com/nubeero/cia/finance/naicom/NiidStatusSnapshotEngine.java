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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NAICOM N07 — NIID upload status snapshot at period close.
 *
 * <p>Module 12 Phase 4 Slice 4.8. A compliance-ops snapshot of where the
 * insurer stands with NIID (Nigerian Insurance Industry Database) at
 * period_end: how many in-force motor / marine policies are registered,
 * how many are still pending upload, and which specific policies need
 * attention.
 *
 * <p>Technically NIID is not a NAICOM submission — it is a separate
 * regulator-adjacent registry that NAICOM examines. But the snapshot
 * shares the Phase 4 submission infrastructure (state machine, artifact
 * rendering, HARD_CLOSED period precondition, audit history), so it
 * lives in the {@code naicom} package alongside the N0n engines.
 *
 * <h2>Substrate: direct read over {@code policies}</h2>
 * <p>NIID upload status is tracked on the policy row itself —
 * {@code niid_required} (set at policy creation/conversion for
 * motor / marine), {@code niid_ref} (NIID-side reference once upload
 * succeeds), {@code niid_uploaded_at} (timestamp). There is no separate
 * {@code niid_upload_log} table in v1; the upload-workflow (the NIID
 * adapter from {@code cia-integrations}) updates the policy row in
 * place. This engine reads those columns directly.
 *
 * <h2>Scope: in-force motor / marine at period_end</h2>
 * <p>A policy is "in force at period_end" when:
 * <ul>
 *   <li>{@code niid_required = TRUE} — motor or marine</li>
 *   <li>{@code approved_at IS NOT NULL AND approved_at::date <= period_end}
 *       — the policy was actually issued on or before snapshot</li>
 *   <li>{@code policy_start_date <= period_end AND
 *       policy_end_date >= period_end} — cover dates encompass
 *       the snapshot</li>
 *   <li>{@code status NOT IN ('REJECTED', 'CANCELLED')} — status flag
 *       hasn't excluded the policy. EXPIRED / LAPSED filtering is
 *       handled by the date filter; relying on status alone would miss
 *       policies whose status flag hasn't been bumped yet by the
 *       expiry workflow.</li>
 * </ul>
 *
 * <p>This is intentionally NOT "approved during the period" — auditors
 * want to know what NIID <em>should</em> currently see, not just the
 * fresh approvals.
 *
 * <h2>Upload-status classification</h2>
 * <ul>
 *   <li><b>UPLOADED</b> — {@code niid_ref IS NOT NULL}. The live NIID
 *       adapter sets the ref on successful upload.</li>
 *   <li><b>PENDING</b> — {@code niid_ref IS NULL} for an in-force policy
 *       that should be uploaded. The actionable bucket — compliance
 *       ops reviews this list each period close.</li>
 * </ul>
 *
 * <h2>Compliance percentage</h2>
 * <p>{@code uploadCompliancePercent = uploaded / inForce × 100},
 * 2dp HALF_UP. Returns {@code null} when {@code inForce = 0} (a tenant
 * with no motor / marine business has no compliance ratio to report).
 *
 * <h2>Payload shape</h2>
 * <pre>
 * {
 *   "submissionType": "NIID_STATUS_SNAPSHOT",
 *   "period":         { "id", "start", "end" },
 *   "asOf":           "period.endDate",
 *   "generatedAt":    ISO-8601,
 *   "totals": {
 *     "inForceCount", "uploadedCount", "pendingCount",
 *     "uploadCompliancePercent"
 *   },
 *   "byClassOfBusiness": [
 *     { "classOfBusinessCode", "classOfBusinessName",
 *       "inForceCount", "uploadedCount", "pendingCount",
 *       "uploadCompliancePercent" }, ...
 *   ],
 *   "pending": [
 *     { "policyId", "policyNumber", "customerName",
 *       "classOfBusinessCode", "classOfBusinessName",
 *       "policyStartDate", "policyEndDate", "status",
 *       "approvedAt", "daysSinceApproval" }, ...
 *   ],
 *   "notes": "v1 scope disclosure"
 * }
 * </pre>
 *
 * <p>Deterministic ordering:
 * <ul>
 *   <li>{@code byClassOfBusiness} by {@code classOfBusinessCode ASC}</li>
 *   <li>{@code pending} by {@code daysSinceApproval DESC}
 *       (oldest pending first — the worst offender at the top), then
 *       {@code policyNumber ASC} as tie-breaker.</li>
 * </ul>
 *
 * <p>Pure read engine — no DB writes, no JE postings. Orchestrator
 * (Slice 4.9) owns the submission upsert + state machine.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class NiidStatusSnapshotEngine {

    private static final int PERCENT_SCALE = 2;

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final JdbcTemplate jdbcTemplate;

    public Map<String, Object> computePayload(UUID periodId) {
        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));

        LocalDate periodEnd = period.getEndDate();

        List<Map<String, Object>> rows = readInForceMotorMarine(periodEnd);

        int totalInForce = 0;
        int totalUploaded = 0;
        Map<String, ClassBucket> byClass = new LinkedHashMap<>();
        List<Map<String, Object>> pending = new ArrayList<>();

        for (Map<String, Object> r : rows) {
            String code = (String) r.get("class_of_business_code");
            String name = (String) r.get("class_of_business_name");
            ClassBucket bucket = byClass.computeIfAbsent(code, k -> new ClassBucket(name));
            bucket.inForce++;
            totalInForce++;
            String niidRef = (String) r.get("niid_ref");
            if (niidRef != null) {
                bucket.uploaded++;
                totalUploaded++;
            } else {
                pending.add(toPendingRow(r, periodEnd));
            }
        }

        int totalPending = totalInForce - totalUploaded;

        // Sort pending: daysSinceApproval DESC (Integer), then policy number ASC.
        pending.sort(Comparator
            .comparing((Map<String, Object> p) -> (Integer) p.get("daysSinceApproval"),
                Comparator.reverseOrder())
            .thenComparing(p -> (String) p.get("policyNumber"), Comparator.nullsLast(Comparator.naturalOrder())));

        // byClassOfBusiness rendered with deterministic alpha ordering.
        List<String> orderedCodes = new ArrayList<>(byClass.keySet());
        orderedCodes.sort(String::compareTo);
        List<Map<String, Object>> classRows = new ArrayList<>(orderedCodes.size());
        for (String code : orderedCodes) {
            ClassBucket b = byClass.get(code);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("classOfBusinessCode", code);
            row.put("classOfBusinessName", b.name);
            row.put("inForceCount", b.inForce);
            row.put("uploadedCount", b.uploaded);
            row.put("pendingCount", b.inForce - b.uploaded);
            row.put("uploadCompliancePercent", compliancePercent(b.uploaded, b.inForce));
            classRows.add(row);
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("inForceCount", totalInForce);
        totals.put("uploadedCount", totalUploaded);
        totals.put("pendingCount", totalPending);
        totals.put("uploadCompliancePercent", compliancePercent(totalUploaded, totalInForce));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("submissionType", NaicomSubmissionType.NIID_STATUS_SNAPSHOT.name());
        payload.put("period", periodMeta(period));
        payload.put("asOf", periodEnd.toString());
        payload.put("generatedAt", Instant.now().toString());
        payload.put("totals", totals);
        payload.put("byClassOfBusiness", classRows);
        payload.put("pending", pending);
        payload.put("notes",
            "v1 scope: snapshot of motor / marine policies (niid_required = TRUE) "
            + "in force at period_end. Upload status read from policies.niid_ref / "
            + "niid_uploaded_at — set by the NIID upload workflow in cia-integrations. "
            + "Endorsements are NOT separately surfaced here; the NIID upload workflow "
            + "submits endorsements automatically when an endorsed policy's niid_ref "
            + "is updated. A future v2 may add an endorsement-level breakdown when "
            + "the niid_endorsement_log table is introduced. EXPIRED / LAPSED status "
            + "filtering is handled by the policy_end_date filter, not by status flag, "
            + "to avoid drift between the date and the status workflow.");

        log.info("NIID Status Snapshot (N07) computed as-of {} — {} in force, "
                + "{} uploaded, {} pending, compliance {}%",
            periodEnd, totalInForce, totalUploaded, totalPending,
            totals.get("uploadCompliancePercent"));

        return payload;
    }

    private List<Map<String, Object>> readInForceMotorMarine(LocalDate periodEnd) {
        return jdbcTemplate.queryForList(
            "SELECT p.id, p.policy_number, p.customer_name, " +
            "       p.class_of_business_code, p.class_of_business_name, " +
            "       p.policy_start_date, p.policy_end_date, p.status, " +
            "       p.approved_at, p.niid_ref, p.niid_uploaded_at " +
            "FROM policies p " +
            "WHERE p.deleted_at IS NULL " +
            "  AND p.niid_required = TRUE " +
            "  AND p.approved_at IS NOT NULL " +
            "  AND p.approved_at::date <= ? " +
            "  AND p.policy_start_date <= ? " +
            "  AND p.policy_end_date >= ? " +
            "  AND p.status NOT IN ('REJECTED', 'CANCELLED') " +
            "ORDER BY p.policy_number",
            java.sql.Date.valueOf(periodEnd),
            java.sql.Date.valueOf(periodEnd),
            java.sql.Date.valueOf(periodEnd));
    }

    private Map<String, Object> toPendingRow(Map<String, Object> r, LocalDate periodEnd) {
        LocalDate approvedDate = toLocalDate(r.get("approved_at"));
        int daysSinceApproval = approvedDate == null
            ? 0
            : (int) Math.max(0, ChronoUnit.DAYS.between(approvedDate, periodEnd));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("policyId", r.get("id").toString());
        row.put("policyNumber", r.get("policy_number"));
        row.put("customerName", r.get("customer_name"));
        row.put("classOfBusinessCode", r.get("class_of_business_code"));
        row.put("classOfBusinessName", r.get("class_of_business_name"));
        row.put("policyStartDate", toIsoDate(r.get("policy_start_date")));
        row.put("policyEndDate", toIsoDate(r.get("policy_end_date")));
        row.put("status", r.get("status"));
        row.put("approvedAt", approvedDate == null ? null : approvedDate.toString());
        row.put("daysSinceApproval", daysSinceApproval);
        return row;
    }

    /**
     * Returns {@code uploaded / inForce × 100} (2dp HALF_UP) — or null when
     * inForce is zero (no compliance ratio to report).
     */
    private static BigDecimal compliancePercent(int uploaded, int inForce) {
        if (inForce == 0) return null;
        return new BigDecimal(uploaded)
            .multiply(new BigDecimal("100"))
            .divide(new BigDecimal(inForce), PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private static LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate ld) return ld;
        if (o instanceof java.sql.Date sql) return sql.toLocalDate();
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        if (o instanceof java.time.OffsetDateTime odt) return odt.toLocalDate();
        return null;
    }

    private static String toIsoDate(Object o) {
        LocalDate ld = toLocalDate(o);
        return ld == null ? null : ld.toString();
    }

    private static Map<String, Object> periodMeta(FiscalPeriod period) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", period.getId().toString());
        meta.put("start", period.getStartDate().toString());
        meta.put("end", period.getEndDate().toString());
        return meta;
    }

    /** Mutable per-class accumulator used during snapshot assembly. */
    private static final class ClassBucket {
        final String name;
        int inForce = 0;
        int uploaded = 0;

        ClassBucket(String name) { this.name = name; }
    }
}
