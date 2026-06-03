package com.nubeero.cia.api.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class TenantSchemaMigratorIT extends TenantProvisioningItSupport {

    @Test
    void migratesFullSchemaIntoTenantSchemaSkippingV1() {
        TenantSchemaMigrator migrator = new TenantSchemaMigrator(dataSource());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());

        migrator.ensureSchema("tenant_alpha");
        migrator.migrate("tenant_alpha");

        // A V31+ table lands in the tenant schema (proves search_path callback beat V2's RESET).
        assertThat(tableExists(jdbc, "tenant_alpha", "journal_entry")).isTrue();
        // V1's shared registry is NOT cloned into the tenant schema (baselineVersion=1 skipped it).
        assertThat(tableExists(jdbc, "tenant_alpha", "tenants")).isFalse();
        // Each tenant has its own Flyway history.
        assertThat(tableExists(jdbc, "tenant_alpha", "flyway_schema_history")).isTrue();

        // Idempotent: a second migrate is a no-op (no exception, no duplicate apply).
        migrator.migrate("tenant_alpha");
        assertThat(tableExists(jdbc, "tenant_alpha", "journal_entry")).isTrue();
    }

    private static boolean tableExists(JdbcTemplate jdbc, String schema, String table) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
            Integer.class, schema, table);
        return n != null && n > 0;
    }
}
