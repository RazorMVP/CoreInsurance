package com.nubeero.cia.api.tenant;

import com.nubeero.cia.setup.keycloak.BootstrapRoles;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
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
        if (schema == null || !schema.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Illegal tenant schema name: " + schema);
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("SET search_path TO \"" + schema + "\"");

        seedAdminGroup(jdbc, adminGroupId);
        seedCurrency(jdbc);
        seedCustomerNumberFormat(jdbc);
        log.info("Tenant schema '{}' seeded (admin group {})", schema, adminGroupId);
    }

    private void seedAdminGroup(JdbcTemplate jdbc, UUID adminGroupId) {
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
