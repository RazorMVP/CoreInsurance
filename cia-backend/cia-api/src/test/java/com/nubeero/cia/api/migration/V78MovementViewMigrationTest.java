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
 * Verifies V78__movement_view_contract_nature.sql applies cleanly and the
 * recreated {@code paa_movement_analysis} view (V38 body, verbatim) now
 * carries a trailing {@code contract_nature} column sourced from
 * {@code portfolio.contract_nature} (V76). Pure JDBC + Flyway +
 * Testcontainers — no Spring context, mirrors
 * {@code V38PaaMovementAnalysisViewMigrationTest}'s harness shape.
 *
 * <p>FAC / IFRS-17 PAA workstream Task 6 — downstream {@code contract_nature}
 * surfacing. {@code CREATE OR REPLACE VIEW} can only APPEND new output
 * columns at the end of the SELECT list (PostgreSQL rejects reordering /
 * removing existing columns), so V78 is a verbatim copy of V38's body with
 * {@code p.contract_nature AS contract_nature} appended last.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V78MovementViewMigrationTest {

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
            .target("78")
            .load()
            .migrate();
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    @DisplayName("paa_movement_analysis view still exists after V78 (recreated, not dropped)")
    void viewStillExists() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT 1 FROM information_schema.views " +
                 "WHERE table_schema = 'public' AND table_name = 'paa_movement_analysis'")) {
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next(), "expected paa_movement_analysis view to still exist after V78");
        }
    }

    @Test
    @DisplayName("SELECT contract_nature FROM paa_movement_analysis succeeds (column present)")
    void selectContractNatureSucceeds() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT contract_nature FROM paa_movement_analysis LIMIT 0")) {
            // No exception on execute == the column resolves.
            ps.executeQuery();
        }
    }

    @Test
    @DisplayName("every pre-existing V38 disclosure column is still present after V78")
    void priorDisclosureColumnsUnchanged() throws SQLException {
        // Full V38 column set (31 columns), in SELECT order, plus V78's trailing
        // contract_nature (32nd). Every one of these must resolve — this is the
        // regression guard that V78's CREATE OR REPLACE VIEW didn't drop or
        // reorder anything V38 already shipped.
        String[] expectedColumns = {
            "period_id", "period_start", "period_end",
            "portfolio_id", "portfolio_code", "portfolio_name",
            "group_id", "cohort_year", "onerousness", "group_status",
            "lrc_opening", "premium_received", "premium_earned",
            "acquisition_costs_deferred", "acquisition_costs_amortised",
            "loss_component", "loss_component_change", "lrc_closing",
            "lic_opening", "claims_incurred", "claims_paid",
            "case_reserve_change", "ibnr_estimate", "ibnr_change",
            "risk_adjustment", "risk_adjustment_change", "discount_unwind", "lic_closing",
            "total_opening", "total_closing", "currency_code",
            "contract_nature"
        };
        for (String column : expectedColumns) {
            try (Connection c = conn();
                 PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM information_schema.columns " +
                     "WHERE table_schema = 'public' AND table_name = 'paa_movement_analysis' " +
                     "AND column_name = ?")) {
                ps.setString(1, column);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next(), "expected column " + column + " on paa_movement_analysis view after V78");
            }
        }
    }

    @Test
    @DisplayName("a FAC_INWARD-natured portfolio's group surfaces contract_nature = 'FAC_INWARD' on the view")
    void facInwardGroupSurfacesContractNature() throws SQLException {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            String portfolioId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO portfolio (code, name, contract_nature) " +
                    "VALUES ('PORT-V78-FIN','V78 FAC_INWARD test','FAC_INWARD') RETURNING id")) {
                ResultSet rs = ps.executeQuery();
                rs.next();
                portfolioId = rs.getString(1);
            }
            String groupId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO group_of_contracts (portfolio_id, cohort_year, onerousness) " +
                    "VALUES (?::uuid, 2030, 'NOT_ONEROUS') RETURNING id")) {
                ps.setString(1, portfolioId);
                ResultSet rs = ps.executeQuery();
                rs.next();
                groupId = rs.getString(1);
            }
            String periodId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fiscal_year (name, start_date, end_date, status) " +
                    "VALUES ('FY-V78', '2030-01-01', '2030-12-31', 'ACTIVE') RETURNING id")) {
                ResultSet rs = ps.executeQuery();
                rs.next();
                String fiscalYearId = rs.getString(1);
                try (PreparedStatement ps2 = c.prepareStatement(
                        "INSERT INTO fiscal_period (fiscal_year_id, period_type, start_date, end_date, status) " +
                        "VALUES (?::uuid, 'MONTH', '2030-01-01', '2030-01-31', 'OPEN') RETURNING id")) {
                    ps2.setString(1, fiscalYearId);
                    ResultSet rs2 = ps2.executeQuery();
                    rs2.next();
                    periodId = rs2.getString(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO paa_lrc (group_id, period_id, opening_balance, premium_received, " +
                    "premium_earned, closing_balance, currency_code) " +
                    "VALUES (?::uuid, ?::uuid, 0, 100, 10, 90, 'NGN')")) {
                ps.setString(1, groupId);
                ps.setString(2, periodId);
                ps.executeUpdate();
            }
            c.commit();

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT contract_nature FROM paa_movement_analysis WHERE group_id = ?::uuid")) {
                ps.setString(1, groupId);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next(), "expected a movement row for the seeded FAC_INWARD group");
                assertEquals("FAC_INWARD", rs.getString("contract_nature"));
            }
        }
    }
}
