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
 * Verifies V36__create_paa_foundation.sql applies cleanly and exercises every
 * CHECK / UNIQUE / FK constraint introduced by the migration. Pure JDBC +
 * Flyway + Testcontainers — no Spring context. Mirrors V31's structure.
 *
 * <p>The {@code target=36} cap ensures we test only V36 in isolation —
 * Flyway runs V1 → V36 on a fresh container.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V36PaaFoundationMigrationTest {

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
            .target("36")
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
    @DisplayName("migration applies; all 5 PAA tables exist")
    void schemaApplies() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_name IN " +
                 "('portfolio','group_of_contracts','paa_lrc','paa_lic','paa_config')")) {
            ResultSet rs = ps.executeQuery();
            int count = 0;
            while (rs.next()) count++;
            assertEquals(5, count, "expected all 5 PAA tables to exist after V36");
        }
    }

    @Test
    @DisplayName("journal_entry_line.portfolio_id FK installed by V36")
    void journalEntryLinePortfolioFkInstalled() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT 1 FROM information_schema.referential_constraints " +
                 "WHERE constraint_name = 'fk_journal_entry_line_portfolio'")) {
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next(), "fk_journal_entry_line_portfolio should exist after V36");
        }
    }

    @Test
    @DisplayName("journal_entry_line.contract_group_id FK installed by V36")
    void journalEntryLineGroupFkInstalled() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT 1 FROM information_schema.referential_constraints " +
                 "WHERE constraint_name = 'fk_journal_entry_line_group'")) {
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next(), "fk_journal_entry_line_group should exist after V36");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // portfolio
    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("portfolio constraints")
    class PortfolioConstraints {

        @Test
        @DisplayName("plain insert with required fields accepted")
        void acceptsValidRow() throws SQLException {
            runSql("INSERT INTO portfolio (code, name) VALUES ('PORT-OK-1','Motor Comp Retail')");
        }

        @Test
        @DisplayName("uq_portfolio_code rejects duplicate code")
        void rejectsDuplicateCode() throws SQLException {
            runSql("INSERT INTO portfolio (code, name) VALUES ('PORT-DUP','First')");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO portfolio (code, name) VALUES ('PORT-DUP','Second')"));
        }

        @Test
        @DisplayName("fk_portfolio_cob rejects unknown class_of_business_id")
        void rejectsUnknownClassOfBusiness() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO portfolio (code, name, class_of_business_id) " +
                "VALUES ('PORT-FK','Bad','11111111-1111-1111-1111-111111111111')"));
        }

        @Test
        @DisplayName("nullable class_of_business_id accepted (loose coupling for legacy taxonomies)")
        void acceptsNullClassOfBusiness() throws SQLException {
            runSql("INSERT INTO portfolio (code, name, class_of_business_id) VALUES ('PORT-NULL-COB','No COB',NULL)");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // group_of_contracts
    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("group_of_contracts constraints")
    class GroupConstraints {

        private String portfolioId(String code) throws SQLException {
            return runSqlReturningId(
                "INSERT INTO portfolio (code, name) VALUES ('" + code + "','Test Portfolio') RETURNING id");
        }

        @Test
        @DisplayName("valid (portfolio, cohort, onerousness) row accepted")
        void acceptsValidRow() throws SQLException {
            String p = portfolioId("PORT-G-OK");
            runSql("INSERT INTO group_of_contracts (portfolio_id, cohort_year, onerousness) " +
                "VALUES ('" + p + "',2026,'NOT_ONEROUS')");
        }

        @Test
        @DisplayName("ck_group_onerousness rejects unknown bucket")
        void rejectsBadOnerousness() throws SQLException {
            String p = portfolioId("PORT-G-BAD-ON");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO group_of_contracts (portfolio_id, cohort_year, onerousness) " +
                "VALUES ('" + p + "',2026,'MAYBE_ONEROUS')"));
        }

        @Test
        @DisplayName("ck_group_status rejects unknown status")
        void rejectsBadStatus() throws SQLException {
            String p = portfolioId("PORT-G-BAD-ST");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO group_of_contracts (portfolio_id, cohort_year, onerousness, status) " +
                "VALUES ('" + p + "',2026,'NOT_ONEROUS','FROZEN')"));
        }

        @Test
        @DisplayName("ck_group_cohort_year rejects implausible year")
        void rejectsBadCohortYear() throws SQLException {
            String p = portfolioId("PORT-G-BAD-YR");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO group_of_contracts (portfolio_id, cohort_year, onerousness) " +
                "VALUES ('" + p + "',1850,'NOT_ONEROUS')"));
        }

        @Test
        @DisplayName("uq_group_portfolio_cohort_onerousness rejects duplicate triple")
        void rejectsDuplicateTriple() throws SQLException {
            String p = portfolioId("PORT-G-DUP");
            runSql("INSERT INTO group_of_contracts (portfolio_id, cohort_year, onerousness) " +
                "VALUES ('" + p + "',2026,'NOT_ONEROUS')");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO group_of_contracts (portfolio_id, cohort_year, onerousness) " +
                "VALUES ('" + p + "',2026,'NOT_ONEROUS')"));
        }

        @Test
        @DisplayName("same (portfolio, cohort) accepts different onerousness — the §16 partition")
        void acceptsDifferentOnerousnessSameCohort() throws SQLException {
            String p = portfolioId("PORT-G-PART");
            runSql("INSERT INTO group_of_contracts (portfolio_id, cohort_year, onerousness) " +
                "VALUES ('" + p + "',2026,'NOT_ONEROUS')");
            runSql("INSERT INTO group_of_contracts (portfolio_id, cohort_year, onerousness) " +
                "VALUES ('" + p + "',2026,'ONEROUS')");
        }

        @Test
        @DisplayName("fk_group_portfolio rejects unknown portfolio_id")
        void rejectsUnknownPortfolio() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO group_of_contracts (portfolio_id, cohort_year, onerousness) " +
                "VALUES ('11111111-1111-1111-1111-111111111111',2026,'NOT_ONEROUS')"));
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // paa_lrc
    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("paa_lrc constraints")
    class LrcConstraints {

        private String[] groupAndPeriod(String codeSuffix) throws SQLException {
            String pid = runSqlReturningId(
                "INSERT INTO portfolio (code, name) " +
                "VALUES ('PORT-LRC-" + codeSuffix + "','LRC test') RETURNING id");
            String gid = runSqlReturningId(
                "INSERT INTO group_of_contracts (portfolio_id, cohort_year, onerousness) " +
                "VALUES ('" + pid + "',2030,'NOT_ONEROUS') RETURNING id");
            String fyId = runSqlReturningId(
                "INSERT INTO fiscal_year (name, start_date, end_date) " +
                "VALUES ('FY-LRC-" + System.nanoTime() + "','2030-01-01','2030-12-31') RETURNING id");
            String periodId = runSqlReturningId(
                "INSERT INTO fiscal_period (fiscal_year_id, period_type, start_date, end_date) " +
                "VALUES ('" + fyId + "','MONTH','2030-01-01','2030-01-31') RETURNING id");
            return new String[] { gid, periodId };
        }

        @Test
        @DisplayName("valid row accepted")
        void acceptsValidRow() throws SQLException {
            String[] gp = groupAndPeriod("OK");
            runSql("INSERT INTO paa_lrc (group_id, period_id, opening_balance, premium_received, " +
                "premium_earned, closing_balance) " +
                "VALUES ('" + gp[0] + "','" + gp[1] + "',0,1000.00,100.00,900.00)");
        }

        @Test
        @DisplayName("ck_paa_lrc_nonneg rejects negative opening_balance")
        void rejectsNegativeOpening() throws SQLException {
            String[] gp = groupAndPeriod("NEG-OP");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO paa_lrc (group_id, period_id, opening_balance) " +
                "VALUES ('" + gp[0] + "','" + gp[1] + "',-1.00)"));
        }

        @Test
        @DisplayName("ck_paa_lrc_nonneg rejects negative closing_balance")
        void rejectsNegativeClosing() throws SQLException {
            String[] gp = groupAndPeriod("NEG-CL");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO paa_lrc (group_id, period_id, closing_balance) " +
                "VALUES ('" + gp[0] + "','" + gp[1] + "',-1.00)"));
        }

        @Test
        @DisplayName("ck_paa_lrc_nonneg rejects negative loss_component")
        void rejectsNegativeLossComponent() throws SQLException {
            String[] gp = groupAndPeriod("NEG-LC");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO paa_lrc (group_id, period_id, loss_component) " +
                "VALUES ('" + gp[0] + "','" + gp[1] + "',-50.00)"));
        }

        @Test
        @DisplayName("uq_paa_lrc_group_period rejects duplicate (group, period)")
        void rejectsDuplicateGroupPeriod() throws SQLException {
            String[] gp = groupAndPeriod("DUP");
            runSql("INSERT INTO paa_lrc (group_id, period_id) VALUES ('" + gp[0] + "','" + gp[1] + "')");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO paa_lrc (group_id, period_id) VALUES ('" + gp[0] + "','" + gp[1] + "')"));
        }

        @Test
        @DisplayName("fk_paa_lrc_group rejects unknown group_id")
        void rejectsUnknownGroup() throws SQLException {
            String[] gp = groupAndPeriod("FK-G");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO paa_lrc (group_id, period_id) " +
                "VALUES ('11111111-1111-1111-1111-111111111111','" + gp[1] + "')"));
        }

        @Test
        @DisplayName("fk_paa_lrc_period rejects unknown period_id")
        void rejectsUnknownPeriod() throws SQLException {
            String[] gp = groupAndPeriod("FK-P");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO paa_lrc (group_id, period_id) " +
                "VALUES ('" + gp[0] + "','11111111-1111-1111-1111-111111111111')"));
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // paa_lic
    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("paa_lic constraints")
    class LicConstraints {

        private String[] groupAndPeriod(String codeSuffix) throws SQLException {
            String pid = runSqlReturningId(
                "INSERT INTO portfolio (code, name) " +
                "VALUES ('PORT-LIC-" + codeSuffix + "','LIC test') RETURNING id");
            String gid = runSqlReturningId(
                "INSERT INTO group_of_contracts (portfolio_id, cohort_year, onerousness) " +
                "VALUES ('" + pid + "',2031,'NOT_ONEROUS') RETURNING id");
            String fyId = runSqlReturningId(
                "INSERT INTO fiscal_year (name, start_date, end_date) " +
                "VALUES ('FY-LIC-" + System.nanoTime() + "','2031-01-01','2031-12-31') RETURNING id");
            String periodId = runSqlReturningId(
                "INSERT INTO fiscal_period (fiscal_year_id, period_type, start_date, end_date) " +
                "VALUES ('" + fyId + "','MONTH','2031-01-01','2031-01-31') RETURNING id");
            return new String[] { gid, periodId };
        }

        @Test
        @DisplayName("valid row accepted")
        void acceptsValidRow() throws SQLException {
            String[] gp = groupAndPeriod("OK");
            runSql("INSERT INTO paa_lic (group_id, period_id, claims_incurred, ibnr_estimate, " +
                "risk_adjustment, closing_balance) " +
                "VALUES ('" + gp[0] + "','" + gp[1] + "',500.00,300.00,100.00,850.00)");
        }

        @Test
        @DisplayName("ck_paa_lic_nonneg rejects negative claims_incurred")
        void rejectsNegativeClaimsIncurred() throws SQLException {
            String[] gp = groupAndPeriod("NEG-CI");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO paa_lic (group_id, period_id, claims_incurred) " +
                "VALUES ('" + gp[0] + "','" + gp[1] + "',-1.00)"));
        }

        @Test
        @DisplayName("ck_paa_lic_nonneg rejects negative ibnr_estimate")
        void rejectsNegativeIbnr() throws SQLException {
            String[] gp = groupAndPeriod("NEG-IBNR");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO paa_lic (group_id, period_id, ibnr_estimate) " +
                "VALUES ('" + gp[0] + "','" + gp[1] + "',-100.00)"));
        }

        @Test
        @DisplayName("ck_paa_lic_nonneg rejects negative risk_adjustment")
        void rejectsNegativeRiskAdjustment() throws SQLException {
            String[] gp = groupAndPeriod("NEG-RA");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO paa_lic (group_id, period_id, risk_adjustment) " +
                "VALUES ('" + gp[0] + "','" + gp[1] + "',-50.00)"));
        }

        @Test
        @DisplayName("uq_paa_lic_group_period rejects duplicate (group, period)")
        void rejectsDuplicateGroupPeriod() throws SQLException {
            String[] gp = groupAndPeriod("DUP");
            runSql("INSERT INTO paa_lic (group_id, period_id) VALUES ('" + gp[0] + "','" + gp[1] + "')");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO paa_lic (group_id, period_id) VALUES ('" + gp[0] + "','" + gp[1] + "')"));
        }

        @Test
        @DisplayName("changes (case_reserve_change, ibnr_change, risk_adjustment_change, discount_unwind) can be negative")
        void acceptsNegativeChangeColumns() throws SQLException {
            // Roll-forward changes are signed deltas — negative values represent
            // run-off / paid-out movements. ck_paa_lic_nonneg deliberately
            // excludes them (it only guards opening, balances, and stocks).
            String[] gp = groupAndPeriod("NEG-OK");
            runSql("INSERT INTO paa_lic (group_id, period_id, case_reserve_change, ibnr_change, " +
                "risk_adjustment_change, discount_unwind) " +
                "VALUES ('" + gp[0] + "','" + gp[1] + "',-100.00,-50.00,-25.00,-10.00)");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // paa_config
    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("paa_config constraints")
    class ConfigConstraints {

        // paa_config is a singleton (uq_paa_config_singleton). Wipe between
        // every test in this nested class so each one starts from an empty
        // table — otherwise the second-insert-attempts in one test would be
        // diagnosed as singleton violations from leftover state.
        @org.junit.jupiter.api.BeforeEach
        void wipeConfig() throws SQLException {
            runSql("DELETE FROM paa_config");
        }

        @Test
        @DisplayName("default insert with no values accepted (singleton default row)")
        void acceptsDefaultRow() throws SQLException {
            runSql("INSERT INTO paa_config DEFAULT VALUES");
        }

        @Test
        @DisplayName("uq_paa_config_singleton rejects a second non-deleted row")
        void rejectsSecondRow() throws SQLException {
            runSql("INSERT INTO paa_config DEFAULT VALUES");
            assertThrows(SQLException.class, () -> runSql("INSERT INTO paa_config DEFAULT VALUES"));
        }

        @Test
        @DisplayName("uq_paa_config_singleton allows replacement after soft-delete")
        void acceptsReplacementAfterSoftDelete() throws SQLException {
            runSql("INSERT INTO paa_config (ra_confidence_level) VALUES (75.00)");
            runSql("UPDATE paa_config SET deleted_at = now() WHERE deleted_at IS NULL");
            // Partial unique index only covers deleted_at IS NULL — a new row is allowed.
            runSql("INSERT INTO paa_config (ra_confidence_level) VALUES (80.00)");
        }

        @Test
        @DisplayName("ck_paa_config_ra_method rejects unknown method")
        void rejectsBadRaMethod() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO paa_config (ra_method) VALUES ('GUESSWORK')"));
        }

        @Test
        @DisplayName("ck_paa_config_acq_method rejects unknown method")
        void rejectsBadAcqMethod() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO paa_config (acquisition_cashflow_method) VALUES ('CAPITALISE')"));
        }

        @Test
        @DisplayName("ck_paa_config_ra_confidence rejects 0 or below")
        void rejectsZeroConfidence() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO paa_config (ra_confidence_level) VALUES (0)"));
        }

        @Test
        @DisplayName("ck_paa_config_ra_confidence rejects > 100")
        void rejectsOverhundredConfidence() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO paa_config (ra_confidence_level) VALUES (101.00)"));
        }

        @Test
        @DisplayName("ck_paa_config_discount_rate rejects negative rate")
        void rejectsNegativeDiscountRate() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO paa_config (discount_rate) VALUES (-0.01)"));
        }

        @Test
        @DisplayName("ck_paa_config_discount_consistency rejects discount_lic=TRUE without discount_rate")
        void rejectsDiscountLicWithoutRate() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO paa_config (discount_lic, discount_rate) VALUES (TRUE, NULL)"));
        }

        @Test
        @DisplayName("ck_paa_config_discount_consistency accepts discount_lic=TRUE with discount_rate")
        void acceptsDiscountLicWithRate() throws SQLException {
            runSql("INSERT INTO paa_config (discount_lic, discount_rate) VALUES (TRUE, 0.05000)");
        }

        @Test
        @DisplayName("ck_paa_config_discount_consistency accepts discount_lic=FALSE with NULL rate")
        void acceptsDiscountLicFalseWithNullRate() throws SQLException {
            runSql("INSERT INTO paa_config (discount_lic, discount_rate) VALUES (FALSE, NULL)");
        }
    }
}
