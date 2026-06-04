package com.nubeero.cia.api.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class TenantSchemaMigratorIT extends TenantProvisioningItSupport {

    @Test
    void migratesFullSchemaIntoTenantSchemaSkippingV1AndV2() {
        TenantSchemaMigrator migrator = new TenantSchemaMigrator(dataSource());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());

        migrator.ensureSchema("tenant_alpha");
        migrator.migrate("tenant_alpha");

        // A V31+ table lands in the tenant schema (proves search_path callback beat V2's RESET).
        assertThat(tableExists(jdbc, "tenant_alpha", "journal_entry")).isTrue();
        // V1's shared registry is NOT cloned into the tenant schema.
        assertThat(tableExists(jdbc, "tenant_alpha", "tenants")).isFalse();
        // Each tenant has its own Flyway history.
        assertThat(tableExists(jdbc, "tenant_alpha", "flyway_schema_history")).isTrue();
        // audit_log (V13, unqualified) must exist even though V2 (which also defines it, but in
        // the shared template_) was baselined out — proves skipping V2 didn't drop the table.
        assertThat(tableExists(jdbc, "tenant_alpha", "audit_log")).isTrue();

        // Idempotent: a second migrate is a no-op (no exception, no duplicate apply).
        migrator.migrate("tenant_alpha");
        assertThat(tableExists(jdbc, "tenant_alpha", "journal_entry")).isTrue();
    }

    @Test
    void provisionsMultipleTenantSchemasInTheSameDatabase() {
        TenantSchemaMigrator migrator = new TenantSchemaMigrator(dataSource());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());

        // Two distinct tenants migrated against the same DB must both succeed — this is the
        // regression guard for the V2 shared-template_ CREATE INDEX collision (relation
        // "idx_audit_entity" already exists) that broke second-tenant provisioning.
        migrator.ensureSchema("tenant_one");
        migrator.migrate("tenant_one");
        migrator.ensureSchema("tenant_two");
        migrator.migrate("tenant_two");   // would throw before the baseline-past-V2 fix

        // audit_log (created by V13, unqualified) must exist in BOTH tenant schemas even though
        // V2 (which also defines audit_log, but in the shared template_) was baselined out.
        assertThat(tableExists(jdbc, "tenant_one", "audit_log")).isTrue();
        assertThat(tableExists(jdbc, "tenant_two", "audit_log")).isTrue();
        // And the late table still lands in both.
        assertThat(tableExists(jdbc, "tenant_one", "journal_entry")).isTrue();
        assertThat(tableExists(jdbc, "tenant_two", "journal_entry")).isTrue();
    }

    private static boolean tableExists(JdbcTemplate jdbc, String schema, String table) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
            Integer.class, schema, table);
        return n != null && n > 0;
    }
}
