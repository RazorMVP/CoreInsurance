package com.nubeero.cia.reports.service;

import com.nubeero.cia.reports.domain.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import jakarta.persistence.TupleElement;
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

    /** Default cap for JSON results (in-memory render). */
    public static final int DEFAULT_MAX_ROWS = 10_000;

    /** Cap for CSV / PDF exports. Higher than JSON because the result is streamed/written to disk. */
    public static final int EXPORT_MAX_ROWS = 100_000;

    private final EntityManager entityManager;

    // Base SQL templates per data source.
    //
    // Contract: each entry ends at the WHERE clause's last condition (no trailing
    // ORDER BY / GROUP BY). The filter loop in execute() appends ` AND <expr>`
    // for each user-supplied filter; if the source needs GROUP BY it lives in
    // BASE_QUERY_TAILS below. Map.ofEntries because we exceed Map.of's 10-pair cap.
    // NOTE: The 6 business sources (POLICIES/CLAIMS/FINANCE/REINSURANCE/CUSTOMERS/
    // ENDORSEMENTS) are NOT in this map — they use dynamic per-field projection via
    // buildBusinessSql() + SOURCE_FROM + SOURCE_COLUMNS below.
    private static final Map<DataSource, String> BASE_QUERIES = Map.ofEntries(
        // ── Module 12 — Period-End Closures (CLOSURES category) ───────────────
        // Trial Balance: aggregated SELECT — GROUP BY supplied by BASE_QUERY_TAILS.
        // Only POSTED journal entries are counted; date_from / date_to clip on
        // je.business_date (the IFRS recording-date anchor per Slice 1.4 D1=A).
        Map.entry(DataSource.TRIAL_BALANCE,
            "SELECT coa.code AS account_code, coa.name AS account_name, " +
            "coa.account_type, " +
            "SUM(jel.debit_amount) AS total_debit, " +
            "SUM(jel.credit_amount) AS total_credit, " +
            "SUM(jel.debit_amount) - SUM(jel.credit_amount) AS net_balance " +
            "FROM journal_entry_line jel " +
            "JOIN journal_entry je ON je.id = jel.journal_entry_id " +
            "  AND je.deleted_at IS NULL AND je.status = 'POSTED' " +
            "JOIN chart_of_account coa ON coa.id = jel.account_id " +
            "  AND coa.deleted_at IS NULL " +
            "WHERE jel.deleted_at IS NULL"),

        // General Ledger: per-line JE listing with COA + class_of_business
        // resolution (Slice 1.10 V42 adds jel.class_of_business_id).
        Map.entry(DataSource.GENERAL_LEDGER,
            "SELECT je.id AS journal_entry_id, je.business_date, je.posting_date, " +
            "je.source_module, je.source_event_type, je.source_reference, " +
            "coa.code AS account_code, coa.name AS account_name, " +
            "jel.debit_amount, jel.credit_amount, jel.currency_code, " +
            "cob.name AS class_of_business, " +
            "je.narrative, je.status " +
            "FROM journal_entry_line jel " +
            "JOIN journal_entry je ON je.id = jel.journal_entry_id AND je.deleted_at IS NULL " +
            "JOIN chart_of_account coa ON coa.id = jel.account_id AND coa.deleted_at IS NULL " +
            "LEFT JOIN classes_of_business cob ON cob.id = jel.class_of_business_id " +
            "WHERE jel.deleted_at IS NULL"),

        // Period Lock Audit Trail: every soft/hard/release event since inception.
        // The Type-2 SCD pattern (Slice 1.7) means a closed-then-reopened period
        // produces 2+ rows in this listing; the timeline IS the audit history.
        Map.entry(DataSource.GL_PERIOD_LOCK,
            "SELECT pl.id, fp.start_date AS period_start, fp.end_date AS period_end, " +
            "fp.period_type, pl.lock_type, pl.locked_at, pl.locked_by, " +
            "pl.grace_window_until, pl.released_at, pl.released_by, pl.release_reason " +
            "FROM period_lock pl " +
            "JOIN fiscal_period fp ON fp.id = pl.fiscal_period_id AND fp.deleted_at IS NULL " +
            "WHERE pl.deleted_at IS NULL"),

        // PAA LRC roll-forward (raw table — one row per group×period).
        // For the disclosure-shaped view that combines LRC + LIC, use IFRS17_MOVEMENT.
        Map.entry(DataSource.PAA_LRC,
            "SELECT lrc.id, fp.start_date AS period_start, fp.end_date AS period_end, " +
            "p.code AS portfolio_code, p.name AS portfolio_name, " +
            "g.cohort_year, g.onerousness, g.status AS group_status, " +
            "lrc.opening_balance, lrc.premium_received, lrc.premium_earned, " +
            "lrc.acquisition_costs_deferred, lrc.acquisition_costs_amortised, " +
            "lrc.loss_component, lrc.loss_component_change, lrc.closing_balance, " +
            "lrc.currency_code " +
            "FROM paa_lrc lrc " +
            "JOIN fiscal_period fp ON fp.id = lrc.period_id AND fp.deleted_at IS NULL " +
            "JOIN group_of_contracts g ON g.id = lrc.group_id AND g.deleted_at IS NULL " +
            "JOIN portfolio p ON p.id = g.portfolio_id AND p.deleted_at IS NULL " +
            "WHERE lrc.deleted_at IS NULL"),

        // Contract Groups listing — §22 portfolios × cohort_year × onerousness.
        // contract_nature (V76/V78, FAC / IFRS-17 PAA workstream Task 6) appended
        // last — existing SYSTEM report field lists are shorter than the column
        // count, so applyComputedFields()'s positional mapping ignores it unless
        // a report explicitly declares the "contract_nature" field key.
        Map.entry(DataSource.PAA_GROUPS,
            "SELECT g.id, p.code AS portfolio_code, p.name AS portfolio_name, " +
            "cob.name AS class_of_business, " +
            "g.cohort_year, g.onerousness, g.status AS group_status, g.created_at, " +
            "p.contract_nature " +
            "FROM group_of_contracts g " +
            "JOIN portfolio p ON p.id = g.portfolio_id AND p.deleted_at IS NULL " +
            "LEFT JOIN classes_of_business cob ON cob.id = p.class_of_business_id " +
            "WHERE g.deleted_at IS NULL"),

        // IFRS 17 §103 movement analysis (V38 view — already shaped for disclosure;
        // V78 appends contract_nature). The view filters out (group, period) pairs
        // with no LRC/LIC activity, so empty cohorts don't appear here.
        Map.entry(DataSource.IFRS17_MOVEMENT,
            "SELECT pma.period_id, pma.period_start, pma.period_end, " +
            "pma.portfolio_code, pma.portfolio_name, " +
            "pma.cohort_year, pma.onerousness, pma.group_status, " +
            "pma.lrc_opening, pma.premium_received, pma.premium_earned, " +
            "pma.acquisition_costs_deferred, pma.acquisition_costs_amortised, " +
            "pma.loss_component, pma.loss_component_change, pma.lrc_closing, " +
            "pma.lic_opening, pma.claims_incurred, pma.claims_paid, " +
            "pma.case_reserve_change, pma.ibnr_estimate, pma.ibnr_change, " +
            "pma.risk_adjustment, pma.risk_adjustment_change, pma.discount_unwind, pma.lic_closing, " +
            "pma.total_opening, pma.total_closing, pma.currency_code, " +
            "pma.contract_nature " +
            "FROM paa_movement_analysis pma WHERE 1=1"),

        // IFRS 9 holdings register — current state from investment_holding.
        // Reclassification history lives in investment_classification_history (Type-2 SCD).
        Map.entry(DataSource.IFRS9_HOLDINGS,
            "SELECT h.id, h.isin, h.security_name, h.issuer, h.asset_type, " +
            "h.classification, h.acquisition_date, h.acquisition_cost, " +
            "h.face_value, h.coupon_rate, h.maturity_date, h.currency_code, " +
            "h.status, h.ecl_stage " +
            "FROM investment_holding h WHERE h.deleted_at IS NULL"),

        // IFRS 9 carrying-value roll-forward (raw table — one row per holding×period).
        // For the disclosure-shaped view, use IFRS9_MOVEMENT.
        Map.entry(DataSource.IFRS9_CARRYING,
            "SELECT cv.id, fp.start_date AS period_start, fp.end_date AS period_end, " +
            "h.isin, h.security_name, h.asset_type, h.classification, " +
            "h.status AS holding_status, " +
            "cv.opening_balance, cv.effective_interest_income, cv.coupon_received, " +
            "cv.fair_value_change_pnl, cv.fair_value_change_oci, " +
            "cv.ecl_movement, cv.impairment_loss, cv.disposals, " +
            "cv.closing_balance, cv.closing_fair_value, cv.ecl_stage, cv.currency_code " +
            "FROM investment_carrying_value cv " +
            "JOIN fiscal_period fp ON fp.id = cv.period_id AND fp.deleted_at IS NULL " +
            "JOIN investment_holding h ON h.id = cv.holding_id AND h.deleted_at IS NULL " +
            "WHERE cv.deleted_at IS NULL"),

        // IFRS 9 §B5.5.39 movement analysis (V40 view — already shaped for disclosure).
        Map.entry(DataSource.IFRS9_MOVEMENT,
            "SELECT imv.period_id, imv.period_start, imv.period_end, " +
            "imv.isin, imv.security_name, imv.issuer, imv.asset_type, " +
            "imv.classification, imv.holding_status, imv.currency_code, " +
            "imv.opening_balance, imv.effective_interest_income, imv.coupon_received, " +
            "imv.fair_value_change_pnl, imv.fair_value_change_oci, " +
            "imv.ecl_movement, imv.impairment_loss, imv.disposals, " +
            "imv.closing_balance, imv.closing_fair_value, imv.ecl_stage, " +
            "imv.total_pnl_income, imv.total_oci_movement " +
            "FROM ifrs9_investment_movement_analysis imv WHERE 1=1"),

        // RM commission (B2 Task 4.1): aggregated per-RM accrual over a period.
        // GROUP BY / ORDER BY supplied by BASE_QUERY_TAILS (mirrors TRIAL_BALANCE).
        // total_accrued = Σ(net_premium × commission_rate / 100) — the SAME basis
        // PolicyService.computeCommissionAmount uses at approval (net_premium ×
        // commission_rate / 100), so it reconciles row-for-row with the Cr-2520
        // commission-accrual postings. Only RELATIONSHIP_MANAGER-sourced,
        // non-deleted policies count. date_from / date_to clip on p.approved_at
        // (the accrual-recognition date), injected as ` AND p.approved_at >= ?`
        // BEFORE the GROUP BY tail — identical injection point to TRIAL_BALANCE.
        Map.entry(DataSource.RM_COMMISSION,
            "SELECT rm.name AS relationship_manager_name, " +
            "COUNT(p.id) AS policy_count, " +
            "SUM(p.net_premium) AS total_premium, " +
            "SUM(p.net_premium * p.commission_rate / 100) AS total_accrued " +
            "FROM policies p " +
            "JOIN relationship_managers rm ON rm.id = p.relationship_manager_id " +
            "WHERE p.commission_source_type = 'RELATIONSHIP_MANAGER' AND p.deleted_at IS NULL"),

        // Underwriting performance (cross-entity by-class aggregate): UNION-ALL event
        // stream over policies (gross written premium), claims (reserve = incurred),
        // and APPROVED claim_expenses, summed per class. Feeds loss_ratio /
        // combined_ratio. GROUP BY supplied by BASE_QUERY_TAILS. The string ENDS at
        // WHERE 1=1 so the filter loop appends ` AND <expr>` before the GROUP BY tail.
        Map.entry(DataSource.UNDERWRITING_PERFORMANCE,
            "SELECT ev.cob AS class_of_business, " +
            "SUM(ev.premium_earned) AS premium_earned, " +
            "SUM(ev.claims_incurred) AS claims_incurred, " +
            "SUM(ev.expenses) AS expenses " +
            "FROM ( " +
            "  SELECT class_of_business_name AS cob, class_of_business_id AS cob_id, " +
            "         total_premium AS premium_earned, " +
            "         CAST(0 AS DECIMAL(18,2)) AS claims_incurred, " +
            "         CAST(0 AS DECIMAL(18,2)) AS expenses, " +
            "         created_at AS event_date " +
            "    FROM policies WHERE deleted_at IS NULL " +
            "  UNION ALL " +
            "  SELECT class_of_business_name, class_of_business_id, " +
            "         CAST(0 AS DECIMAL(18,2)), reserve_amount, CAST(0 AS DECIMAL(18,2)), reported_date " +
            "    FROM claims WHERE deleted_at IS NULL " +
            "  UNION ALL " +
            "  SELECT cl.class_of_business_name, cl.class_of_business_id, " +
            "         CAST(0 AS DECIMAL(18,2)), CAST(0 AS DECIMAL(18,2)), ce.amount, ce.created_at " +
            "    FROM claim_expenses ce JOIN claims cl ON cl.id = ce.claim_id " +
            "    WHERE ce.deleted_at IS NULL AND ce.status = 'APPROVED' " +
            ") ev WHERE 1=1")
    );

    // ── Business-source dynamic projection (Option A) ─────────────────────────
    // The 6 business sources build their SELECT dynamically from each report's
    // declared field keys (see buildBusinessSql). SOURCE_FROM holds the
    // FROM/JOIN/WHERE skeleton; SOURCE_COLUMNS maps each declarable field key to a
    // SQL expression. A declared key absent from the map projects NULL (so a report
    // referencing an unbacked field — e.g. customers.channel — runs with an empty
    // column rather than throwing). Most sources are single-table over denormalised
    // columns; only REINSURANCE needs a join (for the treaty label).
    private static final Map<DataSource, String> SOURCE_FROM = Map.of(
        DataSource.POLICIES,     "FROM policies p WHERE p.deleted_at IS NULL",
        DataSource.CLAIMS,       "FROM claims cl WHERE cl.deleted_at IS NULL",
        DataSource.FINANCE,      "FROM debit_notes dn WHERE dn.deleted_at IS NULL",
        DataSource.REINSURANCE,  "FROM ri_allocations ria "
                               + "LEFT JOIN ri_treaties t ON t.id = ria.treaty_id "
                               + "WHERE ria.deleted_at IS NULL",
        DataSource.CUSTOMERS,    "FROM customers c WHERE c.deleted_at IS NULL",
        DataSource.ENDORSEMENTS, "FROM endorsements e WHERE e.deleted_at IS NULL"
    );

    private static final Map<DataSource, Map<String, String>> SOURCE_COLUMNS = Map.of(
        DataSource.POLICIES, Map.ofEntries(
            Map.entry("policy_number",     "p.policy_number"),
            Map.entry("customer_name",     "p.customer_name"),
            Map.entry("class_of_business", "p.class_of_business_name"),
            Map.entry("product_name",      "p.product_name"),
            Map.entry("sum_insured",       "p.total_sum_insured"),
            Map.entry("premium",           "p.total_premium"),
            Map.entry("status",            "p.status"),
            Map.entry("start_date",        "p.policy_start_date"),
            Map.entry("end_date",          "p.policy_end_date"),
            Map.entry("created_at",        "p.created_at")),
        DataSource.CLAIMS, Map.ofEntries(
            Map.entry("claim_number",      "cl.claim_number"),
            Map.entry("policy_number",     "cl.policy_number"),
            Map.entry("customer_name",     "cl.customer_name"),
            Map.entry("class_of_business", "cl.class_of_business_name"),
            Map.entry("status",            "cl.status"),
            Map.entry("reserve_amount",    "cl.reserve_amount"),
            Map.entry("total_paid",        "cl.approved_amount"),
            Map.entry("registered_at",     "cl.reported_date"),
            Map.entry("created_at",        "cl.created_at")),
        DataSource.FINANCE, Map.ofEntries(
            Map.entry("debit_note_number", "dn.debit_note_number"),
            Map.entry("policy_number",     "dn.entity_reference"),
            Map.entry("customer_name",     "dn.customer_name"),
            Map.entry("amount",            "dn.amount"),
            Map.entry("status",            "dn.status"),
            Map.entry("due_date",          "dn.due_date"),
            Map.entry("created_at",        "dn.created_at")),
        DataSource.REINSURANCE, Map.ofEntries(
            Map.entry("policy_number",     "ria.policy_number"),
            Map.entry("treaty_name",       "COALESCE(t.description, ria.treaty_type)"),
            Map.entry("treaty_type",       "ria.treaty_type"),
            Map.entry("retained_amount",   "ria.retained_amount"),
            Map.entry("ceded_amount",      "ria.ceded_amount"),
            Map.entry("status",            "ria.status"),
            Map.entry("created_at",        "ria.created_at")),
        DataSource.CUSTOMERS, Map.ofEntries(
            Map.entry("full_name",
                "COALESCE(c.company_name, "
                + "NULLIF(TRIM(CONCAT_WS(' ', c.first_name, c.other_names, c.last_name)), ''))"),
            Map.entry("customer_type",     "c.customer_type"),
            Map.entry("kyc_status",        "c.kyc_status"),
            Map.entry("created_at",        "c.created_at")),
        DataSource.ENDORSEMENTS, Map.ofEntries(
            Map.entry("endorsement_number",  "e.endorsement_number"),
            Map.entry("policy_number",       "e.policy_number"),
            Map.entry("customer_name",       "e.customer_name"),
            Map.entry("endorsement_type",    "e.endorsement_type"),
            Map.entry("endorsement_premium", "e.premium_adjustment"),
            Map.entry("effective_date",      "e.effective_date"),
            Map.entry("status",              "e.status"),
            Map.entry("created_at",          "e.created_at"))
    );

    // GROUP BY / HAVING suffix per data source. Applied AFTER the filter
    // WHERE clauses and BEFORE the user-supplied ORDER BY. Sources not in
    // this map have no aggregation tail (the common case).
    private static final Map<DataSource, String> BASE_QUERY_TAILS = Map.of(
        DataSource.TRIAL_BALANCE,
            "GROUP BY coa.code, coa.name, coa.account_type",
        DataSource.RM_COMMISSION,
            "GROUP BY rm.name",
        DataSource.UNDERWRITING_PERFORMANCE,
            "GROUP BY ev.cob"
    );

    public List<Map<String, Object>> execute(ReportDefinition definition,
                                              Map<String, String> filterValues) {
        return execute(definition, filterValues, DEFAULT_MAX_ROWS);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> execute(ReportDefinition definition,
                                              Map<String, String> filterValues,
                                              int maxRows) {
        ReportConfig config = definition.getConfig();
        DataSource ds = definition.getDataSource();
        StringBuilder sql = new StringBuilder(isBusinessSource(ds)
                ? buildBusinessSql(ds, config)
                : BASE_QUERIES.get(ds));
        List<Object> params = new ArrayList<>();
        int paramIdx = 1;

        // Apply filters
        if (config.getFilters() != null && filterValues != null) {
            for (ReportFilter filter : config.getFilters()) {
                String value = filterValues.get(filter.getKey());
                if (value == null || value.isBlank()) continue;

                switch (filter.getKey()) {
                    case "date_from" -> {
                        sql.append(" AND ").append(createdAtCol(ds))
                           .append(" >= ?").append(paramIdx++);
                        params.add(LocalDate.parse(value).atStartOfDay());
                    }
                    case "date_to" -> {
                        sql.append(" AND ").append(createdAtCol(ds))
                           .append(" < ?").append(paramIdx++);
                        params.add(LocalDate.parse(value).plusDays(1).atStartOfDay());
                    }
                    case "class_of_business_id" -> {
                        String cobCol = cobFilterCol(ds);
                        if (cobCol != null) {
                            sql.append(" AND ").append(cobCol).append(" = ?").append(paramIdx++);
                            params.add(UUID.fromString(value));
                        }
                    }
                    case "product_id" -> {
                        if (ds == DataSource.POLICIES) {
                            sql.append(" AND p.product_id = ?").append(paramIdx++);
                            params.add(UUID.fromString(value));
                        }
                    }
                    case "status" -> {
                        String col = statusCol(ds);
                        if (col != null) {
                            sql.append(" AND ").append(col)
                               .append(" = ?").append(paramIdx++);
                            params.add(value);
                        }
                    }
                    // ── Closures filters (Module 12) ─────────────────────────
                    case "account_code" -> {
                        // Sources that JOIN chart_of_account expose coa.code
                        if (ds == DataSource.GENERAL_LEDGER || ds == DataSource.TRIAL_BALANCE) {
                            sql.append(" AND coa.code = ?").append(paramIdx++);
                            params.add(value);
                        }
                    }
                    case "source_module" -> {
                        if (ds == DataSource.GENERAL_LEDGER) {
                            sql.append(" AND je.source_module = ?").append(paramIdx++);
                            params.add(value);
                        }
                    }
                    case "contract_nature" -> {
                        // DIRECT / FAC_INWARD / FAC_OUTWARD — FAC / IFRS-17 PAA
                        // workstream Task 6. Mirrors the statusCol/cobFilterCol
                        // dispatch pattern above.
                        String col = contractNatureCol(ds);
                        if (col != null) {
                            sql.append(" AND ").append(col)
                               .append(" = ?").append(paramIdx++);
                            params.add(value);
                        }
                    }
                    case "classification" -> {
                        // IFRS 9 reports — AC / FVOCI_DEBT / FVOCI_EQUITY / FVPL
                        String col = switch (ds) {
                            case IFRS9_HOLDINGS, IFRS9_CARRYING -> "h.classification";
                            case IFRS9_MOVEMENT                 -> "imv.classification";
                            default                             -> null;
                        };
                        if (col != null) {
                            sql.append(" AND ").append(col)
                               .append(" = ?").append(paramIdx++);
                            params.add(value);
                        }
                    }
                    default -> log.debug("Unhandled filter key: {}", filter.getKey());
                }
            }
        }

        // Apply aggregation tail (GROUP BY / HAVING) before ORDER BY.
        // Business sources (not in BASE_QUERIES) get a dynamic GROUP BY when their
        // report declares a groupBy; everything else keeps its BASE_QUERY_TAILS entry.
        String tail = isBusinessSource(ds)
                ? buildBusinessGroupBy(ds, config)
                : BASE_QUERY_TAILS.get(ds);
        if (tail != null && !tail.isBlank()) {
            sql.append(' ').append(tail);
        }

        // Apply sort — skip if sortBy is a computed field (the alias only exists in the
        // Java result map, never in SQL; ordering by it would cause a DB error).
        if (config.getSortBy() != null && !config.getSortBy().isBlank()
                && !isComputedField(config, config.getSortBy())) {
            String dir = "ASC".equalsIgnoreCase(config.getSortDir()) ? "ASC" : "DESC";
            sql.append(" ORDER BY ").append(sanitizeColumnName(config.getSortBy()))
               .append(" ").append(dir);
        }

        Query query = isBusinessSource(ds)
                ? entityManager.createNativeQuery(sql.toString())
                : entityManager.createNativeQuery(sql.toString(), Tuple.class);
        for (int i = 0; i < params.size(); i++) {
            query.setParameter(i + 1, params.get(i));
        }
        query.setMaxResults(maxRows);

        if (isBusinessSource(ds)) {
            // Business SELECT is emitted in declared-field order (buildBusinessSql) — positional is correct.
            List<Object[]> rawRows = query.getResultList();
            return applyComputedFields(rawRows, config);
        }
        // Fixed source: the hand-written BASE_QUERIES SELECT is NOT in declared-field order and
        // leads with undeclared identity/date columns, so map each declared field key to its
        // column by alias (case-insensitive), then feed the unchanged positional applyComputedFields.
        List<Tuple> tuples = query.getResultList();
        return applyComputedFields(reprojectByAlias(tuples, config), config);
    }

    /**
     * Reproject fixed-source Tuple rows into declared-non-computed-field order by matching
     * each field key to the SELECT column of the same (lowercased) alias — NULL if the source
     * SELECT has no such alias (a mismatch the alias guard IT forbids). This makes the
     * downstream positional applyComputedFields correct for fixed sources exactly as
     * buildBusinessSql's declared-order SELECT makes it correct for business sources.
     */
    private List<Object[]> reprojectByAlias(List<Tuple> tuples, ReportConfig config) {
        List<String> keys = config.getFields() == null ? List.of()
                : config.getFields().stream()
                        .filter(f -> !f.isComputed())
                        .map(f -> f.getKey().toLowerCase(Locale.ROOT))
                        .toList();
        return tuples.stream().map(t -> {
            Map<String, Object> byLabel = new HashMap<>();
            for (TupleElement<?> el : t.getElements()) {
                if (el.getAlias() != null) {
                    byLabel.put(el.getAlias().toLowerCase(Locale.ROOT), t.get(el));
                }
            }
            Object[] row = new Object[keys.size()];
            for (int i = 0; i < keys.size(); i++) {
                row[i] = byLabel.get(keys.get(i));
            }
            return row;
        }).toList();
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

    /** Maps each datasource to its primary table's date column for date_from / date_to filters. */
    private String createdAtCol(DataSource ds) {
        return switch (ds) {
            case POLICIES         -> "p.created_at";
            case CLAIMS           -> "cl.created_at";
            case FINANCE          -> "dn.created_at";
            case REINSURANCE      -> "ria.created_at";
            case CUSTOMERS        -> "c.created_at";
            case ENDORSEMENTS     -> "e.created_at";
            // Closures: business_date / period_start are the natural anchors —
            // not created_at (which is the row's insertion timestamp).
            case TRIAL_BALANCE    -> "je.business_date";
            case GENERAL_LEDGER   -> "je.business_date";
            case GL_PERIOD_LOCK   -> "pl.locked_at";
            case PAA_LRC          -> "fp.start_date";
            case PAA_GROUPS       -> "g.created_at";
            case IFRS17_MOVEMENT  -> "pma.period_start";
            case IFRS9_HOLDINGS   -> "h.acquisition_date";
            case IFRS9_CARRYING   -> "fp.start_date";
            case IFRS9_MOVEMENT   -> "imv.period_start";
            // RM commission: accrual-recognition date = policy approval date.
            // Injected ` AND p.approved_at >= ?` lands BEFORE the GROUP BY tail.
            case RM_COMMISSION    -> "p.approved_at";
            // Underwriting performance: each unioned fact carries its own booking date
            // as ev.event_date (policy created_at / claim reported_date / expense created_at).
            case UNDERWRITING_PERFORMANCE -> "ev.event_date";
        };
    }

    /**
     * Maps each datasource to its status column, or null if the source has no
     * single status column meaningful as a filter (the filter is then skipped).
     */
    private String statusCol(DataSource ds) {
        return switch (ds) {
            case POLICIES         -> "p.status";
            case CLAIMS           -> "cl.status";
            case FINANCE          -> "dn.status";
            case REINSURANCE      -> "ria.status";
            case CUSTOMERS        -> "c.kyc_status";
            case ENDORSEMENTS     -> "e.status";
            // Closures status mappings — null means the source has no useful
            // single status column for a generic filter.
            case TRIAL_BALANCE    -> null;
            case GENERAL_LEDGER   -> "je.status";
            case GL_PERIOD_LOCK   -> "pl.lock_type";
            case PAA_LRC          -> "g.status";
            case PAA_GROUPS       -> "g.status";
            case IFRS17_MOVEMENT  -> "pma.group_status";
            case IFRS9_HOLDINGS   -> "h.status";
            case IFRS9_CARRYING   -> "h.status";
            case IFRS9_MOVEMENT   -> "imv.holding_status";
            // RM commission is grouped by rm.name — no single per-row status
            // column is meaningful as a filter (mirrors TRIAL_BALANCE → null).
            case RM_COMMISSION    -> null;
            // Aggregate over a UNION stream — no single per-row status filter.
            case UNDERWRITING_PERFORMANCE -> null;
        };
    }

    /** Returns true if the given key matches a computed field in the config. */
    private boolean isComputedField(ReportConfig config, String key) {
        if (config.getFields() == null || key == null) return false;
        return config.getFields().stream()
                .anyMatch(f -> f.isComputed() && key.equals(f.getKey()));
    }

    /** Column the class_of_business_id filter targets, or null if the source has none. */
    private String cobFilterCol(DataSource ds) {
        return switch (ds) {
            case POLICIES     -> "p.class_of_business_id";
            case CLAIMS       -> "cl.class_of_business_id";
            case ENDORSEMENTS -> "e.class_of_business_id";
            case GENERAL_LEDGER -> "cob.id";   // joins classes_of_business
            case PAA_GROUPS     -> "cob.id";   // joins classes_of_business
            case UNDERWRITING_PERFORMANCE -> "ev.cob_id"; // narrows event stream pre-aggregation
            default           -> null;
        };
    }

    /**
     * A business source uses dynamic per-field projection (SOURCE_FROM / SOURCE_COLUMNS);
     * every other source uses a fixed BASE_QUERIES entry. The two maps are exact
     * complements over the DataSource enum — this single predicate is the one place that
     * fact is expressed, so the SELECT branch (buildBusinessSql vs BASE_QUERIES) and the
     * tail branch (buildBusinessGroupBy vs BASE_QUERY_TAILS) can never disagree.
     */
    public boolean isBusinessSource(DataSource ds) {
        return SOURCE_COLUMNS.containsKey(ds);
    }

    /**
     * Lowercased column labels of a fixed source's SELECT (LIMIT 0, no rows). Empty for
     * business sources. Backs {@code FixedSourceReportAliasGuardIT} — the structural check
     * that every fixed-source SYSTEM report's declared non-computed field key resolves to a
     * SELECT alias, closing the hole that let {@code reprojectByAlias} misalignment (a
     * mismatch this same method would have caught) rot undetected since V44.
     *
     * <p>Appends the source's {@link #BASE_QUERY_TAILS} entry (GROUP BY) when present —
     * required for the probe to be valid SQL for the 3 aggregate sources (TRIAL_BALANCE,
     * RM_COMMISSION, UNDERWRITING_PERFORMANCE), whose SELECT mixes aggregates with
     * non-aggregated columns and is only legal with its GROUP BY tail. A GROUP BY clause
     * never changes the SELECT's column labels, so this doesn't affect what the guard checks.
     */
    public List<String> fixedSourceColumnLabels(DataSource ds) {
        if (isBusinessSource(ds)) return List.of();
        String base = BASE_QUERIES.get(ds);
        String tail = BASE_QUERY_TAILS.get(ds);
        String full = (tail == null || tail.isBlank()) ? base : base + " " + tail;
        String probe = "SELECT * FROM (" + full + ") _cols LIMIT 0";
        return entityManager.unwrap(org.hibernate.Session.class).doReturningWork(conn -> {
            try (java.sql.PreparedStatement ps = conn.prepareStatement(probe);
                 java.sql.ResultSet rs = ps.executeQuery()) {
                java.sql.ResultSetMetaData md = rs.getMetaData();
                List<String> labels = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    labels.add(md.getColumnLabel(i).toLowerCase(Locale.ROOT));
                }
                return labels;
            }
        });
    }

    /** Aggregate mode: a business source whose report declares a non-blank groupBy. */
    private boolean isAggregateMode(DataSource ds, ReportConfig config) {
        return isBusinessSource(ds)
            && config.getGroupBy() != null && !config.getGroupBy().isBlank();
    }

    /**
     * A measure is a non-computed numeric field — SUM-ed in aggregate mode. PERCENT is
     * intentionally NOT a measure (you don't SUM a ratio): a raw PERCENT field would be
     * treated as a dimension and land in the GROUP BY. The current groupBy reports never
     * hit this — their PERCENT fields (loss_ratio / combined_ratio) are all computed and
     * skipped before classification.
     */
    private boolean isMeasure(ReportField f) {
        String t = f.getType();
        return "MONEY".equals(t) || "NUMBER".equals(t) || "INTEGER".equals(t);
    }

    /**
     * GROUP BY clause for an aggregate-mode business source: the SQL expressions of the
     * report's non-computed dimension (non-measure) fields, in declared order. Returns
     * "" when not in aggregate mode (the 49 no-groupBy business reports stay per-row).
     * Group by the SQL expression (not the alias) for portability; mirrors the
     * dimension/measure split buildBusinessSql uses, so SELECT and GROUP BY agree.
     */
    private String buildBusinessGroupBy(DataSource ds, ReportConfig config) {
        if (!isAggregateMode(ds, config)) return "";
        Map<String, String> columns = SOURCE_COLUMNS.get(ds);
        List<String> dims = new ArrayList<>();
        if (config.getFields() != null) {
            for (ReportField f : config.getFields()) {
                if (f.isComputed() || isMeasure(f)) continue;
                dims.add(columns.getOrDefault(f.getKey(), "NULL"));
            }
        }
        return dims.isEmpty() ? "" : "GROUP BY " + String.join(", ", dims);
    }

    /**
     * Builds the SELECT + FROM/JOIN/WHERE prefix for a business source by projecting
     * the report's declared non-computed field keys, in order, through SOURCE_COLUMNS.
     * The filter loop in execute() appends ` AND <expr>` after this; GROUP BY is added
     * dynamically by buildBusinessGroupBy when the report declares a groupBy.
     * Emitting columns in declared-field order is what makes the positional
     * applyComputedFields() correct.
     *
     * A field key with no SOURCE_COLUMNS entry projects {@code NULL AS <key>} so a
     * report referencing an unbacked field (e.g. customers.channel) still runs.
     */
    private String buildBusinessSql(DataSource ds, ReportConfig config) {
        Map<String, String> columns = SOURCE_COLUMNS.get(ds);
        boolean aggregate = isAggregateMode(ds, config);
        List<String> selects = new ArrayList<>();
        Set<String> projectedKeys = new HashSet<>();
        if (config.getFields() != null) {
            for (ReportField f : config.getFields()) {
                if (f.isComputed()) continue;
                String expr = columns.getOrDefault(f.getKey(), "NULL");
                // Sanitize the alias before interpolating into SQL: a CUSTOM report's
                // field key is persisted unvalidated by ReportDefinitionService, so an
                // unsanitized `AS <key>` would be a SQL-injection vector for privileged
                // report authors. The alias is cosmetic — applyComputedFields keys the
                // result map by the raw config key positionally, not by this alias.
                String alias = sanitizeColumnName(f.getKey());
                // In aggregate mode, numeric measures are SUM-ed and the remaining
                // (dimension) fields form the GROUP BY (see buildBusinessGroupBy).
                // Declared-field order is preserved either way, so the positional
                // applyComputedFields stays correct by construction.
                if (aggregate && isMeasure(f)) {
                    selects.add("SUM(" + expr + ") AS " + alias);
                } else {
                    selects.add(expr + " AS " + alias);
                }
                projectedKeys.add(alias);
            }
        }
        // Sort-column injection: project a backed-but-undeclared sortBy column so the
        // ORDER BY alias resolves in PostgreSQL. Suppressed in aggregate mode — a bare
        // injected column would be illegal under GROUP BY (and no groupBy report needs
        // it: all 6 sort by a SUM-ed measure alias or a computed field that ORDER BY skips).
        if (!aggregate) {
            String sortBy = config.getSortBy();
            if (sortBy != null && !sortBy.isBlank()) {
                String sortKey = sanitizeColumnName(sortBy);
                if (!projectedKeys.contains(sortKey) && columns.containsKey(sortKey)) {
                    selects.add(columns.get(sortKey) + " AS " + sortKey);
                }
            }
        }
        if (selects.isEmpty()) selects.add("1");  // degenerate guard: report with no raw fields
        return "SELECT " + String.join(", ", selects) + " " + SOURCE_FROM.get(ds);
    }

    /**
     * Column the contract_nature filter targets, or null if the source has none.
     * FAC / IFRS-17 PAA workstream Task 6 — DIRECT / FAC_INWARD / FAC_OUTWARD,
     * available on the two sources that surface {@code portfolio.contract_nature}
     * (directly, or via the V78-recreated {@code paa_movement_analysis} view).
     */
    private String contractNatureCol(DataSource ds) {
        return switch (ds) {
            case PAA_GROUPS      -> "p.contract_nature";
            case IFRS17_MOVEMENT -> "pma.contract_nature";
            default              -> null;
        };
    }

    /** Whitelist-based column name sanitizer — prevents SQL injection in ORDER BY. */
    private String sanitizeColumnName(String raw) {
        if (raw == null) return "created_at";
        // Allow only alphanumeric + underscore + dot (for table.column)
        return raw.replaceAll("[^a-zA-Z0-9_.]", "").toLowerCase();
    }
}
