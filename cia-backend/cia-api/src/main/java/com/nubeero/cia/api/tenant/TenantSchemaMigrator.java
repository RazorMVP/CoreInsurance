package com.nubeero.cia.api.tenant;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns per-tenant schema DDL: creating the schema and running Flyway against it.
 *
 * <p>Flyway is configured with baselineVersion=1 and {@code baseline()} is called explicitly
 * before {@code migrate()} so V1 (the shared public.tenants registry) is recorded as
 * already-applied and never runs inside a tenant schema; migration begins at V2 onwards (to the
 * current migration tip). (Flyway 10+ ignores {@code baselineOnMigrate} for empty schemas —
 * explicit {@code baseline()} is required.) A BEFORE_EACH_MIGRATE callback re-pins search_path to
 * the target schema before every migration, neutralising V2's trailing {@code RESET search_path}
 * (which would otherwise drop V3 onwards into public). Note: V2 internally does
 * {@code SET search_path TO template_} so V2's own objects intentionally land in the template_
 * schema; the callback governs the migrations around V2 and neutralises V2's trailing RESET.
 * Postgres DDL is transactional, so a failed migration rolls back to the prior version.
 */
@Slf4j
@Component
public class TenantSchemaMigrator {

    private final DataSource dataSource;

    public TenantSchemaMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Idempotent CREATE SCHEMA. */
    public void ensureSchema(String schema) {
        validate(schema);
        try (var conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
            log.info("Tenant schema '{}' ensured", schema);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create schema " + schema, e);
        }
    }

    /** Run V2 onwards against the tenant schema (V1 baselined out). Throws on failure (fail-fast). */
    public void migrate(String schema) {
        validate(schema);
        Flyway flyway = Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)          // first entry is already the default schema in Flyway 10+
            .baselineVersion("1")
            .locations("classpath:db/migration")
            .callbacks(new SearchPathCallback(schema))
            .load();

        // Flyway 10+ skips baselineOnMigrate for empty schemas — explicitly baseline first so V1
        // (the shared public.tenants registry) is recorded as already-applied and never runs here.
        // baseline() is idempotent: if flyway_schema_history already exists it's a no-op.
        flyway.baseline();
        flyway.migrate();
        log.info("Tenant schema '{}' migrated to current version", schema);
    }

    private static void validate(String schema) {
        if (schema == null || !schema.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Illegal tenant schema name: " + schema);
        }
    }

    /** Re-pins search_path to the tenant schema before each migration (beats V2's RESET). */
    private record SearchPathCallback(String schema) implements Callback {
        @Override public boolean supports(Event event, Context context) {
            return event == Event.BEFORE_EACH_MIGRATE;
        }
        @Override public boolean canHandleInTransaction(Event event, Context context) {
            return true;
        }
        @Override public void handle(Event event, Context context) {
            // schema is pre-validated by migrate()/ensureSchema() before this record is constructed.
            try (Statement st = context.getConnection().createStatement()) {
                st.execute("SET search_path TO \"" + schema + "\"");
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to set search_path to " + schema, e);
            }
        }
        @Override public String getCallbackName() {
            return "tenant-search-path-" + schema;
        }
    }
}
