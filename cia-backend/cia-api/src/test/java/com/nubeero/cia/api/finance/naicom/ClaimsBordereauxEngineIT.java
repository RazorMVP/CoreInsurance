package com.nubeero.cia.api.finance.naicom;

import com.nubeero.cia.finance.naicom.ClaimsBordereauxEngine;
import com.nubeero.cia.finance.naicom.NaicomSubmissionType;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 4.2 IT for {@link ClaimsBordereauxEngine}. Exercises the engine
 * end-to-end against Testcontainers Postgres with seeded {@code policies}
 * + {@code claims} rows.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Empty period — zero totals, empty payload</li>
 *   <li>Single APPROVED claim — appears with full payload + per-class
 *       rollup</li>
 *   <li>Settled claim — paidAmount from {@code dv_amount},
 *       outstandingAmount = reserve − paid</li>
 *   <li>Status filter — WITHDRAWN and REJECTED claims excluded; every
 *       other status (REGISTERED, UNDER_INVESTIGATION, RESERVED,
 *       PENDING_APPROVAL, APPROVED, SETTLED) included</li>
 *   <li>Reported-date filter discipline — incident_date in period but
 *       reported_date outside ⇒ excluded; conversely included</li>
 *   <li>Multi-class rollup — totals match sum of rows; byClass groups
 *       correctly</li>
 *   <li>Deterministic ordering — claim_number ASC</li>
 *   <li>Soft-deleted claims excluded</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    ClaimsBordereauxEngine.class
})
class ClaimsBordereauxEngineIT {

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
        registry.add("spring.flyway.target", () -> "48");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired private ClaimsBordereauxEngine engine;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID aprilPeriodId;
    private UUID mayPeriodId;
    private final LocalDate aprilStart = LocalDate.of(2026, 4, 1);
    private final LocalDate aprilEnd = LocalDate.of(2026, 4, 30);
    private final LocalDate mayStart = LocalDate.of(2026, 5, 1);
    private final LocalDate mayEnd = LocalDate.of(2026, 5, 31);

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM claims");
        jdbcTemplate.update("DELETE FROM policies");
        jdbcTemplate.update("DELETE FROM fiscal_period");
        jdbcTemplate.update("DELETE FROM fiscal_year");

        UUID fyId = UUID.randomUUID();
        aprilPeriodId = UUID.randomUUID();
        mayPeriodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            fyId, "FY-CB-2026",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ACTIVE", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            aprilPeriodId, fyId, "MONTH", aprilStart, aprilEnd, "HARD_CLOSED", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            mayPeriodId, fyId, "MONTH", mayStart, mayEnd, "OPEN", "test");
    }

    @Test
    @DisplayName("empty period — zero totals, empty payload")
    void emptyPeriod() {
        Map<String, Object> payload = engine.computePayload(aprilPeriodId);

        assertThat(payload.get("submissionType"))
            .isEqualTo(NaicomSubmissionType.CLAIMS_BORDEREAUX.name());
        Map<?, ?> totals = (Map<?, ?>) payload.get("totals");
        assertThat(totals.get("claimCount")).isEqualTo(0);
        assertThat((BigDecimal) totals.get("totalReserve")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat((BigDecimal) totals.get("totalPaid")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat((List<?>) payload.get("claims")).isEmpty();
    }

    @Test
    @DisplayName("single APPROVED claim — appears with full payload + correct outstanding")
    void singleApprovedClaim() {
        seedClaim("CLM-2026-00001", "ACME Ltd", "MOTOR-COMP", "Motor",
            "APPROVED",
            LocalDate.of(2026, 3, 20),  // incident in March
            LocalDate.of(2026, 4, 5),   // reported in April → in bordereau
            new BigDecimal("750000.00"),
            null,                       // not settled
            null);                      // no dv_amount

        Map<String, Object> payload = engine.computePayload(aprilPeriodId);

        List<?> claims = (List<?>) payload.get("claims");
        assertThat(claims).hasSize(1);
        Map<?, ?> row = (Map<?, ?>) claims.get(0);
        assertThat(row.get("claimNumber")).isEqualTo("CLM-2026-00001");
        assertThat(row.get("status")).isEqualTo("APPROVED");
        assertThat((BigDecimal) row.get("reserveAmount")).isEqualByComparingTo("750000.00");
        assertThat((BigDecimal) row.get("paidAmount"))
            .as("unsettled claim ⇒ paidAmount is 0")
            .isEqualByComparingTo("0");
        assertThat((BigDecimal) row.get("outstandingAmount"))
            .as("outstanding = reserve − paid = full reserve")
            .isEqualByComparingTo("750000.00");
    }

    @Test
    @DisplayName("SETTLED claim — paidAmount = dv_amount, outstanding = reserve − dv_amount")
    void settledClaim() {
        seedClaim("CLM-2026-00002", "Cust", "MOTOR-COMP", "Motor",
            "SETTLED",
            LocalDate.of(2026, 3, 20),
            LocalDate.of(2026, 4, 5),
            new BigDecimal("800000.00"),
            Instant.parse("2026-04-25T10:00:00Z"),
            new BigDecimal("712500.00"));  // settled for less than reserve

        Map<String, Object> payload = engine.computePayload(aprilPeriodId);

        Map<?, ?> row = (Map<?, ?>) ((List<?>) payload.get("claims")).get(0);
        assertThat((BigDecimal) row.get("paidAmount"))
            .as("settled ⇒ paidAmount = dv_amount")
            .isEqualByComparingTo("712500.00");
        assertThat((BigDecimal) row.get("outstandingAmount"))
            .as("outstanding = reserve − paid = 800000 − 712500")
            .isEqualByComparingTo("87500.00");

        // Totals roll up correctly.
        Map<?, ?> totals = (Map<?, ?>) payload.get("totals");
        assertThat((BigDecimal) totals.get("totalReserve")).isEqualByComparingTo("800000.00");
        assertThat((BigDecimal) totals.get("totalPaid")).isEqualByComparingTo("712500.00");
        assertThat((BigDecimal) totals.get("totalOutstanding")).isEqualByComparingTo("87500.00");
    }

    @Test
    @DisplayName("status filter — WITHDRAWN and REJECTED excluded; every other status included")
    void statusFilter() {
        seedClaim("CLM-WITHDRAWN", "Cust", "MOTOR-COMP", "Motor", "WITHDRAWN",
            aprilStart.minusDays(5), aprilStart.plusDays(2), new BigDecimal("100000.00"), null, null);
        seedClaim("CLM-REJECTED", "Cust", "MOTOR-COMP", "Motor", "REJECTED",
            aprilStart.minusDays(5), aprilStart.plusDays(3), new BigDecimal("100000.00"), null, null);
        seedClaim("CLM-REGISTERED", "Cust", "MOTOR-COMP", "Motor", "REGISTERED",
            aprilStart.minusDays(5), aprilStart.plusDays(4), new BigDecimal("100000.00"), null, null);
        seedClaim("CLM-RESERVED", "Cust", "MOTOR-COMP", "Motor", "RESERVED",
            aprilStart.minusDays(5), aprilStart.plusDays(5), new BigDecimal("100000.00"), null, null);
        seedClaim("CLM-PENDING", "Cust", "MOTOR-COMP", "Motor", "PENDING_APPROVAL",
            aprilStart.minusDays(5), aprilStart.plusDays(6), new BigDecimal("100000.00"), null, null);
        seedClaim("CLM-APPROVED", "Cust", "MOTOR-COMP", "Motor", "APPROVED",
            aprilStart.minusDays(5), aprilStart.plusDays(7), new BigDecimal("100000.00"), null, null);

        Map<String, Object> payload = engine.computePayload(aprilPeriodId);

        List<?> claims = (List<?>) payload.get("claims");
        // 6 statuses tested; 2 excluded (WITHDRAWN, REJECTED) ⇒ 4 remain.
        assertThat(claims).hasSize(4);
        List<String> numbers = claims.stream()
            .map(c -> (String) ((Map<?, ?>) c).get("claimNumber"))
            .toList();
        assertThat(numbers)
            .as("WITHDRAWN and REJECTED filtered; rest pass through")
            .doesNotContain("CLM-WITHDRAWN", "CLM-REJECTED")
            .contains("CLM-REGISTERED", "CLM-RESERVED", "CLM-PENDING", "CLM-APPROVED");
    }

    @Test
    @DisplayName("reported-date filter discipline — incident_date irrelevant; reported_date drives period membership")
    void reportedDateFilterDiscipline() {
        // Claim A: incident March, reported April → IN April bordereau
        seedClaim("CLM-REPORTED-APR", "Cust", "MOTOR-COMP", "Motor", "APPROVED",
            LocalDate.of(2026, 3, 25), LocalDate.of(2026, 4, 10),
            new BigDecimal("200000.00"), null, null);
        // Claim B: incident April, reported May → NOT in April bordereau (in May)
        seedClaim("CLM-REPORTED-MAY", "Cust", "MOTOR-COMP", "Motor", "APPROVED",
            LocalDate.of(2026, 4, 20), LocalDate.of(2026, 5, 5),
            new BigDecimal("300000.00"), null, null);

        Map<String, Object> april = engine.computePayload(aprilPeriodId);
        assertThat(((Map<?, ?>) april.get("totals")).get("claimCount"))
            .as("only the April-reported claim appears in April bordereau")
            .isEqualTo(1);
        assertThat(((Map<?, ?>) ((List<?>) april.get("claims")).get(0)).get("claimNumber"))
            .isEqualTo("CLM-REPORTED-APR");

        Map<String, Object> may = engine.computePayload(mayPeriodId);
        assertThat(((Map<?, ?>) may.get("totals")).get("claimCount"))
            .isEqualTo(1);
        assertThat(((Map<?, ?>) ((List<?>) may.get("claims")).get(0)).get("claimNumber"))
            .isEqualTo("CLM-REPORTED-MAY");
    }

    @Test
    @DisplayName("multi-class rollup — totals match sum of rows")
    void multiClassRollup() {
        seedClaim("CLM-MOTOR-1", "C1", "MOTOR-COMP", "Motor", "APPROVED",
            aprilStart, aprilStart.plusDays(5), new BigDecimal("100000.00"), null, null);
        seedClaim("CLM-MOTOR-2", "C2", "MOTOR-COMP", "Motor", "APPROVED",
            aprilStart, aprilStart.plusDays(6), new BigDecimal("150000.00"), null, null);
        seedClaim("CLM-FIRE-1",  "C3", "FIRE",       "Fire",  "APPROVED",
            aprilStart, aprilStart.plusDays(7), new BigDecimal("500000.00"), null, null);

        Map<String, Object> payload = engine.computePayload(aprilPeriodId);

        Map<?, ?> totals = (Map<?, ?>) payload.get("totals");
        assertThat(totals.get("claimCount")).isEqualTo(3);
        assertThat((BigDecimal) totals.get("totalReserve")).isEqualByComparingTo("750000.00");

        List<?> byClass = (List<?>) payload.get("byClass");
        assertThat(byClass).hasSize(2);

        // First encountered (via claim_number ASC) is CLM-FIRE-1 → FIRE class first.
        Map<?, ?> fire = (Map<?, ?>) byClass.get(0);
        assertThat(fire.get("classOfBusinessCode")).isEqualTo("FIRE");
        assertThat(fire.get("claimCount")).isEqualTo(1);
        assertThat((BigDecimal) fire.get("totalReserve")).isEqualByComparingTo("500000.00");

        Map<?, ?> motor = (Map<?, ?>) byClass.get(1);
        assertThat(motor.get("classOfBusinessCode")).isEqualTo("MOTOR-COMP");
        assertThat(motor.get("claimCount")).isEqualTo(2);
        assertThat((BigDecimal) motor.get("totalReserve")).isEqualByComparingTo("250000.00");
    }

    @Test
    @DisplayName("claims ordered by claim_number ASC — deterministic")
    void deterministicOrdering() {
        seedClaim("CLM-Z", "Cust", "MOTOR-COMP", "Motor", "APPROVED",
            aprilStart, aprilStart.plusDays(5), new BigDecimal("100000.00"), null, null);
        seedClaim("CLM-A", "Cust", "MOTOR-COMP", "Motor", "APPROVED",
            aprilStart, aprilStart.plusDays(6), new BigDecimal("100000.00"), null, null);
        seedClaim("CLM-M", "Cust", "MOTOR-COMP", "Motor", "APPROVED",
            aprilStart, aprilStart.plusDays(7), new BigDecimal("100000.00"), null, null);

        Map<String, Object> payload = engine.computePayload(aprilPeriodId);

        List<String> numbers = ((List<?>) payload.get("claims")).stream()
            .map(c -> (String) ((Map<?, ?>) c).get("claimNumber"))
            .toList();
        assertThat(numbers).containsExactly("CLM-A", "CLM-M", "CLM-Z");
    }

    @Test
    @DisplayName("soft-deleted claims excluded")
    void softDeletedExcluded() {
        seedClaim("CLM-LIVE",    "Cust", "MOTOR-COMP", "Motor", "APPROVED",
            aprilStart, aprilStart.plusDays(5), new BigDecimal("100000.00"), null, null);
        UUID deletedId = seedClaim("CLM-DELETED", "Cust", "MOTOR-COMP", "Motor", "APPROVED",
            aprilStart, aprilStart.plusDays(6), new BigDecimal("100000.00"), null, null);
        jdbcTemplate.update("UPDATE claims SET deleted_at = now() WHERE id = ?", deletedId);

        Map<String, Object> payload = engine.computePayload(aprilPeriodId);
        assertThat(((Map<?, ?>) payload.get("totals")).get("claimCount")).isEqualTo(1);
        assertThat(((Map<?, ?>) ((List<?>) payload.get("claims")).get(0)).get("claimNumber"))
            .isEqualTo("CLM-LIVE");
    }

    // ── Fixture helpers ────────────────────────────────────────────────────
    //
    // Each seedClaim creates its OWN parent policy carrying the matching
    // class_of_business_code. The engine reads class_of_business_code from
    // the joined policies row (claims has only _id and _name, no _code) —
    // so a shared "parent policy" would force every claim into the same
    // class regardless of its own snapshot, defeating per-class tests.

    private UUID seedClaim(String claimNumber, String customerName,
                            String classCode, String className,
                            String status, LocalDate incidentDate, LocalDate reportedDate,
                            BigDecimal reserveAmount, Instant settledAt, BigDecimal dvAmount) {
        UUID policyId = seedParentPolicy(classCode, className);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO claims (id, claim_number, status, " +
            "policy_id, policy_number, policy_start_date, policy_end_date, " +
            "customer_id, customer_name, " +
            "product_id, product_name, " +
            "class_of_business_id, class_of_business_name, " +
            "incident_date, reported_date, description, " +
            "reserve_amount, currency_code, " +
            "settled_at, dv_amount, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, claimNumber, status,
            policyId, "POL-FOR-" + claimNumber,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            UUID.randomUUID(), customerName,
            UUID.randomUUID(), "Test Product",
            UUID.randomUUID(), className,
            incidentDate, reportedDate, "Test loss",
            reserveAmount, "NGN",
            settledAt != null ? Timestamp.from(settledAt) : null, dvAmount,
            "test");
        return id;
    }

    private UUID seedParentPolicy(String classCode, String className) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO policies (id, policy_number, customer_id, customer_name, " +
            "product_id, product_name, product_code, product_rate, " +
            "class_of_business_id, class_of_business_name, class_of_business_code, " +
            "policy_start_date, policy_end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, "POL-" + id, UUID.randomUUID(), "Parent",
            UUID.randomUUID(), "Parent Product", "PROD-PARENT", new BigDecimal("0.0250"),
            UUID.randomUUID(), className, classCode,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ACTIVE", "test");
        return id;
    }
}
