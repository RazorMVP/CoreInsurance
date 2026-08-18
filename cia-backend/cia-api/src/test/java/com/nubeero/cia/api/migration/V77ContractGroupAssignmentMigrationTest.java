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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies V76__portfolio_contract_nature.sql + V77__contract_group_assignment.sql
 * apply cleanly: the direct-policy-only {@code policy_group_assignment} table is
 * generalised into the polymorphic {@code contract_group_assignment}
 * {@code (contract_type, contract_id)}, the old table is dropped, and
 * {@code portfolio} gains a {@code contract_nature} dimension defaulting
 * {@code DIRECT}. Pure JDBC + Flyway + Testcontainers — no Spring context.
 *
 * <p>Task 1 of the FAC / IFRS-17 PAA workstream — data model only. Mirrors
 * the {@code columnExists} / {@code tableExists} JDBC-against-
 * {@code information_schema} pattern used by {@code V42JournalEntryLineClassOfBusinessMigrationTest}
 * and the constraint-acceptance/rejection pattern used by
 * {@code V37PolicyGroupAssignmentMigrationTest} (the migration this one
 * supersedes).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V77ContractGroupAssignmentMigrationTest {

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
            .target("77")
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

    private boolean columnExists(String table, String column) throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT 1 FROM information_schema.columns " +
                 "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        }
    }

    private boolean tableExists(String table) throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT 1 FROM information_schema.tables " +
                 "WHERE table_schema = 'public' AND table_name = ?")) {
            ps.setString(1, table);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        }
    }

    /**
     * Builds a group_of_contracts row (via a fresh portfolio) — the sole FK
     * target contract_group_assignment still enforces. Unlike V37's
     * policy_group_assignment, contract_id carries NO foreign key (it's
     * polymorphic across POLICY / FAC_INWARD / FAC_OUTWARD), so no policies
     * row needs to be seeded.
     */
    private String seedGroup(String suffix) throws SQLException {
        String portfolioId = runSqlReturningId(
            "INSERT INTO portfolio (code, name) VALUES ('PORT-V77-" + suffix + "','V77 test') RETURNING id");
        return runSqlReturningId(
            "INSERT INTO group_of_contracts (portfolio_id, cohort_year, onerousness) " +
            "VALUES ('" + portfolioId + "',2030,'NOT_ONEROUS') RETURNING id");
    }

    @Test
    @DisplayName("contract_group_assignment replaces policy_group_assignment; portfolio gains contract_nature")
    void contractGroupAssignmentReplacesPolicyGroupAssignment() throws SQLException {
        // table exists with the polymorphic columns
        assertTrue(columnExists("contract_group_assignment", "contract_type"));
        assertTrue(columnExists("contract_group_assignment", "contract_id"));
        // old table dropped
        assertFalse(tableExists("policy_group_assignment"));
        // portfolio has contract_nature defaulting DIRECT
        assertTrue(columnExists("portfolio", "contract_nature"));
    }

    @Test
    @DisplayName("valid POLICY assignment row accepted")
    void acceptsValidPolicyRow() throws SQLException {
        String groupId = seedGroup("OK");
        String contractId = UUID.randomUUID().toString();
        runSql("INSERT INTO contract_group_assignment (id, contract_type, contract_id, group_id, assigned_at) " +
            "VALUES (gen_random_uuid(), 'POLICY', '" + contractId + "', '" + groupId + "', now())");

        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM contract_group_assignment " +
                 "WHERE contract_type = 'POLICY' AND contract_id = ?")) {
            ps.setObject(1, UUID.fromString(contractId));
            ResultSet rs = ps.executeQuery();
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    @DisplayName("uq_cga_type_contract rejects duplicate (contract_type, contract_id)")
    void rejectsDuplicateTypeAndContract() throws SQLException {
        String groupId = seedGroup("DUP");
        String contractId = UUID.randomUUID().toString();
        runSql("INSERT INTO contract_group_assignment (id, contract_type, contract_id, group_id, assigned_at) " +
            "VALUES (gen_random_uuid(), 'POLICY', '" + contractId + "', '" + groupId + "', now())");
        assertThrows(SQLException.class, () -> runSql(
            "INSERT INTO contract_group_assignment (id, contract_type, contract_id, group_id, assigned_at) " +
            "VALUES (gen_random_uuid(), 'POLICY', '" + contractId + "', '" + groupId + "', now())"));
    }

    @Test
    @DisplayName("ck_cga_contract_type rejects an unknown contract_type")
    void rejectsInvalidContractType() throws SQLException {
        String groupId = seedGroup("BADTYPE");
        String contractId = UUID.randomUUID().toString();
        assertThrows(SQLException.class, () -> runSql(
            "INSERT INTO contract_group_assignment (id, contract_type, contract_id, group_id, assigned_at) " +
            "VALUES (gen_random_uuid(), 'BOGUS', '" + contractId + "', '" + groupId + "', now())"));
    }

    @Test
    @DisplayName("fk_cga_group rejects an unknown group_id")
    void rejectsUnknownGroup() throws SQLException {
        String contractId = UUID.randomUUID().toString();
        assertThrows(SQLException.class, () -> runSql(
            "INSERT INTO contract_group_assignment (id, contract_type, contract_id, group_id, assigned_at) " +
            "VALUES (gen_random_uuid(), 'POLICY', '" + contractId + "', " +
            "'11111111-1111-1111-1111-111111111111', now())"));
    }

    @Test
    @DisplayName("portfolio.contract_nature defaults to DIRECT when not specified")
    void portfolioContractNatureDefaultsToDirect() throws SQLException {
        String portfolioId = runSqlReturningId(
            "INSERT INTO portfolio (code, name) VALUES ('PORT-V77-NATURE','V77 nature test') RETURNING id");

        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT contract_nature FROM portfolio WHERE id = ?::uuid")) {
            ps.setString(1, portfolioId);
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            assertEquals("DIRECT", rs.getString("contract_nature"));
        }
    }

    @Test
    @DisplayName("ck_portfolio_contract_nature rejects an unknown nature")
    void rejectsInvalidContractNature() {
        assertThrows(SQLException.class, () -> runSql(
            "INSERT INTO portfolio (code, name, contract_nature) " +
            "VALUES ('PORT-V77-BADNATURE','bad nature test','BOGUS')"));
    }
}
