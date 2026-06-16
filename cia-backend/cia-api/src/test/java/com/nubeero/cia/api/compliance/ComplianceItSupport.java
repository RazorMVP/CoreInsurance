package com.nubeero.cia.api.compliance;

import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared base class for cia-compliance integration tests (NDPR DSAR + retention).
 *
 * <p>Mirrors {@code FinanceItSupport}: a single Postgres 16 Testcontainers
 * instance wired into the Spring test context via {@link DynamicPropertySource},
 * all Flyway migrations run to the compliance tip (V69 — the
 * {@code data_retention_policy} table), and multi-tenancy disabled
 * ({@code NONE}) because the IT schema is a single-tenant in-process database.
 *
 * <p>Beyond the finance base it additionally installs {@code pgcrypto} and pins
 * {@code app.pii_key} on every Hikari connection ({@code connection-init-sql}),
 * so the {@code @ColumnTransformer} {@code pgp_sym_encrypt}/{@code pgp_sym_decrypt}
 * round-trip works for the customer-PII reads/writes that Tasks 5 (DSAR export)
 * and 8 (retention purge) exercise on top of this base.
 *
 * @since Task 4 — NDPR retention-policy endpoint
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class ComplianceItSupport {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ciatest").withUsername("ciatest").withPassword("ciatest");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.target", () -> "70");   // was "69"
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
        registry.add("cia.security.pii-key", () -> "test-pii-key-do-not-use-in-prod");
        registry.add("spring.datasource.hikari.connection-init-sql",
                () -> "SET app.pii_key = 'test-pii-key-do-not-use-in-prod'");
    }

    @Autowired
    protected DataSource dataSource;

    @BeforeEach
    void installPgcrypto() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public");
        }
    }
}
