package com.nubeero.cia.api.quotation;

import static org.assertj.core.api.Assertions.assertThat;

import com.nubeero.cia.common.clause.ClauseSnapshot;
import com.nubeero.cia.common.config.CiaCommonAutoConfiguration;
import com.nubeero.cia.quotation.Quote;
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
 * Verifies the {@code quotes.selected_clauses} JSONB column (V73) maps to
 * {@code List<ClauseSnapshot>} on {@link Quote} — i.e. the frozen clause snapshot survives the
 * Hibernate {@code @JdbcTypeCode(JSON)} round-trip (a record-list in JSONB). Also confirms the
 * V72 clause seed is present (8 rows). Mirrors {@code QuoteRiskGrossPremiumColumnIT}.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CiaCommonAutoConfiguration.class)
class QuoteSelectedClausesIT {

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
        registry.add("spring.flyway.target", () -> "73");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    private UUID seedQuoteWithClauses(String clausesJson) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO quotes (id, quote_number, customer_id, customer_name, "
                + "product_id, product_name, product_code, product_rate, "
                + "class_of_business_id, class_of_business_name, policy_start_date, policy_end_date, "
                + "selected_clauses) VALUES (?,?,?,?,?,?,?,?,?,?,?,?, ?::jsonb)",
            id, "QT-2026-09001", UUID.randomUUID(), "Smith & Sons Ltd",
            UUID.randomUUID(), "Motor Comprehensive", "MOTOR", new BigDecimal("5.0000"),
            UUID.randomUUID(), "Motor", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            clausesJson);
        return id;
    }

    @Test
    void selectedClausesSnapshotRoundTripsThroughJsonb() {
        UUID id = seedQuoteWithClauses(
            "[{\"id\":\"00000000-0000-0000-0000-0000000000c1\",\"title\":\"Third Party Liability\","
                + "\"text\":\"Indemnity for third party...\",\"type\":\"STANDARD\"}]");
        em.clear();

        Quote q = em.find(Quote.class, id);
        assertThat(q.getSelectedClauses()).hasSize(1);
        ClauseSnapshot c = q.getSelectedClauses().get(0);
        assertThat(c.id()).isEqualTo("00000000-0000-0000-0000-0000000000c1");
        assertThat(c.title()).isEqualTo("Third Party Liability");
        assertThat(c.text()).isEqualTo("Indemnity for third party...");
        assertThat(c.type()).isEqualTo("STANDARD");
    }

    @Test
    void emptySnapshotDefaultsToEmptyList() {
        UUID id = seedQuoteWithClauses("[]");
        em.clear();
        assertThat(em.find(Quote.class, id).getSelectedClauses()).isEmpty();
    }

    @Test
    void clauseSeedIsPresent() {
        Long count = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM clauses WHERE deleted_at IS NULL").getSingleResult()).longValue();
        assertThat(count).isEqualTo(8L);
    }
}
