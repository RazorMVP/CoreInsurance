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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies V42__add_class_of_business_to_journal_entry_line.sql applies
 * cleanly and produces the expected schema shape. Pure JDBC + Flyway +
 * Testcontainers — no Spring context.
 *
 * <p>Slice 1.10a. Asserts:
 * <ul>
 *   <li>The {@code class_of_business_id} column exists on
 *       {@code journal_entry_line}, is UUID-typed, and is nullable.</li>
 *   <li>The partial index
 *       {@code idx_journal_entry_line_class_of_business} exists and
 *       carries the expected predicate.</li>
 *   <li>The column has NO foreign-key constraint to
 *       {@code classes_of_business} — module-boundary preservation.</li>
 *   <li>Inserting a row with the new column populated round-trips
 *       through the schema correctly.</li>
 *   <li>Inserting a row WITHOUT the new column (i.e. legacy callers)
 *       still succeeds and leaves the column null.</li>
 * </ul>
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V42JournalEntryLineClassOfBusinessMigrationTest {

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
            .target("42")
            .load()
            .migrate();
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    @DisplayName("class_of_business_id column exists on journal_entry_line, UUID-typed, nullable")
    void columnExists() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT data_type, is_nullable, udt_name " +
                 "FROM information_schema.columns " +
                 "WHERE table_schema = 'public' " +
                 "AND table_name = 'journal_entry_line' " +
                 "AND column_name = 'class_of_business_id'")) {
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next(), "expected class_of_business_id column to exist");
            assertEquals("uuid", rs.getString("udt_name"));
            assertEquals("YES", rs.getString("is_nullable"),
                "column must be nullable — historical rows and Phase 3 IFRS-9 JEs carry null");
        }
    }

    @Test
    @DisplayName("partial index exists with the expected predicate")
    void partialIndexExists() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT indexdef FROM pg_indexes " +
                 "WHERE schemaname = 'public' " +
                 "AND tablename = 'journal_entry_line' " +
                 "AND indexname = 'idx_journal_entry_line_class_of_business'")) {
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next(), "expected idx_journal_entry_line_class_of_business to exist");
            String def = rs.getString("indexdef");
            // Predicate keeps the index lean — only non-deleted, non-null rows.
            assertTrue(def.contains("deleted_at IS NULL"),
                "expected partial predicate on deleted_at IS NULL, got: " + def);
            assertTrue(def.contains("class_of_business_id IS NOT NULL"),
                "expected partial predicate on class_of_business_id IS NOT NULL, got: " + def);
        }
    }

    @Test
    @DisplayName("NO foreign-key constraint to classes_of_business — module-boundary preservation")
    void noForeignKeyToClassesOfBusiness() throws SQLException {
        // The decision is documented in the V42 SQL header: cia-finance is a
        // downstream event consumer of class data, not a transactional
        // collaborator with cia-setup. Same pattern as portfolio_id /
        // contract_group_id (no FK to cia-setup classes table either).
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT 1 FROM information_schema.referential_constraints rc " +
                 "JOIN information_schema.key_column_usage kcu " +
                 "  ON kcu.constraint_name = rc.constraint_name " +
                 "WHERE kcu.table_schema = 'public' " +
                 "AND kcu.table_name = 'journal_entry_line' " +
                 "AND kcu.column_name = 'class_of_business_id'")) {
            ResultSet rs = ps.executeQuery();
            assertFalse(rs.next(),
                "class_of_business_id must NOT have a FK constraint; "
                + "cia-finance is a downstream event consumer of cia-setup, "
                + "not a transactional collaborator");
        }
    }

    @Test
    @DisplayName("insert with class_of_business_id round-trips correctly")
    void insertWithClassRoundtrips() throws SQLException {
        try (Connection c = conn()) {
            // Need fiscal_period + chart_of_account + journal_entry parents.
            String fyId = uuid();
            String periodId = uuid();
            String accountId;
            String jeId = uuid();
            String lineId = uuid();
            String classId = uuid();

            try (Statement st = c.createStatement()) {
                st.executeUpdate(
                    "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
                    "VALUES ('" + fyId + "', 'FY-V42', '2026-01-01', '2026-12-31', 'ACTIVE', 'test')");
                st.executeUpdate(
                    "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
                    "VALUES ('" + periodId + "', '" + fyId + "', 'QUARTER', '2026-01-01', '2026-03-31', 'OPEN', 'test')");
                // Resolve any COA seed (V32) — pick the first one available.
                try (ResultSet rs = st.executeQuery("SELECT id FROM chart_of_account LIMIT 1")) {
                    assertTrue(rs.next(), "expected V32 to seed chart_of_account");
                    accountId = rs.getString(1);
                }
                st.executeUpdate(
                    "INSERT INTO journal_entry (id, business_date, period_id, " +
                    "source_module, source_event_type, source_reference, posted_by, status, created_by) " +
                    "VALUES ('" + jeId + "', '2026-01-15', '" + periodId + "', " +
                    "'test', 'V42_TEST', 'ref-1', 'test', 'POSTED', 'test')");
                st.executeUpdate(
                    "INSERT INTO journal_entry_line (id, journal_entry_id, line_no, account_id, " +
                    "debit_amount, credit_amount, class_of_business_id, created_by) " +
                    "VALUES ('" + lineId + "', '" + jeId + "', 1, '" + accountId + "', " +
                    "100.00, 0.00, '" + classId + "', 'test')");
            }

            try (PreparedStatement ps = c.prepareStatement(
                "SELECT class_of_business_id FROM journal_entry_line WHERE id = ?::uuid")) {
                ps.setString(1, lineId);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next(), "expected the inserted row to be retrievable");
                assertEquals(classId, rs.getString("class_of_business_id"));
            }
        }
    }

    @Test
    @DisplayName("insert without class_of_business_id leaves the column null (legacy-caller compat)")
    void insertWithoutClassIsAllowed() throws SQLException {
        try (Connection c = conn()) {
            String fyId = uuid();
            String periodId = uuid();
            String accountId;
            String jeId = uuid();
            String lineId = uuid();

            try (Statement st = c.createStatement()) {
                st.executeUpdate(
                    "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
                    "VALUES ('" + fyId + "', 'FY-V42-NULL', '2026-01-01', '2026-12-31', 'ACTIVE', 'test')");
                st.executeUpdate(
                    "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
                    "VALUES ('" + periodId + "', '" + fyId + "', 'QUARTER', '2026-04-01', '2026-06-30', 'OPEN', 'test')");
                try (ResultSet rs = st.executeQuery("SELECT id FROM chart_of_account LIMIT 1")) {
                    rs.next();
                    accountId = rs.getString(1);
                }
                st.executeUpdate(
                    "INSERT INTO journal_entry (id, business_date, period_id, " +
                    "source_module, source_event_type, source_reference, posted_by, status, created_by) " +
                    "VALUES ('" + jeId + "', '2026-04-15', '" + periodId + "', " +
                    "'test', 'V42_TEST_NULL', 'ref-2', 'test', 'POSTED', 'test')");
                // No class_of_business_id in the column list — must succeed.
                st.executeUpdate(
                    "INSERT INTO journal_entry_line (id, journal_entry_id, line_no, account_id, " +
                    "debit_amount, credit_amount, created_by) " +
                    "VALUES ('" + lineId + "', '" + jeId + "', 1, '" + accountId + "', " +
                    "50.00, 0.00, 'test')");
            }

            try (PreparedStatement ps = c.prepareStatement(
                "SELECT class_of_business_id FROM journal_entry_line WHERE id = ?::uuid")) {
                ps.setString(1, lineId);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertNull(rs.getString("class_of_business_id"),
                    "absent column at insert time → null in storage");
            }
        }
    }

    private static String uuid() {
        return java.util.UUID.randomUUID().toString();
    }
}
