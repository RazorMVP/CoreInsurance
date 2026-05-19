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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies V37__create_policy_group_assignment.sql applies cleanly and
 * exercises every UNIQUE / FK constraint introduced by the migration.
 * Pure JDBC + Flyway + Testcontainers — no Spring context.
 *
 * <p>Slice 2.2: this table is the link between policies and IFRS 17 groups.
 * Two FKs (policy + group) and one UNIQUE(policy_id) idempotency key are
 * the entire surface; the test asserts each.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V37PolicyGroupAssignmentMigrationTest {

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
            .target("37")
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

    /**
     * Builds (policyId, groupId) — the two FK targets needed to insert a
     * policy_group_assignment row. Builds them from scratch each call so
     * tests stay independent.
     */
    private String[] policyAndGroup(String suffix) throws SQLException {
        // The policies table has no FK to customers / products / COB at the
        // DB level (V6 stores them as plain UUIDs + denormalised snapshot
        // fields), so we can satisfy its NOT NULL surface with synthetic
        // UUIDs and short literals — no need to seed a real customer or
        // product. V37's FK only constrains policy_id, not the policy row's
        // own internal references.
        String policyId = runSqlReturningId(
            "INSERT INTO policies (id, policy_number, customer_id, customer_name, " +
            "product_id, product_name, product_code, product_rate, " +
            "class_of_business_id, class_of_business_name, class_of_business_code, " +
            "policy_start_date, policy_end_date) " +
            "VALUES (gen_random_uuid(),'POL-V37-" + suffix + "',gen_random_uuid(),'Test Customer'," +
            "gen_random_uuid(),'Test Product','PROD-V37',0.0500," +
            "gen_random_uuid(),'Test COB','COB-V37'," +
            "'2030-01-01','2030-12-31') RETURNING id");

        String portfolioId = runSqlReturningId(
            "INSERT INTO portfolio (code, name) VALUES ('PORT-V37-" + suffix + "','V37 test') RETURNING id");
        String groupId = runSqlReturningId(
            "INSERT INTO group_of_contracts (portfolio_id, cohort_year, onerousness) " +
            "VALUES ('" + portfolioId + "',2030,'NOT_ONEROUS') RETURNING id");

        return new String[] { policyId, groupId };
    }

    @Test
    @DisplayName("migration applies; policy_group_assignment table exists")
    void schemaApplies() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT 1 FROM information_schema.tables " +
                 "WHERE table_schema='public' AND table_name='policy_group_assignment'")) {
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next(), "expected policy_group_assignment table to exist after V37");
        }
    }

    @Test
    @DisplayName("valid assignment row accepted")
    void acceptsValidRow() throws SQLException {
        String[] pg = policyAndGroup("OK");
        runSql("INSERT INTO policy_group_assignment (policy_id, group_id) " +
            "VALUES ('" + pg[0] + "','" + pg[1] + "')");

        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM policy_group_assignment WHERE policy_id = ?")) {
            ps.setObject(1, java.util.UUID.fromString(pg[0]));
            ResultSet rs = ps.executeQuery();
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    @DisplayName("uq_policy_group_assignment_policy rejects duplicate policy_id")
    void rejectsDuplicatePolicy() throws SQLException {
        String[] pg = policyAndGroup("DUP");
        runSql("INSERT INTO policy_group_assignment (policy_id, group_id) " +
            "VALUES ('" + pg[0] + "','" + pg[1] + "')");
        assertThrows(SQLException.class, () -> runSql(
            "INSERT INTO policy_group_assignment (policy_id, group_id) " +
            "VALUES ('" + pg[0] + "','" + pg[1] + "')"));
    }

    @Test
    @DisplayName("fk_policy_group_assignment_policy rejects unknown policy_id")
    void rejectsUnknownPolicy() throws SQLException {
        String[] pg = policyAndGroup("FK-P");
        assertThrows(SQLException.class, () -> runSql(
            "INSERT INTO policy_group_assignment (policy_id, group_id) " +
            "VALUES ('11111111-1111-1111-1111-111111111111','" + pg[1] + "')"));
    }

    @Test
    @DisplayName("fk_policy_group_assignment_group rejects unknown group_id")
    void rejectsUnknownGroup() throws SQLException {
        String[] pg = policyAndGroup("FK-G");
        assertThrows(SQLException.class, () -> runSql(
            "INSERT INTO policy_group_assignment (policy_id, group_id) " +
            "VALUES ('" + pg[0] + "','11111111-1111-1111-1111-111111111111')"));
    }

    @Test
    @DisplayName("uq_policy_group_assignment_policy rejects re-insert even after soft-delete (§22 permanence)")
    void rejectsReinsertAfterSoftDelete() throws SQLException {
        // IFRS 17 §22: contract group assignment is permanent at initial
        // recognition. To pin that intent in the schema, V37 uses a FULL
        // UNIQUE constraint on policy_id (not a partial unique index that
        // ignores deleted rows). Audit corrections must therefore update
        // the existing row's group_id directly, not soft-delete + re-insert
        // — preserving "one assignment per policy" as a hard invariant.
        //
        // Compare paa_config (V36): that singleton uses a PARTIAL unique
        // index WHERE deleted_at IS NULL precisely because soft-delete +
        // replace is the correct audit pattern for accounting policy.
        // The two intentions live in different table contracts.
        String[] pg = policyAndGroup("REASSIGN");
        String portfolioId2 = runSqlReturningId(
            "INSERT INTO portfolio (code, name) VALUES ('PORT-V37-REASSIGN-2','V37 test 2') RETURNING id");
        String groupId2 = runSqlReturningId(
            "INSERT INTO group_of_contracts (portfolio_id, cohort_year, onerousness) " +
            "VALUES ('" + portfolioId2 + "',2030,'ONEROUS') RETURNING id");

        runSql("INSERT INTO policy_group_assignment (policy_id, group_id) " +
            "VALUES ('" + pg[0] + "','" + pg[1] + "')");
        runSql("UPDATE policy_group_assignment SET deleted_at = now() WHERE policy_id = '" + pg[0] + "'");

        // UNIQUE applies to all rows, including soft-deleted ones — so this
        // insert is rejected even after soft-delete.
        assertThrows(SQLException.class, () -> runSql(
            "INSERT INTO policy_group_assignment (policy_id, group_id) " +
            "VALUES ('" + pg[0] + "','" + groupId2 + "')"));
    }
}
