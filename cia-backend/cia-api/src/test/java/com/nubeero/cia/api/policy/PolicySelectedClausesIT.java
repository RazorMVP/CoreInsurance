package com.nubeero.cia.api.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.nubeero.cia.common.clause.ClauseSnapshot;
import com.nubeero.cia.common.config.CiaCommonAutoConfiguration;
import com.nubeero.cia.policy.Policy;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
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

/**
 * Verifies the {@code policies.selected_clauses} JSONB column (V74) maps to
 * {@code List<ClauseSnapshot>} on {@link Policy} — the frozen clause snapshot survives the
 * Hibernate {@code @JdbcTypeCode(JSON)} round-trip. Mirrors {@code QuoteSelectedClausesIT}.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CiaCommonAutoConfiguration.class)
class PolicySelectedClausesIT {

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
        registry.add("spring.flyway.target", () -> "74");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    private UUID seedPolicy(String clausesJson) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO policies (id, policy_number, customer_id, customer_name, "
                + "product_id, product_name, product_code, product_rate, "
                + "class_of_business_id, class_of_business_name, class_of_business_code, "
                + "business_type, policy_start_date, policy_end_date, selected_clauses) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?, ?::jsonb)",
            id, "POL-2026-09001", UUID.randomUUID(), "Smith & Sons Ltd",
            UUID.randomUUID(), "Motor Comprehensive", "MOTOR", new BigDecimal("5.0000"),
            UUID.randomUUID(), "Motor", "MOT", "DIRECT",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), clausesJson);
        return id;
    }

    @Test
    void selectedClausesSnapshotRoundTripsThroughJsonb() {
        UUID id = seedPolicy(
            "[{\"id\":\"00000000-0000-0000-0000-0000000000c3\",\"title\":\"Exclusion — Racing\","
                + "\"text\":\"No cover whilst racing.\",\"type\":\"EXCLUSION\"}]");
        em.clear();

        Policy p = em.find(Policy.class, id);
        assertThat(p.getSelectedClauses()).hasSize(1);
        ClauseSnapshot c = p.getSelectedClauses().get(0);
        assertThat(c.title()).isEqualTo("Exclusion — Racing");
        assertThat(c.text()).isEqualTo("No cover whilst racing.");
        assertThat(c.type()).isEqualTo("EXCLUSION");
    }

    @Test
    void emptySnapshotDefaultsToEmptyList() {
        UUID id = seedPolicy("[]");
        em.clear();
        assertThat(em.find(Policy.class, id).getSelectedClauses()).isEmpty();
    }
}
