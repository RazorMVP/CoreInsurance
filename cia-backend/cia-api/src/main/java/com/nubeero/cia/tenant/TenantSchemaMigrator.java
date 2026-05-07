package com.nubeero.cia.tenant;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
public class TenantSchemaMigrator {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";
    private static final String HISTORY_TABLE = "flyway_schema_history";
    private static final MigrationVersion TENANT_BASELINE_VERSION = MigrationVersion.fromVersion("2");

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public TenantSchemaMigrator(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void migrateTenantSchema(String schemaName) {
        if (!TenantSchemaName.isSafeSchemaName(schemaName)) {
            throw new TenantProvisioningException(
                    "TENANT_SCHEMA_INVALID",
                    "Tenant schema name is not safe",
                    HttpStatus.BAD_REQUEST
            );
        }

        ensureSchemaExists(schemaName);
        Flyway flyway = tenantFlyway(schemaName);
        if (!hasFlywayHistory(schemaName)) {
            flyway.baseline();
        }
        flyway.migrate();
    }

    private Flyway tenantFlyway(String schemaName) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATION_LOCATION)
                .schemas(schemaName)
                .defaultSchema(schemaName)
                .table(HISTORY_TABLE)
                .baselineVersion(TENANT_BASELINE_VERSION)
                .baselineDescription("Tenant schema baseline")
                .cleanDisabled(true)
                .load();
    }

    private void ensureSchemaExists(String schemaName) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                Integer.class,
                schemaName
        );
        if (exists == null || exists == 0) {
            throw new TenantProvisioningException(
                    "TENANT_SCHEMA_MISSING",
                    "Tenant schema does not exist",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }
    }

    private boolean hasFlywayHistory(String schemaName) {
        String relationName = schemaName + "." + HISTORY_TABLE;
        String regclass = jdbcTemplate.queryForObject("SELECT to_regclass(?)", String.class, relationName);
        return regclass != null;
    }
}
