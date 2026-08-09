package com.nubeero.cia.api.finance.paa;

import com.nubeero.cia.common.event.FacPremiumCededEvent;
import com.nubeero.cia.common.event.RiFacInwardAcceptedEvent;
import com.nubeero.cia.finance.paa.ContractGroupAssignment;
import com.nubeero.cia.finance.paa.ContractGroupAssignmentRepository;
import com.nubeero.cia.finance.paa.ContractGroupingService;
import com.nubeero.cia.finance.paa.ContractNature;
import com.nubeero.cia.finance.paa.ContractType;
import com.nubeero.cia.finance.gl.PolicyClassResolver;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Testcontainers IT for the FAC / IFRS-17 PAA workstream Task 2
 * grouping listeners on {@link ContractGroupingService} —
 * {@code onFacInwardAccepted} and {@code onFacPremiumCeded}.
 *
 * <p>Publishes the two FAC events via the real Spring
 * {@link ApplicationEventPublisher} (rather than calling the listener
 * methods directly) so the {@code @EventListener} wiring itself is exercised,
 * then asserts on {@link ContractGroupAssignmentRepository} — mirrors the
 * harness of {@code ContractGroupingServiceIT} (same {@code @DataJpaTest} +
 * {@code @AutoConfigureTestDatabase.NONE} + explicit {@code @Import} +
 * Testcontainers Postgres pattern, Flyway pinned to V77).
 *
 * <p>Both FAC listeners resolve their coverage-start year via a scalar
 * native-SQL read against {@code ri_fac_inwards.cover_from} /
 * {@code ri_fac_covers.cover_from} — so each test seeds the relevant row
 * with the event's id as the primary key <em>before</em> publishing.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    ContractGroupingService.class,
    PolicyClassResolver.class
})
class FacContractGroupingIT {

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
        registry.add("spring.flyway.target", () -> "77");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private ContractGroupAssignmentRepository assignmentRepository;

    private UUID motorCobId;

    @BeforeEach
    void seedClassOfBusiness() {
        motorCobId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO classes_of_business (id, name, code, description, created_by) " +
            "VALUES (?, ?, ?, ?, ?)",
            motorCobId, "Motor Comprehensive", "MOTOR-COMP", "Motor comp test", "test");
    }

    // ── 1. Inward FAC groups into a FAC_INWARD-nature portfolio ──────────────
    @Test
    @DisplayName("RiFacInwardAcceptedEvent groups the inward FAC into a FAC_INWARD portfolio")
    void inwardFacGroupsIntoFacInwardPortfolio() {
        UUID facInwardId = UUID.randomUUID();
        seedFacInward(facInwardId, motorCobId, LocalDate.of(2026, 1, 1));

        publisher.publishEvent(inwardAcceptedEvent(facInwardId, motorCobId));
        entityManager.flush();

        ContractGroupAssignment assignment = assignmentRepository
            .findByContractTypeAndContractIdAndDeletedAtIsNull(ContractType.FAC_INWARD, facInwardId)
            .orElseThrow();

        assertThat(assignment.getGroup().getPortfolio().getContractNature())
            .isEqualTo(ContractNature.FAC_INWARD);
        assertThat(assignment.getGroup().getCohortYear()).isEqualTo(2026);
        assertThat(assignment.getGroup().getPortfolio().getCode()).isEqualTo("FIN-MOTOR-COMP");
    }

    // ── 2. Outward FAC groups into a FAC_OUTWARD-nature portfolio ────────────
    @Test
    @DisplayName("FacPremiumCededEvent groups the outward FAC into a FAC_OUTWARD portfolio")
    void outwardFacGroupsIntoFacOutwardPortfolio() {
        UUID policyId = seedPolicy("POL-FAC-OUT-001", motorCobId, LocalDate.of(2026, 2, 1));
        UUID facCoverId = UUID.randomUUID();
        seedFacCover(facCoverId, policyId, "POL-FAC-OUT-001", LocalDate.of(2026, 2, 1));

        publisher.publishEvent(premiumCededEvent(facCoverId, policyId, "POL-FAC-OUT-001"));
        entityManager.flush();

        ContractGroupAssignment assignment = assignmentRepository
            .findByContractTypeAndContractIdAndDeletedAtIsNull(ContractType.FAC_OUTWARD, facCoverId)
            .orElseThrow();

        assertThat(assignment.getGroup().getPortfolio().getContractNature())
            .isEqualTo(ContractNature.FAC_OUTWARD);
        assertThat(assignment.getGroup().getCohortYear()).isEqualTo(2026);
        assertThat(assignment.getGroup().getPortfolio().getCode()).isEqualTo("FOU-MOTOR-COMP");
    }

    // ── 3. Re-firing either event is idempotent (one assignment row) ─────────
    @Test
    @DisplayName("re-firing the same FAC event is idempotent (no duplicate assignment row)")
    void reFireIsIdempotent() {
        UUID facInwardId = UUID.randomUUID();
        seedFacInward(facInwardId, motorCobId, LocalDate.of(2026, 3, 1));
        RiFacInwardAcceptedEvent event = inwardAcceptedEvent(facInwardId, motorCobId);

        publisher.publishEvent(event);
        entityManager.flush();
        publisher.publishEvent(event);
        entityManager.flush();

        Long count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM contract_group_assignment WHERE contract_type = 'FAC_INWARD' AND contract_id = ?",
            Long.class, facInwardId);
        assertThat(count).isEqualTo(1L);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void seedFacInward(UUID id, UUID cobId, LocalDate coverFrom) {
        jdbcTemplate.update(
            "INSERT INTO ri_fac_inwards (id, fac_inward_reference, ceding_company_id, ceding_company_name, " +
            "class_of_business_id, class_of_business_name, status, sum_insured, our_share_pct, " +
            "accepted_sum_insured, premium_rate, gross_premium, commission_rate, commission_amount, " +
            "net_premium, currency_code, cover_from, cover_to, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, "FIN-2026-0001", UUID.randomUUID(), "Test Ceding Co",
            cobId, "Motor Comprehensive", "ACTIVE", new BigDecimal("1000000.00"), new BigDecimal("50.0000"),
            new BigDecimal("500000.00"), new BigDecimal("2.500000"), new BigDecimal("12500.00"),
            new BigDecimal("10.0000"), new BigDecimal("1250.00"),
            new BigDecimal("11250.00"), "NGN", coverFrom, coverFrom.plusYears(1), "test");
    }

    private void seedFacCover(UUID id, UUID policyId, String policyNumber, LocalDate coverFrom) {
        jdbcTemplate.update(
            "INSERT INTO ri_fac_covers (id, fac_reference, policy_id, policy_number, " +
            "reinsurance_company_id, reinsurance_company_name, status, sum_insured_ceded, premium_rate, " +
            "premium_ceded, commission_rate, commission_amount, net_premium, currency_code, " +
            "cover_from, cover_to, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, "FOU-2026-0001", policyId, policyNumber,
            UUID.randomUUID(), "Test Reinsurer", "APPROVED", new BigDecimal("500000.00"), new BigDecimal("2.500000"),
            new BigDecimal("12500.00"), new BigDecimal("10.0000"), new BigDecimal("1250.00"), new BigDecimal("11250.00"),
            "NGN", coverFrom, coverFrom.plusYears(1), "test");
    }

    private UUID seedPolicy(String policyNumber, UUID cobId, LocalDate startDate) {
        UUID policyId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO policies (id, policy_number, customer_id, customer_name, " +
            "product_id, product_name, product_code, product_rate, " +
            "class_of_business_id, class_of_business_name, class_of_business_code, " +
            "policy_start_date, policy_end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            policyId, policyNumber, UUID.randomUUID(), "Test Customer",
            UUID.randomUUID(), "Test Product", "PROD-TEST", new BigDecimal("0.0500"),
            cobId, "Motor Comprehensive", "MOTOR-COMP",
            startDate, startDate.plusYears(1), "APPROVED", "test");
        return policyId;
    }

    private RiFacInwardAcceptedEvent inwardAcceptedEvent(UUID facInwardId, UUID cobId) {
        return new RiFacInwardAcceptedEvent(
            facInwardId, "FIN-2026-0001", UUID.randomUUID(), "Test Ceding Co",
            cobId, new BigDecimal("12500.00"), new BigDecimal("1250.00"), new BigDecimal("11250.00"), "NGN");
    }

    private FacPremiumCededEvent premiumCededEvent(UUID facCoverId, UUID policyId, String policyNumber) {
        return new FacPremiumCededEvent(
            facCoverId, "FOU-2026-0001", policyId, policyNumber,
            UUID.randomUUID(), "Test Reinsurer",
            new BigDecimal("12500.00"), new BigDecimal("1250.00"), new BigDecimal("11250.00"), "NGN");
    }
}
