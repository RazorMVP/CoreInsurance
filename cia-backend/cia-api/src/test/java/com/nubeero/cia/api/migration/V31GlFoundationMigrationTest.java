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
 * Verifies V31__create_gl_foundation.sql applies cleanly and exercises every
 * CHECK / UNIQUE / FK constraint introduced by the migration. Pure JDBC + Flyway
 * + Testcontainers — no Spring context. Runs against a fresh Postgres container
 * per test class.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V31GlFoundationMigrationTest {

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
            .target("31")
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
    @DisplayName("migration applies; all 7 GL tables exist")
    void schemaApplies() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_name IN " +
                 "('chart_of_account','fiscal_year','fiscal_period','period_lock','journal_entry','journal_entry_line','posting_rule')")) {
            ResultSet rs = ps.executeQuery();
            int count = 0;
            while (rs.next()) count++;
            assertEquals(7, count, "expected all 7 GL tables to exist after V31");
        }
    }

    @Nested
    @DisplayName("chart_of_account constraints")
    class ChartOfAccount {

        @Test
        @DisplayName("ck_chart_of_account_type rejects unknown account_type")
        void rejectsBadType() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO chart_of_account (code, name, account_type) VALUES ('COA-BAD-1','Bad','BOGUS')"));
        }

        @Test
        @DisplayName("uq_chart_of_account_code rejects duplicate code")
        void rejectsDuplicateCode() throws SQLException {
            runSql("INSERT INTO chart_of_account (code, name, account_type) VALUES ('COA-DUP-1','First','ASSET')");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO chart_of_account (code, name, account_type) VALUES ('COA-DUP-1','Second','ASSET')"));
        }

        @Test
        @DisplayName("fk_chart_of_account_parent rejects unknown parent_id")
        void rejectsUnknownParent() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO chart_of_account (code, name, account_type, parent_id) " +
                "VALUES ('COA-FK-1','Orphan','ASSET','11111111-1111-1111-1111-111111111111')"));
        }

        @Test
        @DisplayName("self-referencing parent_id accepted")
        void acceptsHierarchy() throws SQLException {
            runSql("INSERT INTO chart_of_account (id, code, name, account_type) " +
                "VALUES ('22222222-2222-2222-2222-222222222222','COA-PARENT','Parent','ASSET')");
            runSql("INSERT INTO chart_of_account (code, name, account_type, parent_id) " +
                "VALUES ('COA-CHILD','Child','ASSET','22222222-2222-2222-2222-222222222222')");
        }
    }

    @Nested
    @DisplayName("fiscal_year constraints")
    class FiscalYear {

        @Test
        @DisplayName("ck_fiscal_year_dates rejects end_date <= start_date")
        void rejectsBadDates() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO fiscal_year (name, start_date, end_date) VALUES ('FY-BAD','2026-01-01','2025-12-31')"));
        }

        @Test
        @DisplayName("ck_fiscal_year_status rejects unknown status")
        void rejectsBadStatus() {
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO fiscal_year (name, start_date, end_date, status) " +
                "VALUES ('FY-BAD-STATUS','2026-01-01','2026-12-31','UNKNOWN')"));
        }

        @Test
        @DisplayName("uq_fiscal_year_name rejects duplicate name")
        void rejectsDuplicateName() throws SQLException {
            runSql("INSERT INTO fiscal_year (name, start_date, end_date) VALUES ('FY-2030','2030-01-01','2030-12-31')");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO fiscal_year (name, start_date, end_date) VALUES ('FY-2030','2030-01-01','2030-12-31')"));
        }
    }

    @Nested
    @DisplayName("fiscal_period constraints")
    class FiscalPeriod {

        private String fyId() throws SQLException {
            return runSqlReturningId(
                "INSERT INTO fiscal_year (name, start_date, end_date) " +
                "VALUES ('FY-FP-" + System.nanoTime() + "','2031-01-01','2031-12-31') RETURNING id");
        }

        @Test
        @DisplayName("ck_fiscal_period_type rejects unknown period_type")
        void rejectsBadType() throws SQLException {
            String fy = fyId();
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO fiscal_period (fiscal_year_id, period_type, start_date, end_date) " +
                "VALUES ('" + fy + "','WEEK','2031-01-01','2031-01-07')"));
        }

        @Test
        @DisplayName("ck_fiscal_period_close_chronology rejects hard_close before soft_close")
        void rejectsBadCloseOrder() throws SQLException {
            String fy = fyId();
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO fiscal_period (fiscal_year_id, period_type, start_date, end_date, " +
                "soft_closed_at, hard_closed_at) " +
                "VALUES ('" + fy + "','MONTH','2031-01-01','2031-01-31'," +
                "'2031-02-15 10:00:00+00','2031-02-10 10:00:00+00')"));
        }

        @Test
        @DisplayName("ck_fiscal_period_close_chronology rejects hard_close without soft_close")
        void rejectsHardWithoutSoft() throws SQLException {
            String fy = fyId();
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO fiscal_period (fiscal_year_id, period_type, start_date, end_date, hard_closed_at) " +
                "VALUES ('" + fy + "','MONTH','2031-02-01','2031-02-28','2031-03-15 10:00:00+00')"));
        }

        @Test
        @DisplayName("uq_fiscal_period_year_type_start rejects duplicate")
        void rejectsDuplicate() throws SQLException {
            String fy = fyId();
            runSql("INSERT INTO fiscal_period (fiscal_year_id, period_type, start_date, end_date) " +
                "VALUES ('" + fy + "','MONTH','2031-03-01','2031-03-31')");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO fiscal_period (fiscal_year_id, period_type, start_date, end_date) " +
                "VALUES ('" + fy + "','MONTH','2031-03-01','2031-04-30')"));
        }
    }

    @Nested
    @DisplayName("period_lock constraints")
    class PeriodLock {

        private String periodId() throws SQLException {
            String fyId = runSqlReturningId(
                "INSERT INTO fiscal_year (name, start_date, end_date) " +
                "VALUES ('FY-PL-" + System.nanoTime() + "','2032-01-01','2032-12-31') RETURNING id");
            return runSqlReturningId(
                "INSERT INTO fiscal_period (fiscal_year_id, period_type, start_date, end_date) " +
                "VALUES ('" + fyId + "','MONTH','2032-01-01','2032-01-31') RETURNING id");
        }

        @Test
        @DisplayName("ck_period_lock_type rejects unknown lock_type")
        void rejectsBadType() throws SQLException {
            String pid = periodId();
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO period_lock (fiscal_period_id, lock_type, locked_by) " +
                "VALUES ('" + pid + "','LOCKED','tester')"));
        }

        @Test
        @DisplayName("ck_period_lock_release rejects partial release columns")
        void rejectsPartialRelease() throws SQLException {
            String pid = periodId();
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO period_lock (fiscal_period_id, lock_type, locked_by, released_at) " +
                "VALUES ('" + pid + "','SOFT','tester','2032-02-15 10:00:00+00')"));
        }

        @Test
        @DisplayName("all-three release columns accepted")
        void acceptsFullRelease() throws SQLException {
            String pid = periodId();
            runSql("INSERT INTO period_lock (fiscal_period_id, lock_type, locked_by, " +
                "released_at, released_by, release_reason) " +
                "VALUES ('" + pid + "','SOFT','tester','2032-02-15 10:00:00+00','admin','correction')");
        }
    }

    @Nested
    @DisplayName("journal_entry constraints")
    class JournalEntry {

        private String periodId() throws SQLException {
            String fyId = runSqlReturningId(
                "INSERT INTO fiscal_year (name, start_date, end_date) " +
                "VALUES ('FY-JE-" + System.nanoTime() + "','2033-01-01','2033-12-31') RETURNING id");
            return runSqlReturningId(
                "INSERT INTO fiscal_period (fiscal_year_id, period_type, start_date, end_date) " +
                "VALUES ('" + fyId + "','MONTH','2033-01-01','2033-01-31') RETURNING id");
        }

        @Test
        @DisplayName("ck_journal_entry_dates rejects business_date > posting_date")
        void rejectsFutureBusinessDate() throws SQLException {
            String pid = periodId();
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO journal_entry (posting_date, business_date, period_id, " +
                "source_module, source_event_type, source_reference, posted_by) " +
                "VALUES ('2033-01-15','2033-01-20','" + pid + "','test','TestEvent','ref-1','tester')"));
        }

        @Test
        @DisplayName("ck_journal_entry_status rejects unknown status")
        void rejectsBadStatus() throws SQLException {
            String pid = periodId();
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO journal_entry (posting_date, business_date, period_id, " +
                "source_module, source_event_type, source_reference, posted_by, status) " +
                "VALUES ('2033-01-15','2033-01-15','" + pid + "','test','TestEvent','ref-2','tester','PENDING')"));
        }

        @Test
        @DisplayName("uq_journal_entry_idempotency rejects duplicate (module,event_type,reference)")
        void rejectsDuplicateIdempotencyTuple() throws SQLException {
            String pid = periodId();
            runSql("INSERT INTO journal_entry (posting_date, business_date, period_id, " +
                "source_module, source_event_type, source_reference, posted_by) " +
                "VALUES ('2033-01-15','2033-01-15','" + pid + "','cia-finance','DebitNoteApprovedEvent','DN-001','tester')");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO journal_entry (posting_date, business_date, period_id, " +
                "source_module, source_event_type, source_reference, posted_by) " +
                "VALUES ('2033-01-16','2033-01-16','" + pid + "','cia-finance','DebitNoteApprovedEvent','DN-001','tester')"));
        }

        @Test
        @DisplayName("same source_reference under different event_type accepted")
        void acceptsSameRefDifferentEvent() throws SQLException {
            String pid = periodId();
            runSql("INSERT INTO journal_entry (posting_date, business_date, period_id, " +
                "source_module, source_event_type, source_reference, posted_by) " +
                "VALUES ('2033-01-15','2033-01-15','" + pid + "','cia-finance','EventA','REF-X','tester')");
            runSql("INSERT INTO journal_entry (posting_date, business_date, period_id, " +
                "source_module, source_event_type, source_reference, posted_by) " +
                "VALUES ('2033-01-15','2033-01-15','" + pid + "','cia-finance','EventB','REF-X','tester')");
        }
    }

    @Nested
    @DisplayName("journal_entry_line constraints")
    class JournalEntryLine {

        private String[] periodAndAccount() throws SQLException {
            String fyId = runSqlReturningId(
                "INSERT INTO fiscal_year (name, start_date, end_date) " +
                "VALUES ('FY-JEL-" + System.nanoTime() + "','2034-01-01','2034-12-31') RETURNING id");
            String pid = runSqlReturningId(
                "INSERT INTO fiscal_period (fiscal_year_id, period_type, start_date, end_date) " +
                "VALUES ('" + fyId + "','MONTH','2034-01-01','2034-01-31') RETURNING id");
            String acctId = runSqlReturningId(
                "INSERT INTO chart_of_account (code, name, account_type) " +
                "VALUES ('COA-JEL-" + System.nanoTime() + "','Test','ASSET') RETURNING id");
            return new String[] { pid, acctId };
        }

        private String makeJe(String periodId, String ref) throws SQLException {
            return runSqlReturningId(
                "INSERT INTO journal_entry (posting_date, business_date, period_id, " +
                "source_module, source_event_type, source_reference, posted_by) " +
                "VALUES ('2034-01-15','2034-01-15','" + periodId + "','test','TestEvent','" + ref + "','tester') " +
                "RETURNING id");
        }

        @Test
        @DisplayName("valid debit-only line accepted")
        void acceptsDebitLine() throws SQLException {
            String[] pa = periodAndAccount();
            String je = makeJe(pa[0], "JEL-OK-1");
            runSql("INSERT INTO journal_entry_line (journal_entry_id, line_no, account_id, debit_amount, credit_amount) " +
                "VALUES ('" + je + "',1,'" + pa[1] + "',100.00,0)");
        }

        @Test
        @DisplayName("ck_journal_entry_line_amount rejects both-zero")
        void rejectsBothZero() throws SQLException {
            String[] pa = periodAndAccount();
            String je = makeJe(pa[0], "JEL-BAD-1");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO journal_entry_line (journal_entry_id, line_no, account_id, debit_amount, credit_amount) " +
                "VALUES ('" + je + "',1,'" + pa[1] + "',0,0)"));
        }

        @Test
        @DisplayName("ck_journal_entry_line_amount rejects both-positive")
        void rejectsBothPositive() throws SQLException {
            String[] pa = periodAndAccount();
            String je = makeJe(pa[0], "JEL-BAD-2");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO journal_entry_line (journal_entry_id, line_no, account_id, debit_amount, credit_amount) " +
                "VALUES ('" + je + "',1,'" + pa[1] + "',50.00,50.00)"));
        }

        @Test
        @DisplayName("ck_journal_entry_line_amount_nonneg rejects negative amount")
        void rejectsNegative() throws SQLException {
            String[] pa = periodAndAccount();
            String je = makeJe(pa[0], "JEL-BAD-3");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO journal_entry_line (journal_entry_id, line_no, account_id, debit_amount, credit_amount) " +
                "VALUES ('" + je + "',1,'" + pa[1] + "',-10.00,0)"));
        }

        @Test
        @DisplayName("uq_journal_entry_line_no rejects duplicate (je_id, line_no)")
        void rejectsDuplicateLineNo() throws SQLException {
            String[] pa = periodAndAccount();
            String je = makeJe(pa[0], "JEL-DUP");
            runSql("INSERT INTO journal_entry_line (journal_entry_id, line_no, account_id, debit_amount, credit_amount) " +
                "VALUES ('" + je + "',1,'" + pa[1] + "',100.00,0)");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO journal_entry_line (journal_entry_id, line_no, account_id, debit_amount, credit_amount) " +
                "VALUES ('" + je + "',1,'" + pa[1] + "',0,100.00)"));
        }

        @Test
        @DisplayName("dimension_tags JSONB defaults to empty object")
        void dimensionTagsDefault() throws SQLException {
            String[] pa = periodAndAccount();
            String je = makeJe(pa[0], "JEL-DIM");
            runSql("INSERT INTO journal_entry_line (journal_entry_id, line_no, account_id, debit_amount, credit_amount) " +
                "VALUES ('" + je + "',1,'" + pa[1] + "',100.00,0)");
            try (Connection c = conn();
                 PreparedStatement ps = c.prepareStatement(
                     "SELECT dimension_tags::text FROM journal_entry_line WHERE journal_entry_id = ?")) {
                ps.setObject(1, java.util.UUID.fromString(je));
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals("{}", rs.getString(1));
            }
        }
    }

    @Nested
    @DisplayName("posting_rule constraints")
    class PostingRule {

        @Test
        @DisplayName("ck_posting_rule_distinct_accounts rejects same debit/credit code")
        void rejectsSameAccount() throws SQLException {
            runSql("INSERT INTO chart_of_account (code, name, account_type) VALUES ('PR-SAME','Same','ASSET')");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO posting_rule (source_event_type, debit_account_code, credit_account_code) " +
                "VALUES ('TestEventSame','PR-SAME','PR-SAME')"));
        }

        @Test
        @DisplayName("fk_posting_rule_debit rejects unknown debit account code")
        void rejectsUnknownDebitCode() throws SQLException {
            runSql("INSERT INTO chart_of_account (code, name, account_type) VALUES ('PR-CR','CrOnly','LIABILITY')");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO posting_rule (source_event_type, debit_account_code, credit_account_code) " +
                "VALUES ('TestEventBadDr','NOPE-CODE','PR-CR')"));
        }

        @Test
        @DisplayName("uq_posting_rule_event rejects duplicate source_event_type")
        void rejectsDuplicateEvent() throws SQLException {
            runSql("INSERT INTO chart_of_account (code, name, account_type) VALUES ('PR-DR','DrOnly','ASSET')");
            runSql("INSERT INTO chart_of_account (code, name, account_type) VALUES ('PR-CR2','CrOnly2','LIABILITY')");
            runSql("INSERT INTO posting_rule (source_event_type, debit_account_code, credit_account_code) " +
                "VALUES ('DupEvent','PR-DR','PR-CR2')");
            assertThrows(SQLException.class, () -> runSql(
                "INSERT INTO posting_rule (source_event_type, debit_account_code, credit_account_code) " +
                "VALUES ('DupEvent','PR-DR','PR-CR2')"));
        }
    }
}
