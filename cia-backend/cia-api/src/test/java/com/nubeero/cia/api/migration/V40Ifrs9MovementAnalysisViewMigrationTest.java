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
 * Verifies V40__create_ifrs9_movement_analysis_view.sql applies cleanly
 * and exposes the §B5.5.39 disclosure columns. Pure JDBC + Flyway +
 * Testcontainers; no Spring.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V40Ifrs9MovementAnalysisViewMigrationTest {

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
            .target("40")
            .load()
            .migrate();
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    @DisplayName("ifrs9_investment_movement_analysis view exists after V40")
    void viewExists() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT 1 FROM information_schema.views " +
                 "WHERE table_schema = 'public' AND table_name = 'ifrs9_investment_movement_analysis'")) {
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next(), "expected ifrs9_investment_movement_analysis view to exist after V40");
        }
    }

    @Test
    @DisplayName("view exposes §B5.5.39 disclosure columns")
    void viewExposesDisclosureColumns() throws SQLException {
        String[] expected = {
            "period_id", "period_start", "period_end",
            "holding_id", "isin", "security_name", "issuer",
            "asset_type", "classification", "holding_status", "currency_code", "maturity_date",
            "opening_balance", "effective_interest_income", "coupon_received",
            "fair_value_change_pnl", "fair_value_change_oci",
            "ecl_movement", "impairment_loss", "disposals",
            "closing_balance", "closing_fair_value", "ecl_stage",
            "total_pnl_income", "total_oci_movement"
        };
        for (String column : expected) {
            try (Connection c = conn();
                 PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM information_schema.columns " +
                     "WHERE table_schema = 'public' AND table_name = 'ifrs9_investment_movement_analysis' " +
                     "AND column_name = ?")) {
                ps.setString(1, column);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next(), "expected column " + column + " on view");
            }
        }
    }

    @Test
    @DisplayName("empty investment_carrying_value → view returns zero rows")
    void emptyTablesReturnNoRows() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM ifrs9_investment_movement_analysis")) {
            ResultSet rs = ps.executeQuery();
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
    }
}
