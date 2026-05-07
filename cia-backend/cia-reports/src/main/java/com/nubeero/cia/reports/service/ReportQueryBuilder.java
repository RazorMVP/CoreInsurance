package com.nubeero.cia.reports.service;

import com.nubeero.cia.reports.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
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

    /** Default cap for JSON results (in-memory render). */
    public static final int DEFAULT_MAX_ROWS = 10_000;

    /** Cap for CSV / PDF exports. Higher than JSON because the result is streamed/written to disk. */
    public static final int EXPORT_MAX_ROWS = 100_000;

    private final JdbcTemplate jdbcTemplate;

    private static final Set<String> AGGREGATE_FIELDS = Set.of(
            "amount",
            "ceded_amount",
            "premium",
            "reserve_amount",
            "retained_amount",
            "sum_insured",
            "total_paid"
    );

    private static final Map<DataSource, String> FROM_CLAUSES = Map.of(
            DataSource.POLICIES, " FROM policies p WHERE p.deleted_at IS NULL",
            DataSource.CLAIMS, " FROM claims cl WHERE cl.deleted_at IS NULL",
            DataSource.FINANCE, " FROM debit_notes dn WHERE dn.deleted_at IS NULL",
            DataSource.REINSURANCE, """
                     FROM ri_allocations ria
                     LEFT JOIN ri_treaties t ON t.id = ria.treaty_id
                    WHERE ria.deleted_at IS NULL""",
            DataSource.CUSTOMERS, " FROM customers c WHERE c.deleted_at IS NULL",
            DataSource.ENDORSEMENTS, " FROM endorsements e WHERE e.deleted_at IS NULL"
    );

    private static final Map<DataSource, Map<String, String>> FIELD_EXPRESSIONS = Map.of(
            DataSource.POLICIES, Map.ofEntries(
                    Map.entry("amount", "p.total_premium"),
                    Map.entry("class_of_business", "p.class_of_business_name"),
                    Map.entry("created_at", "p.created_at"),
                    Map.entry("customer_name", "p.customer_name"),
                    Map.entry("end_date", "p.policy_end_date"),
                    Map.entry("policy_number", "p.policy_number"),
                    Map.entry("premium", "p.total_premium"),
                    Map.entry("product_name", "p.product_name"),
                    Map.entry("start_date", "p.policy_start_date"),
                    Map.entry("status", "p.status"),
                    Map.entry("sum_insured", "p.total_sum_insured")
            ),
            DataSource.CLAIMS, Map.ofEntries(
                    Map.entry("amount", "COALESCE(cl.approved_amount, cl.reserve_amount, 0)"),
                    Map.entry("claim_number", "cl.claim_number"),
                    Map.entry("class_of_business", "cl.class_of_business_name"),
                    Map.entry("created_at", "cl.created_at"),
                    Map.entry("customer_name", "cl.customer_name"),
                    Map.entry("policy_number", "cl.policy_number"),
                    Map.entry("registered_at", "cl.created_at"),
                    Map.entry("reserve_amount", "cl.reserve_amount"),
                    Map.entry("status", "cl.status"),
                    Map.entry("total_paid", "COALESCE(cl.approved_amount, 0)")
            ),
            DataSource.FINANCE, Map.ofEntries(
                    Map.entry("amount", "dn.total_amount"),
                    Map.entry("created_at", "dn.created_at"),
                    Map.entry("customer_name", "dn.customer_name"),
                    Map.entry("debit_note_number", "dn.debit_note_number"),
                    Map.entry("due_date", "dn.due_date"),
                    Map.entry("policy_number", "dn.entity_reference"),
                    Map.entry("status", "dn.status")
            ),
            DataSource.REINSURANCE, Map.ofEntries(
                    Map.entry("amount", "ria.ceded_amount"),
                    Map.entry("ceded_amount", "ria.ceded_amount"),
                    Map.entry("created_at", "ria.created_at"),
                    Map.entry("policy_number", "ria.policy_number"),
                    Map.entry("retained_amount", "ria.retained_amount"),
                    Map.entry("status", "ria.status"),
                    Map.entry("treaty_name", "COALESCE(NULLIF(t.description, ''), ria.treaty_type)"),
                    Map.entry("treaty_type", "ria.treaty_type")
            ),
            DataSource.CUSTOMERS, Map.ofEntries(
                    Map.entry("channel", "'DIRECT'"),
                    Map.entry("created_at", "c.created_at"),
                    Map.entry("customer_name", customerDisplayNameExpression()),
                    Map.entry("customer_type", "c.customer_type"),
                    Map.entry("full_name", customerDisplayNameExpression()),
                    Map.entry("id", "c.id"),
                    Map.entry("kyc_status", "c.kyc_status"),
                    Map.entry("status", "c.customer_status")
            ),
            DataSource.ENDORSEMENTS, Map.ofEntries(
                    Map.entry("amount", "e.premium_adjustment"),
                    Map.entry("class_of_business", "e.class_of_business_name"),
                    Map.entry("created_at", "e.created_at"),
                    Map.entry("customer_name", "e.customer_name"),
                    Map.entry("effective_date", "e.effective_date"),
                    Map.entry("endorsement_number", "e.endorsement_number"),
                    Map.entry("endorsement_type", "e.endorsement_type"),
                    Map.entry("end_date", "e.policy_end_date"),
                    Map.entry("policy_number", "e.policy_number"),
                    Map.entry("premium", "e.premium_adjustment"),
                    Map.entry("product_name", "e.product_name"),
                    Map.entry("status", "e.status"),
                    Map.entry("sum_insured", "e.new_sum_insured")
            )
    );

    public List<Map<String, Object>> execute(ReportDefinition definition,
                                              Map<String, String> filterValues) {
        return execute(definition, filterValues, DEFAULT_MAX_ROWS);
    }

    public List<Map<String, Object>> execute(ReportDefinition definition,
                                              Map<String, String> filterValues,
                                              int maxRows) {
        if (maxRows <= 0) {
            throw new IllegalArgumentException("maxRows must be greater than zero");
        }

        ReportConfig config = definition.getConfig();
        DataSource dataSource = definition.getDataSource();
        List<ReportField> rawFields = rawFields(config);
        if (rawFields.isEmpty()) {
            throw new IllegalArgumentException("Report definition must include at least one raw field");
        }
        boolean grouped = config.getGroupBy() != null && !config.getGroupBy().isBlank();
        Set<String> selectedAliases = new HashSet<>();
        Set<String> groupByExpressions = new LinkedHashSet<>();

        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < rawFields.size(); i++) {
            ReportField field = rawFields.get(i);
            String expression = fieldExpression(dataSource, field.getKey());
            if (i > 0) {
                sql.append(", ");
            }

            if (grouped && isAggregateField(field)) {
                sql.append("COALESCE(SUM(").append(expression).append("), 0) AS ").append(field.getKey());
            } else {
                sql.append(expression).append(" AS ").append(field.getKey());
                if (grouped) {
                    groupByExpressions.add(expression);
                }
            }
            selectedAliases.add(field.getKey());
        }
        sql.append(FROM_CLAUSES.get(dataSource));

        List<Object> params = new ArrayList<>();

        // Apply filters
        if (config.getFilters() != null && filterValues != null) {
            for (ReportFilter filter : config.getFilters()) {
                String value = filterValues.get(filter.getKey());
                if (value == null || value.isBlank()) continue;

                switch (filter.getKey()) {
                    case "date_from" -> {
                        sql.append(" AND ").append(createdAtCol(definition.getDataSource()))
                           .append(" >= ?");
                        params.add(LocalDate.parse(value).atStartOfDay());
                    }
                    case "date_to" -> {
                        sql.append(" AND ").append(createdAtCol(definition.getDataSource()))
                           .append(" < ?");
                        params.add(LocalDate.parse(value).plusDays(1).atStartOfDay());
                    }
                    case "class_of_business_id" -> {
                        // Only datasources that expose class_of_business_id support this filter.
                        if (hasCobJoin(definition.getDataSource())) {
                            sql.append(" AND ").append(classOfBusinessIdCol(dataSource)).append(" = ?");
                            params.add(UUID.fromString(value));
                        }
                    }
                    case "product_id" -> {
                        if (definition.getDataSource() == DataSource.POLICIES) {
                            sql.append(" AND p.product_id = ?");
                            params.add(UUID.fromString(value));
                        }
                    }
                    case "status" -> {
                        sql.append(" AND ").append(statusCol(definition.getDataSource()))
                           .append(" = ?");
                        params.add(value);
                    }
                    default -> log.debug("Unhandled filter key: {}", filter.getKey());
                }
            }
        }

        if (grouped && !groupByExpressions.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", groupByExpressions));
        }

        // Apply sort
        appendSort(sql, config, dataSource, selectedAliases, grouped);
        sql.append(" LIMIT ?");
        params.add(maxRows);

        return applyComputedFields(jdbcTemplate.queryForList(sql.toString(), params.toArray()), config);
    }

    private List<Map<String, Object>> applyComputedFields(List<Map<String, Object>> rawRows,
                                                            ReportConfig config) {
        return rawRows.stream().map(row -> {
            Map<String, Object> map = new LinkedHashMap<>(row);
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

    private String classOfBusinessIdCol(DataSource ds) {
        return switch (ds) {
            case POLICIES     -> "p.class_of_business_id";
            case CLAIMS       -> "cl.class_of_business_id";
            case ENDORSEMENTS -> "e.class_of_business_id";
            default -> throw new IllegalArgumentException("Data source does not support class filter: " + ds);
        };
    }

    /** Returns true only for datasources whose base query JOINs class_of_business. */
    private boolean hasCobJoin(DataSource ds) {
        return ds == DataSource.POLICIES || ds == DataSource.CLAIMS
                || ds == DataSource.ENDORSEMENTS;
    }

    private List<ReportField> rawFields(ReportConfig config) {
        if (config.getFields() == null) {
            return List.of();
        }
        return config.getFields().stream()
                .filter(field -> !field.isComputed())
                .toList();
    }

    private String fieldExpression(DataSource dataSource, String fieldKey) {
        String expression = FIELD_EXPRESSIONS.getOrDefault(dataSource, Map.of()).get(fieldKey);
        if (expression == null) {
            throw new IllegalArgumentException("Unsupported report field " + fieldKey
                    + " for data source " + dataSource);
        }
        return expression;
    }

    private boolean isAggregateField(ReportField field) {
        return AGGREGATE_FIELDS.contains(field.getKey());
    }

    private void appendSort(StringBuilder sql,
                            ReportConfig config,
                            DataSource dataSource,
                            Set<String> selectedAliases,
                            boolean grouped) {
        if (config.getSortBy() == null || config.getSortBy().isBlank()) {
            return;
        }

        String sortKey = config.getSortBy();
        String dir = "ASC".equalsIgnoreCase(config.getSortDir()) ? "ASC" : "DESC";
        if (selectedAliases.contains(sortKey)) {
            sql.append(" ORDER BY ").append(sortKey).append(" ").append(dir);
            return;
        }

        String expression = fieldExpression(dataSource, sortKey);
        if (grouped && AGGREGATE_FIELDS.contains(sortKey)) {
            sql.append(" ORDER BY COALESCE(SUM(").append(expression).append("), 0) ").append(dir);
        } else {
            sql.append(" ORDER BY ").append(expression).append(" ").append(dir);
        }
    }

    private static String customerDisplayNameExpression() {
        return """
                COALESCE(
                    NULLIF(TRIM(CONCAT_WS(' ', c.first_name, c.last_name)), ''),
                    NULLIF(c.company_name, ''),
                    NULLIF(c.contact_person, ''),
                    c.id::text
                )""";
    }
}
