package com.nubeero.cia.api.tenant;

import com.nubeero.cia.setup.keycloak.BootstrapRoles;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Seeds the sensible-defaults baseline into a freshly migrated tenant schema: an Administrators
 * access group (+ permissions), the NGN default currency, and the customer-number-format singleton.
 * Every insert is existence-guarded so re-running is a no-op. No users row is written — users live
 * in Keycloak. No policy-number format is seeded — it is per-product and created during product setup.
 */
@Slf4j
@Component
public class TenantSeeder {

    private final DataSource dataSource;

    public TenantSeeder(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void seed(String schema, UUID adminGroupId) {
        TenantSchemas.validate(schema);            // FIX 3: shared validator (security boundary)
        if (adminGroupId == null) {                // FIX 2: null guard
            throw new IllegalArgumentException("adminGroupId must not be null");
        }
        // FIX 1: pin the entire seed body to ONE physical connection so SET search_path holds.
        // JdbcTemplate normally borrows a pooled connection per statement; under pool
        // eviction/concurrency the INSERTs could run on a different connection and write into public.
        // SingleConnectionDataSource(conn, suppressClose=true) wraps the live connection so that
        // JdbcTemplate operations never close it — every seed*() call executes on the same socket.
        try (Connection conn = dataSource.getConnection()) {
            try {
                try (Statement st = conn.createStatement()) {
                    st.execute("SET search_path TO \"" + schema + "\"");
                }
                // suppressClose=true so JdbcTemplate operations don't close the real connection;
                // every seed*() call now runs on the SAME physical connection, so search_path holds.
                JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
                seedAdminGroup(jdbc, adminGroupId);
                seedCurrency(jdbc);
                seedCustomerNumberFormat(jdbc);
            } finally {
                // Reset search_path so this pooled connection isn't returned to Hikari poisoned.
                // conn.setSchema mirrors MultiTenantConnectionProvider.releaseConnection behaviour.
                conn.setSchema("public");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to seed tenant schema " + schema, e);
        }
        log.info("Tenant schema '{}' seeded (admin group {})", schema, adminGroupId);
    }

    private void seedAdminGroup(JdbcTemplate jdbc, UUID adminGroupId) {
        // ON CONFLICT (id): re-seeding with the same adminGroupId is a no-op; a different id for
        // 'Administrators' would be a caller contract violation and should fail loud.
        jdbc.update("""
            INSERT INTO access_groups (id, name, description, created_at, updated_at, created_by)
            VALUES (?, 'Administrators', 'Full system access (bootstrap)', NOW(), NOW(), 'system')
            ON CONFLICT (id) DO NOTHING
            """, adminGroupId);
        for (String permission : BootstrapRoles.ADMIN_PERMISSIONS) {
            jdbc.update("""
                INSERT INTO access_group_permissions
                    (id, access_group_id, permission, created_at, updated_at, created_by)
                VALUES (gen_random_uuid(), ?, ?, NOW(), NOW(), 'system')
                ON CONFLICT (access_group_id, permission) DO NOTHING
                """, adminGroupId, permission);
        }
    }

    private void seedCurrency(JdbcTemplate jdbc) {
        Integer existing = jdbc.queryForObject(
            "SELECT COUNT(*) FROM currencies WHERE code = 'NGN'", Integer.class);
        if (existing == null || existing == 0) {
            jdbc.update("""
                INSERT INTO currencies (id, code, name, symbol, is_default, created_at, updated_at, created_by)
                VALUES (gen_random_uuid(), 'NGN', 'Nigerian Naira', '₦', TRUE, NOW(), NOW(), 'system')
                """);
        }
    }

    private void seedCustomerNumberFormat(JdbcTemplate jdbc) {
        Integer existing = jdbc.queryForObject(
            "SELECT COUNT(*) FROM customer_number_format", Integer.class);
        if (existing == null || existing == 0) {
            jdbc.update("""
                INSERT INTO customer_number_format
                    (id, prefix, include_year, include_type, sequence_length,
                     last_sequence, last_sequence_individual, last_sequence_corporate,
                     created_at, updated_at, created_by)
                VALUES (gen_random_uuid(), 'CUST', TRUE, TRUE, 8, 0, 0, 0, NOW(), NOW(), 'system')
                """);
        }
    }
}
