package com.nubeero.cia.api.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies V32__seed_chart_of_accounts.sql seeds exactly the Chart of Accounts
 * defined in cia-api/src/test/resources/db/coa/expected-tree.txt. Pure JDBC +
 * Flyway + Testcontainers — no Spring context.
 *
 * The fixture is the locked contract: every code, name, account_type, parent
 * code, ifrs17_role, and ifrs9_role must match exactly. Any drift between the
 * migration and the fixture fails the build.
 *
 * Also asserts:
 *   - row counts match (5 classes + 27 groups + 97 leaves = 129)
 *   - seed is idempotent (re-running V32's INSERTs does not duplicate rows)
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V32ChartOfAccountSeedMigrationTest {

    private static final String FIXTURE_PATH = "/db/coa/expected-tree.txt";
    private static final int EXPECTED_TOTAL = 129;
    private static final int EXPECTED_CLASSES = 5;
    private static final int EXPECTED_GROUPS = 27;
    private static final int EXPECTED_LEAVES = 97;

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ciatest")
            .withUsername("ciatest")
            .withPassword("ciatest");

    private Map<String, ExpectedRow> expected;

    @BeforeAll
    void migrate() throws Exception {
        POSTGRES.start();
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target("32")
            .load()
            .migrate();
        expected = loadFixture();
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    @DisplayName("V32 seeds exactly 129 rows (5 classes + 27 groups + 97 leaves)")
    void rowCounts() throws SQLException {
        assertEquals(EXPECTED_TOTAL, count("SELECT count(*) FROM chart_of_account"),
            "total chart_of_account row count");
        assertEquals(EXPECTED_CLASSES, count("SELECT count(*) FROM chart_of_account WHERE parent_id IS NULL"),
            "Level 1 class count");
        assertEquals(EXPECTED_GROUPS, count(
            "SELECT count(*) FROM chart_of_account c " +
            "JOIN chart_of_account p ON p.id = c.parent_id " +
            "WHERE p.parent_id IS NULL"),
            "Level 2 group count (parent is class)");
        assertEquals(EXPECTED_LEAVES, count(
            "SELECT count(*) FROM chart_of_account c " +
            "JOIN chart_of_account p ON p.id = c.parent_id " +
            "JOIN chart_of_account gp ON gp.id = p.parent_id " +
            "WHERE gp.parent_id IS NULL"),
            "Level 3 leaf count (parent is group)");
    }

    @Test
    @DisplayName("every seeded row matches expected-tree.txt exactly")
    void everyRowMatchesFixture() throws SQLException {
        Map<String, ExpectedRow> actual = loadActual();

        // Code-set equality first — surfaces missing or extra rows cleanly.
        assertEquals(expected.keySet(), actual.keySet(),
            "set of account codes must match fixture exactly");

        // Field-by-field check on every row.
        for (Map.Entry<String, ExpectedRow> entry : expected.entrySet()) {
            String code = entry.getKey();
            ExpectedRow exp = entry.getValue();
            ExpectedRow act = actual.get(code);
            assertNotNull(act, "missing row for code " + code);

            assertEquals(exp.name, act.name,
                "name mismatch for code " + code);
            assertEquals(exp.accountType, act.accountType,
                "account_type mismatch for code " + code);
            assertEquals(exp.parentCode, act.parentCode,
                "parent code mismatch for code " + code);
            assertEquals(exp.ifrs17Role, act.ifrs17Role,
                "ifrs17_role mismatch for code " + code);
            assertEquals(exp.ifrs9Role, act.ifrs9Role,
                "ifrs9_role mismatch for code " + code);
        }
    }

    @Test
    @DisplayName("ifrs17_role coverage matches fixture (every tag present)")
    void ifrs17RoleCoverage() throws SQLException {
        long expectedTagged = expected.values().stream()
            .filter(r -> r.ifrs17Role != null).count();
        long actualTagged = count(
            "SELECT count(*) FROM chart_of_account WHERE ifrs17_role IS NOT NULL");
        assertEquals(expectedTagged, actualTagged,
            "number of rows with non-null ifrs17_role must match fixture");
    }

    @Test
    @DisplayName("ifrs9_role coverage matches fixture (every tag present)")
    void ifrs9RoleCoverage() throws SQLException {
        long expectedTagged = expected.values().stream()
            .filter(r -> r.ifrs9Role != null).count();
        long actualTagged = count(
            "SELECT count(*) FROM chart_of_account WHERE ifrs9_role IS NOT NULL");
        assertEquals(expectedTagged, actualTagged,
            "number of rows with non-null ifrs9_role must match fixture");
    }

    @Test
    @DisplayName("seed is idempotent — ON CONFLICT (code) DO NOTHING blocks duplicates")
    void seedIsIdempotent() throws SQLException {
        // Re-run a representative class-row insert. The unique code constraint
        // plus ON CONFLICT DO NOTHING must short-circuit the duplicate cleanly.
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO chart_of_account (code, name, account_type, parent_id) " +
                 "VALUES ('1000','Assets','ASSET',NULL) ON CONFLICT (code) DO NOTHING")) {
            ps.execute();
        }
        assertEquals(EXPECTED_TOTAL, count("SELECT count(*) FROM chart_of_account"),
            "row count must be unchanged after re-applying a seed insert");
    }

    @Test
    @DisplayName("created_by stamp is system-seed on every seeded row")
    void createdByStamp() throws SQLException {
        assertEquals(0L, count(
            "SELECT count(*) FROM chart_of_account WHERE created_by IS DISTINCT FROM 'system-seed'"),
            "every seeded row must carry created_by='system-seed'");
    }

    @Test
    @DisplayName("is_active defaults to TRUE on every seeded row")
    void isActiveDefault() throws SQLException {
        assertEquals(0L, count(
            "SELECT count(*) FROM chart_of_account WHERE is_active IS NOT TRUE"),
            "every seeded row must be is_active=TRUE");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private long count(String sql) throws SQLException {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private Map<String, ExpectedRow> loadActual() throws SQLException {
        Map<String, ExpectedRow> rows = new TreeMap<>();
        String sql =
            "SELECT c.code, c.name, c.account_type, p.code AS parent_code, c.ifrs17_role, c.ifrs9_role " +
            "FROM chart_of_account c " +
            "LEFT JOIN chart_of_account p ON p.id = c.parent_id " +
            "ORDER BY c.code";
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.put(rs.getString("code"), new ExpectedRow(
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getString("account_type"),
                    rs.getString("parent_code"),
                    rs.getString("ifrs17_role"),
                    rs.getString("ifrs9_role")));
            }
        }
        return rows;
    }

    private Map<String, ExpectedRow> loadFixture() throws Exception {
        Map<String, ExpectedRow> rows = new LinkedHashMap<>();
        InputStream stream = V32ChartOfAccountSeedMigrationTest.class.getResourceAsStream(FIXTURE_PATH);
        Objects.requireNonNull(stream, "fixture not found on classpath: " + FIXTURE_PATH);
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split("\\|", -1);
                if (parts.length != 6) {
                    throw new IllegalStateException("malformed fixture row: " + line);
                }
                rows.put(parts[0], new ExpectedRow(
                    parts[0],
                    parts[1],
                    parts[2],
                    fromDash(parts[3]),
                    fromDash(parts[4]),
                    fromDash(parts[5])));
            }
        }
        return rows;
    }

    private static String fromDash(String value) {
        return "-".equals(value) ? null : value;
    }

    private record ExpectedRow(
        String code,
        String name,
        String accountType,
        String parentCode,
        String ifrs17Role,
        String ifrs9Role
    ) {}
}
