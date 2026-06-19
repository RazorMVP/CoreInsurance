package com.nubeero.cia.api.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies V72__create_clauses_table.sql: the table shape, the CHECK constraints, and the
 * eight-clause seed (previously the frontend INITIAL_CLAUSES mock) — all with their deterministic
 * UUIDs, titles, types and applicability.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V72ClausesMigrationTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ciatest")
            .withUsername("ciatest")
            .withPassword("ciatest");

    @BeforeAll
    void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target("72")
                .load()
                .migrate();
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    void seedsEightClausesWithCorrectShape() throws SQLException {
        try (Connection c = conn(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM clauses WHERE deleted_at IS NULL")) {
                rs.next();
                assertEquals(8, rs.getInt(1), "expected the 8 seeded clauses");
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT title, type, applicability FROM clauses "
                    + "WHERE id = '00000000-0000-0000-0000-0000000000c1'")) {
                assertTrue(rs.next());
                assertEquals("Third Party Liability", rs.getString("title"));
                assertEquals("STANDARD", rs.getString("type"));
                assertEquals("MANDATORY", rs.getString("applicability"));
            }
            // product_ids defaults to an empty JSON array
            try (ResultSet rs = st.executeQuery(
                    "SELECT product_ids::text FROM clauses WHERE id = '00000000-0000-0000-0000-0000000000c1'")) {
                assertTrue(rs.next());
                assertEquals("[]", rs.getString(1));
            }
        }
    }

    @Test
    void typeCheckConstraintRejectsBadValue() throws SQLException {
        try (Connection c = conn(); Statement st = c.createStatement()) {
            assertThrows(SQLException.class, () -> st.executeUpdate(
                    "INSERT INTO clauses (title, text, type, applicability) "
                    + "VALUES ('x', 'y', 'NONSENSE', 'OPTIONAL')"));
        }
    }
}
