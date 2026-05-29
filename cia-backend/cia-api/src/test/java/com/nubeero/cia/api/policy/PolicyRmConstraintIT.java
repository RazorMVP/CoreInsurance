package com.nubeero.cia.api.policy;

import com.nubeero.cia.common.config.CiaCommonAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for the V62 commission-source constraints on {@code policies}
 * against a real PostgreSQL container.
 *
 * <p>Verifies:
 * <ol>
 *   <li>{@code ck_policies_commission_source_one} — at most one of
 *       broker_id / agent_id / relationship_manager_id may be non-null.</li>
 *   <li>{@code ck_policies_rm_source_requires_rm} — when
 *       commission_source_type = 'RELATIONSHIP_MANAGER', relationship_manager_id
 *       must be present.</li>
 *   <li>A valid RM-attributed policy (rm_id + source + rate) persists.</li>
 * </ol>
 *
 * <p>Pattern mirrors {@code TenantNotificationTemplateRepositoryIT} /
 * {@code FinanceItSupport}: {@code @DataJpaTest} +
 * {@code @AutoConfigureTestDatabase(NONE)} + Testcontainers + Flyway target
 * pinned to V62 (the migration that introduces these CHECKs). Raw JDBC inserts
 * exercise the CHECKs directly without the full Policy aggregate.
 *
 * @since B2 Task 1.3 — RM commission constraint smoke-tests
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CiaCommonAutoConfiguration.class)
class PolicyRmConstraintIT {

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
        registry.add("spring.flyway.target", () -> "62");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired
    JdbcTemplate jdbc;

    /** Seed a relationship_managers row (only name is NOT NULL w/o default), return its id. */
    private UUID seedRm(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO relationship_managers (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    /**
     * Insert a policies row satisfying every NOT NULL column (defaults cover status,
     * business_type, niid_required, the financial columns, and the audit columns).
     * Only the commission-source columns + commission_source_type/rate vary per test,
     * so any DataIntegrityViolationException surfaces on the targeted CHECK.
     */
    private void insertPolicy(UUID brokerId, UUID agentId, UUID rmId,
                              String commissionSourceType, BigDecimal rate) {
        jdbc.update(
            "INSERT INTO policies ("
                + "customer_id, customer_name, "
                + "product_id, product_name, product_code, product_rate, "
                + "class_of_business_id, class_of_business_name, class_of_business_code, "
                + "policy_start_date, policy_end_date, "
                + "broker_id, agent_id, relationship_manager_id, "
                + "commission_source_type, commission_rate"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), "Acme Ltd",
            UUID.randomUUID(), "Motor Comprehensive", "MOTOR", new BigDecimal("5.0000"),
            UUID.randomUUID(), "Motor", "MOT",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            brokerId, agentId, rmId,
            commissionSourceType, rate);
    }

    @Test
    void rejectsTwoOfThreeSources() {
        assertThatThrownBy(() ->
            insertPolicy(UUID.randomUUID(), UUID.randomUUID(), null, null, null))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_policies_commission_source_one");
    }

    @Test
    void rejectsRmSourceWithoutRmId() {
        assertThatThrownBy(() ->
            insertPolicy(null, null, null, "RELATIONSHIP_MANAGER", new BigDecimal("2.5000")))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_policies_rm_source_requires_rm");
    }

    @Test
    void acceptsRmSourceWithRmId() {
        UUID rmId = seedRm("Ada RM");

        insertPolicy(null, null, rmId, "RELATIONSHIP_MANAGER", new BigDecimal("2.5000"));

        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM policies WHERE relationship_manager_id = ?",
            Integer.class, rmId);
        assertThat(n).isEqualTo(1);
    }
}
