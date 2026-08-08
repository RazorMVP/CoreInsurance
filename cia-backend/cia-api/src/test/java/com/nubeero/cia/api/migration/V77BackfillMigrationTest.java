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
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies V77__contract_group_assignment.sql's row-transformation — the
 * {@code INSERT ... SELECT FROM policy_group_assignment} immediately
 * followed by {@code DROP TABLE policy_group_assignment} (a one-way door) —
 * actually preserves existing rows correctly. {@link V77ContractGroupAssignmentMigrationTest}
 * only proves the resulting schema shape / constraints; every Testcontainers
 * instance there starts empty, so that guard alone never exercises the
 * backfill's data path.
 *
 * <p>Two-phase strategy, mirrors {@link V43BackfillClassOfBusinessMigrationTest}:
 * stop Flyway at V76 (policy_group_assignment still exists — created in
 * V37, not yet dropped; contract_group_assignment does not exist yet),
 * seed old-shape rows via JDBC with fixed UUIDs plus their FK parents
 * (portfolio, group_of_contracts, policies — policy_group_assignment.policy_id
 * carries a real FK to policies.id, unlike the new polymorphic table),
 * capture the pre-migration audit columns, then migrate to V77 and assert
 * the backfilled contract_group_assignment rows.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V77BackfillMigrationTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ciatest")
            .withUsername("ciatest")
            .withPassword("ciatest");

    private static final UUID PORTFOLIO_ID = UUID.randomUUID();
    private static final UUID GROUP_ID = UUID.randomUUID();

    private static final UUID POLICY_1_ID = UUID.randomUUID();
    private static final UUID ASSIGNMENT_1_ID = UUID.randomUUID();
    private static final Instant ASSIGNED_1_AT = Instant.parse("2026-01-15T10:30:00Z");

    private static final UUID POLICY_2_ID = UUID.randomUUID();
    private static final UUID ASSIGNMENT_2_ID = UUID.randomUUID();
    private static final Instant ASSIGNED_2_AT = Instant.parse("2026-02-20T08:00:00Z");

    // Captured from policy_group_assignment BEFORE the V77 backfill runs, so
    // the post-backfill assertions prove created_at/updated_at were copied
    // across (not re-stamped), not merely that they're non-null.
    private static Instant capturedCreatedAt1;
    private static Instant capturedUpdatedAt1;
    private static Instant capturedCreatedAt2;
    private static Instant capturedUpdatedAt2;

    @BeforeAll
    void migrateSeedAndBackfill() throws SQLException {
        POSTGRES.start();

        // Phase 1: migrate up to V76 only — policy_group_assignment (V37)
        // still exists; contract_group_assignment (V77) does not yet.
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target("76")
            .load()
            .migrate();

        // Phase 2: seed old-shape rows (with FK parents) via JDBC, and
        // capture their audit columns before V77 touches anything.
        try (Connection c = conn()) {
            try (Statement st = c.createStatement()) {
                seedPortfolioAndGroup(st);
                seedPolicy(st, POLICY_1_ID, "POL-BACKFILL-001");
                seedPolicy(st, POLICY_2_ID, "POL-BACKFILL-002");
            }
            seedAssignment(c, ASSIGNMENT_1_ID, POLICY_1_ID, ASSIGNED_1_AT, "seed-user-1");
            seedAssignment(c, ASSIGNMENT_2_ID, POLICY_2_ID, ASSIGNED_2_AT, "seed-user-2");

            capturedCreatedAt1 = readTimestamp(c, "policy_group_assignment", ASSIGNMENT_1_ID, "created_at");
            capturedUpdatedAt1 = readTimestamp(c, "policy_group_assignment", ASSIGNMENT_1_ID, "updated_at");
            capturedCreatedAt2 = readTimestamp(c, "policy_group_assignment", ASSIGNMENT_2_ID, "created_at");
            capturedUpdatedAt2 = readTimestamp(c, "policy_group_assignment", ASSIGNMENT_2_ID, "updated_at");
        }

        // Phase 3: migrate up to V77 — runs the INSERT...SELECT backfill,
        // then DROP TABLE policy_group_assignment.
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

    // ── Assertions ────────────────────────────────────────────────────────

    @Test
    @DisplayName("V77 backfills every existing policy_group_assignment row as contract_type = POLICY, "
        + "preserving contract_id/group_id/assigned_at/created_at/updated_at/created_by")
    void backfillsBothRowsAsPolicyPreservingAuditColumns() throws SQLException {
        assertBackfilledRow(ASSIGNMENT_1_ID, POLICY_1_ID, ASSIGNED_1_AT, "seed-user-1",
            capturedCreatedAt1, capturedUpdatedAt1);
        assertBackfilledRow(ASSIGNMENT_2_ID, POLICY_2_ID, ASSIGNED_2_AT, "seed-user-2",
            capturedCreatedAt2, capturedUpdatedAt2);
    }

    @Test
    @DisplayName("row count is preserved — no rows dropped or duplicated by the backfill")
    void rowCountMatchesSeededCount() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM contract_group_assignment WHERE group_id = ?")) {
            ps.setObject(1, GROUP_ID);
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
        }
    }

    @Test
    @DisplayName("policy_group_assignment no longer exists after V77 (one-way door)")
    void oldTableDropped() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT 1 FROM information_schema.tables " +
                 "WHERE table_schema = 'public' AND table_name = 'policy_group_assignment'")) {
            ResultSet rs = ps.executeQuery();
            assertFalse(rs.next());
        }
    }

    private void assertBackfilledRow(UUID assignmentId, UUID expectedPolicyId,
                                      Instant expectedAssignedAt, String expectedCreatedBy,
                                      Instant expectedCreatedAt, Instant expectedUpdatedAt) throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT contract_type, contract_id, group_id, assigned_at, " +
                 "created_at, updated_at, created_by, deleted_at " +
                 "FROM contract_group_assignment WHERE id = ?")) {
            ps.setObject(1, assignmentId);
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next(), "expected a backfilled row for assignment " + assignmentId);
            assertEquals("POLICY", rs.getString("contract_type"));
            assertEquals(expectedPolicyId, rs.getObject("contract_id", UUID.class));
            assertEquals(GROUP_ID, rs.getObject("group_id", UUID.class));
            assertEquals(expectedAssignedAt, rs.getTimestamp("assigned_at").toInstant());
            assertEquals(expectedCreatedBy, rs.getString("created_by"));
            assertEquals(expectedCreatedAt, rs.getTimestamp("created_at").toInstant(),
                "created_at must be copied from the old row, not re-stamped");
            assertEquals(expectedUpdatedAt, rs.getTimestamp("updated_at").toInstant(),
                "updated_at must be copied from the old row, not re-stamped");
            assertNull(rs.getTimestamp("deleted_at"), "seeded rows were never soft-deleted");
        }
    }

    // ── Seed routines (phase-1 shape: policy_group_assignment / policies / portfolio / group_of_contracts) ──

    private static void seedPortfolioAndGroup(Statement st) throws SQLException {
        st.executeUpdate(
            "INSERT INTO portfolio (id, code, name, created_by) " +
            "VALUES ('" + PORTFOLIO_ID + "', 'PORT-V77-BACKFILL', 'V77 backfill test', 'test')");
        st.executeUpdate(
            "INSERT INTO group_of_contracts (id, portfolio_id, cohort_year, onerousness, status, created_by) " +
            "VALUES ('" + GROUP_ID + "', '" + PORTFOLIO_ID + "', 2026, 'NOT_ONEROUS', 'OPEN', 'test')");
    }

    private static void seedPolicy(Statement st, UUID policyId, String policyNumber) throws SQLException {
        st.executeUpdate(
            "INSERT INTO policies (id, policy_number, customer_id, customer_name, " +
            "product_id, product_name, product_code, product_rate, " +
            "class_of_business_id, class_of_business_name, class_of_business_code, " +
            "policy_start_date, policy_end_date, created_by) " +
            "VALUES ('" + policyId + "', '" + policyNumber + "', '" + UUID.randomUUID() + "', 'Test Customer', " +
            "'" + UUID.randomUUID() + "', 'Test Product', 'PROD-V77', 0.0500, " +
            "'" + UUID.randomUUID() + "', 'Test COB', 'COB-V77', " +
            "'2026-01-01', '2026-12-31', 'test')");
    }

    /**
     * Inserts a policy_group_assignment row with a fixed {@code assigned_at}
     * via a bound parameter (not string interpolation) so the TIMESTAMPTZ
     * round-trips exactly regardless of session timezone.
     */
    private static void seedAssignment(Connection c, UUID assignmentId, UUID policyId,
                                        Instant assignedAt, String createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO policy_group_assignment (id, policy_id, group_id, assigned_at, created_by) " +
            "VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, assignmentId);
            ps.setObject(2, policyId);
            ps.setObject(3, GROUP_ID);
            ps.setTimestamp(4, Timestamp.from(assignedAt));
            ps.setString(5, createdBy);
            ps.execute();
        }
    }

    private static Instant readTimestamp(Connection c, String table, UUID id, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT " + column + " FROM " + table + " WHERE id = ?")) {
            ps.setObject(1, id);
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            Timestamp ts = rs.getTimestamp(column);
            return ts == null ? null : ts.toInstant();
        }
    }
}
