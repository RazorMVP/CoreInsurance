package com.nubeero.cia.api.reports;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.reports.controller.dto.ReportResultDto;
import com.nubeero.cia.reports.controller.dto.ReportRunRequest;
import com.nubeero.cia.reports.service.ReportRunnerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke IT: every SYSTEM report definition must execute against a real PostgreSQL
 * schema without a SQL exception. This is the regression guard that the
 * reports-base-query-table-drift bug class lacked — cia-reports had no test dir,
 * so no SYSTEM report's base query had ever run against a real DB.
 *
 * <p>Runs with NO seeded business data: the assertion is that every base query is
 * structurally valid (tables + columns resolve), so empty result lists are expected
 * and correct. Value correctness is covered by {@link BusinessReportValueIT}.
 *
 * @since reports-base-query-table-drift fix (Option A)
 */
class SystemReportSmokeIT extends FinanceWebItSupport {

    @Autowired
    ReportRunnerService reportRunnerService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void everySystemReportExecutes() {
        List<Map<String, Object>> defs = jdbc.queryForList(
            "SELECT id, name, data_source FROM report_definition WHERE type = 'SYSTEM' ORDER BY name");
        assertThat(defs).as("V18 + V44 seed 59 SYSTEM reports").hasSizeGreaterThanOrEqualTo(59);

        for (Map<String, Object> def : defs) {
            UUID id = (UUID) def.get("id");
            String name = (String) def.get("name");

            ReportRunRequest request = new ReportRunRequest();
            request.setReportId(id);
            // Required filters across V18/V44 SYSTEM configs are date_from/date_to only.
            // A wide window admits any seeded/empty data; other filters default to absent.
            request.setFilters(Map.of("date_from", "2000-01-01", "date_to", "2100-01-01"));

            try {
                ReportResultDto result = reportRunnerService.run(request);
                assertThat(result.getRows())
                    .as("report '%s' (%s) returned a non-null row list", name, def.get("data_source"))
                    .isNotNull();
            } catch (Exception e) {
                throw new AssertionError(
                    "SYSTEM report '" + name + "' (data_source=" + def.get("data_source")
                        + ") failed to execute: " + e.getMessage(), e);
            }
        }
    }
}
