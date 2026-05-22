package com.nubeero.cia.api.finance.paa;

import com.nubeero.cia.common.event.PolicyApprovedEvent;
import com.nubeero.cia.finance.paa.ContractGroupingService;
import com.nubeero.cia.finance.paa.GroupOfContractsRepository;
import com.nubeero.cia.finance.paa.GroupStatus;
import com.nubeero.cia.finance.paa.Onerousness;
import com.nubeero.cia.finance.paa.PolicyGroupAssignment;
import com.nubeero.cia.finance.paa.PolicyGroupAssignmentRepository;
import com.nubeero.cia.finance.paa.PortfolioRepository;
import jakarta.persistence.EntityManager;
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
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Testcontainers IT for {@link ContractGroupingService}. Each test
 * builds a {@link PolicyApprovedEvent}, hands it to the service (mimicking the
 * Spring event publisher) and verifies the resulting state across three tables:
 * {@code portfolio}, {@code group_of_contracts}, {@code policy_group_assignment}.
 *
 * <p>Schema: Flyway runs through V37 so all the PAA tables (V36) and the
 * link table (V37) exist. A class_of_business + policy row are seeded per
 * test via {@code JdbcTemplate} so the {@link
 * com.nubeero.cia.setup.product.ClassOfBusinessRepository} lookup resolves
 * and the FK from {@code policy_group_assignment.policy_id → policies.id}
 * is satisfied.
 *
 * <p>Pattern lifted from {@code SubledgerPostingServiceIT} (Slice 1.5) —
 * @DataJpaTest + AutoConfigureTestDatabase.NONE + explicit @Import for the
 * service under test.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    ContractGroupingService.class
})
class ContractGroupingServiceIT {

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
        registry.add("spring.flyway.target", () -> "49");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired private ContractGroupingService service;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private PortfolioRepository portfolioRepository;
    @Autowired private GroupOfContractsRepository groupRepository;
    @Autowired private PolicyGroupAssignmentRepository assignmentRepository;

    private UUID motorCobId;

    @BeforeEach
    void seedClassOfBusiness() {
        motorCobId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO classes_of_business (id, name, code, description, created_by) " +
            "VALUES (?, ?, ?, ?, ?)",
            motorCobId, "Motor Comprehensive", "MOTOR-COMP", "Motor comp test", "test");
    }

    // ── 1. Lazy portfolio + group + assignment on first event ─────────────────
    @Test
    @DisplayName("first PolicyApprovedEvent lazy-creates portfolio + group + writes assignment")
    void lazyCreatesAll() {
        UUID policyId = seedPolicy("POL-LAZY-001", motorCobId, LocalDate.of(2026, 3, 15));

        service.onPolicyApproved(buildEvent(policyId, "POL-LAZY-001", motorCobId,
            LocalDate.of(2026, 3, 15), new BigDecimal("500000.00")));
        entityManager.flush();

        var portfolios = portfolioRepository.findByClassOfBusinessIdAndDeletedAtIsNullOrderByCodeAsc(motorCobId);
        assertThat(portfolios).hasSize(1);
        assertThat(portfolios.get(0).getCode()).isEqualTo("COB-MOTOR-COMP");
        assertThat(portfolios.get(0).getName()).isEqualTo("Motor Comprehensive");
        assertThat(portfolios.get(0).getClassOfBusinessId()).isEqualTo(motorCobId);

        var group = groupRepository.findByPortfolioIdAndCohortYearAndOnerousnessAndDeletedAtIsNull(
            portfolios.get(0).getId(), 2026, Onerousness.NOT_ONEROUS);
        assertThat(group).isPresent();
        assertThat(group.get().getStatus()).isEqualTo(GroupStatus.OPEN);

        var assignment = assignmentRepository.findByPolicyIdAndDeletedAtIsNull(policyId);
        assertThat(assignment).isPresent();
        assertThat(assignment.get().getGroup().getId()).isEqualTo(group.get().getId());
    }

    // ── 2. Second policy of same (class, year) reuses portfolio + group ──────
    @Test
    @DisplayName("second policy of same class + cohort reuses portfolio and group")
    void reusesPortfolioAndGroup() {
        UUID policyId1 = seedPolicy("POL-REUSE-001", motorCobId, LocalDate.of(2026, 4, 1));
        UUID policyId2 = seedPolicy("POL-REUSE-002", motorCobId, LocalDate.of(2026, 6, 1));

        service.onPolicyApproved(buildEvent(policyId1, "POL-REUSE-001", motorCobId,
            LocalDate.of(2026, 4, 1), new BigDecimal("100000.00")));
        service.onPolicyApproved(buildEvent(policyId2, "POL-REUSE-002", motorCobId,
            LocalDate.of(2026, 6, 1), new BigDecimal("200000.00")));
        entityManager.flush();

        assertThat(portfolioRepository.findByClassOfBusinessIdAndDeletedAtIsNullOrderByCodeAsc(motorCobId))
            .hasSize(1);
        assertThat(groupRepository.findByCohortYearAndDeletedAtIsNullOrderByPortfolioIdAsc(2026))
            .hasSize(1);
        assertThat(assignmentRepository.findByPolicyIdAndDeletedAtIsNull(policyId1)).isPresent();
        assertThat(assignmentRepository.findByPolicyIdAndDeletedAtIsNull(policyId2)).isPresent();
    }

    // ── 3. Different cohort year creates new group under same portfolio ──────
    @Test
    @DisplayName("policy in a new cohort year creates a new group under the same portfolio")
    void newCohortNewGroup() {
        UUID policyId1 = seedPolicy("POL-COHORT-1", motorCobId, LocalDate.of(2026, 6, 1));
        UUID policyId2 = seedPolicy("POL-COHORT-2", motorCobId, LocalDate.of(2027, 6, 1));

        service.onPolicyApproved(buildEvent(policyId1, "POL-COHORT-1", motorCobId,
            LocalDate.of(2026, 6, 1), new BigDecimal("100000.00")));
        service.onPolicyApproved(buildEvent(policyId2, "POL-COHORT-2", motorCobId,
            LocalDate.of(2027, 6, 1), new BigDecimal("100000.00")));
        entityManager.flush();

        // One portfolio for the class, two groups (2026, 2027)
        var portfolios = portfolioRepository.findByClassOfBusinessIdAndDeletedAtIsNullOrderByCodeAsc(motorCobId);
        assertThat(portfolios).hasSize(1);

        var groups = jdbcTemplate.queryForList(
            "SELECT cohort_year FROM group_of_contracts WHERE portfolio_id = ? AND deleted_at IS NULL ORDER BY cohort_year",
            Integer.class, portfolios.get(0).getId());
        assertThat(groups).containsExactly(2026, 2027);
    }

    // ── 4. Idempotency: re-firing returns the same assignment, no duplicate ──
    @Test
    @DisplayName("re-firing the same PolicyApprovedEvent is idempotent (no duplicate row)")
    void idempotencyOnReplay() {
        UUID policyId = seedPolicy("POL-IDP-001", motorCobId, LocalDate.of(2026, 5, 1));
        PolicyApprovedEvent event = buildEvent(policyId, "POL-IDP-001", motorCobId,
            LocalDate.of(2026, 5, 1), new BigDecimal("100000.00"));

        PolicyGroupAssignment first = service.replayPolicyApproved(event);
        entityManager.flush();
        PolicyGroupAssignment second = service.replayPolicyApproved(event);
        entityManager.flush();

        assertThat(second.getId()).isEqualTo(first.getId());

        Long count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM policy_group_assignment WHERE policy_id = ?",
            Long.class, policyId);
        assertThat(count).isEqualTo(1L);
    }

    // ── 5. Null class-of-business falls back to UNCLASSIFIED portfolio ───────
    @Test
    @DisplayName("null class_of_business_id falls back to UNCLASSIFIED portfolio")
    void nullClassFallsBackToUnclassified() {
        UUID policyId = seedPolicy("POL-NULL-COB", motorCobId, LocalDate.of(2026, 5, 1));
        // The policies row carries motorCobId but the event we build below
        // omits class_of_business_id — simulates an upstream publisher
        // that fails to populate it.
        service.onPolicyApproved(new PolicyApprovedEvent(
            policyId, "POL-NULL-COB", UUID.randomUUID(), "Acme", null, null,
            "Motor", new BigDecimal("100000.00"), "NGN",
            LocalDate.of(2027, 5, 14),
            UUID.randomUUID(),
            null, // class_of_business_id intentionally null
            new BigDecimal("10000000.00"),
            LocalDate.of(2026, 5, 1),
            null, null, null, null));
        entityManager.flush();

        var portfolio = portfolioRepository.findByCodeAndDeletedAtIsNull("UNCLASSIFIED");
        assertThat(portfolio).isPresent();
        assertThat(portfolio.get().getClassOfBusinessId()).isNull();

        var assignment = assignmentRepository.findByPolicyIdAndDeletedAtIsNull(policyId);
        assertThat(assignment).isPresent();
        assertThat(assignment.get().getGroup().getPortfolio().getCode()).isEqualTo("UNCLASSIFIED");
    }

    // ── 6. Unknown class-of-business id also falls back to UNCLASSIFIED ──────
    @Test
    @DisplayName("unknown class_of_business_id falls back to UNCLASSIFIED portfolio")
    void unknownClassFallsBackToUnclassified() {
        UUID policyId = seedPolicy("POL-UNK-COB", motorCobId, LocalDate.of(2026, 5, 1));
        UUID unknownCobId = UUID.randomUUID();

        service.onPolicyApproved(buildEvent(policyId, "POL-UNK-COB", unknownCobId,
            LocalDate.of(2026, 5, 1), new BigDecimal("100000.00")));
        entityManager.flush();

        var portfolio = portfolioRepository.findByCodeAndDeletedAtIsNull("UNCLASSIFIED");
        assertThat(portfolio).isPresent();

        var assignment = assignmentRepository.findByPolicyIdAndDeletedAtIsNull(policyId);
        assertThat(assignment).isPresent();
        assertThat(assignment.get().getGroup().getPortfolio().getCode()).isEqualTo("UNCLASSIFIED");
    }

    // ── 7. Auto-portfolio code truncated when class-of-business code is long ─
    @Test
    @DisplayName("auto-portfolio code is truncated to fit VARCHAR(20)")
    void autoCodeTruncatedToFit() {
        // class-of-business code = 18 chars → "COB-" + 18 = 22, exceeds VARCHAR(20).
        // Service must truncate the suffix to 16 chars so total is exactly 20.
        UUID longCobId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO classes_of_business (id, name, code, description, created_by) " +
            "VALUES (?, ?, ?, ?, ?)",
            longCobId, "Very Long Name", "VERY-LONG-COB-1234", "long code test", "test");

        UUID policyId = seedPolicy("POL-LONG-COB", longCobId, LocalDate.of(2026, 7, 1));
        service.onPolicyApproved(buildEvent(policyId, "POL-LONG-COB", longCobId,
            LocalDate.of(2026, 7, 1), new BigDecimal("50000.00")));
        entityManager.flush();

        var portfolios = portfolioRepository.findByClassOfBusinessIdAndDeletedAtIsNullOrderByCodeAsc(longCobId);
        assertThat(portfolios).hasSize(1);
        // "COB-" (4) + first 16 chars of "VERY-LONG-COB-1234" = "COB-VERY-LONG-COB-12"
        // Exactly 20 chars = VARCHAR(20) limit.
        assertThat(portfolios.get(0).getCode())
            .as("auto-portfolio code must fit VARCHAR(20)")
            .hasSize(20)
            .isEqualTo("COB-VERY-LONG-COB-12");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

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

    private PolicyApprovedEvent buildEvent(UUID policyId, String policyNumber, UUID cobId,
                                            LocalDate startDate, BigDecimal netPremium) {
        return new PolicyApprovedEvent(
            policyId, policyNumber, UUID.randomUUID(), "Test Customer",
            null, null,
            "Test Product", netPremium, "NGN",
            startDate.plusYears(1),
            UUID.randomUUID(),
            cobId,
            new BigDecimal("10000000.00"),
            startDate,
            null, null, null, null);
    }

}
