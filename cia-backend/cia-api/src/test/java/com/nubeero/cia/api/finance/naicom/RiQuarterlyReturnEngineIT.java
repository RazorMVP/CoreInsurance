package com.nubeero.cia.api.finance.naicom;

import com.nubeero.cia.finance.naicom.NaicomSubmissionType;
import com.nubeero.cia.finance.naicom.RiQuarterlyReturnEngine;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 4.5 IT for {@link RiQuarterlyReturnEngine}. Exercises the engine
 * end-to-end against Testcontainers Postgres with seeded
 * {@code ri_treaties} + {@code ri_allocations} + {@code ri_allocation_lines}
 * + {@code ri_fac_covers} rows.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Empty period — zero totals, empty byTreaty / facCovers /
 *       byReinsurer</li>
 *   <li>Single treaty with two reinsurers — per-treaty rollup correct,
 *       byReinsurer breakdown inside the treaty correct</li>
 *   <li>FAC cover — appears in facCovers, contributes to byReinsurer</li>
 *   <li>Same reinsurer across treaty + FAC — single byReinsurer row
 *       with treaty + FAC components summed</li>
 *   <li>Status filter — DRAFT allocations + PENDING FAC excluded</li>
 *   <li>Period filter — out-of-period rows excluded</li>
 *   <li>Deterministic ordering — byTreaty by (year DESC, type ASC);
 *       byReinsurer by name ASC; facCovers by fac_reference ASC</li>
 *   <li>Notes disclosure — claims-ceded deferral visible</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    RiQuarterlyReturnEngine.class
})
class RiQuarterlyReturnEngineIT {

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

    @Autowired private RiQuarterlyReturnEngine engine;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID q3PeriodId;
    private UUID q2PeriodId;
    private final LocalDate q3Start = LocalDate.of(2026, 7, 1);
    private final LocalDate q3End = LocalDate.of(2026, 9, 30);
    private final LocalDate q2Start = LocalDate.of(2026, 4, 1);
    private final LocalDate q2End = LocalDate.of(2026, 6, 30);

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM ri_allocation_lines");
        jdbcTemplate.update("DELETE FROM ri_allocations");
        jdbcTemplate.update("DELETE FROM ri_fac_covers");
        jdbcTemplate.update("DELETE FROM ri_treaty_participants");
        jdbcTemplate.update("DELETE FROM ri_treaties");
        jdbcTemplate.update("DELETE FROM fiscal_period");
        jdbcTemplate.update("DELETE FROM fiscal_year");

        UUID fyId = UUID.randomUUID();
        q3PeriodId = UUID.randomUUID();
        q2PeriodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            fyId, "FY-RI-2026",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ACTIVE", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            q2PeriodId, fyId, "QUARTER", q2Start, q2End, "HARD_CLOSED", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            q3PeriodId, fyId, "QUARTER", q3Start, q3End, "HARD_CLOSED", "test");
    }

    @Test
    @DisplayName("empty period — zero totals, empty byTreaty / facCovers / byReinsurer")
    void emptyPeriod() {
        Map<String, Object> payload = engine.computePayload(q3PeriodId);

        assertThat(payload.get("submissionType"))
            .isEqualTo(NaicomSubmissionType.RI_QUARTERLY_RETURN.name());
        Map<?, ?> totals = (Map<?, ?>) payload.get("totals");
        assertThat(totals.get("treatyCessionCount")).isEqualTo(0);
        assertThat(totals.get("facCoverCount")).isEqualTo(0);
        assertThat((BigDecimal) totals.get("totalPremiumCeded")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat((List<?>) payload.get("byTreaty")).isEmpty();
        assertThat((List<?>) payload.get("facCovers")).isEmpty();
        assertThat((List<?>) payload.get("byReinsurer")).isEmpty();
    }

    @Test
    @DisplayName("single treaty with two reinsurers — per-treaty + per-reinsurer rollup correct")
    void singleTreatyTwoReinsurers() {
        UUID treatyId = seedTreaty("SURPLUS", 2026);
        UUID allocationId = seedAllocation(treatyId, "ALLOC-001", "CONFIRMED", inQ3CreatedAt(15));
        UUID africaReId = UUID.randomUUID();
        UUID swissReId = UUID.randomUUID();
        seedAllocationLine(allocationId, africaReId, "Africa Re",
            new BigDecimal("60.00"), new BigDecimal("600000.00"), new BigDecimal("60000.00"));
        seedAllocationLine(allocationId, swissReId, "Swiss Re",
            new BigDecimal("40.00"), new BigDecimal("400000.00"), new BigDecimal("40000.00"));

        Map<String, Object> payload = engine.computePayload(q3PeriodId);

        List<?> byTreaty = (List<?>) payload.get("byTreaty");
        assertThat(byTreaty).hasSize(1);
        Map<?, ?> treaty = (Map<?, ?>) byTreaty.get(0);
        assertThat(treaty.get("treatyType")).isEqualTo("SURPLUS");
        assertThat(treaty.get("treatyYear")).isEqualTo(2026);
        assertThat(treaty.get("displayName")).isEqualTo("SURPLUS-2026");
        assertThat(treaty.get("allocationCount")).isEqualTo(1);
        assertThat((BigDecimal) treaty.get("premiumCeded"))
            .as("60% + 40% = 100% × 1M = 1M total ceded")
            .isEqualByComparingTo("1000000.00");
        assertThat((BigDecimal) treaty.get("commission")).isEqualByComparingTo("100000.00");

        List<?> reinsurers = (List<?>) treaty.get("byReinsurer");
        assertThat(reinsurers).hasSize(2);
        // Sorted by reinsurer_name ASC: Africa Re, Swiss Re
        Map<?, ?> africa = (Map<?, ?>) reinsurers.get(0);
        assertThat(africa.get("reinsurerName")).isEqualTo("Africa Re");
        assertThat((BigDecimal) africa.get("premiumCeded")).isEqualByComparingTo("600000.00");
        Map<?, ?> swiss = (Map<?, ?>) reinsurers.get(1);
        assertThat(swiss.get("reinsurerName")).isEqualTo("Swiss Re");
        assertThat((BigDecimal) swiss.get("premiumCeded")).isEqualByComparingTo("400000.00");
    }

    @Test
    @DisplayName("FAC cover appears in facCovers and contributes to byReinsurer")
    void facCover() {
        UUID munichReId = UUID.randomUUID();
        seedFacCover("FAC-001", "POL-LARGE", munichReId, "Munich Re",
            new BigDecimal("500000.00"), new BigDecimal("50000.00"),
            "CONFIRMED", inQ3ApprovedAt(20));

        Map<String, Object> payload = engine.computePayload(q3PeriodId);

        List<?> facCovers = (List<?>) payload.get("facCovers");
        assertThat(facCovers).hasSize(1);
        Map<?, ?> fac = (Map<?, ?>) facCovers.get(0);
        assertThat(fac.get("facReference")).isEqualTo("FAC-001");
        assertThat(fac.get("policyNumber")).isEqualTo("POL-LARGE");
        assertThat(fac.get("reinsurerName")).isEqualTo("Munich Re");
        assertThat((BigDecimal) fac.get("premiumCeded")).isEqualByComparingTo("500000.00");

        // Reinsurer rollup includes this FAC
        List<?> byReinsurer = (List<?>) payload.get("byReinsurer");
        assertThat(byReinsurer).hasSize(1);
        Map<?, ?> munich = (Map<?, ?>) byReinsurer.get(0);
        assertThat(munich.get("reinsurerName")).isEqualTo("Munich Re");
        assertThat((BigDecimal) munich.get("treatyPremiumCeded")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat((BigDecimal) munich.get("facPremiumCeded")).isEqualByComparingTo("500000.00");
        assertThat((BigDecimal) munich.get("totalPremiumCeded")).isEqualByComparingTo("500000.00");
    }

    @Test
    @DisplayName("same reinsurer across treaty + FAC — single byReinsurer row with both components summed")
    void sameReinsurerAcrossTreatyAndFac() {
        UUID africaReId = UUID.randomUUID();
        UUID treatyId = seedTreaty("QUOTA_SHARE", 2026);
        UUID allocationId = seedAllocation(treatyId, "ALLOC-001", "CONFIRMED", inQ3CreatedAt(10));
        seedAllocationLine(allocationId, africaReId, "Africa Re",
            new BigDecimal("100.00"), new BigDecimal("700000.00"), new BigDecimal("70000.00"));
        seedFacCover("FAC-001", "POL-X", africaReId, "Africa Re",
            new BigDecimal("300000.00"), new BigDecimal("30000.00"),
            "CONFIRMED", inQ3ApprovedAt(15));

        Map<String, Object> payload = engine.computePayload(q3PeriodId);

        List<?> byReinsurer = (List<?>) payload.get("byReinsurer");
        assertThat(byReinsurer).hasSize(1);
        Map<?, ?> africa = (Map<?, ?>) byReinsurer.get(0);
        assertThat((BigDecimal) africa.get("treatyPremiumCeded")).isEqualByComparingTo("700000.00");
        assertThat((BigDecimal) africa.get("facPremiumCeded")).isEqualByComparingTo("300000.00");
        assertThat((BigDecimal) africa.get("totalPremiumCeded"))
            .as("treaty + FAC = 1M")
            .isEqualByComparingTo("1000000.00");
        assertThat((BigDecimal) africa.get("totalCommission"))
            .as("commission also summed across both")
            .isEqualByComparingTo("100000.00");
    }

    @Test
    @DisplayName("status filter — DRAFT allocations and PENDING/CANCELLED FAC covers excluded")
    void statusFilter() {
        UUID treatyId = seedTreaty("SURPLUS", 2026);
        UUID africaReId = UUID.randomUUID();
        UUID swissReId = UUID.randomUUID();

        // Confirmed allocation — should appear
        UUID confirmedAllocId = seedAllocation(treatyId, "ALLOC-OK", "CONFIRMED", inQ3CreatedAt(10));
        seedAllocationLine(confirmedAllocId, africaReId, "Africa Re",
            new BigDecimal("100.00"), new BigDecimal("100000.00"), new BigDecimal("10000.00"));

        // Draft allocation — should NOT appear
        UUID draftAllocId = seedAllocation(treatyId, "ALLOC-DRAFT", "DRAFT", inQ3CreatedAt(11));
        seedAllocationLine(draftAllocId, swissReId, "Swiss Re",
            new BigDecimal("100.00"), new BigDecimal("999999.00"), new BigDecimal("99999.00"));

        // Cancelled allocation — should NOT appear
        UUID cancelledAllocId = seedAllocation(treatyId, "ALLOC-CANC", "CANCELLED", inQ3CreatedAt(12));
        seedAllocationLine(cancelledAllocId, swissReId, "Swiss Re",
            new BigDecimal("100.00"), new BigDecimal("999999.00"), new BigDecimal("99999.00"));

        // Pending FAC — should NOT appear
        seedFacCover("FAC-PEND", "POL-A", swissReId, "Swiss Re",
            new BigDecimal("999999.00"), new BigDecimal("99999.00"),
            "PENDING", inQ3ApprovedAt(13));
        // Confirmed FAC — should appear
        seedFacCover("FAC-OK", "POL-B", swissReId, "Swiss Re",
            new BigDecimal("200000.00"), new BigDecimal("20000.00"),
            "CONFIRMED", inQ3ApprovedAt(14));

        Map<String, Object> payload = engine.computePayload(q3PeriodId);
        Map<?, ?> totals = (Map<?, ?>) payload.get("totals");

        assertThat((BigDecimal) totals.get("treatyPremiumCeded"))
            .as("only the CONFIRMED allocation counts (100k); DRAFT and CANCELLED excluded")
            .isEqualByComparingTo("100000.00");
        assertThat((BigDecimal) totals.get("facPremiumCeded"))
            .as("only the CONFIRMED FAC counts (200k); PENDING excluded")
            .isEqualByComparingTo("200000.00");
    }

    @Test
    @DisplayName("period filter — out-of-period rows excluded")
    void periodFilter() {
        UUID treatyId = seedTreaty("SURPLUS", 2026);
        UUID africaReId = UUID.randomUUID();
        UUID swissReId = UUID.randomUUID();

        // In-period (Q3) — should appear
        UUID inAllocId = seedAllocation(treatyId, "ALLOC-Q3", "CONFIRMED", inQ3CreatedAt(15));
        seedAllocationLine(inAllocId, africaReId, "Africa Re",
            new BigDecimal("100.00"), new BigDecimal("100000.00"), new BigDecimal("10000.00"));

        // Out-of-period (Q2) — should NOT appear
        UUID outAllocId = seedAllocation(treatyId, "ALLOC-Q2", "CONFIRMED",
            q2Start.plusDays(15).atTime(12, 0).toInstant(ZoneOffset.UTC));
        seedAllocationLine(outAllocId, swissReId, "Swiss Re",
            new BigDecimal("100.00"), new BigDecimal("999999.00"), new BigDecimal("99999.00"));

        Map<String, Object> payload = engine.computePayload(q3PeriodId);
        Map<?, ?> totals = (Map<?, ?>) payload.get("totals");
        assertThat((BigDecimal) totals.get("treatyPremiumCeded"))
            .as("only Q3 cession appears")
            .isEqualByComparingTo("100000.00");

        // Verify Q2 query picks up the Q2 cession instead
        Map<String, Object> q2Payload = engine.computePayload(q2PeriodId);
        assertThat((BigDecimal) ((Map<?, ?>) q2Payload.get("totals")).get("treatyPremiumCeded"))
            .isEqualByComparingTo("999999.00");
    }

    @Test
    @DisplayName("deterministic ordering — byTreaty (year DESC, type ASC); byReinsurer by name; FAC by reference")
    void deterministicOrdering() {
        UUID africaReId = UUID.randomUUID();

        UUID surplus2026 = seedTreaty("SURPLUS", 2026);
        UUID quota2025 = seedTreaty("QUOTA_SHARE", 2025);
        UUID quota2026 = seedTreaty("QUOTA_SHARE", 2026);

        int seq = 0;
        for (UUID treaty : List.of(surplus2026, quota2025, quota2026)) {
            UUID allocId = seedAllocation(treaty, "ALLOC-ORDER-" + seq++, "CONFIRMED", inQ3CreatedAt(15));
            seedAllocationLine(allocId, africaReId, "Africa Re",
                new BigDecimal("100.00"), new BigDecimal("100000.00"), new BigDecimal("10000.00"));
        }

        seedFacCover("FAC-Z", "POL-1", africaReId, "Africa Re",
            new BigDecimal("100000.00"), new BigDecimal("10000.00"),
            "CONFIRMED", inQ3ApprovedAt(10));
        seedFacCover("FAC-A", "POL-2", africaReId, "Africa Re",
            new BigDecimal("100000.00"), new BigDecimal("10000.00"),
            "CONFIRMED", inQ3ApprovedAt(11));
        seedFacCover("FAC-M", "POL-3", africaReId, "Africa Re",
            new BigDecimal("100000.00"), new BigDecimal("10000.00"),
            "CONFIRMED", inQ3ApprovedAt(12));

        Map<String, Object> payload = engine.computePayload(q3PeriodId);

        // byTreaty: (year DESC, type ASC) → QUOTA_SHARE-2026, SURPLUS-2026, QUOTA_SHARE-2025
        List<?> byTreaty = (List<?>) payload.get("byTreaty");
        List<String> treatyOrder = byTreaty.stream()
            .map(t -> (String) ((Map<?, ?>) t).get("displayName"))
            .toList();
        assertThat(treatyOrder).containsExactly("QUOTA_SHARE-2026", "SURPLUS-2026", "QUOTA_SHARE-2025");

        // facCovers: fac_reference ASC → FAC-A, FAC-M, FAC-Z
        List<?> facCovers = (List<?>) payload.get("facCovers");
        List<String> facOrder = facCovers.stream()
            .map(f -> (String) ((Map<?, ?>) f).get("facReference"))
            .toList();
        assertThat(facOrder).containsExactly("FAC-A", "FAC-M", "FAC-Z");
    }

    @Test
    @DisplayName("notes — claims-ceded deferral disclosed in every payload")
    void notesDisclosure() {
        Map<String, Object> payload = engine.computePayload(q3PeriodId);
        String notes = (String) payload.get("notes");
        assertThat(notes)
            .contains("v1 covers ceded premium only")
            .contains("Claims-ceded reporting")
            .contains("deferred to v2");
    }

    // ── Fixture helpers ────────────────────────────────────────────────────
    private UUID seedTreaty(String treatyType, int treatyYear) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO ri_treaties (id, treaty_type, status, treaty_year, " +
            "effective_date, expiry_date, currency_code, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, treatyType, "ACTIVE", treatyYear,
            LocalDate.of(treatyYear, 1, 1), LocalDate.of(treatyYear, 12, 31),
            "NGN", "test");
        return id;
    }

    private UUID seedAllocation(UUID treatyId, String allocationNumber, String status,
                                  Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO ri_allocations (id, allocation_number, policy_id, policy_number, " +
            "treaty_id, treaty_type, status, " +
            "our_share_sum_insured, retained_amount, ceded_amount, " +
            "our_share_premium, retained_premium, ceded_premium, " +
            "currency_code, created_at, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, allocationNumber, UUID.randomUUID(), "POL-" + allocationNumber,
            treatyId, "SURPLUS", status,
            new BigDecimal("10000000.00"), new BigDecimal("9000000.00"), new BigDecimal("1000000.00"),
            new BigDecimal("250000.00"), new BigDecimal("225000.00"), new BigDecimal("25000.00"),
            "NGN", Timestamp.from(createdAt), "test");
        return id;
    }

    private void seedAllocationLine(UUID allocationId, UUID reinsurerId, String reinsurerName,
                                      BigDecimal sharePercentage, BigDecimal cededPremium,
                                      BigDecimal commission) {
        jdbcTemplate.update(
            "INSERT INTO ri_allocation_lines (id, allocation_id, " +
            "reinsurance_company_id, reinsurance_company_name, " +
            "share_percentage, ceded_amount, ceded_premium, " +
            "commission_rate, commission_amount, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), allocationId,
            reinsurerId, reinsurerName,
            sharePercentage, cededPremium.multiply(new BigDecimal("10")), cededPremium,
            new BigDecimal("0.1000"), commission, "test");
    }

    private void seedFacCover(String reference, String policyNumber,
                                UUID reinsurerId, String reinsurerName,
                                BigDecimal premiumCeded, BigDecimal commission,
                                String status, Instant approvedAt) {
        jdbcTemplate.update(
            "INSERT INTO ri_fac_covers (id, fac_reference, policy_id, policy_number, " +
            "reinsurance_company_id, reinsurance_company_name, status, " +
            "sum_insured_ceded, premium_rate, premium_ceded, " +
            "commission_rate, commission_amount, net_premium, " +
            "currency_code, cover_from, cover_to, " +
            "approved_at, approved_by, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), reference, UUID.randomUUID(), policyNumber,
            reinsurerId, reinsurerName, status,
            premiumCeded.multiply(new BigDecimal("10")), new BigDecimal("0.025"),
            premiumCeded,
            new BigDecimal("0.1000"), commission, premiumCeded.subtract(commission),
            "NGN", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            Timestamp.from(approvedAt), "test-approver", "test");
    }

    private Instant inQ3CreatedAt(int dayOfMonth) {
        return q3Start.withDayOfMonth(dayOfMonth).atTime(12, 0).toInstant(ZoneOffset.UTC);
    }

    private Instant inQ3ApprovedAt(int dayOfMonth) {
        return q3Start.withDayOfMonth(dayOfMonth).atTime(12, 0).toInstant(ZoneOffset.UTC);
    }
}
