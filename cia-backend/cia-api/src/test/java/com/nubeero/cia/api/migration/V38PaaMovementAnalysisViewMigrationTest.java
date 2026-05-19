package com.nubeero.cia.api.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies V38__create_paa_movement_analysis_view.sql applies cleanly and
 * the view exposes the expected disclosure columns. Pure JDBC + Flyway +
 * Testcontainers — no Spring context.
 *
 * <p>The view is a SELECT-only derivation over paa_lrc + paa_lic + group +
 * portfolio. We assert: (a) the view exists, (b) it carries the §103
 * disclosure columns, (c) selecting from it with no underlying data
 * returns zero rows (the WHERE filter excludes inactive groups).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V38PaaMovementAnalysisViewMigrationTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ciatest")
            .withUsername("ciatest")
            .withPassword("ciatest");

    @BeforeAll
    void migrate() {
        POSTGRES.start();
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target("38")
            .load()
            .migrate();
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    @DisplayName("paa_movement_analysis view exists after V38")
    void viewExists() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT 1 FROM information_schema.views " +
                 "WHERE table_schema = 'public' AND table_name = 'paa_movement_analysis'")) {
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next(), "expected paa_movement_analysis view to exist after V38");
        }
    }

    @Test
    @DisplayName("paa_movement_analysis exposes core §103 disclosure columns")
    void viewExposesDisclosureColumns() throws SQLException {
        String[] expectedColumns = {
            "period_id", "period_start", "period_end",
            "portfolio_id", "portfolio_code", "group_id", "cohort_year", "onerousness",
            "lrc_opening", "premium_received", "premium_earned", "lrc_closing",
            "lic_opening", "claims_incurred", "claims_paid", "lic_closing",
            "loss_component", "loss_component_change",
            "discount_unwind", "risk_adjustment",
            "total_opening", "total_closing", "currency_code"
        };
        for (String column : expectedColumns) {
            try (Connection c = conn();
                 PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM information_schema.columns " +
                     "WHERE table_schema = 'public' AND table_name = 'paa_movement_analysis' " +
                     "AND column_name = ?")) {
                ps.setString(1, column);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next(), "expected column " + column + " on paa_movement_analysis view");
            }
        }
    }

    @Test
    @DisplayName("empty paa_lrc + paa_lic → view returns zero rows")
    void emptyTablesReturnNoRows() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM paa_movement_analysis")) {
            ResultSet rs = ps.executeQuery();
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
    }
}
