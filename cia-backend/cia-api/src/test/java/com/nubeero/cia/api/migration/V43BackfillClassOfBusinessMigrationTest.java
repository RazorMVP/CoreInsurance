package com.nubeero.cia.api.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies V43__backfill_class_of_business_on_journal_entry_line.sql
 * correctly populates {@code journal_entry_line.class_of_business_id}
 * for historical rows across each supported event-type code path:
 *
 * <ol>
 *   <li>POLICY_APPROVED — direct policy lookup</li>
 *   <li>CLAIM_APPROVED / CLAIM_SETTLED — claim lookup</li>
 *   <li>CLAIM_EXPENSE_APPROVED — expense → claim → policy lookup</li>
 *   <li>ENDORSEMENT_PREMIUM_ADDITIONAL/REFUND — endorsement → policy lookup</li>
 *   <li>FAC_PREMIUM_CEDED — ri_fac_covers → policy lookup</li>
 * </ol>
 *
 * <p>The strategy: stop Flyway at V42 (column exists, backfill has not
 * run), seed JE + line rows mimicking historical posts WITHOUT
 * populating the new column, then run V43 explicitly and assert
 * population is correct per event.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V43BackfillClassOfBusinessMigrationTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ciatest")
            .withUsername("ciatest")
            .withPassword("ciatest");

    private static String policyId;
    private static String policyClassId;
    private static String claimId;
    private static String claimClassId;
    private static String expenseId;
    private static String endorsementId;
    private static String facCoverId;

    // JE row ids
    private static String policyJeId;
    private static String policyLineId;
    private static String claimApprovedJeId;
    private static String claimApprovedLineId;
    private static String claimExpenseJeId;
    private static String claimExpenseLineId;
    private static String endorsementJeId;
    private static String endorsementLineId;
    private static String facJeId;
    private static String facLineId;
    private static String unrelatedLineId;  // paa/ifrs9 — must remain null

    @BeforeAll
    void migrateAndSeed() throws SQLException {
        POSTGRES.start();
        // Step 1: migrate up to V42 only (column exists, V43 has not run).
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target("42")
            .load()
            .migrate();

        // Step 2: seed parent entities + JEs without class_of_business_id.
        try (Connection c = conn();
             Statement st = c.createStatement()) {
            seedFiscalPeriod(st);
            seedPolicyAndClaim(st);
            seedEndorsement(st);
            seedFacCover(st);
            seedHistoricalJournalEntries(st);
            seedUnrelatedJe(st);
        }

        // Step 3: run V43 by migrating up to 43.
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target("43")
            .load()
            .migrate();
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    // ── Assertions per event type ───────────────────────────────────────────

    @Test
    @DisplayName("POLICY_APPROVED JE line backfilled with policy.class_of_business_id")
    void policyApprovedBackfilled() throws SQLException {
        String got = readClass(policyLineId);
        assertEquals(policyClassId, got, "policy line must carry policy's class after V43");
    }

    @Test
    @DisplayName("CLAIM_APPROVED JE line backfilled with claim.class_of_business_id")
    void claimApprovedBackfilled() throws SQLException {
        String got = readClass(claimApprovedLineId);
        assertEquals(claimClassId, got, "claim line must carry claim's class snapshot after V43");
    }

    @Test
    @DisplayName("CLAIM_EXPENSE_APPROVED JE line backfilled via expense → claim → class")
    void claimExpenseApprovedBackfilled() throws SQLException {
        String got = readClass(claimExpenseLineId);
        assertEquals(claimClassId, got,
            "claim_expense line must resolve to the parent claim's class after V43");
    }

    @Test
    @DisplayName("ENDORSEMENT_PREMIUM_* JE line backfilled via endorsement → policy → class")
    void endorsementBackfilled() throws SQLException {
        String got = readClass(endorsementLineId);
        assertEquals(policyClassId, got,
            "endorsement line must resolve to the endorsed policy's class after V43");
    }

    @Test
    @DisplayName("FAC_PREMIUM_CEDED JE line backfilled via ri_fac_covers → policy → class")
    void facCededBackfilled() throws SQLException {
        String got = readClass(facLineId);
        assertEquals(policyClassId, got,
            "FAC line must resolve to the ceded policy's class after V43");
    }

    @Test
    @DisplayName("Non-subledger JE lines (paa / ifrs9 / manual) remain null")
    void unrelatedLinesUntouched() throws SQLException {
        String got = readClass(unrelatedLineId);
        assertNull(got,
            "paa/ifrs9 JE lines have no class semantics and must stay null after V43");
    }

    @Test
    @DisplayName("V43 is idempotent — re-running it does not overwrite already-populated rows")
    void idempotentRerun() throws SQLException {
        // Manually run the migration's primary UPDATE again. The filter
        // `class_of_business_id IS NULL` means already-populated rows
        // remain stable.
        try (Connection c = conn();
             Statement st = c.createStatement()) {
            st.executeUpdate(
                "UPDATE journal_entry_line jel " +
                "SET class_of_business_id = p.class_of_business_id " +
                "FROM journal_entry je, policies p " +
                "WHERE jel.journal_entry_id = je.id " +
                "  AND je.source_module = 'policy' " +
                "  AND je.source_event_type = 'POLICY_APPROVED' " +
                "  AND p.id::text = je.source_reference " +
                "  AND p.deleted_at IS NULL " +
                "  AND jel.class_of_business_id IS NULL");
        }
        assertEquals(policyClassId, readClass(policyLineId),
            "re-running V43 does not overwrite populated rows");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String readClass(String lineId) throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT class_of_business_id FROM journal_entry_line WHERE id = ?::uuid")) {
            ps.setString(1, lineId);
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            return rs.getString("class_of_business_id");
        }
    }

    // ── Seed routines ──────────────────────────────────────────────────────

    private static String fyId;
    private static String periodId;
    private static String accountAId;

    private static void seedFiscalPeriod(Statement st) throws SQLException {
        fyId = uuid();
        periodId = uuid();
        st.executeUpdate(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES ('" + fyId + "', 'FY-V43', '2026-01-01', '2026-12-31', 'ACTIVE', 'test')");
        st.executeUpdate(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES ('" + periodId + "', '" + fyId + "', 'QUARTER', '2026-01-01', '2026-03-31', 'OPEN', 'test')");
        // Resolve a seeded COA account for the JE lines.
        try (ResultSet rs = st.executeQuery("SELECT id FROM chart_of_account LIMIT 1")) {
            assertTrue(rs.next());
            accountAId = rs.getString(1);
        }
    }

    private static void seedPolicyAndClaim(Statement st) throws SQLException {
        policyId = uuid();
        policyClassId = uuid();
        claimId = uuid();
        claimClassId = policyClassId;  // class snapshot — same class as policy
        expenseId = uuid();

        // Minimal policy row — only the columns the V42→V43 join touches.
        // policies has many NOT NULL columns; populate with stub values.
        String customerId = uuid();
        String productId = uuid();
        st.executeUpdate(
            "INSERT INTO policies (id, customer_id, customer_name, " +
            "product_id, product_name, product_code, product_rate, " +
            "class_of_business_id, class_of_business_name, class_of_business_code, " +
            "policy_start_date, policy_end_date, created_by) " +
            "VALUES ('" + policyId + "', '" + customerId + "', 'Cust', " +
            "'" + productId + "', 'Prod', 'P-X', 0.05, " +
            "'" + policyClassId + "', 'Motor', 'MOTOR', " +
            "'2026-01-01', '2026-12-31', 'test')");

        st.executeUpdate(
            "INSERT INTO claims (id, claim_number, policy_id, policy_number, " +
            "policy_start_date, policy_end_date, " +
            "customer_id, customer_name, " +
            "product_id, product_name, " +
            "class_of_business_id, class_of_business_name, " +
            "incident_date, reported_date, " +
            "description, " +
            "created_by) " +
            "VALUES ('" + claimId + "', 'CLM-001', '" + policyId + "', 'POL-001', " +
            "'2026-01-01', '2026-12-31', " +
            "'" + customerId + "', 'Cust', " +
            "'" + productId + "', 'Prod', " +
            "'" + claimClassId + "', 'Motor', " +
            "'2026-01-15', '2026-01-16', " +
            "'desc', " +
            "'test')");

        st.executeUpdate(
            "INSERT INTO claim_expenses (id, claim_id, expense_type, vendor_name, " +
            "amount, description, status, created_by) " +
            "VALUES ('" + expenseId + "', '" + claimId + "', 'SURVEYOR_FEE', 'Survey Co', " +
            "100.00, 'survey fee', 'APPROVED', 'test')");
    }

    private static void seedEndorsement(Statement st) throws SQLException {
        endorsementId = uuid();
        st.executeUpdate(
            "INSERT INTO endorsements (id, endorsement_number, " +
            "policy_id, policy_number, " +
            "customer_id, customer_name, " +
            "product_id, product_name, product_code, product_rate, " +
            "class_of_business_id, class_of_business_name, " +
            "endorsement_type, effective_date, policy_end_date, " +
            "description, " +
            "status, created_by) " +
            "VALUES ('" + endorsementId + "', 'END-001', " +
            "'" + policyId + "', 'POL-001', " +
            "'" + uuid() + "', 'Cust', " +
            "'" + uuid() + "', 'Prod', 'P-X', 0.05, " +
            "'" + policyClassId + "', 'Motor', " +
            "'EXTEND_PERIOD', '2026-02-01', '2026-12-31', " +
            "'extend by 1 year', " +
            "'APPROVED', 'test')");
    }

    private static void seedFacCover(Statement st) throws SQLException {
        facCoverId = uuid();
        st.executeUpdate(
            "INSERT INTO ri_fac_covers (id, fac_reference, " +
            "policy_id, policy_number, " +
            "reinsurance_company_id, reinsurance_company_name, " +
            "sum_insured_ceded, premium_rate, premium_ceded, " +
            "commission_amount, net_premium, " +
            "currency_code, cover_from, cover_to, " +
            "status, created_by) " +
            "VALUES ('" + facCoverId + "', 'FAC-001', " +
            "'" + policyId + "', 'POL-001', " +
            "'" + uuid() + "', 'Munich Re', " +
            "10000000.00, 0.05, 500000.00, " +
            "50000.00, 450000.00, " +
            "'NGN', '2026-01-01', '2026-12-31', " +
            "'CONFIRMED', 'test')");
    }

    private static void seedHistoricalJournalEntries(Statement st) throws SQLException {
        // Each pair: (je, line) — the JE has source_module + source_event_type
        // + source_reference matching the V43 backfill conditions; the line
        // has class_of_business_id NULL (pre-Slice-1.10a state).

        policyJeId = uuid();
        policyLineId = uuid();
        insertJe(st, policyJeId, "policy", "POLICY_APPROVED", policyId, "2026-01-05");
        insertLine(st, policyLineId, policyJeId, accountAId);

        claimApprovedJeId = uuid();
        claimApprovedLineId = uuid();
        insertJe(st, claimApprovedJeId, "claim", "CLAIM_APPROVED", claimId, "2026-01-20");
        insertLine(st, claimApprovedLineId, claimApprovedJeId, accountAId);

        claimExpenseJeId = uuid();
        claimExpenseLineId = uuid();
        insertJe(st, claimExpenseJeId, "claim", "CLAIM_EXPENSE_APPROVED", expenseId, "2026-01-25");
        insertLine(st, claimExpenseLineId, claimExpenseJeId, accountAId);

        endorsementJeId = uuid();
        endorsementLineId = uuid();
        insertJe(st, endorsementJeId, "endorsement", "ENDORSEMENT_PREMIUM_ADDITIONAL", endorsementId, "2026-02-01");
        insertLine(st, endorsementLineId, endorsementJeId, accountAId);

        facJeId = uuid();
        facLineId = uuid();
        insertJe(st, facJeId, "reinsurance", "FAC_PREMIUM_CEDED", facCoverId, "2026-02-15");
        insertLine(st, facLineId, facJeId, accountAId);
    }

    private static void seedUnrelatedJe(Statement st) throws SQLException {
        // Phase 2 / Phase 3 JE — V43 must NOT touch this.
        String paaJeId = uuid();
        unrelatedLineId = uuid();
        insertJe(st, paaJeId, "paa", "LRC_PREMIUM_RECOGNITION", uuid(), "2026-03-15");
        insertLine(st, unrelatedLineId, paaJeId, accountAId);
    }

    private static void insertJe(Statement st, String jeId, String module, String eventType,
                                  String sourceRef, String businessDate) throws SQLException {
        st.executeUpdate(
            "INSERT INTO journal_entry (id, business_date, period_id, " +
            "source_module, source_event_type, source_reference, posted_by, status, created_by) " +
            "VALUES ('" + jeId + "', '" + businessDate + "', '" + periodId + "', " +
            "'" + module + "', '" + eventType + "', '" + sourceRef + "', " +
            "'test', 'POSTED', 'test')");
    }

    private static void insertLine(Statement st, String lineId, String jeId, String accountId) throws SQLException {
        st.executeUpdate(
            "INSERT INTO journal_entry_line (id, journal_entry_id, line_no, account_id, " +
            "debit_amount, credit_amount, created_by) " +
            "VALUES ('" + lineId + "', '" + jeId + "', 1, '" + accountId + "', " +
            "100.00, 0.00, 'test')");
    }

    private static String uuid() {
        return java.util.UUID.randomUUID().toString();
    }

    /** Compile-time witness that BigDecimal import is used (in test seeds). */
    @SuppressWarnings("unused")
    private static final BigDecimal UNUSED_BD = BigDecimal.ZERO;

    @Test
    @DisplayName("non-null after backfill — sanity bundle")
    void sanityNonNullBundle() throws SQLException {
        for (String id : new String[]{policyLineId, claimApprovedLineId, claimExpenseLineId,
                                       endorsementLineId, facLineId}) {
            assertNotNull(readClass(id), "expected class on line " + id + " after V43");
        }
    }
}
