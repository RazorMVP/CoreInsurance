package com.nubeero.cia.dashboard;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final EntityManager em;

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("MMM yy");
    private static final DateTimeFormatter DAY_ABBR   = DateTimeFormatter.ofPattern("EEE");

    // ── 1. Eight stat cards ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DashboardStatsDto stats() {
        return DashboardStatsDto.builder()
                .activePolicies(countWhere("policies", "status = 'ACTIVE'"))
                .openClaims(countWhere("claims",
                        "status NOT IN ('SETTLED','CLOSED','CANCELLED')"))
                .pendingApprovals(pendingApprovals())
                .premiumsMtd(sumWhere("receipts", "amount",
                        "status = 'APPROVED' AND DATE_TRUNC('month', created_at) = DATE_TRUNC('month', NOW())"))
                .claimsReserveTotal(sumWhere("claims", "reserve_amount",
                        "status NOT IN ('SETTLED','CLOSED','CANCELLED')"))
                .renewalsDue30Days(countWhere("policies",
                        "status = 'ACTIVE' AND policy_end_date BETWEEN NOW() AND NOW() + INTERVAL '30 days'"))
                .outstandingPremium(sumWhere("debit_notes", "amount",
                        "status NOT IN ('PAID','REVERSED')"))
                .riUtilisationPct(riUtilisation())
                .build();
    }

    // ── 2. Approval queue by type ──────────────────────────────────────────

    @Transactional(readOnly = true)
    public ApprovalQueueDto approvalQueue() {
        return ApprovalQueueDto.builder()
                .policies(countWhere("policies",    "status = 'PENDING_APPROVAL'"))
                .quotes(countWhere("quotes",        "status = 'PENDING_APPROVAL'"))
                .endorsements(countWhere("endorsements", "status = 'PENDING_APPROVAL'"))
                .claims(countWhere("claims",        "status = 'PENDING_APPROVAL'"))
                .receipts(countWhere("receipts",    "status = 'PENDING_APPROVAL'"))
                .payments(countWhere("payments",    "status = 'PENDING_APPROVAL'"))
                .build();
    }

    // ── 3. Loss ratio — last 6 months ─────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<LossRatioMonthDto> lossRatioTrend() {
        String sql = """
            SELECT
                TO_CHAR(gs.month_start, 'Mon YY')              AS month,
                COALESCE(SUM(p.total_premium), 0)              AS premium,
                COALESCE(SUM(c.reserve_amount), 0)             AS claims
            FROM (
                SELECT generate_series(
                    DATE_TRUNC('month', NOW()) - INTERVAL '5 months',
                    DATE_TRUNC('month', NOW()),
                    '1 month'
                ) AS month_start
            ) gs
            LEFT JOIN policies p
                ON DATE_TRUNC('month', p.created_at) = gs.month_start
                AND p.deleted_at IS NULL
                AND p.status != 'CANCELLED'
            LEFT JOIN claims c
                ON DATE_TRUNC('month', c.created_at) = gs.month_start
                AND c.deleted_at IS NULL
            GROUP BY gs.month_start
            ORDER BY gs.month_start
            """;

        List<Object[]> rows = em.createNativeQuery(sql).getResultList();
        return rows.stream().map(row -> {
            String month   = (String) row[0];
            BigDecimal prem = toBD(row[1]);
            BigDecimal clm  = toBD(row[2]);
            BigDecimal ratio = prem.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : clm.divide(prem, 4, RoundingMode.HALF_UP)
                         .multiply(BigDecimal.valueOf(100))
                         .setScale(1, RoundingMode.HALF_UP);
            return LossRatioMonthDto.builder()
                    .month(month)
                    .premium(prem)
                    .claims(clm)
                    .lossRatioPct(ratio)
                    .build();
        }).collect(Collectors.toList());
    }

    // ── 4. Renewals due — next 7 days ─────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<RenewalDayDto> renewalsDue() {
        String sql = """
            SELECT DATE(policy_end_date) AS expiry_date, COUNT(*) AS cnt
            FROM policies
            WHERE status = 'ACTIVE'
              AND deleted_at IS NULL
              AND policy_end_date BETWEEN CURRENT_DATE AND CURRENT_DATE + INTERVAL '6 days'
            GROUP BY DATE(policy_end_date)
            ORDER BY expiry_date
            """;

        List<Object[]> rows = em.createNativeQuery(sql).getResultList();
        Map<LocalDate, Long> byDate = rows.stream().collect(
                Collectors.toMap(
                        r -> ((java.sql.Date) r[0]).toLocalDate(),
                        r -> ((Number) r[1]).longValue()
                )
        );

        // Always return all 7 days, filling 0 for days with no renewals
        List<RenewalDayDto> result = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 7; i++) {
            LocalDate day = today.plusDays(i);
            result.add(RenewalDayDto.builder()
                    .date(day.toString())
                    .label(day.format(DAY_ABBR))
                    .count(byDate.getOrDefault(day, 0L))
                    .build());
        }
        return result;
    }

    // ── 5. Global search ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<SearchResultDto> search(String term) {
        if (term == null || term.isBlank() || term.length() < 2) return List.of();
        String like = "%" + term.toLowerCase() + "%";

        String sql = """
            (SELECT id::text, 'Policy' AS type, policy_number AS label, status AS sub
             FROM policies
             WHERE deleted_at IS NULL AND LOWER(policy_number) LIKE ?
             ORDER BY created_at DESC LIMIT 5)
            UNION ALL
            (SELECT id::text, 'Claim' AS type, claim_number AS label, status AS sub
             FROM claims
             WHERE deleted_at IS NULL AND LOWER(claim_number) LIKE ?
             ORDER BY created_at DESC LIMIT 5)
            UNION ALL
            (SELECT id::text, 'Customer' AS type,
                    COALESCE(company_name, TRIM(first_name || ' ' || COALESCE(last_name, ''))) AS label,
                    customer_type AS sub
             FROM customers
             WHERE deleted_at IS NULL
               AND (LOWER(COALESCE(company_name,'')) LIKE ?
                    OR LOWER(COALESCE(first_name,'') || ' ' || COALESCE(last_name,'')) LIKE ?)
             ORDER BY created_at DESC LIMIT 5)
            UNION ALL
            (SELECT id::text, 'Quote' AS type, quote_number AS label, status AS sub
             FROM quotes
             WHERE deleted_at IS NULL AND LOWER(quote_number) LIKE ?
             ORDER BY created_at DESC LIMIT 5)
            LIMIT 20
            """;

        List<Object[]> rows;
        try {
            rows = em.createNativeQuery(sql)
                    .setParameter(1, like)   // policy_number
                    .setParameter(2, like)   // claim_number
                    .setParameter(3, like)   // company_name
                    .setParameter(4, like)   // first_name + last_name
                    .setParameter(5, like)   // quote_number
                    .getResultList();
        } catch (Exception e) {
            log.warn("Search query failed for term '{}': {}", term, e.getMessage());
            return List.of();
        }

        return rows.stream().map(row -> {
            String type = (String) row[1];
            String id   = (String) row[0];
            return SearchResultDto.builder()
                    .id(id)
                    .type(type)
                    .label((String) row[2])
                    .sub(row[3] != null ? capitalize((String) row[3]) : "")
                    .path(typeToPath(type, id))
                    .build();
        }).toList();
    }

    private String typeToPath(String type, String id) {
        return switch (type) {
            case "Policy"   -> "/policies/" + id;
            case "Claim"    -> "/claims/" + id;
            case "Customer" -> "/customers/" + id;
            case "Quote"    -> "/quotation/" + id;
            default         -> "/dashboard";
        };
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase().replace("_", " ");
    }

    // ── 6. Recent activity — last 10 audit log entries ─────────────────────

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<RecentActivityDto> recentActivity() {
        String sql = """
            SELECT id::text, entity_type, entity_id, action, user_name, timestamp
            FROM audit_log
            ORDER BY timestamp DESC
            LIMIT 10
            """;

        List<Object[]> rows;
        try {
            rows = em.createNativeQuery(sql).getResultList();
        } catch (Exception e) {
            log.warn("Recent activity query failed: {}", e.getMessage());
            return List.of();
        }

        return rows.stream().map(row -> {
            String action = (String) row[3];
            return RecentActivityDto.builder()
                    .id((String) row[0])
                    .entityType(formatEntityType((String) row[1]))
                    .entityId((String) row[2])
                    .action(action)
                    .userName(row[4] != null ? (String) row[4] : "System")
                    .timeAgo(timeAgo((Instant) row[5]))
                    .statusGroup(actionToStatus(action))
                    .build();
        }).toList();
    }

    private String formatEntityType(String raw) {
        if (raw == null) return "System";
        // e.g. "POLICY" → "Policy", "CLAIM" → "Claim"
        return raw.substring(0, 1).toUpperCase() + raw.substring(1).toLowerCase().replace("_", " ");
    }

    private String actionToStatus(String action) {
        if (action == null) return "pending";
        return switch (action) {
            case "APPROVE", "CREATE", "SEND", "EXECUTE" -> "active";
            case "REJECT", "CANCEL", "DELETE", "REVERSE" -> "rejected";
            default -> "pending";
        };
    }

    private String timeAgo(Instant ts) {
        if (ts == null) return "—";
        Duration d = Duration.between(ts, Instant.now());
        if (d.toMinutes() < 1)  return "just now";
        if (d.toMinutes() < 60) return d.toMinutes() + "m ago";
        if (d.toHours()   < 24) return d.toHours()   + "h ago";
        return d.toDays() + "d ago";
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private long countWhere(String table, String where) {
        try {
            String sql = "SELECT COUNT(*) FROM " + sanitize(table)
                    + " WHERE deleted_at IS NULL AND (" + where + ")";
            return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
        } catch (Exception e) {
            log.warn("Dashboard count failed for {}: {}", table, e.getMessage());
            return 0L;
        }
    }

    private BigDecimal sumWhere(String table, String column, String where) {
        try {
            String sql = "SELECT COALESCE(SUM(" + sanitize(column) + "), 0) FROM "
                    + sanitize(table) + " WHERE deleted_at IS NULL AND (" + where + ")";
            return toBD(em.createNativeQuery(sql).getSingleResult());
        } catch (Exception e) {
            log.warn("Dashboard sum failed for {}.{}: {}", table, column, e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    private long pendingApprovals() {
        try {
            String sql = """
                SELECT
                  (SELECT COUNT(*) FROM policies     WHERE deleted_at IS NULL AND status = 'PENDING_APPROVAL') +
                  (SELECT COUNT(*) FROM quotes       WHERE deleted_at IS NULL AND status = 'PENDING_APPROVAL') +
                  (SELECT COUNT(*) FROM endorsements WHERE deleted_at IS NULL AND status = 'PENDING_APPROVAL') +
                  (SELECT COUNT(*) FROM claims       WHERE deleted_at IS NULL AND status = 'PENDING_APPROVAL') +
                  (SELECT COUNT(*) FROM receipts     WHERE deleted_at IS NULL AND status = 'PENDING_APPROVAL') +
                  (SELECT COUNT(*) FROM payments     WHERE deleted_at IS NULL AND status = 'PENDING_APPROVAL')
                """;
            return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
        } catch (Exception e) {
            log.warn("Dashboard pending approvals failed: {}", e.getMessage());
            return 0L;
        }
    }

    private BigDecimal riUtilisation() {
        try {
            String sql = """
                SELECT
                    CASE WHEN SUM(t.retention_limit + COALESCE(t.surplus_capacity, 0)) = 0 THEN 0
                    ELSE ROUND(
                        SUM(a.ceded_amount) * 100.0
                        / SUM(t.retention_limit + COALESCE(t.surplus_capacity, 0)),
                        1
                    ) END
                FROM ri_allocations a
                JOIN ri_treaties t ON t.id = a.treaty_id
                WHERE a.deleted_at IS NULL
                  AND a.status = 'APPROVED'
                  AND t.deleted_at IS NULL
                """;
            Object result = em.createNativeQuery(sql).getSingleResult();
            return result == null ? BigDecimal.ZERO : toBD(result);
        } catch (Exception e) {
            log.warn("Dashboard RI utilisation failed: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal toBD(Object val) {
        if (val == null) return BigDecimal.ZERO;
        if (val instanceof BigDecimal bd) return bd;
        if (val instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(val.toString());
    }

    /** Whitelist table/column names to prevent any injection in helper methods. */
    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_]", "");
    }
}
