package com.nubeero.cia.api.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * Verifies V41__create_naicom_submission_foundation.sql applies cleanly and
 * exercises every CHECK / UNIQUE / FK constraint introduced by the migration.
 *
 * <p>Mirrors {@code V39Ifrs9FoundationMigrationTest}'s structure for Phase 3.
 *
 * <p>Tested constraints (per Phase 4 Slice 4.1 schema design):
 * <ul>
 *   <li>3 tables present after migration</li>
 *   <li>{@code submission_type} CK rejects unknown values</li>
 *   <li>{@code state} CK rejects unknown values</li>
 *   <li>State→required-field invariants for SUBMITTED, ACKNOWLEDGED, ARCHIVED, RETRACTED</li>
 *   <li>{@code period_end >= period_start} CK</li>
 *   <li>UNIQUE(submission_type, period_id) idempotency partial index</li>
 *   <li>Artifact format CK + SHA-256-length CK + size-nonneg CK</li>
 *   <li>Artifact UNIQUE(submission_id, format) partial index</li>
 *   <li>Event state CKs + no-op-only-draft CK</li>
 *   <li>CASCADE DELETE from submission → artifacts + events</li>
 * </ul>
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V41NaicomSubmissionFoundationMigrationTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ciatest")
            .withUsername("ciatest")
            .withPassword("ciatest");

    private String fiscalYearId;
    private String periodId;

    @BeforeAll
    void migrate() {
        POSTGRES.start();
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target("41")
            .load()
            .migrate();
    }

    @BeforeEach
    void seedFiscalPeriod() throws SQLException {
        // Wipe and reseed a fiscal_period per test so UNIQUE(submission_type,
        // period_id) idempotency assertions start from a clean slate.
        runSql("DELETE FROM naicom_submission_event");
        runSql("DELETE FROM naicom_submission_artifact");
        runSql("DELETE FROM naicom_submission");
        runSql("DELETE FROM fiscal_period");
        runSql("DELETE FROM fiscal_year");

        fiscalYearId = runSqlReturningId(
            "INSERT INTO fiscal_year (name, start_date, end_date, status) " +
            "VALUES ('FY-2026-MIG-TEST', '2026-01-01', '2026-12-31', 'ACTIVE') RETURNING id");
        periodId = runSqlReturningId(
            "INSERT INTO fiscal_period (fiscal_year_id, period_type, start_date, end_date, status) " +
            "VALUES ('" + fiscalYearId + "', 'MONTH', '2026-04-01', '2026-04-30', 'HARD_CLOSED') RETURNING id");
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
    @DisplayName("V41 applies; all 3 NAICOM submission tables exist")
    void schemaApplies() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT table_name FROM information_schema.tables " +
                 "WHERE table_schema='public' AND table_name IN " +
                 "('naicom_submission','naicom_submission_artifact','naicom_submission_event')")) {
            ResultSet rs = ps.executeQuery();
            int count = 0;
            while (rs.next()) count++;
            assertEquals(3, count, "expected all 3 NAICOM submission tables to exist after V41");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // naicom_submission
    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("naicom_submission constraints")
    class SubmissionConstraints {

        @Test
        @DisplayName("can insert a valid DRAFT submission")
        void canInsertDraft() throws SQLException {
            runSql(insertDraftSubmission("PREMIUM_BORDEREAUX"));
            try (Connection c = conn();
                 PreparedStatement ps = c.prepareStatement(
                     "SELECT state FROM naicom_submission WHERE submission_type='PREMIUM_BORDEREAUX'")) {
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals("DRAFT", rs.getString("state"));
            }
        }

        @Test
        @DisplayName("CK rejects unknown submission_type")
        void rejectsUnknownType() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO naicom_submission (submission_type, period_id, period_start, period_end) " +
                "VALUES ('FOO_BAR', '" + periodId + "', '2026-04-01', '2026-04-30')"));
        }

        @Test
        @DisplayName("CK rejects unknown state")
        void rejectsUnknownState() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO naicom_submission (submission_type, period_id, period_start, period_end, state) " +
                "VALUES ('PREMIUM_BORDEREAUX', '" + periodId + "', '2026-04-01', '2026-04-30', 'WAITING')"));
        }

        @Test
        @DisplayName("CK rejects SUBMITTED without submitted_at + submitted_by")
        void rejectsSubmittedWithoutFields() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO naicom_submission (submission_type, period_id, period_start, period_end, state) " +
                "VALUES ('PREMIUM_BORDEREAUX', '" + periodId + "', '2026-04-01', '2026-04-30', 'SUBMITTED')"));
        }

        @Test
        @DisplayName("CK rejects ACKNOWLEDGED without acknowledged_at + naicom_uid")
        void rejectsAcknowledgedWithoutFields() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO naicom_submission " +
                "(submission_type, period_id, period_start, period_end, state, submitted_at, submitted_by) " +
                "VALUES ('PREMIUM_BORDEREAUX', '" + periodId + "', '2026-04-01', '2026-04-30', " +
                "'ACKNOWLEDGED', now(), 'tester')"));
        }

        @Test
        @DisplayName("CK rejects RETRACTED without retracted_at + retracted_by")
        void rejectsRetractedWithoutFields() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO naicom_submission " +
                "(submission_type, period_id, period_start, period_end, state, submitted_at, submitted_by) " +
                "VALUES ('PREMIUM_BORDEREAUX', '" + periodId + "', '2026-04-01', '2026-04-30', " +
                "'RETRACTED', now(), 'tester')"));
        }

        @Test
        @DisplayName("CK rejects ARCHIVED without archived_at")
        void rejectsArchivedWithoutTimestamp() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO naicom_submission " +
                "(submission_type, period_id, period_start, period_end, state, submitted_at, submitted_by, " +
                "acknowledged_at, acknowledged_by, naicom_uid) " +
                "VALUES ('PREMIUM_BORDEREAUX', '" + periodId + "', '2026-04-01', '2026-04-30', " +
                "'ARCHIVED', now(), 'tester', now(), 'naicom', 'NAICOM-ACK-001')"));
        }

        @Test
        @DisplayName("CK rejects period_end < period_start")
        void rejectsInvertedPeriodRange() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO naicom_submission (submission_type, period_id, period_start, period_end) " +
                "VALUES ('PREMIUM_BORDEREAUX', '" + periodId + "', '2026-04-30', '2026-04-01')"));
        }

        @Test
        @DisplayName("UNIQUE(submission_type, period_id) rejects second live row")
        void uniqueIdempotencyKey() throws SQLException {
            runSql(insertDraftSubmission("PREMIUM_BORDEREAUX"));
            assertThrows(SQLException.class, () ->
                runSql(insertDraftSubmission("PREMIUM_BORDEREAUX")));
        }

        @Test
        @DisplayName("UNIQUE(submission_type, period_id) allows insert after soft-delete")
        void uniqueIdempotencyAllowsAfterSoftDelete() throws SQLException {
            runSql(insertDraftSubmission("PREMIUM_BORDEREAUX"));
            runSql("UPDATE naicom_submission SET deleted_at = now() " +
                   "WHERE submission_type = 'PREMIUM_BORDEREAUX'");
            // Second live row now allowed.
            runSql(insertDraftSubmission("PREMIUM_BORDEREAUX"));
        }

        @Test
        @DisplayName("FK rejects non-existent period_id")
        void rejectsBadPeriodFk() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO naicom_submission (submission_type, period_id, period_start, period_end) " +
                "VALUES ('PREMIUM_BORDEREAUX', '00000000-0000-0000-0000-000000000000', " +
                "'2026-04-01', '2026-04-30')"));
        }

        @Test
        @DisplayName("happy path — full SUBMITTED row with all required fields")
        void fullSubmittedRowAccepted() throws SQLException {
            runSql(
                "INSERT INTO naicom_submission " +
                "(submission_type, period_id, period_start, period_end, state, " +
                "submitted_at, submitted_by, notes) " +
                "VALUES ('CLAIMS_BORDEREAUX', '" + periodId + "', '2026-04-01', '2026-04-30', " +
                "'SUBMITTED', now(), 'cfo@tenant', 'April recap')");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // naicom_submission_artifact
    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("naicom_submission_artifact constraints")
    class ArtifactConstraints {

        private String submissionId;

        @BeforeEach
        void createSubmission() throws SQLException {
            submissionId = runSqlReturningId(
                "INSERT INTO naicom_submission (submission_type, period_id, period_start, period_end) " +
                "VALUES ('PREMIUM_BORDEREAUX', '" + periodId + "', '2026-04-01', '2026-04-30') RETURNING id");
        }

        @Test
        @DisplayName("can insert a valid PDF artifact")
        void canInsertPdf() throws SQLException {
            runSql(insertArtifact(submissionId, "PDF", validSha256(), 1024));
        }

        @Test
        @DisplayName("CK rejects unknown format")
        void rejectsUnknownFormat() {
            assertThrows(SQLException.class, () ->
                runSql(insertArtifact(submissionId, "DOCX", validSha256(), 1024)));
        }

        @Test
        @DisplayName("CK rejects SHA-256 of wrong length")
        void rejectsBadShaLength() {
            assertThrows(SQLException.class, () ->
                runSql(insertArtifact(submissionId, "PDF", "abc", 1024)));
        }

        @Test
        @DisplayName("CK rejects negative size_bytes")
        void rejectsNegativeSize() {
            assertThrows(SQLException.class, () ->
                runSql(insertArtifact(submissionId, "PDF", validSha256(), -1)));
        }

        @Test
        @DisplayName("UNIQUE(submission_id, format) rejects duplicate live artifact")
        void uniquePerFormat() throws SQLException {
            runSql(insertArtifact(submissionId, "PDF", validSha256(), 1024));
            assertThrows(SQLException.class, () ->
                runSql(insertArtifact(submissionId, "PDF", validSha256(), 2048)));
        }

        @Test
        @DisplayName("UNIQUE(submission_id, format) allows distinct formats per submission")
        void distinctFormatsPerSubmission() throws SQLException {
            runSql(insertArtifact(submissionId, "PDF", validSha256(), 1024));
            runSql(insertArtifact(submissionId, "CSV", validSha256(), 512));
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // naicom_submission_event
    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("naicom_submission_event constraints")
    class EventConstraints {

        private String submissionId;

        @BeforeEach
        void createSubmission() throws SQLException {
            submissionId = runSqlReturningId(
                "INSERT INTO naicom_submission (submission_type, period_id, period_start, period_end) " +
                "VALUES ('PREMIUM_BORDEREAUX', '" + periodId + "', '2026-04-01', '2026-04-30') RETURNING id");
        }

        @Test
        @DisplayName("initial DRAFT-creation event accepts NULL from_state")
        void initialEventNullFromState() throws SQLException {
            runSql(
                "INSERT INTO naicom_submission_event (submission_id, from_state, to_state, actor) " +
                "VALUES ('" + submissionId + "', NULL, 'DRAFT', 'tester')");
        }

        @Test
        @DisplayName("CK rejects no-op transition unless DRAFT→DRAFT (re-generation)")
        void rejectsNonDraftNoOp() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO naicom_submission_event (submission_id, from_state, to_state, actor) " +
                "VALUES ('" + submissionId + "', 'SUBMITTED', 'SUBMITTED', 'tester')"));
        }

        @Test
        @DisplayName("DRAFT→DRAFT is accepted (captures re-generation)")
        void draftToDraftAccepted() throws SQLException {
            runSql(
                "INSERT INTO naicom_submission_event (submission_id, from_state, to_state, reason, actor) " +
                "VALUES ('" + submissionId + "', 'DRAFT', 'DRAFT', 'Re-generation: figures refreshed', 'engine')");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Cascade behaviour
    // ────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("ON DELETE CASCADE removes artifacts and events when parent submission is deleted")
    void cascadeDelete() throws SQLException {
        String subId = runSqlReturningId(
            "INSERT INTO naicom_submission (submission_type, period_id, period_start, period_end) " +
            "VALUES ('CLAIMS_BORDEREAUX', '" + periodId + "', '2026-04-01', '2026-04-30') RETURNING id");
        runSql(insertArtifact(subId, "PDF", validSha256(), 1024));
        runSql(
            "INSERT INTO naicom_submission_event (submission_id, to_state, actor) " +
            "VALUES ('" + subId + "', 'DRAFT', 'tester')");

        runSql("DELETE FROM naicom_submission WHERE id = '" + subId + "'");

        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT (SELECT COUNT(*) FROM naicom_submission_artifact WHERE submission_id = '" + subId + "') AS arts, " +
                 "(SELECT COUNT(*) FROM naicom_submission_event WHERE submission_id = '" + subId + "') AS evs")) {
            ResultSet rs = ps.executeQuery();
            rs.next();
            assertEquals(0, rs.getInt("arts"), "artifacts should cascade-delete with parent submission");
            assertEquals(0, rs.getInt("evs"), "events should cascade-delete with parent submission");
        }
    }

    // ── Fixture helpers ────────────────────────────────────────────────────
    private String insertDraftSubmission(String type) {
        return "INSERT INTO naicom_submission (submission_type, period_id, period_start, period_end) " +
            "VALUES ('" + type + "', '" + periodId + "', '2026-04-01', '2026-04-30')";
    }

    private static String insertArtifact(String submissionId, String format, String sha, long size) {
        return "INSERT INTO naicom_submission_artifact " +
            "(submission_id, format, storage_path, size_bytes, sha256_hex) " +
            "VALUES ('" + submissionId + "', '" + format + "', " +
            "'naicom/sub/" + submissionId + "." + format.toLowerCase() + "', " +
            size + ", '" + sha + "')";
    }

    private static String validSha256() {
        // 64 lowercase hex chars
        return "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    }
}
