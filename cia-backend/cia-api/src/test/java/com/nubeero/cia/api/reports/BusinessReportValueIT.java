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
 * Per-business-source value assertions: seed minimal rows, run a representative
 * SYSTEM report, and assert the projected columns carry the right values — proving
 * the SOURCE_COLUMNS fieldKey->expr mapping is correct, not merely non-crashing.
 * Complements {@link SystemReportSmokeIT} (which proves all 68 execute).
 *
 * @since reports-base-query-table-drift fix (Option A)
 */
class BusinessReportValueIT extends FinanceWebItSupport {

    private static final Map<String, String> WIDE =
        Map.of("date_from", "2000-01-01", "date_to", "2100-01-01");

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
        ReportResultDto result = reportRunnerService.run(req);
        return result.getRows();
    }

    /**
     * Inserts a minimal policies row satisfying every NOT NULL constraint
     * and returns the generated id. Used as a FK target by claims + ri_allocations tests.
     */
    private UUID insertMinimalPolicy(String policyNumber) {
        UUID policyId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO policies (id, customer_id, customer_name, "
                + "product_id, product_name, product_code, product_rate, "
                + "class_of_business_id, class_of_business_name, class_of_business_code, "
                + "policy_start_date, policy_end_date, policy_number) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            policyId,
            UUID.randomUUID(), "Fixture Corp",
            UUID.randomUUID(), "Fire Special", "FIRE", new BigDecimal("2.5000"),
            UUID.randomUUID(), "Fire", "FIR",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            policyNumber);
        return policyId;
    }

    @Test
    void policyRegisterMapsDenormalisedColumns() {
        jdbc.update(
            "INSERT INTO policies (customer_id, customer_name, "
                + "product_id, product_name, product_code, product_rate, "
                + "class_of_business_id, class_of_business_name, class_of_business_code, "
                + "policy_start_date, policy_end_date, policy_number, "
                + "total_sum_insured, total_premium, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), "Globex Ltd",
            UUID.randomUUID(), "Fire Special", "FIRE", new BigDecimal("2.5000"),
            UUID.randomUUID(), "Fire", "FIR",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "POL-VAL-001",
            new BigDecimal("5000000.00"), new BigDecimal("125000.00"), "ACTIVE");

        Map<String, Object> row = run("Policy Register", WIDE).stream()
            .filter(r -> "POL-VAL-001".equals(r.get("policy_number"))).findFirst().orElseThrow();
        assertThat(row.get("customer_name")).isEqualTo("Globex Ltd");
        assertThat(row.get("class_of_business")).isEqualTo("Fire");
        assertThat(row.get("product_name")).isEqualTo("Fire Special");
        assertThat(new BigDecimal(row.get("sum_insured").toString())).isEqualByComparingTo("5000000.00");
        assertThat(new BigDecimal(row.get("premium").toString())).isEqualByComparingTo("125000.00");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void claimsRegisterMapsReserveAndProxyPaid() {
        // claims.policy_id is a NOT NULL FK → policies(id); insert a fixture policy first.
        UUID policyId = insertMinimalPolicy("POL-CLM-FIX-001");

        jdbc.update(
            "INSERT INTO claims (claim_number, policy_id, policy_number, "
                + "policy_start_date, policy_end_date, "
                + "customer_id, customer_name, "
                + "product_id, product_name, "
                + "class_of_business_id, class_of_business_name, "
                + "status, reserve_amount, approved_amount, "
                + "reported_date, incident_date, description) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "CLM-VAL-001", policyId, "POL-VAL-001",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            UUID.randomUUID(), "Globex Ltd",
            UUID.randomUUID(), "Fire Special",
            UUID.randomUUID(), "Fire",
            "APPROVED", new BigDecimal("300000.00"),
            new BigDecimal("250000.00"),
            LocalDate.of(2026, 3, 1), LocalDate.of(2026, 2, 20),
            "Fire loss at warehouse");

        Map<String, Object> row = run("Claims Register", WIDE).stream()
            .filter(r -> "CLM-VAL-001".equals(r.get("claim_number"))).findFirst().orElseThrow();
        assertThat(row.get("customer_name")).isEqualTo("Globex Ltd");
        assertThat(row.get("class_of_business")).isEqualTo("Fire");
        assertThat(new BigDecimal(row.get("reserve_amount").toString())).isEqualByComparingTo("300000.00");
        assertThat(new BigDecimal(row.get("total_paid").toString())).isEqualByComparingTo("250000.00");
    }

    @Test
    void debitNoteAnalysisMapsEntityReference() {
        jdbc.update(
            "INSERT INTO debit_notes (debit_note_number, entity_type, entity_id, entity_reference, "
                + "customer_id, customer_name, description, amount, total_amount, status, due_date) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "DN-VAL-001", "POLICY", UUID.randomUUID(), "POL-VAL-001",
            UUID.randomUUID(), "Globex Ltd",
            "Premium debit note for POL-VAL-001",
            new BigDecimal("125000.00"),
            new BigDecimal("125000.00"), "PENDING", LocalDate.of(2026, 2, 1));

        Map<String, Object> row = run("Debit Note Analysis", WIDE).stream()
            .filter(r -> "DN-VAL-001".equals(r.get("debit_note_number"))).findFirst().orElseThrow();
        assertThat(row.get("policy_number")).isEqualTo("POL-VAL-001");
        assertThat(row.get("customer_name")).isEqualTo("Globex Ltd");
        assertThat(new BigDecimal(row.get("amount").toString())).isEqualByComparingTo("125000.00");
    }

    @Test
    void riPremiumBordereauxMapsTreatyLabel() {
        // ri_allocations.policy_id is a NOT NULL FK → policies(id); insert a fixture policy first.
        UUID policyId = insertMinimalPolicy("POL-RI-FIX-001");

        UUID treatyId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO ri_treaties (id, treaty_type, treaty_year, description, status, "
                + "effective_date, expiry_date) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            treatyId, "SURPLUS", 2026, "Main Surplus Treaty 2026", "ACTIVE",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        jdbc.update(
            "INSERT INTO ri_allocations (allocation_number, policy_id, policy_number, treaty_id, "
                + "treaty_type, retained_amount, ceded_amount, "
                + "our_share_sum_insured, our_share_premium, retained_premium, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "RIA-VAL-001", policyId, "POL-VAL-001", treatyId, "SURPLUS",
            new BigDecimal("1000000.00"), new BigDecimal("4000000.00"),
            new BigDecimal("5000000.00"), new BigDecimal("125000.00"),
            new BigDecimal("25000.00"), "APPROVED");

        // RI report projects: policy_number, treaty_name, ceded_amount, status.
        // Filter by the unique ceded_amount we seeded to isolate this row.
        Map<String, Object> row = run("RI Premium Bordereaux", WIDE).stream()
            .filter(r -> new BigDecimal("4000000.00")
                .compareTo(new BigDecimal(r.get("ceded_amount").toString())) == 0)
            .findFirst().orElseThrow();
        assertThat(row.get("treaty_name")).isEqualTo("Main Surplus Treaty 2026");
        assertThat(new BigDecimal(row.get("ceded_amount").toString())).isEqualByComparingTo("4000000.00");
    }

    @Test
    void activeCustomersMapsFullNameAndNullChannel() {
        jdbc.update(
            "INSERT INTO customers (customer_number, customer_type, company_name, kyc_status, "
                + "customer_status) VALUES (?, ?, ?, ?, ?)",
            "CUS-VAL-CORP", "CORPORATE", "Initech Plc", "VERIFIED", "ACTIVE");
        jdbc.update(
            "INSERT INTO customers (customer_number, customer_type, first_name, last_name, "
                + "kyc_status, customer_status) VALUES (?, ?, ?, ?, ?, ?)",
            "CUS-VAL-IND", "INDIVIDUAL", "Ada", "Lovelace", "VERIFIED", "ACTIVE");

        List<Map<String, Object>> rows = run("Active Customers", WIDE);
        Map<String, Object> corp = rows.stream()
            .filter(r -> "Initech Plc".equals(r.get("full_name"))).findFirst().orElseThrow();
        assertThat(corp.get("customer_type")).isEqualTo("CORPORATE");
        assertThat(corp.get("channel")).as("channel has no backing column → NULL").isNull();

        Map<String, Object> ind = rows.stream()
            .filter(r -> "Ada Lovelace".equals(r.get("full_name"))).findFirst().orElseThrow();
        assertThat(ind.get("customer_type")).isEqualTo("INDIVIDUAL");
    }
}
