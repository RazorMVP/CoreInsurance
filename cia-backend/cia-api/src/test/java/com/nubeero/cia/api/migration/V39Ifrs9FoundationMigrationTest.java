package com.nubeero.cia.api.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies V39__create_ifrs9_foundation.sql applies cleanly and exercises
 * every CHECK / UNIQUE / FK constraint introduced by the migration.
 * Mirrors V36PaaFoundationMigrationTest's structure for Phase 2.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V39Ifrs9FoundationMigrationTest {

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
            .target("39")
            .load()
            .migrate();
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void runSql(String sql) throws SQLException {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.execute();
        }
    }

    private String runSqlReturningId(String sql) throws SQLException {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    @DisplayName("V39 applies; all 4 IFRS 9 tables exist")
    void schemaApplies() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_name IN " +
                 "('investment_holding','investment_carrying_value','investment_classification_history','ifrs9_config')")) {
            ResultSet rs = ps.executeQuery();
            int count = 0;
            while (rs.next()) count++;
            assertEquals(4, count, "expected all 4 IFRS 9 tables to exist after V39");
        }
    }

    @Test
    @DisplayName("journal_entry_line.holding_id FK installed by V39")
    void journalEntryLineHoldingFkInstalled() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT 1 FROM information_schema.referential_constraints " +
                 "WHERE constraint_name = 'fk_journal_entry_line_holding'")) {
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next(), "fk_journal_entry_line_holding should exist after V39");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // investment_holding
    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("investment_holding constraints")
    class HoldingConstraints {

        @Test
        @DisplayName("valid debt holding accepted")
        void acceptsValidDebt() throws SQLException {
            runSql("INSERT INTO investment_holding (security_name, asset_type, classification, " +
                "acquisition_date, acquisition_cost, face_value, coupon_rate, maturity_date, sppi_test_passed) " +
                "VALUES ('Test Bond','DEBT','AMORTISED_COST','2026-01-01',100000.00,100000.00,0.05000," +
                "'2031-01-01',TRUE)");
        }

        @Test
        @DisplayName("ck_investment_asset_type rejects unknown type")
        void rejectsBadAssetType() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO investment_holding (security_name, asset_type, classification, " +
                "acquisition_date, acquisition_cost) " +
                "VALUES ('Bad','BOGUS','FVPL','2026-01-01',1000)"));
        }

        @Test
        @DisplayName("ck_investment_classification rejects unknown bucket")
        void rejectsBadClassification() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO investment_holding (security_name, asset_type, classification, " +
                "acquisition_date, acquisition_cost) " +
                "VALUES ('Bad','DEBT','HOLD_TO_MATURITY','2026-01-01',1000)"));
        }

        @Test
        @DisplayName("ck_investment_status rejects unknown status")
        void rejectsBadStatus() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO investment_holding (security_name, asset_type, classification, " +
                "acquisition_date, acquisition_cost, status) " +
                "VALUES ('Bad','DEBT','FVPL','2026-01-01',1000,'ZOMBIE')"));
        }

        @Test
        @DisplayName("ck_investment_ecl_stage rejects stage outside 1..3")
        void rejectsBadEclStage() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO investment_holding (security_name, asset_type, classification, " +
                "acquisition_date, acquisition_cost, ecl_stage) " +
                "VALUES ('Bad','DEBT','AMORTISED_COST','2026-01-01',1000,4)"));
        }

        @Test
        @DisplayName("ck_investment_ecl_stage accepts NULL (FVPL / FVOCI_EQUITY)")
        void acceptsNullEclStage() throws SQLException {
            runSql("INSERT INTO investment_holding (security_name, asset_type, classification, " +
                "acquisition_date, acquisition_cost) " +
                "VALUES ('NoStage','EQUITY','FVPL','2026-01-01',5000)");
        }

        @Test
        @DisplayName("ck_investment_equity_no_maturity rejects equity with maturity date")
        void rejectsEquityWithMaturity() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO investment_holding (security_name, asset_type, classification, " +
                "acquisition_date, acquisition_cost, maturity_date) " +
                "VALUES ('BadEq','EQUITY','FVPL','2026-01-01',5000,'2030-01-01')"));
        }

        @Test
        @DisplayName("ck_investment_equity_no_maturity rejects equity with coupon rate")
        void rejectsEquityWithCoupon() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO investment_holding (security_name, asset_type, classification, " +
                "acquisition_date, acquisition_cost, coupon_rate) " +
                "VALUES ('BadEq','EQUITY','FVPL','2026-01-01',5000,0.05)"));
        }

        @Test
        @DisplayName("ck_investment_acquisition_cost_nonneg rejects negative cost")
        void rejectsNegativeCost() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO investment_holding (security_name, asset_type, classification, " +
                "acquisition_date, acquisition_cost) " +
                "VALUES ('Bad','DEBT','FVPL','2026-01-01',-1.00)"));
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // investment_carrying_value
    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("investment_carrying_value constraints")
    class CarryingValueConstraints {

        private String[] holdingAndPeriod(String suffix) throws SQLException {
            String holdingId = runSqlReturningId(
                "INSERT INTO investment_holding (security_name, asset_type, classification, " +
                "acquisition_date, acquisition_cost) " +
                "VALUES ('CV-" + suffix + "','DEBT','AMORTISED_COST','2026-01-01',100000.00) RETURNING id");
            String fyId = runSqlReturningId(
                "INSERT INTO fiscal_year (name, start_date, end_date) " +
                "VALUES ('FY-CV-" + System.nanoTime() + "','2026-01-01','2026-12-31') RETURNING id");
            String periodId = runSqlReturningId(
                "INSERT INTO fiscal_period (fiscal_year_id, period_type, start_date, end_date) " +
                "VALUES ('" + fyId + "','MONTH','2026-01-01','2026-01-31') RETURNING id");
            return new String[] { holdingId, periodId };
        }

        @Test
        @DisplayName("valid row accepted")
        void acceptsValidRow() throws SQLException {
            String[] hp = holdingAndPeriod("OK");
            runSql("INSERT INTO investment_carrying_value (holding_id, period_id, opening_balance, " +
                "effective_interest_income, closing_balance, ecl_stage) " +
                "VALUES ('" + hp[0] + "','" + hp[1] + "',100000.00,500.00,100500.00,1)");
        }

        @Test
        @DisplayName("uq_investment_carrying_holding_period rejects duplicate")
        void rejectsDuplicate() throws SQLException {
            String[] hp = holdingAndPeriod("DUP");
            runSql("INSERT INTO investment_carrying_value (holding_id, period_id) " +
                "VALUES ('" + hp[0] + "','" + hp[1] + "')");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO investment_carrying_value (holding_id, period_id) " +
                "VALUES ('" + hp[0] + "','" + hp[1] + "')"));
        }

        @Test
        @DisplayName("ck_investment_carrying_nonneg rejects negative opening")
        void rejectsNegativeOpening() throws SQLException {
            String[] hp = holdingAndPeriod("NEG-OP");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO investment_carrying_value (holding_id, period_id, opening_balance) " +
                "VALUES ('" + hp[0] + "','" + hp[1] + "',-1.00)"));
        }

        @Test
        @DisplayName("ck_investment_carrying_nonneg rejects negative closing")
        void rejectsNegativeClosing() throws SQLException {
            String[] hp = holdingAndPeriod("NEG-CL");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO investment_carrying_value (holding_id, period_id, closing_balance) " +
                "VALUES ('" + hp[0] + "','" + hp[1] + "',-1.00)"));
        }

        @Test
        @DisplayName("change columns accept negative deltas (ECL reversal, FV down)")
        void acceptsNegativeDeltas() throws SQLException {
            String[] hp = holdingAndPeriod("NEG-OK");
            runSql("INSERT INTO investment_carrying_value (holding_id, period_id, " +
                "fair_value_change_pnl, fair_value_change_oci, ecl_movement) " +
                "VALUES ('" + hp[0] + "','" + hp[1] + "',-500.00,-200.00,-50.00)");
        }

        @Test
        @DisplayName("fk_investment_carrying_holding rejects unknown holding")
        void rejectsUnknownHolding() throws SQLException {
            String[] hp = holdingAndPeriod("FK-H");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO investment_carrying_value (holding_id, period_id) " +
                "VALUES ('11111111-1111-1111-1111-111111111111','" + hp[1] + "')"));
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // investment_classification_history
    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("investment_classification_history constraints")
    class ClassificationHistoryConstraints {

        private String holdingId(String suffix) throws SQLException {
            return runSqlReturningId(
                "INSERT INTO investment_holding (security_name, asset_type, classification, " +
                "acquisition_date, acquisition_cost) " +
                "VALUES ('CH-" + suffix + "','DEBT','AMORTISED_COST','2026-01-01',100000.00) RETURNING id");
        }

        @Test
        @DisplayName("valid reclassification row accepted")
        void acceptsValidRow() throws SQLException {
            String h = holdingId("OK");
            runSql("INSERT INTO investment_classification_history (holding_id, previous_classification, " +
                "new_classification, reclassification_date, reason, approved_by) " +
                "VALUES ('" + h + "','AMORTISED_COST','FVPL','2027-01-01','Business model change','cfo@test')");
        }

        @Test
        @DisplayName("ck_investment_classification_history_distinct rejects same-to-same")
        void rejectsSameToSame() throws SQLException {
            String h = holdingId("SAME");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO investment_classification_history (holding_id, previous_classification, " +
                "new_classification, reclassification_date, reason, approved_by) " +
                "VALUES ('" + h + "','FVPL','FVPL','2027-01-01','no-op','admin')"));
        }

        @Test
        @DisplayName("ck_investment_classification_history_previous rejects bad value")
        void rejectsBadPrevious() throws SQLException {
            String h = holdingId("BADPREV");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO investment_classification_history (holding_id, previous_classification, " +
                "new_classification, reclassification_date, reason, approved_by) " +
                "VALUES ('" + h + "','HTM','FVPL','2027-01-01','test','admin')"));
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // ifrs9_config
    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("ifrs9_config constraints")
    class ConfigConstraints {

        @org.junit.jupiter.api.BeforeEach
        void wipe() throws SQLException {
            runSql("DELETE FROM ifrs9_config");
        }

        @Test
        @DisplayName("default insert accepted (singleton default row)")
        void acceptsDefaultRow() throws SQLException {
            runSql("INSERT INTO ifrs9_config DEFAULT VALUES");
        }

        @Test
        @DisplayName("uq_ifrs9_config_singleton rejects a second non-deleted row")
        void rejectsSecondRow() throws SQLException {
            runSql("INSERT INTO ifrs9_config DEFAULT VALUES");
            assertThrows(SQLException.class, () -> runSql("INSERT INTO ifrs9_config DEFAULT VALUES"));
        }

        @Test
        @DisplayName("uq_ifrs9_config_singleton allows replacement after soft-delete")
        void acceptsReplacementAfterSoftDelete() throws SQLException {
            runSql("INSERT INTO ifrs9_config (default_threshold_days_past_due) VALUES (60)");
            runSql("UPDATE ifrs9_config SET deleted_at = now() WHERE deleted_at IS NULL");
            runSql("INSERT INTO ifrs9_config (default_threshold_days_past_due) VALUES (90)");
        }

        @Test
        @DisplayName("ck_ifrs9_config_investment_ecl_method rejects unknown method")
        void rejectsBadInvestmentMethod() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO ifrs9_config (investment_ecl_method) VALUES ('GUESS')"));
        }

        @Test
        @DisplayName("ck_ifrs9_config_sicr_pd rejects 0 or below")
        void rejectsZeroSicrPd() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO ifrs9_config (sicr_threshold_pd_increase) VALUES (0)"));
        }

        @Test
        @DisplayName("ck_ifrs9_config_sicr_pd rejects > 100")
        void rejectsOverHundredSicrPd() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO ifrs9_config (sicr_threshold_pd_increase) VALUES (150)"));
        }

        @Test
        @DisplayName("ck_ifrs9_config_default_dpd rejects negative days")
        void rejectsNegativeDefaultDpd() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO ifrs9_config (default_threshold_days_past_due) VALUES (-1)"));
        }
    }
}
