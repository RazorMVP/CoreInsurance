package com.nubeero.cia.api.reports;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.reports.controller.dto.ReportRunRequest;
import com.nubeero.cia.reports.service.ReportRunnerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixed-source (BASE_QUERIES) column mapping value test. Complements
 * {@link BusinessReportValueIT} (business sources) and {@link SystemReportSmokeIT}
 * (every SYSTEM report executes without throwing). Fixed-source SELECTs lead with
 * undeclared identity/date columns not present in the declared field list, so the
 * old positional {@code applyComputedFields} mapping silently garbled every fixed
 * source's columns. Proven here against GENERAL_LEDGER's "General Journal Listing".
 *
 * <p>{@code @Transactional} so the JDBC-seeded rows roll back per method — mirrors
 * {@link BusinessReportValueIT}.
 */
@Transactional
class FixedSourceReportValueIT extends FinanceWebItSupport {

    @Autowired ReportRunnerService reportRunnerService;
    @Autowired JdbcTemplate jdbc;

    private UUID reportId(String name) {
        return jdbc.queryForObject(
            "SELECT id FROM report_definition WHERE name = ? AND type = 'SYSTEM'", UUID.class, name);
    }

    private List<Map<String, Object>> run(String reportName, Map<String, String> filters) {
        ReportRunRequest req = new ReportRunRequest();
        req.setReportId(reportId(reportName));
        req.setFilters(filters);
        return reportRunnerService.run(req).getRows();
    }

    private static final Map<String, String> WIDE = Map.of("date_from", "2000-01-01", "date_to", "2100-01-01");

    @Test
    void generalJournalListing_businessDateColumnHoldsDate_notUuid() {
        // COA is V32-seeded in the test tenant; look up a real postable account id by code.
        UUID accountId = jdbc.queryForObject(
            "SELECT id FROM chart_of_account WHERE code = '1330'", UUID.class);

        // journal_entry.period_id is a NOT NULL FK -> fiscal_period; seed a minimal
        // fiscal_year + fiscal_period to satisfy it (schema has no default seed).
        UUID fiscalYearId = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();
        jdbc.update("INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, DATE '2026-01-01', DATE '2026-12-31', 'ACTIVE', 'test')",
            fiscalYearId, "FY-FSR-2026-" + fiscalYearId);
        jdbc.update("INSERT INTO fiscal_period " +
            "(id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, 'MONTH', DATE '2026-03-01', DATE '2026-03-31', 'OPEN', 'test')",
            periodId, fiscalYearId);

        UUID jeId = UUID.randomUUID();
        jdbc.update("INSERT INTO journal_entry " +
            "(id, posting_date, business_date, period_id, source_module, source_event_type, " +
            "source_reference, narrative, posted_by, status) " +
            "VALUES (?, DATE '2026-03-15', DATE '2026-03-15', ?, 'policy', 'POLICY_APPROVED', 'POL-1', 'test', 'test', 'POSTED')",
            jeId, periodId);
        jdbc.update("INSERT INTO journal_entry_line " +
            "(id, journal_entry_id, line_no, account_id, debit_amount, credit_amount) " +
            "VALUES (?, ?, 1, ?, 1000.00, 0.00)",
            UUID.randomUUID(), jeId, accountId);

        List<Map<String, Object>> rows = run("General Journal Listing", WIDE);

        assertThat(rows).isNotEmpty();
        Object businessDate = rows.get(0).get("business_date");
        // Correct: a date/timestamp. Bug (positional): the JE UUID string/UUID.
        assertThat(businessDate).isNotInstanceOf(UUID.class);
        assertThat(String.valueOf(businessDate)).startsWith("2026-03-15");
        // account_code must be the code, not a downstream-shifted value.
        assertThat(String.valueOf(rows.get(0).get("account_code"))).isEqualTo("1330");
    }
}
