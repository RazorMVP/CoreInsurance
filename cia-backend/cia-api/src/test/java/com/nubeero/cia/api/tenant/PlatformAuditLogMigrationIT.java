package com.nubeero.cia.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Proves V68 is schema-aware: it relaxes {@code target_schema} to NULL on the canonical
 * {@code public.platform_audit_log} and drops the dead per-tenant copies that V67's
 * unqualified above-baseline CREATE cloned into every tenant schema.
 *
 * <p>Inlines its own Postgres (isolated from the shared-container ITs) and runs the REAL
 * main Flyway to head + the REAL {@link TenantSchemaMigrator} for a tenant schema.
 */
class PlatformAuditLogMigrationIT {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ciamig")
                    .withUsername("ciamig")
                    .withPassword("ciamig");

    static final HikariDataSource DS;

    static {
        POSTGRES.start();
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
        cfg.setUsername(POSTGRES.getUsername());
        cfg.setPassword(POSTGRES.getPassword());
        cfg.setMaximumPoolSize(4);
        DS = new HikariDataSource(cfg);
    }

    private static boolean tableExists(JdbcTemplate jdbc, String schema, String table) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                Integer.class, schema, table);
        return n != null && n > 0;
    }

    private static String columnNullability(JdbcTemplate jdbc, String schema, String table, String column) {
        return jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ? AND column_name = ?",
                String.class, schema, table, column);
    }

    @Test
    void v68_makesPublicColumnNullable_andDropsTenantCopy() {
        JdbcTemplate jdbc = new JdbcTemplate(DS);

        // Main Flyway runs the full chain (V1..V68) against public — mirrors the app's
        // spring.flyway.schemas=public, baseline-on-migrate=true.
        Flyway.configure()
                .dataSource(DS)
                .schemas("public")
                .baselineOnMigrate(true)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // Per-tenant sweep runs V3..V68 against the tenant schema.
        TenantSchemaMigrator migrator = new TenantSchemaMigrator(DS);
        migrator.ensureSchema("tenant_mig");
        migrator.migrate("tenant_mig");

        // public.platform_audit_log survives, with target_schema now nullable.
        assertThat(tableExists(jdbc, "public", "platform_audit_log"))
                .as("public copy must survive V68").isTrue();
        assertThat(columnNullability(jdbc, "public", "platform_audit_log", "target_schema"))
                .as("V68 relaxes target_schema to NULL on the public table").isEqualTo("YES");

        // The dead tenant copy is gone.
        assertThat(tableExists(jdbc, "tenant_mig", "platform_audit_log"))
                .as("V68 drops the vestigial per-tenant copy").isFalse();

        // A NULL-target_schema row (a user-targeted super-admin action) now inserts cleanly.
        assertThatCode(() -> jdbc.update(
                "INSERT INTO public.platform_audit_log "
                        + "(action, target_schema, actor_username, actor_realm, detail, source_ip) "
                        + "VALUES ('INVITE_SUPER_ADMIN', NULL, 'sa', 'platform', "
                        + "'{\"username\":\"x\"}'::jsonb, '127.0.0.1')"))
                .doesNotThrowAnyException();
    }
}
