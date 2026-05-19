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
 * NAICOM N04 — Quarterly RI Returns.
 *
 * <p>Module 12 Phase 4 Slice 4.5. Per-treaty + per-reinsurer ceded
 * premium register for one fiscal period, plus a separate list of
 * facultative covers (which are one-off cessions outside any treaty).
 *
 * <h2>Underwriting view</h2>
 * <p>Reads {@code ri_allocations} + {@code ri_allocation_lines} +
 * {@code ri_treaties} (for treaty cessions) and {@code ri_fac_covers}
 * (for facultative cessions). Does NOT read {@code journal_entry_line}.
 * Same trade-off as {@link AnnualRevenueAccountEngine}: source-table view,
 * not GL view. Reconciliation against the GL is possible after Slice 1.10
 * promotes {@code treaty_id} into {@code dimension_tags}.
 *
 * <h2>Period filter convention</h2>
 * <ul>
 *   <li>Treaty cessions ({@code ri_allocations}): {@code created_at::date
 *       BETWEEN period_start AND period_end}. Allocations have no
 *       {@code approved_at} column; {@code created_at} is the canonical
 *       booking-date proxy (auto-allocation runs at policy approval, so in
 *       practice the two dates align within minutes).</li>
 *   <li>Facultative cessions ({@code ri_fac_covers}): {@code approved_at::date
 *       BETWEEN period_start AND period_end} — FAC covers DO have
 *       {@code approved_at}, and a FAC cession is recognised only on
 *       confirmation, not on creation.</li>
 * </ul>
 *
 * <h2>Status filter</h2>
 * <ul>
 *   <li>{@code ri_allocations.status = 'CONFIRMED'} — only finalised cessions.
 *       DRAFT allocations aren't finalised; CANCELLED reversals are excluded.</li>
 *   <li>{@code ri_fac_covers.status = 'CONFIRMED'} — same semantic for FAC.
 *       PENDING covers aren't reported; CANCELLED covers are excluded.</li>
 * </ul>
 *
 * <h2>What's deferred to v2</h2>
 * <p>Claims-ceded reporting. The NAICOM N04 format ideally includes both
 * ceded premium AND ceded claims (recoveries from reinsurers). The
 * claims-recovery posting flow isn't yet wired in cia-claims, so v1 of
 * this engine covers ceded premium only. The deferral is disclosed in the
 * {@code notes} field of every emitted payload so the regulator sees the
 * scope explicitly.
 *
 * <h2>Treaty display name</h2>
 * <p>{@code ri_treaties} doesn't carry a {@code treaty_name} column; the
 * schema models treaties by (type, year) with optional product / class
 * scope. The engine synthesises a display name as
 * {@code "{type}-{year}"} (e.g. {@code "SURPLUS-2026"}) so the regulator's
 * eyeball-comparison against treaty documentation works. v2 may add an
 * explicit {@code treaty_name} column to the schema.
 *
 * <h2>Payload shape</h2>
 * <pre>
 * {
 *   "submissionType": "RI_QUARTERLY_RETURN",
 *   "period":         { "id", "start", "end" },
 *   "generatedAt":    ISO-8601,
 *   "totals":         { "treatyCessionCount", "treatyPremiumCeded",
 *                       "treatyCommission",
 *                       "facCoverCount", "facPremiumCeded", "facCommission",
 *                       "totalPremiumCeded", "totalCommission" },
 *   "byTreaty":       [ { "treatyId", "treatyType", "treatyYear",
 *                          "displayName", "allocationCount",
 *                          "premiumCeded", "commission",
 *                          "byReinsurer": [...] }, ... ],
 *   "facCovers":      [ { "facReference", "policyNumber",
 *                          "reinsurerId", "reinsurerName",
 *                          "premiumCeded", "commission",
 *                          "coverFrom", "coverTo", "approvedAt" }, ... ],
 *   "byReinsurer":    [ { "reinsurerId", "reinsurerName",
 *                          "treatyPremiumCeded", "facPremiumCeded",
 *                          "totalPremiumCeded", "totalCommission" }, ... ],
 *   "notes":          "v1 disclosure"
 * }
 * </pre>
 *
 * <p>Deterministic ordering: treaties by (year DESC, type ASC, id ASC);
 * reinsurer rows within each treaty + the overall byReinsurer rollup by
 * reinsurer_name ASC; FAC covers by fac_reference ASC.
 *
 * <p>Pure read engine — no DB writes. Orchestrator (Slice 4.9) owns the
 * submission upsert + state machine.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RiQuarterlyReturnEngine implements NaicomSubmissionEngine {

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public NaicomSubmissionType type() {
        return NaicomSubmissionType.RI_QUARTERLY_RETURN;
    }

    @Override
    public Map<String, Object> computePayload(UUID periodId) {
        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));

        LocalDate start = period.getStartDate();
        LocalDate end = period.getEndDate();

        List<Map<String, Object>> byTreaty = loadTreatyCessions(start, end);
        List<Map<String, Object>> facCovers = loadFacCovers(start, end);
        List<Map<String, Object>> byReinsurer = rollUpByReinsurer(byTreaty, facCovers);
        Map<String, Object> totals = aggregateTotals(byTreaty, facCovers);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("submissionType", NaicomSubmissionType.RI_QUARTERLY_RETURN.name());
        payload.put("period", periodMeta(period));
        payload.put("generatedAt", Instant.now().toString());
        payload.put("totals", totals);
        payload.put("byTreaty", byTreaty);
        payload.put("facCovers", facCovers);
        payload.put("byReinsurer", byReinsurer);
        payload.put("notes",
            "v1 covers ceded premium only (treaty cessions + facultative). "
            + "Claims-ceded reporting (recoveries from reinsurers) is deferred to v2 "
            + "pending the claims-recovery posting flow in cia-claims.");

        log.info("RI Quarterly Return computed for period {} ({} → {}) — {} treaty cessions, {} FAC covers, total ceded {}",
            periodId, start, end, byTreaty.size(), facCovers.size(), totals.get("totalPremiumCeded"));

        return payload;
    }

    /**
     * Loads treaty cessions for the period, grouped by treaty with a
     * per-reinsurer drilldown nested inside each treaty row.
     */
    private List<Map<String, Object>> loadTreatyCessions(LocalDate start, LocalDate end) {
        // Two-step approach:
        //   1) sum at (treaty, reinsurer) granularity from ri_allocation_lines
        //   2) group those rows by treaty, attaching the per-reinsurer list

        List<Map<String, Object>> rows = jdbcTemplate.query(
            "SELECT t.id AS treaty_id, t.treaty_type, t.treaty_year, " +
            "       l.reinsurance_company_id, l.reinsurance_company_name, " +
            "       COUNT(DISTINCT a.id) AS allocation_count, " +
            "       COALESCE(SUM(l.ceded_premium), 0) AS premium_ceded, " +
            "       COALESCE(SUM(l.commission_amount), 0) AS commission_amount " +
            "FROM ri_allocation_lines l " +
            "JOIN ri_allocations a ON a.id = l.allocation_id " +
            "JOIN ri_treaties t    ON t.id = a.treaty_id " +
            "WHERE a.deleted_at IS NULL " +
            "  AND a.status = 'CONFIRMED' " +
            "  AND a.created_at::date BETWEEN ? AND ? " +
            "GROUP BY t.id, t.treaty_type, t.treaty_year, " +
            "         l.reinsurance_company_id, l.reinsurance_company_name " +
            "ORDER BY t.treaty_year DESC, t.treaty_type ASC, t.id ASC, " +
            "         l.reinsurance_company_name ASC",
            (rs, i) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("treatyId", (UUID) rs.getObject("treaty_id"));
                row.put("treatyType", rs.getString("treaty_type"));
                row.put("treatyYear", rs.getInt("treaty_year"));
                row.put("reinsurerId", (UUID) rs.getObject("reinsurance_company_id"));
                row.put("reinsurerName", rs.getString("reinsurance_company_name"));
                row.put("allocationCount", rs.getInt("allocation_count"));
                row.put("premiumCeded", rs.getBigDecimal("premium_ceded"));
                row.put("commission", rs.getBigDecimal("commission_amount"));
                return row;
            },
            java.sql.Date.valueOf(start), java.sql.Date.valueOf(end));

        // Group by treaty, preserving order. Within each treaty, the
        // reinsurer rows are already sorted by the SQL ORDER BY.
        Map<UUID, Map<String, Object>> treatyMap = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            UUID treatyId = (UUID) row.get("treatyId");
            Map<String, Object> treaty = treatyMap.computeIfAbsent(treatyId, k -> {
                Map<String, Object> seed = new LinkedHashMap<>();
                String type = (String) row.get("treatyType");
                Integer year = (Integer) row.get("treatyYear");
                seed.put("treatyId", treatyId);
                seed.put("treatyType", type);
                seed.put("treatyYear", year);
                seed.put("displayName", type + "-" + year);
                seed.put("allocationCount", 0);
                seed.put("premiumCeded", BigDecimal.ZERO);
                seed.put("commission", BigDecimal.ZERO);
                seed.put("byReinsurer", new ArrayList<Map<String, Object>>());
                return seed;
            });

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> byReinsurer = (List<Map<String, Object>>) treaty.get("byReinsurer");
            Map<String, Object> reinsurerRow = new LinkedHashMap<>();
            reinsurerRow.put("reinsurerId", row.get("reinsurerId"));
            reinsurerRow.put("reinsurerName", row.get("reinsurerName"));
            reinsurerRow.put("premiumCeded", row.get("premiumCeded"));
            reinsurerRow.put("commission", row.get("commission"));
            byReinsurer.add(reinsurerRow);

            // Treaty totals — sum the per-reinsurer rows.
            // allocationCount is the max of any single reinsurer's count
            // (each line of the same allocation references the same
            // distinct allocation_id; COUNT(DISTINCT a.id) gives that count
            // per reinsurer share, all equal). Use the first one seen.
            if ((Integer) treaty.get("allocationCount") == 0) {
                treaty.put("allocationCount", row.get("allocationCount"));
            }
            treaty.put("premiumCeded",
                ((BigDecimal) treaty.get("premiumCeded")).add((BigDecimal) row.get("premiumCeded")));
            treaty.put("commission",
                ((BigDecimal) treaty.get("commission")).add((BigDecimal) row.get("commission")));
        }
        return new ArrayList<>(treatyMap.values());
    }

    /**
     * Loads facultative covers confirmed in the period, sorted by
     * fac_reference for determinism.
     */
    private List<Map<String, Object>> loadFacCovers(LocalDate start, LocalDate end) {
        return jdbcTemplate.query(
            "SELECT fac_reference, policy_number, " +
            "       reinsurance_company_id, reinsurance_company_name, " +
            "       premium_ceded, commission_amount, " +
            "       cover_from, cover_to, approved_at " +
            "FROM ri_fac_covers " +
            "WHERE deleted_at IS NULL " +
            "  AND status = 'CONFIRMED' " +
            "  AND approved_at IS NOT NULL " +
            "  AND approved_at::date BETWEEN ? AND ? " +
            "ORDER BY fac_reference ASC",
            (rs, i) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("facReference", rs.getString("fac_reference"));
                row.put("policyNumber", rs.getString("policy_number"));
                row.put("reinsurerId", (UUID) rs.getObject("reinsurance_company_id"));
                row.put("reinsurerName", rs.getString("reinsurance_company_name"));
                row.put("premiumCeded", rs.getBigDecimal("premium_ceded"));
                row.put("commission", rs.getBigDecimal("commission_amount"));
                row.put("coverFrom", rs.getDate("cover_from").toLocalDate().toString());
                row.put("coverTo", rs.getDate("cover_to").toLocalDate().toString());
                row.put("approvedAt", rs.getTimestamp("approved_at").toInstant().toString());
                return row;
            },
            java.sql.Date.valueOf(start), java.sql.Date.valueOf(end));
    }

    /**
     * Combined per-reinsurer rollup across both treaty cessions and FAC
     * covers. Sorted by reinsurer_name ASC for determinism. Same reinsurer
     * appearing in multiple treaties + FAC covers is aggregated into a
     * single row showing both component totals plus a grand total.
     */
    private List<Map<String, Object>> rollUpByReinsurer(
            List<Map<String, Object>> byTreaty, List<Map<String, Object>> facCovers) {
        Map<UUID, Map<String, Object>> reinsurerMap = new LinkedHashMap<>();

        for (Map<String, Object> treaty : byTreaty) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> reinsurers = (List<Map<String, Object>>) treaty.get("byReinsurer");
            for (Map<String, Object> r : reinsurers) {
                UUID id = (UUID) r.get("reinsurerId");
                Map<String, Object> agg = reinsurerMap.computeIfAbsent(id, k -> freshReinsurerAgg(r));
                agg.put("treatyPremiumCeded",
                    ((BigDecimal) agg.get("treatyPremiumCeded")).add((BigDecimal) r.get("premiumCeded")));
                agg.put("totalCommission",
                    ((BigDecimal) agg.get("totalCommission")).add((BigDecimal) r.get("commission")));
            }
        }

        for (Map<String, Object> fac : facCovers) {
            UUID id = (UUID) fac.get("reinsurerId");
            Map<String, Object> agg = reinsurerMap.computeIfAbsent(id, k -> freshReinsurerAgg(fac));
            agg.put("facPremiumCeded",
                ((BigDecimal) agg.get("facPremiumCeded")).add((BigDecimal) fac.get("premiumCeded")));
            agg.put("totalCommission",
                ((BigDecimal) agg.get("totalCommission")).add((BigDecimal) fac.get("commission")));
        }

        // Compute totalPremiumCeded per row.
        for (Map<String, Object> agg : reinsurerMap.values()) {
            agg.put("totalPremiumCeded",
                ((BigDecimal) agg.get("treatyPremiumCeded"))
                    .add((BigDecimal) agg.get("facPremiumCeded")));
        }

        // Re-sort by reinsurer_name ASC (LinkedHashMap was just-encountered order,
        // not name-sorted).
        List<Map<String, Object>> sorted = new ArrayList<>(reinsurerMap.values());
        sorted.sort((a, b) -> ((String) a.get("reinsurerName"))
            .compareTo((String) b.get("reinsurerName")));
        return sorted;
    }

    private static Map<String, Object> freshReinsurerAgg(Map<String, Object> source) {
        Map<String, Object> agg = new LinkedHashMap<>();
        agg.put("reinsurerId", source.get("reinsurerId"));
        agg.put("reinsurerName", source.get("reinsurerName"));
        agg.put("treatyPremiumCeded", BigDecimal.ZERO);
        agg.put("facPremiumCeded", BigDecimal.ZERO);
        agg.put("totalPremiumCeded", BigDecimal.ZERO);
        agg.put("totalCommission", BigDecimal.ZERO);
        return agg;
    }

    private Map<String, Object> aggregateTotals(
            List<Map<String, Object>> byTreaty, List<Map<String, Object>> facCovers) {
        BigDecimal treatyPremium = BigDecimal.ZERO;
        BigDecimal treatyCommission = BigDecimal.ZERO;
        int treatyCessionCount = 0;
        for (Map<String, Object> t : byTreaty) {
            treatyPremium = treatyPremium.add((BigDecimal) t.get("premiumCeded"));
            treatyCommission = treatyCommission.add((BigDecimal) t.get("commission"));
            treatyCessionCount += (Integer) t.get("allocationCount");
        }

        BigDecimal facPremium = BigDecimal.ZERO;
        BigDecimal facCommission = BigDecimal.ZERO;
        for (Map<String, Object> f : facCovers) {
            facPremium = facPremium.add((BigDecimal) f.get("premiumCeded"));
            facCommission = facCommission.add((BigDecimal) f.get("commission"));
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("treatyCessionCount", treatyCessionCount);
        totals.put("treatyPremiumCeded", treatyPremium);
        totals.put("treatyCommission", treatyCommission);
        totals.put("facCoverCount", facCovers.size());
        totals.put("facPremiumCeded", facPremium);
        totals.put("facCommission", facCommission);
        totals.put("totalPremiumCeded", treatyPremium.add(facPremium));
        totals.put("totalCommission", treatyCommission.add(facCommission));
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
