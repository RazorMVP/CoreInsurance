package com.nubeero.cia.api.finance.naicom;

import com.nubeero.cia.finance.naicom.NaicomSubmissionType;
import com.nubeero.cia.finance.naicom.PremiumBordereauxEngine;
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
 * Slice 4.2 IT for {@link PremiumBordereauxEngine}. Exercises the engine
 * end-to-end against Testcontainers Postgres with the live V41 schema and
 * seeded {@code policies} rows.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Empty period — zero totals, empty byClass, empty policies</li>
 *   <li>Single policy in period — payload populated, totals match,
 *       byClass has one entry</li>
 *   <li>Multi-policy, multi-class — totals = sum of rows; byClass rolls
 *       up by class_of_business_code with deterministic ordering</li>
 *   <li>Status exclusion — DRAFT / PENDING_APPROVAL / REJECTED policies
 *       are filtered out</li>
 *   <li>Post-approval inclusion — LAPSED and REINSTATED policies (which
 *       had premium WRITTEN at approval-time) DO appear in the bordereau.
 *       This is the regression guard for the earlier inclusion-list bug.</li>
 *   <li>Booking-date filter — a policy with approved_at OUTSIDE the
 *       period but policy_start_date INSIDE the period is excluded;
 *       conversely, a policy approved INSIDE the period with
 *       policy_start_date OUTSIDE is included. Confirms the booking-date
 *       semantic documented in the engine's javadoc.</li>
 *   <li>Deterministic ordering — policies emitted in policy_number ASC</li>
 *   <li>Soft-deleted policies excluded</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    PremiumBordereauxEngine.class
})
class PremiumBordereauxEngineIT {

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
        registry.add("spring.flyway.target", () -> "47");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired private PremiumBordereauxEngine engine;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID aprilPeriodId;
    private UUID mayPeriodId;
    private final LocalDate aprilStart = LocalDate.of(2026, 4, 1);
    private final LocalDate aprilEnd = LocalDate.of(2026, 4, 30);
    private final LocalDate mayStart = LocalDate.of(2026, 5, 1);
    private final LocalDate mayEnd = LocalDate.of(2026, 5, 31);

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM policies");
        jdbcTemplate.update("DELETE FROM fiscal_period");
        jdbcTemplate.update("DELETE FROM fiscal_year");

        UUID fyId = UUID.randomUUID();
        aprilPeriodId = UUID.randomUUID();
        mayPeriodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            fyId, "FY-PB-2026",
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
    @DisplayName("empty period — zero totals, empty byClass, empty policies")
    void emptyPeriod() {
        Map<String, Object> payload = engine.computePayload(aprilPeriodId);

        assertThat(payload.get("submissionType"))
            .isEqualTo(NaicomSubmissionType.PREMIUM_BORDEREAUX.name());
        Map<?, ?> totals = (Map<?, ?>) payload.get("totals");
        assertThat(totals.get("policyCount")).isEqualTo(0);
        assertThat((BigDecimal) totals.get("totalPremium")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat((List<?>) payload.get("byClass")).isEmpty();
        assertThat((List<?>) payload.get("policies")).isEmpty();
    }

    @Test
    @DisplayName("single ACTIVE policy approved in April — appears with totals matching")
    void singlePolicy() {
        seedPolicy("POL-2026-00001", "ACME Ltd", "Motor Comprehensive", "MOTOR-COMP",
            "Motor", new BigDecimal("5000000.00"), new BigDecimal("125000.00"),
            "ACTIVE", inAprilApprovedAt(15), aprilStart, aprilStart.plusYears(1));

        Map<String, Object> payload = engine.computePayload(aprilPeriodId);

        Map<?, ?> totals = (Map<?, ?>) payload.get("totals");
        assertThat(totals.get("policyCount")).isEqualTo(1);
        assertThat((BigDecimal) totals.get("totalSumInsured"))
            .isEqualByComparingTo("5000000.00");
        assertThat((BigDecimal) totals.get("totalPremium"))
            .isEqualByComparingTo("125000.00");

        List<?> policies = (List<?>) payload.get("policies");
        assertThat(policies).hasSize(1);
        Map<?, ?> row = (Map<?, ?>) policies.get(0);
        assertThat(row.get("policyNumber")).isEqualTo("POL-2026-00001");
        assertThat(row.get("customerName")).isEqualTo("ACME Ltd");
        assertThat(row.get("classOfBusinessCode")).isEqualTo("MOTOR-COMP");
    }

    @Test
    @DisplayName("multi-class — byClass rolls up correctly with deterministic order")
    void multiClassRollup() {
        seedPolicy("POL-A", "Cust A", "Marine", "MARINE", "Marine",
            new BigDecimal("10000000.00"), new BigDecimal("200000.00"),
            "ACTIVE", inAprilApprovedAt(10), aprilStart, aprilStart.plusYears(1));
        seedPolicy("POL-B", "Cust B", "Motor", "MOTOR-COMP", "Motor",
            new BigDecimal("3000000.00"), new BigDecimal("75000.00"),
            "ACTIVE", inAprilApprovedAt(11), aprilStart, aprilStart.plusYears(1));
        seedPolicy("POL-C", "Cust C", "Motor", "MOTOR-COMP", "Motor",
            new BigDecimal("4000000.00"), new BigDecimal("100000.00"),
            "ACTIVE", inAprilApprovedAt(12), aprilStart, aprilStart.plusYears(1));

        Map<String, Object> payload = engine.computePayload(aprilPeriodId);

        Map<?, ?> totals = (Map<?, ?>) payload.get("totals");
        assertThat(totals.get("policyCount")).isEqualTo(3);
        assertThat((BigDecimal) totals.get("totalPremium"))
            .isEqualByComparingTo("375000.00");

        List<?> byClass = (List<?>) payload.get("byClass");
        assertThat(byClass).hasSize(2);
        // byClass is keyed by first-encountered insertion order — policy_number
        // ASC means POL-A (MARINE) appears first, then POL-B (MOTOR-COMP).
        Map<?, ?> marineClass = (Map<?, ?>) byClass.get(0);
        assertThat(marineClass.get("classOfBusinessCode")).isEqualTo("MARINE");
        assertThat(marineClass.get("policyCount")).isEqualTo(1);

        Map<?, ?> motorClass = (Map<?, ?>) byClass.get(1);
        assertThat(motorClass.get("classOfBusinessCode")).isEqualTo("MOTOR-COMP");
        assertThat(motorClass.get("policyCount")).isEqualTo(2);
        assertThat((BigDecimal) motorClass.get("totalPremium"))
            .as("MOTOR-COMP rollup = 75000 + 100000")
            .isEqualByComparingTo("175000.00");
    }

    @Test
    @DisplayName("status filter excludes DRAFT / PENDING_APPROVAL / REJECTED policies")
    void statusFilterExcludesPreApproval() {
        seedPolicy("POL-DRAFT",       "Cust", "Motor", "MOTOR-COMP", "Motor",
            new BigDecimal("1000000.00"), new BigDecimal("25000.00"),
            "DRAFT", inAprilApprovedAt(10), aprilStart, aprilStart.plusYears(1));
        seedPolicy("POL-PENDING",     "Cust", "Motor", "MOTOR-COMP", "Motor",
            new BigDecimal("1000000.00"), new BigDecimal("25000.00"),
            "PENDING_APPROVAL", inAprilApprovedAt(11), aprilStart, aprilStart.plusYears(1));
        seedPolicy("POL-REJECTED",    "Cust", "Motor", "MOTOR-COMP", "Motor",
            new BigDecimal("1000000.00"), new BigDecimal("25000.00"),
            "REJECTED", inAprilApprovedAt(12), aprilStart, aprilStart.plusYears(1));
        seedPolicy("POL-OK",          "Cust", "Motor", "MOTOR-COMP", "Motor",
            new BigDecimal("1000000.00"), new BigDecimal("25000.00"),
            "ACTIVE", inAprilApprovedAt(15), aprilStart, aprilStart.plusYears(1));

        Map<String, Object> payload = engine.computePayload(aprilPeriodId);

        assertThat(((Map<?, ?>) payload.get("totals")).get("policyCount")).isEqualTo(1);
        List<?> policies = (List<?>) payload.get("policies");
        assertThat(policies).hasSize(1);
        assertThat(((Map<?, ?>) policies.get(0)).get("policyNumber")).isEqualTo("POL-OK");
    }

    @Test
    @DisplayName("post-approval inclusion — LAPSED + REINSTATED policies DO appear (regression guard)")
    void lapsedAndReinstatedAreIncluded() {
        seedPolicy("POL-LAPSED",    "Cust", "Motor", "MOTOR-COMP", "Motor",
            new BigDecimal("2000000.00"), new BigDecimal("50000.00"),
            "LAPSED", inAprilApprovedAt(10), aprilStart, aprilStart.plusYears(1));
        seedPolicy("POL-REINST",    "Cust", "Motor", "MOTOR-COMP", "Motor",
            new BigDecimal("3000000.00"), new BigDecimal("75000.00"),
            "REINSTATED", inAprilApprovedAt(11), aprilStart, aprilStart.plusYears(1));
        seedPolicy("POL-CANCELLED", "Cust", "Motor", "MOTOR-COMP", "Motor",
            new BigDecimal("1000000.00"), new BigDecimal("25000.00"),
            "CANCELLED", inAprilApprovedAt(12), aprilStart, aprilStart.plusYears(1));

        Map<String, Object> payload = engine.computePayload(aprilPeriodId);

        // 3 policies must appear — they all had premium WRITTEN at approval.
        // The status transitions (LAPSED / REINSTATED / CANCELLED) are
        // downstream endorsements; the original write goes in this bordereau.
        assertThat(((Map<?, ?>) payload.get("totals")).get("policyCount")).isEqualTo(3);
        assertThat((BigDecimal) ((Map<?, ?>) payload.get("totals")).get("totalPremium"))
            .isEqualByComparingTo("150000.00");
    }

    @Test
    @DisplayName("booking-date filter — approved_at not policy_start_date drives period membership")
    void bookingDateFilterDiscipline() {
        // Policy A: approved IN April, but policy_start_date in March.
        //   Belongs to April bordereau (booked April).
        seedPolicy("POL-BOOKED-APR", "Cust", "Motor", "MOTOR-COMP", "Motor",
            new BigDecimal("1000000.00"), new BigDecimal("25000.00"),
            "ACTIVE", inAprilApprovedAt(10), LocalDate.of(2026, 3, 15), LocalDate.of(2027, 3, 14));
        // Policy B: approved IN May, but policy_start_date in April.
        //   Does NOT belong to April bordereau — booked May.
        seedPolicy("POL-BOOKED-MAY", "Cust", "Motor", "MOTOR-COMP", "Motor",
            new BigDecimal("2000000.00"), new BigDecimal("50000.00"),
            "ACTIVE", inMayApprovedAt(5), aprilStart.plusDays(10), aprilStart.plusYears(1));

        Map<String, Object> april = engine.computePayload(aprilPeriodId);
        assertThat(((Map<?, ?>) april.get("totals")).get("policyCount"))
            .as("only POL-BOOKED-APR has approved_at in April")
            .isEqualTo(1);
        List<?> aprPolicies = (List<?>) april.get("policies");
        assertThat(((Map<?, ?>) aprPolicies.get(0)).get("policyNumber"))
            .isEqualTo("POL-BOOKED-APR");

        Map<String, Object> may = engine.computePayload(mayPeriodId);
        assertThat(((Map<?, ?>) may.get("totals")).get("policyCount"))
            .as("only POL-BOOKED-MAY has approved_at in May")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("policies ordered by policy_number ASC — deterministic across runs")
    void deterministicOrdering() {
        seedPolicy("POL-Z", "Cust Z", "Motor", "MOTOR-COMP", "Motor",
            new BigDecimal("1000000.00"), new BigDecimal("25000.00"),
            "ACTIVE", inAprilApprovedAt(10), aprilStart, aprilStart.plusYears(1));
        seedPolicy("POL-A", "Cust A", "Motor", "MOTOR-COMP", "Motor",
            new BigDecimal("1000000.00"), new BigDecimal("25000.00"),
            "ACTIVE", inAprilApprovedAt(20), aprilStart, aprilStart.plusYears(1));
        seedPolicy("POL-M", "Cust M", "Motor", "MOTOR-COMP", "Motor",
            new BigDecimal("1000000.00"), new BigDecimal("25000.00"),
            "ACTIVE", inAprilApprovedAt(15), aprilStart, aprilStart.plusYears(1));

        Map<String, Object> payload = engine.computePayload(aprilPeriodId);
        List<?> policies = (List<?>) payload.get("policies");
        List<String> numbers = policies.stream()
            .map(p -> (String) ((Map<?, ?>) p).get("policyNumber"))
            .toList();
        assertThat(numbers).containsExactly("POL-A", "POL-M", "POL-Z");
    }

    @Test
    @DisplayName("soft-deleted policies excluded")
    void softDeletedExcluded() {
        seedPolicy("POL-LIVE",    "Cust", "Motor", "MOTOR-COMP", "Motor",
            new BigDecimal("1000000.00"), new BigDecimal("25000.00"),
            "ACTIVE", inAprilApprovedAt(10), aprilStart, aprilStart.plusYears(1));
        UUID deletedId = seedPolicy("POL-DELETED", "Cust", "Motor", "MOTOR-COMP", "Motor",
            new BigDecimal("1000000.00"), new BigDecimal("25000.00"),
            "ACTIVE", inAprilApprovedAt(11), aprilStart, aprilStart.plusYears(1));
        jdbcTemplate.update("UPDATE policies SET deleted_at = now() WHERE id = ?", deletedId);

        Map<String, Object> payload = engine.computePayload(aprilPeriodId);
        assertThat(((Map<?, ?>) payload.get("totals")).get("policyCount")).isEqualTo(1);
        assertThat(((Map<?, ?>) ((List<?>) payload.get("policies")).get(0)).get("policyNumber"))
            .isEqualTo("POL-LIVE");
    }

    // ── Fixture helpers ────────────────────────────────────────────────────
    private UUID seedPolicy(String policyNumber, String customerName,
                             String productName, String classCode, String className,
                             BigDecimal sumInsured, BigDecimal premium,
                             String status, Instant approvedAt,
                             LocalDate startDate, LocalDate endDate) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO policies (id, policy_number, customer_id, customer_name, " +
            "product_id, product_name, product_code, product_rate, " +
            "class_of_business_id, class_of_business_name, class_of_business_code, " +
            "policy_start_date, policy_end_date, status, " +
            "total_sum_insured, total_premium, net_premium, " +
            "approved_at, approved_by, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, policyNumber, UUID.randomUUID(), customerName,
            UUID.randomUUID(), productName, "PROD-X", new BigDecimal("0.0250"),
            UUID.randomUUID(), className, classCode,
            startDate, endDate, status,
            sumInsured, premium, premium,
            Timestamp.from(approvedAt), "test-approver", "test");
        return id;
    }

    private Instant inAprilApprovedAt(int dayOfMonth) {
        return aprilStart.withDayOfMonth(dayOfMonth)
            .atTime(12, 0).toInstant(java.time.ZoneOffset.UTC);
    }

    private Instant inMayApprovedAt(int dayOfMonth) {
        return mayStart.withDayOfMonth(dayOfMonth)
            .atTime(12, 0).toInstant(java.time.ZoneOffset.UTC);
    }
}
