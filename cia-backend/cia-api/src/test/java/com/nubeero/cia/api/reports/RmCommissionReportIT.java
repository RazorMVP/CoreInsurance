package com.nubeero.cia.api.reports;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.reports.controller.dto.ReportResultDto;
import com.nubeero.cia.reports.controller.dto.ReportRunRequest;
import com.nubeero.cia.reports.service.ReportRunnerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the V64-seeded {@code RM Commission Accrual}
 * SYSTEM report (DataSource.RM_COMMISSION) against a real PostgreSQL container.
 *
 * <p>This is the empirical validation of the whole report path: it confirms the
 * {@code RM_COMMISSION} base query's {@code FROM policies} table reference
 * resolves, the {@code GROUP BY rm.name} tail + {@code ORDER BY total_accrued
 * DESC} sort + {@code p.approved_at} date filter all execute, and the accrual
 * reconciles row-for-row with the {@code net_premium × commission_rate / 100}
 * basis that {@code PolicyService.computeCommissionAmount} uses at approval.
 *
 * <p>Entry point: {@link ReportRunnerService#run(ReportRunRequest)} →
 * {@link ReportResultDto}; rows are {@code List<Map<String,Object>>} keyed by the
 * V64 config field keys (relationship_manager_name / policy_count / total_premium
 * / total_accrued).
 *
 * <p>Extends {@link FinanceWebItSupport} (full {@code @SpringBootTest} context,
 * singleton Postgres at Flyway target 64 — sees the V64 seed + V62 RM columns).
 *
 * <p>Seeds: 2 RMs. RM Alpha owns two RM-sourced policies (100000 @2%, 200000 @2%);
 * RM Beta owns one (50000 @3%); plus one BROKER-sourced policy (999999 @5%) that
 * the {@code commission_source_type = 'RELATIONSHIP_MANAGER'} predicate must
 * exclude. All policies approved within the report's [date_from, date_to] window.
 *
 * @since B2 Task 4.3 — per-RM report integration test
 */
class RmCommissionReportIT extends FinanceWebItSupport {

    /** Fixed approval date inside the report window (2026-01-01 .. 2026-12-31). */
    private static final LocalDate APPROVED_ON = LocalDate.of(2026, 3, 15);

    @Autowired
    ReportRunnerService reportRunnerService;

    @Autowired
    JdbcTemplate jdbc;

    /** Seed a relationship_managers row (only name is NOT NULL w/o default), return its id. */
    private UUID seedRm(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO relationship_managers (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    /**
     * Insert a policies row satisfying every NOT NULL column, with the
     * commission-source columns + net_premium + approved_at set per the report's
     * aggregation needs. Mirrors PolicyRmConstraintIT.insertPolicy (Task 1.3),
     * extended with net_premium + approved_at.
     */
    private void insertPolicy(UUID brokerId, UUID rmId, String commissionSourceType,
                              String netPremium, String rate, LocalDate approvedOn) {
        jdbc.update(
            "INSERT INTO policies ("
                + "customer_id, customer_name, "
                + "product_id, product_name, product_code, product_rate, "
                + "class_of_business_id, class_of_business_name, class_of_business_code, "
                + "policy_start_date, policy_end_date, "
                + "net_premium, approved_at, "
                + "broker_id, relationship_manager_id, "
                + "commission_source_type, commission_rate"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), "Acme Ltd",
            UUID.randomUUID(), "Motor Comprehensive", "MOTOR", new BigDecimal("5.0000"),
            UUID.randomUUID(), "Motor", "MOT",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            new BigDecimal(netPremium), approvedOn.atStartOfDay(),
            brokerId, rmId,
            commissionSourceType, new BigDecimal(rate));
    }

    private void seedRmPolicy(UUID rmId, String netPremium, String rate, LocalDate approvedOn) {
        insertPolicy(null, rmId, "RELATIONSHIP_MANAGER", netPremium, rate, approvedOn);
    }

    private void seedBrokerPolicy(String netPremium, String rate, LocalDate approvedOn) {
        insertPolicy(UUID.randomUUID(), null, "BROKER", netPremium, rate, approvedOn);
    }

    private UUID rmCommissionReportId() {
        return jdbc.queryForObject(
            "SELECT id FROM report_definition WHERE name = 'RM Commission Accrual'",
            UUID.class);
    }

    private Map<String, Object> rowFor(List<Map<String, Object>> rows, String rmName) {
        return rows.stream()
            .filter(r -> rmName.equals(r.get("relationship_manager_name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No report row for RM: " + rmName));
    }

    private BigDecimal money(Object val) {
        if (val instanceof BigDecimal bd) return bd;
        return new BigDecimal(String.valueOf(val));
    }

    private long count(Object val) {
        return ((Number) val).longValue();
    }

    @Test
    void aggregatesAccruedCommissionByRm() {
        UUID rmA = seedRm("RM Alpha");
        UUID rmB = seedRm("RM Beta");
        seedRmPolicy(rmA, "100000.00", "2.0000", APPROVED_ON);
        seedRmPolicy(rmA, "200000.00", "2.0000", APPROVED_ON);
        seedRmPolicy(rmB, "50000.00",  "3.0000", APPROVED_ON);
        seedBrokerPolicy("999999.00", "5.0000", APPROVED_ON);   // excluded (source = BROKER)

        ReportRunRequest request = new ReportRunRequest();
        request.setReportId(rmCommissionReportId());
        request.setFilters(Map.of("date_from", "2026-01-01", "date_to", "2026-12-31"));

        ReportResultDto result = reportRunnerService.run(request);
        List<Map<String, Object>> rows = result.getRows();

        // Exactly two RM rows (broker policy excluded → no third group).
        assertThat(rows).hasSize(2);

        // RM Alpha: 2 policies, premium 300000, accrued = 100000*2% + 200000*2% = 6000.00
        Map<String, Object> alpha = rowFor(rows, "RM Alpha");
        assertThat(count(alpha.get("policy_count"))).isEqualTo(2L);
        assertThat(money(alpha.get("total_premium"))).isEqualByComparingTo("300000.00");
        assertThat(money(alpha.get("total_accrued"))).isEqualByComparingTo("6000.00");

        // RM Beta: 1 policy, premium 50000, accrued = 50000*3% = 1500.00
        Map<String, Object> beta = rowFor(rows, "RM Beta");
        assertThat(count(beta.get("policy_count"))).isEqualTo(1L);
        assertThat(money(beta.get("total_premium"))).isEqualByComparingTo("50000.00");
        assertThat(money(beta.get("total_accrued"))).isEqualByComparingTo("1500.00");

        // Sorted by total_accrued DESC → Alpha (6000) before Beta (1500).
        assertThat(rows.get(0).get("relationship_manager_name")).isEqualTo("RM Alpha");
        assertThat(rows.get(1).get("relationship_manager_name")).isEqualTo("RM Beta");

        // Broker-sourced policy (premium 999999) excluded from every row.
        assertThat(rows).noneMatch(r ->
            money(r.get("total_premium")).compareTo(new BigDecimal("999999.00")) == 0);
    }
}
