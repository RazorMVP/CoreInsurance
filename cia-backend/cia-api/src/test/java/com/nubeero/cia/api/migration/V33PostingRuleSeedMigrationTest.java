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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code V33__seed_posting_rules.sql} delivers exactly the six
 * default rules with the expected event types, COA codes, narrative
 * templates, and metadata. Also confirms the seed is idempotent (re-run on
 * an already-migrated schema does not double-insert).
 *
 * <p>Pure JDBC + Flyway + Testcontainers — no Spring context. Mirrors the
 * structure of {@code V31GlFoundationMigrationTest} and
 * {@code V32ChartOfAccountSeedMigrationTest}.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V33PostingRuleSeedMigrationTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ciatest")
            .withUsername("ciatest")
            .withPassword("ciatest");

    /** Locked seed expectations: event_type → (debit_code, credit_code). */
    private static final Map<String, String[]> EXPECTED = new LinkedHashMap<>();
    static {
        EXPECTED.put("POLICY_APPROVED",                  new String[]{"1310", "2110"});
        EXPECTED.put("CLAIM_APPROVED",                   new String[]{"5110", "2140"});
        EXPECTED.put("CLAIM_SETTLED",                    new String[]{"2140", "1120"});
        EXPECTED.put("CLAIM_EXPENSE_APPROVED",           new String[]{"5140", "2350"});
        EXPECTED.put("ENDORSEMENT_PREMIUM_ADDITIONAL",   new String[]{"1310", "2110"});
        EXPECTED.put("ENDORSEMENT_PREMIUM_REFUND",       new String[]{"2110", "1310"});
    }

    @BeforeAll
    void migrate() {
        POSTGRES.start();
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target("33")
            .load()
            .migrate();
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    @DisplayName("V33 seeds exactly 6 active posting rules")
    void rowCount() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM posting_rule WHERE is_active = TRUE AND deleted_at IS NULL");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(6, rs.getInt(1));
        }
    }

    @Test
    @DisplayName("Each expected event type is seeded with the locked Dr/Cr account codes")
    void eachRuleHasExpectedAccountCodes() throws SQLException {
        for (Map.Entry<String, String[]> e : EXPECTED.entrySet()) {
            try (Connection c = conn();
                 PreparedStatement ps = c.prepareStatement(
                     "SELECT debit_account_code, credit_account_code FROM posting_rule WHERE source_event_type = ?")) {
                ps.setString(1, e.getKey());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "missing seed row for " + e.getKey());
                    assertEquals(e.getValue()[0], rs.getString(1), "wrong debit code for " + e.getKey());
                    assertEquals(e.getValue()[1], rs.getString(2), "wrong credit code for " + e.getKey());
                }
            }
        }
    }

    @Test
    @DisplayName("Narrative templates are populated and use %s positional placeholders")
    void narrativeTemplatesPopulated() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT source_event_type, narrative_template FROM posting_rule ORDER BY source_event_type");
             ResultSet rs = ps.executeQuery()) {
            int rowCount = 0;
            while (rs.next()) {
                rowCount++;
                String eventType = rs.getString(1);
                String template = rs.getString(2);
                assertNotNull(template, "narrative template null for " + eventType);
                assertTrue(template.contains("%s"),
                    "narrative template for " + eventType + " missing %s placeholder: " + template);
            }
            assertEquals(6, rowCount);
        }
    }

    @Test
    @DisplayName("created_by stamp is system-seed (provenance for audit)")
    void seedProvenance() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM posting_rule WHERE created_by = 'system-seed'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(6, rs.getInt(1));
        }
    }

    @Test
    @DisplayName("Re-running the V33 INSERT is idempotent (ON CONFLICT DO NOTHING)")
    void idempotent() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO posting_rule (source_event_type, debit_account_code, credit_account_code, " +
                 "narrative_template, is_active, created_by) " +
                 "VALUES ('POLICY_APPROVED', '1310', '2110', 'dup', TRUE, 'duplicate') " +
                 "ON CONFLICT (source_event_type) DO NOTHING")) {
            int affected = ps.executeUpdate();
            assertEquals(0, affected, "ON CONFLICT should suppress the duplicate insert");
        }
        // Confirm count unchanged
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM posting_rule WHERE source_event_type = 'POLICY_APPROVED'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    @DisplayName("Every Dr/Cr account code in the seed resolves to an existing chart_of_account row")
    void foreignKeyIntegrity() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM posting_rule pr " +
                 "WHERE NOT EXISTS (SELECT 1 FROM chart_of_account a WHERE a.code = pr.debit_account_code) " +
                 "   OR NOT EXISTS (SELECT 1 FROM chart_of_account a WHERE a.code = pr.credit_account_code)");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "FK integrity violation — seed references unknown COA code");
        }
    }

    @Test
    @DisplayName("ck_posting_rule_distinct_accounts holds: no rule has Dr == Cr")
    void debitAndCreditDistinct() throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM posting_rule WHERE debit_account_code = credit_account_code");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }
}
