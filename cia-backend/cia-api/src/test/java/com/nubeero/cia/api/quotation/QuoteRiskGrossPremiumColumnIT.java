package com.nubeero.cia.api.quotation;

import com.nubeero.cia.common.config.CiaCommonAutoConfiguration;
import com.nubeero.cia.quotation.QuoteRisk;
import jakarta.persistence.EntityManager;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression test for the {@code QuoteRisk.grossPremium} ↔ {@code quote_risks}
 * schema drift against a real PostgreSQL container.
 *
 * <p>{@link QuoteRisk} maps a {@code gross_premium} column (Hibernate default
 * naming on the {@code grossPremium} field — the per-item premium BEFORE
 * loadings/discounts, = {@code sum_insured × rate / 100}). No migration ever
 * created it: V5 created {@code quote_risks}; V22 added only
 * {@code rate}/{@code loadings}/{@code discounts}. Every Hibernate fetch of a
 * {@code QuoteRisk} therefore emits {@code SELECT ... r.gross_premium ...} and,
 * against a clean schema, fails with {@code column quote_risks.gross_premium
 * does not exist}. V65 adds the column; this IT is the empirical guard that the
 * entity mapping and the schema agree.
 *
 * <p>Two checks:
 * <ol>
 *   <li>a JPQL {@code SELECT r FROM QuoteRisk r} resolves (the column exists) —
 *       the direct reproduction of the reported failure;</li>
 *   <li>a JDBC-seeded row round-trips through JPA with {@code getGrossPremium()}
 *       returning the persisted value — the column maps to the right field.</li>
 * </ol>
 *
 * <p>Pattern mirrors {@code PolicyRmConstraintIT}: {@code @DataJpaTest} +
 * {@code @AutoConfigureTestDatabase(NONE)} + Testcontainers + Flyway target
 * pinned to V65. {@code @DataJpaTest} loads {@code CiaApplication}'s config
 * (package {@code com.nubeero.cia}), whose default entity scan covers the
 * cia-quotation entities, so the JPQL/managed-entity path exercises the real
 * mapping (raw JDBC alone would not surface a column-mapping drift).
 *
 * @since quote-risk-gross-premium-drift backlog fix
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CiaCommonAutoConfiguration.class)
class QuoteRiskGrossPremiumColumnIT {

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
        registry.add("spring.flyway.target", () -> "65");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager em;

    /** Insert a minimal quotes row satisfying every NOT NULL column without a default. */
    private UUID seedQuote() {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO quotes ("
                + "id, quote_number, customer_id, customer_name, "
                + "product_id, product_name, product_code, product_rate, "
                + "class_of_business_id, class_of_business_name, "
                + "policy_start_date, policy_end_date"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, "QT-2026-00001", UUID.randomUUID(), "Acme Ltd",
            UUID.randomUUID(), "Motor Comprehensive", "MOTOR", new BigDecimal("5.0000"),
            UUID.randomUUID(), "Motor",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        return id;
    }

    /** Insert a quote_risks row, setting gross_premium explicitly (V65 column). */
    private void seedRisk(UUID quoteId, BigDecimal sumInsured, BigDecimal rate,
                          BigDecimal grossPremium, BigDecimal premium) {
        jdbc.update(
            "INSERT INTO quote_risks ("
                + "id, quote_id, description, sum_insured, rate, gross_premium, premium, order_no"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), quoteId, "Toyota Corolla 2024",
            sumInsured, rate, grossPremium, premium, 0);
    }

    @Test
    void quoteRiskSelectResolvesGrossPremiumColumn() {
        assertThatCode(() ->
            em.createQuery("SELECT r FROM QuoteRisk r", QuoteRisk.class).getResultList())
            .doesNotThrowAnyException();
    }

    @Test
    void grossPremiumPersistsAndReads() {
        UUID quoteId = seedQuote();
        // gross = 1,000,000 × 5% = 50,000.00 (the QuoteService basis); net 47,500.00
        seedRisk(quoteId, new BigDecimal("1000000.00"), new BigDecimal("5.0000"),
                 new BigDecimal("50000.00"), new BigDecimal("47500.00"));
        em.clear();

        List<QuoteRisk> risks =
            em.createQuery("SELECT r FROM QuoteRisk r", QuoteRisk.class).getResultList();

        assertThat(risks).hasSize(1);
        assertThat(risks.get(0).getGrossPremium()).isEqualByComparingTo("50000.00");
        assertThat(risks.get(0).getPremium()).isEqualByComparingTo("47500.00");
    }
}
