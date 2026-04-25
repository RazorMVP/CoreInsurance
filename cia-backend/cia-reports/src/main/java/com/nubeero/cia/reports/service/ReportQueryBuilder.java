package com.nubeero.cia.reports.service;

import com.nubeero.cia.reports.domain.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Builds and executes tenant-scoped native SQL from a ReportConfig.
 * Never depends on any business module — queries the tenant schema directly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportQueryBuilder {

    private final EntityManager entityManager;

    // Base SQL templates per data source
    private static final Map<DataSource, String> BASE_QUERIES = Map.of(
        DataSource.POLICIES,
            "SELECT p.policy_number, c.full_name AS customer_name, " +
            "cob.name AS class_of_business, pr.name AS product_name, " +
            "p.sum_insured, p.premium, p.status, p.start_date, p.end_date, " +
            "p.inception_date, p.created_at FROM policy p " +
            "LEFT JOIN customer c ON c.id = p.customer_id " +
            "LEFT JOIN class_of_business cob ON cob.id = p.class_of_business_id " +
            "LEFT JOIN product pr ON pr.id = p.product_id WHERE p.deleted_at IS NULL",

        DataSource.CLAIMS,
            "SELECT cl.claim_number, p.policy_number, c.full_name AS customer_name, " +
            "cob.name AS class_of_business, cl.status, cl.reserve_amount, " +
            "cl.total_paid, cl.incident_date, cl.registered_at, cl.created_at " +
            "FROM claim cl " +
            "LEFT JOIN policy p ON p.id = cl.policy_id " +
            "LEFT JOIN customer c ON c.id = p.customer_id " +
            "LEFT JOIN class_of_business cob ON cob.id = p.class_of_business_id " +
            "WHERE cl.deleted_at IS NULL",

        DataSource.FINANCE,
            "SELECT dn.debit_note_number, p.policy_number, c.full_name AS customer_name, " +
            "dn.amount, dn.status, dn.due_date, dn.created_at FROM debit_note dn " +
            "LEFT JOIN policy p ON p.id = dn.policy_id " +
            "LEFT JOIN customer c ON c.id = p.customer_id " +
            "WHERE dn.deleted_at IS NULL",

        DataSource.REINSURANCE,
            "SELECT ria.id AS allocation_id, p.policy_number, t.name AS treaty_name, " +
            "t.type AS treaty_type, ria.retained_amount, ria.ceded_amount, " +
            "ria.status, ria.created_at FROM ri_allocation ria " +
            "LEFT JOIN policy p ON p.id = ria.policy_id " +
            "LEFT JOIN reinsurance_treaty t ON t.id = ria.treaty_id " +
            "WHERE ria.deleted_at IS NULL",

        DataSource.CUSTOMERS,
            "SELECT c.id, c.full_name, c.customer_type, c.kyc_status, " +
            "c.channel, c.created_at FROM customer c WHERE c.deleted_at IS NULL",

        DataSource.ENDORSEMENTS,
            "SELECT e.endorsement_number, p.policy_number, c.full_name AS customer_name, " +
            "e.type AS endorsement_type, e.endorsement_premium, e.effective_date, " +
            "e.status, e.created_at FROM endorsement e " +
            "LEFT JOIN policy p ON p.id = e.policy_id " +
            "LEFT JOIN customer c ON c.id = p.customer_id " +
            "WHERE e.deleted_at IS NULL"
    );

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> execute(ReportDefinition definition,
                                              Map<String, String> filterValues) {
        ReportConfig config = definition.getConfig();
        StringBuilder sql = new StringBuilder(BASE_QUERIES.get(definition.getDataSource()));
        List<Object> params = new ArrayList<>();
        int paramIdx = 1;

        // Apply filters
        if (config.getFilters() != null && filterValues != null) {
            for (ReportFilter filter : config.getFilters()) {
                String value = filterValues.get(filter.getKey());
                if (value == null || value.isBlank()) continue;

                switch (filter.getKey()) {
                    case "date_from" -> {
                        sql.append(" AND ").append(createdAtCol(definition.getDataSource()))
                           .append(" >= ?").append(paramIdx++);
                        params.add(LocalDate.parse(value).atStartOfDay());
                    }
                    case "date_to" -> {
                        sql.append(" AND ").append(createdAtCol(definition.getDataSource()))
                           .append(" < ?").append(paramIdx++);
                        params.add(LocalDate.parse(value).plusDays(1).atStartOfDay());
                    }
                    case "class_of_business_id" -> {
                        // Only datasources that JOIN class_of_business support this filter
                        if (hasCobJoin(definition.getDataSource())) {
                            sql.append(" AND cob.id = ?").append(paramIdx++);
                            params.add(UUID.fromString(value));
                        }
                    }
                    case "product_id" -> {
                        if (definition.getDataSource() == DataSource.POLICIES) {
                            sql.append(" AND pr.id = ?").append(paramIdx++);
                            params.add(UUID.fromString(value));
                        }
                    }
                    case "status" -> {
                        sql.append(" AND ").append(statusCol(definition.getDataSource()))
                           .append(" = ?").append(paramIdx++);
                        params.add(value);
                    }
                    default -> log.debug("Unhandled filter key: {}", filter.getKey());
                }
            }
        }

        // Apply sort
        if (config.getSortBy() != null && !config.getSortBy().isBlank()) {
            String dir = "ASC".equalsIgnoreCase(config.getSortDir()) ? "ASC" : "DESC";
            sql.append(" ORDER BY ").append(sanitizeColumnName(config.getSortBy()))
               .append(" ").append(dir);
        }

        Query query = entityManager.createNativeQuery(sql.toString());
        for (int i = 0; i < params.size(); i++) {
            query.setParameter(i + 1, params.get(i));
        }

        List<Object[]> rawRows = query.getResultList();
        return applyComputedFields(rawRows, config);
    }

    private List<Map<String, Object>> applyComputedFields(List<Object[]> rawRows,
                                                            ReportConfig config) {
        if (config.getFields() == null) return List.of();

        List<ReportField> rawFields = config.getFields().stream()
                .filter(f -> !f.isComputed()).toList();

        return rawRows.stream().map(row -> {
            Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < Math.min(rawFields.size(), row.length); i++) {
                map.put(rawFields.get(i).getKey(), row[i]);
            }
            // Apply computed formulas
            for (ReportField f : config.getFields()) {
                if (!f.isComputed()) continue;
                switch (f.getKey()) {
                    case "loss_ratio" -> map.put("loss_ratio",
                            computeRatio(map, "claims_incurred", "premium_earned"));
                    case "combined_ratio" -> map.put("combined_ratio",
                            computeCombinedRatio(map));
                    case "expense_ratio" -> map.put("expense_ratio",
                            computeRatio(map, "expenses", "premium_earned"));
                    case "retention_pct" -> map.put("retention_pct",
                            computeRatio(map, "retained_si", "gross_si"));
                    case "cession_pct" -> map.put("cession_pct",
                            computeRatio(map, "ceded_si", "gross_si"));
                    case "conversion_pct" -> map.put("conversion_pct",
                            computeRatio(map, "bound_quotes", "total_quotes"));
                    case "utilisation_pct" -> map.put("utilisation_pct",
                            computeRatio(map, "ceded_amount", "retained_amount"));
                    default -> log.debug("Unknown computed field: {}", f.getKey());
                }
            }
            return map;
        }).toList();
    }

    private BigDecimal computeRatio(Map<String, Object> row,
                                     String numeratorKey, String denominatorKey) {
        try {
            BigDecimal num = toBigDecimal(row.get(numeratorKey));
            BigDecimal den = toBigDecimal(row.get(denominatorKey));
            if (den == null || den.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
            return num.divide(den, 4, RoundingMode.HALF_UP)
                      .multiply(BigDecimal.valueOf(100))
                      .setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal computeCombinedRatio(Map<String, Object> row) {
        try {
            BigDecimal claims = toBigDecimal(row.get("claims_incurred"));
            BigDecimal expenses = toBigDecimal(row.get("expenses"));
            BigDecimal premium = toBigDecimal(row.get("premium_earned"));
            if (premium == null || premium.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
            return claims.add(expenses)
                         .divide(premium, 4, RoundingMode.HALF_UP)
                         .multiply(BigDecimal.valueOf(100))
                         .setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal toBigDecimal(Object val) {
        if (val == null) return BigDecimal.ZERO;
        if (val instanceof BigDecimal bd) return bd;
        if (val instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(val.toString());
    }

    /** Maps each datasource to its primary table's created_at column alias. */
    private String createdAtCol(DataSource ds) {
        return switch (ds) {
            case POLICIES     -> "p.created_at";
            case CLAIMS       -> "cl.created_at";
            case FINANCE      -> "dn.created_at";
            case REINSURANCE  -> "ria.created_at";
            case CUSTOMERS    -> "c.created_at";
            case ENDORSEMENTS -> "e.created_at";
        };
    }

    /** Maps each datasource to its status column alias. */
    private String statusCol(DataSource ds) {
        return switch (ds) {
            case POLICIES     -> "p.status";
            case CLAIMS       -> "cl.status";
            case FINANCE      -> "dn.status";
            case REINSURANCE  -> "ria.status";
            case CUSTOMERS    -> "c.kyc_status";
            case ENDORSEMENTS -> "e.status";
        };
    }

    /** Returns true only for datasources whose base query JOINs class_of_business. */
    private boolean hasCobJoin(DataSource ds) {
        return ds == DataSource.POLICIES || ds == DataSource.CLAIMS
                || ds == DataSource.ENDORSEMENTS;
    }

    /** Whitelist-based column name sanitizer — prevents SQL injection in ORDER BY. */
    private String sanitizeColumnName(String raw) {
        if (raw == null) return "created_at";
        // Allow only alphanumeric + underscore + dot (for table.column)
        return raw.replaceAll("[^a-zA-Z0-9_.]", "").toLowerCase();
    }
}
