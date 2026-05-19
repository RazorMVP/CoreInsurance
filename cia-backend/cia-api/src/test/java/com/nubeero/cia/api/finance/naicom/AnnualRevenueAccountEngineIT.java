package com.nubeero.cia.api.finance.naicom;

import com.nubeero.cia.finance.naicom.AnnualRevenueAccountEngine;
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
 * Slice 4.3 IT for {@link AnnualRevenueAccountEngine}.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Empty year — zero totals, empty byClass</li>
 *   <li>Single class with policies + claims — per-class line populated,
 *       loss ratio computed correctly</li>
 *   <li>Multi-class — separate rows per class, totals aggregate
 *       correctly</li>
 *   <li>Claims-only class (no premium written) — emits {@code "lossRatio": null}
 *       rather than divide-by-zero or infinity</li>
 *   <li>Period boundary — policies / claims outside the year are excluded</li>
 *   <li>Status filters — DRAFT/REJECTED policies and WITHDRAWN claims
 *       excluded</li>
 *   <li>Deterministic ordering — byClass sorted by classOfBusinessCode ASC</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    AnnualRevenueAccountEngine.class
})
class AnnualRevenueAccountEngineIT {

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
        registry.add("spring.flyway.target", () -> "41");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired private AnnualRevenueAccountEngine engine;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID yearPeriodId;
    private final LocalDate yearStart = LocalDate.of(2026, 1, 1);
    private final LocalDate yearEnd = LocalDate.of(2026, 12, 31);

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM claims");
        jdbcTemplate.update("DELETE FROM policies");
        jdbcTemplate.update("DELETE FROM fiscal_period");
        jdbcTemplate.update("DELETE FROM fiscal_year");

        UUID fyId = UUID.randomUUID();
        yearPeriodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            fyId, "FY-AR-2026", yearStart, yearEnd, "ACTIVE", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            yearPeriodId, fyId, "YEAR", yearStart, yearEnd, "HARD_CLOSED", "test");
    }

    @Test
    @DisplayName("empty year — zero totals + empty byClass")
    void emptyYear() {
        Map<String, Object> payload = engine.computePayload(yearPeriodId);

        assertThat(payload.get("submissionType"))
            .isEqualTo(NaicomSubmissionType.ANNUAL_REVENUE_ACCOUNT.name());
        Map<?, ?> totals = (Map<?, ?>) payload.get("totals");
        assertThat(totals.get("policyCount")).isEqualTo(0);
        assertThat(totals.get("claimCount")).isEqualTo(0);
        assertThat((BigDecimal) totals.get("grossPremium")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(totals.get("lossRatio")).as("zero premium ⇒ null lossRatio").isNull();
        assertThat((List<?>) payload.get("byClass")).isEmpty();
    }

    @Test
    @DisplayName("single class with policies + claims — loss ratio computed")
    void singleClassLossRatio() {
        UUID motorPolicyId = seedPolicy("POL-MOT-1", "MOTOR-COMP", "Motor",
            new BigDecimal("100000.00"), "ACTIVE", inYearApprovedAt(15));
        seedClaim("CLM-MOT-1", motorPolicyId, "Motor",
            new BigDecimal("65000.00"), "APPROVED",
            LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 12));

        Map<String, Object> payload = engine.computePayload(yearPeriodId);

        List<?> byClass = (List<?>) payload.get("byClass");
        assertThat(byClass).hasSize(1);
        Map<?, ?> row = (Map<?, ?>) byClass.get(0);
        assertThat(row.get("classOfBusinessCode")).isEqualTo("MOTOR-COMP");
        assertThat(row.get("policyCount")).isEqualTo(1);
        assertThat((BigDecimal) row.get("grossPremium")).isEqualByComparingTo("100000.00");
        assertThat(row.get("claimCount")).isEqualTo(1);
        assertThat((BigDecimal) row.get("claimsIncurred")).isEqualByComparingTo("65000.00");
        assertThat((BigDecimal) row.get("lossRatio"))
            .as("65000/100000 × 100 = 65.00%")
            .isEqualByComparingTo("65.00");
    }

    @Test
    @DisplayName("claims-only class — lossRatio null, no divide-by-zero")
    void claimsOnlyClassEmitsNullLossRatio() {
        // Parent policy in a different year (won't be counted in this period's gross premium).
        UUID parent = seedPolicy("POL-OLD", "FIRE", "Fire",
            new BigDecimal("500000.00"), "ACTIVE",
            LocalDate.of(2025, 8, 1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC));
        // Claim reported in 2026 — appears in this year's claims; no
        // gross premium in this year ⇒ loss ratio undefined.
        seedClaim("CLM-FIRE-1", parent, "Fire",
            new BigDecimal("250000.00"), "APPROVED",
            LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 5));

        Map<String, Object> payload = engine.computePayload(yearPeriodId);

        Map<?, ?> row = (Map<?, ?>) ((List<?>) payload.get("byClass")).get(0);
        assertThat(row.get("classOfBusinessCode")).isEqualTo("FIRE");
        assertThat((BigDecimal) row.get("grossPremium")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat((BigDecimal) row.get("claimsIncurred")).isEqualByComparingTo("250000.00");
        assertThat(row.get("lossRatio"))
            .as("zero premium ⇒ null lossRatio (not 0, not Infinity)")
            .isNull();
    }

    @Test
    @DisplayName("multi-class — per-class rows + totals aggregate correctly + deterministic order")
    void multiClass() {
        UUID motor = seedPolicy("POL-M", "MOTOR-COMP", "Motor",
            new BigDecimal("400000.00"), "ACTIVE", inYearApprovedAt(10));
        UUID fire = seedPolicy("POL-F", "FIRE", "Fire",
            new BigDecimal("200000.00"), "ACTIVE", inYearApprovedAt(11));
        UUID marine = seedPolicy("POL-MAR", "MARINE", "Marine",
            new BigDecimal("600000.00"), "ACTIVE", inYearApprovedAt(12));
        seedClaim("CLM-M-1", motor, "Motor", new BigDecimal("200000.00"),
            "APPROVED", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 5));
        seedClaim("CLM-F-1", fire, "Fire", new BigDecimal("80000.00"),
            "APPROVED", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 5));
        seedClaim("CLM-MAR-1", marine, "Marine", new BigDecimal("100000.00"),
            "APPROVED", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 5));

        Map<String, Object> payload = engine.computePayload(yearPeriodId);

        // Deterministic order: classOfBusinessCode ASC → FIRE, MARINE, MOTOR-COMP
        List<?> byClass = (List<?>) payload.get("byClass");
        assertThat(byClass).hasSize(3);
        List<String> codes = byClass.stream()
            .map(r -> (String) ((Map<?, ?>) r).get("classOfBusinessCode"))
            .toList();
        assertThat(codes).containsExactly("FIRE", "MARINE", "MOTOR-COMP");

        Map<?, ?> totals = (Map<?, ?>) payload.get("totals");
        assertThat(totals.get("policyCount")).isEqualTo(3);
        assertThat(totals.get("claimCount")).isEqualTo(3);
        assertThat((BigDecimal) totals.get("grossPremium")).isEqualByComparingTo("1200000.00");
        assertThat((BigDecimal) totals.get("claimsIncurred")).isEqualByComparingTo("380000.00");
        assertThat((BigDecimal) totals.get("lossRatio"))
            .as("380000/1200000 × 100 = 31.67% (HALF_UP)")
            .isEqualByComparingTo("31.67");
    }

    @Test
    @DisplayName("period boundary — policies / claims outside year are excluded")
    void periodBoundary() {
        // In-year policy (approved June 2026)
        seedPolicy("POL-IN", "MOTOR-COMP", "Motor",
            new BigDecimal("100000.00"), "ACTIVE", inYearApprovedAt(150));
        // Out-of-year policy (approved Dec 2025)
        seedPolicy("POL-OUT", "MOTOR-COMP", "Motor",
            new BigDecimal("999999.00"), "ACTIVE",
            LocalDate.of(2025, 12, 5).atStartOfDay().toInstant(java.time.ZoneOffset.UTC));

        Map<String, Object> payload = engine.computePayload(yearPeriodId);

        Map<?, ?> totals = (Map<?, ?>) payload.get("totals");
        assertThat(totals.get("policyCount"))
            .as("only the in-year policy is counted")
            .isEqualTo(1);
        assertThat((BigDecimal) totals.get("grossPremium")).isEqualByComparingTo("100000.00");
    }

    @Test
    @DisplayName("status filter — DRAFT / REJECTED policies + WITHDRAWN / REJECTED claims excluded")
    void statusFilter() {
        UUID liveParent = seedPolicy("POL-OK", "MOTOR-COMP", "Motor",
            new BigDecimal("100000.00"), "ACTIVE", inYearApprovedAt(10));
        seedPolicy("POL-DRAFT", "MOTOR-COMP", "Motor",
            new BigDecimal("999999.00"), "DRAFT", inYearApprovedAt(11));
        seedPolicy("POL-REJ", "MOTOR-COMP", "Motor",
            new BigDecimal("999999.00"), "REJECTED", inYearApprovedAt(12));

        seedClaim("CLM-OK", liveParent, "Motor",
            new BigDecimal("50000.00"), "APPROVED",
            LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 5));
        seedClaim("CLM-WD", liveParent, "Motor",
            new BigDecimal("999999.00"), "WITHDRAWN",
            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 5));
        seedClaim("CLM-REJ", liveParent, "Motor",
            new BigDecimal("999999.00"), "REJECTED",
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 5));

        Map<String, Object> payload = engine.computePayload(yearPeriodId);

        Map<?, ?> totals = (Map<?, ?>) payload.get("totals");
        assertThat(totals.get("policyCount")).isEqualTo(1);
        assertThat(totals.get("claimCount")).isEqualTo(1);
        assertThat((BigDecimal) totals.get("grossPremium")).isEqualByComparingTo("100000.00");
        assertThat((BigDecimal) totals.get("claimsIncurred")).isEqualByComparingTo("50000.00");
    }

    // ── Fixture helpers ────────────────────────────────────────────────────
    private UUID seedPolicy(String policyNumber, String classCode, String className,
                             BigDecimal premium, String status, Instant approvedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO policies (id, policy_number, customer_id, customer_name, " +
            "product_id, product_name, product_code, product_rate, " +
            "class_of_business_id, class_of_business_name, class_of_business_code, " +
            "policy_start_date, policy_end_date, status, " +
            "total_sum_insured, total_premium, net_premium, " +
            "approved_at, approved_by, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, policyNumber, UUID.randomUUID(), "Cust",
            UUID.randomUUID(), "Product-X", "PROD-X", new BigDecimal("0.0250"),
            UUID.randomUUID(), className, classCode,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), status,
            premium.multiply(new BigDecimal("40")), premium, premium,
            Timestamp.from(approvedAt), "test-approver", "test");
        return id;
    }

    private UUID seedClaim(String claimNumber, UUID parentPolicyId, String className,
                            BigDecimal reserveAmount, String status,
                            LocalDate incidentDate, LocalDate reportedDate) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO claims (id, claim_number, status, " +
            "policy_id, policy_number, policy_start_date, policy_end_date, " +
            "customer_id, customer_name, " +
            "product_id, product_name, " +
            "class_of_business_id, class_of_business_name, " +
            "incident_date, reported_date, description, " +
            "reserve_amount, currency_code, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, claimNumber, status,
            parentPolicyId, "POL-FOR-" + claimNumber,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            UUID.randomUUID(), "Cust",
            UUID.randomUUID(), "Product-X",
            UUID.randomUUID(), className,
            incidentDate, reportedDate, "Test loss",
            reserveAmount, "NGN", "test");
        return id;
    }

    /** Approval-time within the test year. Distinguishes by day-of-year. */
    private Instant inYearApprovedAt(int dayOfYear) {
        return yearStart.plusDays(dayOfYear)
            .atTime(12, 0).toInstant(java.time.ZoneOffset.UTC);
    }
}
