package com.nubeero.cia.api.finance.naicom;

import com.nubeero.cia.finance.naicom.NaicomSubmissionType;
import com.nubeero.cia.finance.naicom.NiidStatusSnapshotEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 4.8 IT for {@link NiidStatusSnapshotEngine} (NAICOM N07).
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Empty period — envelope populated, totals zero, pending empty.</li>
 *   <li>All uploaded — 100% compliance percent, no pending entries.</li>
 *   <li>Mixed uploaded / pending — compliance percent + pending list correct.</li>
 *   <li>Non-NIID policies (niid_required=false) excluded.</li>
 *   <li>Out-of-force policies excluded by date filter.</li>
 *   <li>REJECTED / CANCELLED policies excluded.</li>
 *   <li>byClassOfBusiness aggregates correctly across two classes.</li>
 *   <li>pending sorted by daysSinceApproval DESC.</li>
 *   <li>Missing fiscal period throws FiscalPeriodNotFoundException.</li>
 *   <li>Payload envelope shape matches NAICOM contract.</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    NiidStatusSnapshotEngine.class
})
class NiidStatusSnapshotEngineIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ciatest")
            .withUsername("ciatest")
            .withPassword("ciatest");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.target", () -> "43");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired private NiidStatusSnapshotEngine engine;
    @Autowired private JdbcTemplate jdbcTemplate;

    // Snapshot taken at Q3 2026 end (2026-09-30) — well before today (2026-05-19).
    // Adjust to a period whose end_date is <= today so approved_at::date filter
    // doesn't accidentally exclude approvals.
    private UUID q1PeriodId;
    private final LocalDate q1Start = LocalDate.of(2026, 1, 1);
    private final LocalDate q1End = LocalDate.of(2026, 3, 31);

    @BeforeEach
    void seedPeriod() {
        jdbcTemplate.update("DELETE FROM policy_risks");
        jdbcTemplate.update("DELETE FROM policies");
        jdbcTemplate.update("DELETE FROM fiscal_period");
        jdbcTemplate.update("DELETE FROM fiscal_year");

        UUID fyId = UUID.randomUUID();
        q1PeriodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            fyId, "FY-N07-2026", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ACTIVE", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            q1PeriodId, fyId, "QUARTER", q1Start, q1End, "HARD_CLOSED", "test");
    }

    @Test
    @DisplayName("empty period — envelope populated, totals zero, pending empty")
    void emptyPeriod() {
        Map<String, Object> payload = engine.computePayload(q1PeriodId);

        assertThat(payload.get("submissionType"))
            .isEqualTo(NaicomSubmissionType.NIID_STATUS_SNAPSHOT.name());
        assertThat(payload.get("asOf")).isEqualTo(q1End.toString());

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) payload.get("totals");
        assertThat(totals.get("inForceCount")).isEqualTo(0);
        assertThat(totals.get("uploadedCount")).isEqualTo(0);
        assertThat(totals.get("pendingCount")).isEqualTo(0);
        assertThat(totals.get("uploadCompliancePercent"))
            .as("null compliance percent when no in-force motor/marine policies exist")
            .isNull();

        assertThat((List<?>) payload.get("byClassOfBusiness")).isEmpty();
        assertThat((List<?>) payload.get("pending")).isEmpty();
    }

    @Test
    @DisplayName("all uploaded — 100% compliance, pending empty")
    void allUploaded() {
        seedMotorPolicy("POL-M-001", "Acme Motors Ltd", true,
            LocalDate.of(2026, 1, 5), LocalDate.of(2026, 12, 31),
            "ACTIVE", ts(2026, 1, 5, 10, 0),
            "NIID-REF-001", ts(2026, 1, 5, 11, 0));
        seedMotorPolicy("POL-M-002", "BetaCo Insurance", true,
            LocalDate.of(2026, 1, 10), LocalDate.of(2026, 12, 31),
            "ACTIVE", ts(2026, 1, 10, 10, 0),
            "NIID-REF-002", ts(2026, 1, 10, 11, 0));

        Map<String, Object> payload = engine.computePayload(q1PeriodId);

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) payload.get("totals");
        assertThat(totals.get("inForceCount")).isEqualTo(2);
        assertThat(totals.get("uploadedCount")).isEqualTo(2);
        assertThat(totals.get("pendingCount")).isEqualTo(0);
        assertThat((BigDecimal) totals.get("uploadCompliancePercent"))
            .isEqualByComparingTo("100.00");

        assertThat((List<?>) payload.get("pending")).isEmpty();
    }

    @Test
    @DisplayName("mixed uploaded / pending — compliance percent + pending list correct")
    void mixedUploadedPending() {
        seedMotorPolicy("POL-M-001", "Customer A", true,
            LocalDate.of(2026, 1, 5), LocalDate.of(2026, 12, 31),
            "ACTIVE", ts(2026, 1, 5, 10, 0),
            "NIID-REF-001", ts(2026, 1, 5, 11, 0));
        // No niid_ref → pending
        seedMotorPolicy("POL-M-002", "Customer B", true,
            LocalDate.of(2026, 1, 10), LocalDate.of(2026, 12, 31),
            "ACTIVE", ts(2026, 1, 10, 10, 0),
            null, null);
        seedMotorPolicy("POL-M-003", "Customer C", true,
            LocalDate.of(2026, 2, 1), LocalDate.of(2026, 12, 31),
            "ACTIVE", ts(2026, 2, 1, 10, 0),
            null, null);

        Map<String, Object> payload = engine.computePayload(q1PeriodId);

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) payload.get("totals");
        assertThat(totals.get("inForceCount")).isEqualTo(3);
        assertThat(totals.get("uploadedCount")).isEqualTo(1);
        assertThat(totals.get("pendingCount")).isEqualTo(2);
        // 1 / 3 × 100 = 33.333... → 33.33 (HALF_UP)
        assertThat((BigDecimal) totals.get("uploadCompliancePercent"))
            .isEqualByComparingTo("33.33");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pending = (List<Map<String, Object>>) payload.get("pending");
        assertThat(pending).hasSize(2);
        assertThat(pending).extracting(p -> p.get("policyNumber"))
            .as("oldest pending first by daysSinceApproval DESC")
            .containsExactly("POL-M-002", "POL-M-003");
    }

    @Test
    @DisplayName("non-NIID policies (fire / liability) excluded from snapshot")
    void nonNiidPoliciesExcluded() {
        seedMotorPolicy("POL-M-001", "Customer", true,
            LocalDate.of(2026, 1, 5), LocalDate.of(2026, 12, 31),
            "ACTIVE", ts(2026, 1, 5, 10, 0),
            "NIID-REF-001", ts(2026, 1, 5, 11, 0));
        // niid_required = FALSE — should be excluded
        seedFirePolicy("POL-F-001", "Customer",
            LocalDate.of(2026, 1, 5), LocalDate.of(2026, 12, 31),
            "ACTIVE", ts(2026, 1, 5, 10, 0));

        Map<String, Object> payload = engine.computePayload(q1PeriodId);

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) payload.get("totals");
        assertThat(totals.get("inForceCount")).isEqualTo(1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classes = (List<Map<String, Object>>) payload.get("byClassOfBusiness");
        assertThat(classes).hasSize(1);
        assertThat(classes.get(0).get("classOfBusinessCode")).isEqualTo("MOTOR");
    }

    @Test
    @DisplayName("out-of-force policies (dates outside period_end) excluded")
    void outOfForceExcluded() {
        // Expired before period_end — policy_end_date < period_end
        seedMotorPolicy("POL-EXPIRED", "Customer 1", true,
            LocalDate.of(2025, 1, 5), LocalDate.of(2025, 12, 31),
            "EXPIRED", ts(2025, 1, 5, 10, 0),
            "NIID-REF-OLD", ts(2025, 1, 5, 11, 0));
        // Future inception — policy_start_date > period_end
        seedMotorPolicy("POL-FUTURE", "Customer 2", true,
            LocalDate.of(2026, 5, 1), LocalDate.of(2027, 4, 30),
            "ACTIVE", ts(2026, 4, 1, 10, 0),
            null, null);
        // In-force — included
        seedMotorPolicy("POL-INFORCE", "Customer 3", true,
            LocalDate.of(2026, 1, 5), LocalDate.of(2026, 12, 31),
            "ACTIVE", ts(2026, 1, 5, 10, 0),
            "NIID-REF-NEW", ts(2026, 1, 5, 11, 0));

        Map<String, Object> payload = engine.computePayload(q1PeriodId);

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) payload.get("totals");
        assertThat(totals.get("inForceCount"))
            .as("expired and future-inception policies excluded by date filter")
            .isEqualTo(1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classes = (List<Map<String, Object>>) payload.get("byClassOfBusiness");
        assertThat(classes.get(0).get("uploadedCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("REJECTED and CANCELLED policies excluded")
    void rejectedCancelledExcluded() {
        seedMotorPolicy("POL-REJ", "Customer 1", true,
            LocalDate.of(2026, 1, 5), LocalDate.of(2026, 12, 31),
            "REJECTED", ts(2026, 1, 5, 10, 0),
            null, null);
        seedMotorPolicy("POL-CAN", "Customer 2", true,
            LocalDate.of(2026, 1, 5), LocalDate.of(2026, 12, 31),
            "CANCELLED", ts(2026, 1, 5, 10, 0),
            null, null);
        seedMotorPolicy("POL-OK", "Customer 3", true,
            LocalDate.of(2026, 1, 5), LocalDate.of(2026, 12, 31),
            "ACTIVE", ts(2026, 1, 5, 10, 0),
            "NIID-REF-OK", ts(2026, 1, 5, 11, 0));

        Map<String, Object> payload = engine.computePayload(q1PeriodId);

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) payload.get("totals");
        assertThat(totals.get("inForceCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("byClassOfBusiness aggregates across motor + marine — sorted by code ASC")
    void multipleClassesAggregated() {
        seedMotorPolicy("POL-M-001", "C1", true,
            LocalDate.of(2026, 1, 5), LocalDate.of(2026, 12, 31),
            "ACTIVE", ts(2026, 1, 5, 10, 0),
            "MOTOR-REF-001", ts(2026, 1, 5, 11, 0));
        seedMotorPolicy("POL-M-002", "C2", true,
            LocalDate.of(2026, 1, 5), LocalDate.of(2026, 12, 31),
            "ACTIVE", ts(2026, 1, 5, 10, 0),
            null, null);
        seedMarinePolicy("POL-MN-001", "C3", true,
            LocalDate.of(2026, 1, 10), LocalDate.of(2026, 12, 31),
            "ACTIVE", ts(2026, 1, 10, 10, 0),
            "MARINE-REF-001", ts(2026, 1, 10, 11, 0));

        Map<String, Object> payload = engine.computePayload(q1PeriodId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classes = (List<Map<String, Object>>) payload.get("byClassOfBusiness");
        // Alphabetical: MARINE, MOTOR
        assertThat(classes).extracting(c -> c.get("classOfBusinessCode"))
            .containsExactly("MARINE", "MOTOR");
        // Marine: 1 in force, 1 uploaded
        assertThat(classes.get(0).get("inForceCount")).isEqualTo(1);
        assertThat(classes.get(0).get("uploadedCount")).isEqualTo(1);
        assertThat((BigDecimal) classes.get(0).get("uploadCompliancePercent"))
            .isEqualByComparingTo("100.00");
        // Motor: 2 in force, 1 uploaded
        assertThat(classes.get(1).get("inForceCount")).isEqualTo(2);
        assertThat(classes.get(1).get("uploadedCount")).isEqualTo(1);
        assertThat((BigDecimal) classes.get(1).get("uploadCompliancePercent"))
            .isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("pending sorted by daysSinceApproval DESC (oldest first)")
    void pendingSortedByAge() {
        // Approved Jan 5 — 85 days before Q1 end (Mar 31)
        seedMotorPolicy("POL-OLDEST", "C1", true,
            LocalDate.of(2026, 1, 5), LocalDate.of(2026, 12, 31),
            "ACTIVE", ts(2026, 1, 5, 10, 0),
            null, null);
        // Approved Mar 1 — 30 days before Q1 end
        seedMotorPolicy("POL-NEWEST", "C2", true,
            LocalDate.of(2026, 3, 1), LocalDate.of(2026, 12, 31),
            "ACTIVE", ts(2026, 3, 1, 10, 0),
            null, null);
        // Approved Feb 10 — 49 days before Q1 end
        seedMotorPolicy("POL-MIDDLE", "C3", true,
            LocalDate.of(2026, 2, 10), LocalDate.of(2026, 12, 31),
            "ACTIVE", ts(2026, 2, 10, 10, 0),
            null, null);

        Map<String, Object> payload = engine.computePayload(q1PeriodId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pending = (List<Map<String, Object>>) payload.get("pending");
        assertThat(pending).extracting(p -> p.get("policyNumber"))
            .as("oldest pending appears first; daysSinceApproval DESC")
            .containsExactly("POL-OLDEST", "POL-MIDDLE", "POL-NEWEST");

        assertThat(pending.get(0).get("daysSinceApproval")).isEqualTo(85);
        assertThat(pending.get(1).get("daysSinceApproval")).isEqualTo(49);
        assertThat(pending.get(2).get("daysSinceApproval")).isEqualTo(30);
    }

    @Test
    @DisplayName("missing fiscal period throws FiscalPeriodNotFoundException")
    void unknownPeriodThrows() {
        assertThatThrownBy(() -> engine.computePayload(UUID.randomUUID()))
            .isInstanceOf(com.nubeero.cia.finance.gl.FiscalPeriodNotFoundException.class);
    }

    @Test
    @DisplayName("payload envelope keys + notes content")
    void payloadEnvelope() {
        Map<String, Object> payload = engine.computePayload(q1PeriodId);
        assertThat(payload.keySet()).containsExactly(
            "submissionType", "period", "asOf", "generatedAt",
            "totals", "byClassOfBusiness", "pending", "notes");
        assertThat(payload.get("notes")).asString()
            .contains("niid_required")
            .contains("in force");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Timestamp ts(int y, int m, int d, int hh, int mm) {
        return Timestamp.valueOf(LocalDateTime.of(y, m, d, hh, mm));
    }

    private void seedMotorPolicy(String policyNumber, String customerName, boolean niidRequired,
                                  LocalDate startDate, LocalDate endDate,
                                  String status, Timestamp approvedAt,
                                  String niidRef, Timestamp niidUploadedAt) {
        seedPolicyRow(policyNumber, customerName, niidRequired,
            "MOTOR", "Motor Insurance",
            startDate, endDate, status, approvedAt, niidRef, niidUploadedAt);
    }

    private void seedMarinePolicy(String policyNumber, String customerName, boolean niidRequired,
                                   LocalDate startDate, LocalDate endDate,
                                   String status, Timestamp approvedAt,
                                   String niidRef, Timestamp niidUploadedAt) {
        seedPolicyRow(policyNumber, customerName, niidRequired,
            "MARINE", "Marine Insurance",
            startDate, endDate, status, approvedAt, niidRef, niidUploadedAt);
    }

    /**
     * Fire policy — niid_required=FALSE; should be excluded from the snapshot.
     * Lets the test prove the engine's niid_required filter is correct.
     */
    private void seedFirePolicy(String policyNumber, String customerName,
                                 LocalDate startDate, LocalDate endDate,
                                 String status, Timestamp approvedAt) {
        seedPolicyRow(policyNumber, customerName, false,
            "FIRE", "Fire Insurance",
            startDate, endDate, status, approvedAt, null, null);
    }

    private void seedPolicyRow(String policyNumber, String customerName, boolean niidRequired,
                                String cobCode, String cobName,
                                LocalDate startDate, LocalDate endDate,
                                String status, Timestamp approvedAt,
                                String niidRef, Timestamp niidUploadedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO policies (id, policy_number, status, " +
            "customer_id, customer_name, " +
            "product_id, product_name, product_code, product_rate, " +
            "class_of_business_id, class_of_business_name, class_of_business_code, " +
            "business_type, niid_required, " +
            "policy_start_date, policy_end_date, " +
            "total_sum_insured, total_premium, net_premium, " +
            "approved_at, niid_ref, niid_uploaded_at, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, policyNumber, status,
            UUID.randomUUID(), customerName,
            UUID.randomUUID(), "Product-" + cobCode, "PROD-" + cobCode, new BigDecimal("0.0500"),
            UUID.randomUUID(), cobName, cobCode,
            "DIRECT", niidRequired,
            startDate, endDate,
            new BigDecimal("1000000.00"), new BigDecimal("50000.00"), new BigDecimal("50000.00"),
            approvedAt, niidRef, niidUploadedAt, "test");
    }
}
