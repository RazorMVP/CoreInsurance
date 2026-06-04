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
 * <p>Flyway is configured with {@code baselineVersion=2} and {@code baseline()} is called
 * explicitly before {@code migrate()} so both V1 and V2 are recorded as already-applied and never
 * run inside a tenant schema; migration effectively runs V3 onwards (to the current tip).
 *
 * <p>Why baseline past V1: V1 creates the shared {@code public.tenants} registry — it must never
 * run per-tenant.
 *
 * <p>Why baseline past V2: V2 targets the shared, vestigial {@code template_} schema (it does
 * {@code SET search_path TO template_} internally) and its {@code CREATE INDEX} statements are
 * NOT {@code IF NOT EXISTS}. Re-running V2 for a second tenant against the same database therefore
 * fails with "relation already exists". Skipping V2 is safe: the three tables it would have
 * created ({@code audit_log}, {@code partner_apps}, {@code webhook_registrations}) are all
 * re-created by V12/V13 as unqualified DDL, so they still land correctly in each tenant schema.
 *
 * <p>(Flyway 10+ ignores {@code baselineOnMigrate} for empty schemas — explicit {@code baseline()}
 * is required.) Before running Flyway, {@link #ensurePgcryptoInPublic()} installs the pgcrypto
 * extension into {@code public} schema (idempotent). This prevents V24's
 * {@code CREATE EXTENSION IF NOT EXISTS pgcrypto} from installing into the tenant schema on the
 * first call (when pgcrypto doesn't yet exist), which would make {@code pgp_sym_encrypt}
 * inaccessible for all subsequent tenants. A BEFORE_EACH_MIGRATE callback then re-pins
 * search_path to {@code "<tenant>", public} before every migration script, so unqualified DDL
 * lands in the tenant schema while pgcrypto functions in {@code public} remain resolvable.
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
        TenantSchemas.validate(schema);
        try (var conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
            log.info("Tenant schema '{}' ensured", schema);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create schema " + schema, e);
        }
    }

    /**
     * Run V3 onwards against the tenant schema (V1 and V2 baselined out). Throws on failure
     * (fail-fast).
     *
     * <p>V1 is the shared public.tenants registry — must never run per-tenant. V2 targets the
     * shared {@code template_} schema with non-idempotent {@code CREATE INDEX} statements;
     * re-running it for a second tenant would fail with "relation already exists". The three tables
     * V2 defines ({@code audit_log}, {@code partner_apps}, {@code webhook_registrations}) are
     * created by V12/V13 as unqualified DDL and therefore still land in the tenant schema.
     */
    public void migrate(String schema) {
        TenantSchemas.validate(schema);
        // Ensure pgcrypto is installed in public before running migrations so that V24's
        // pgp_sym_encrypt calls resolve on every tenant, not just the first one.
        ensurePgcryptoInPublic();

        Flyway flyway = Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)          // first entry is already the default schema in Flyway 10+
            .baselineVersion("2")
            .locations("classpath:db/migration")
            .callbacks(new SearchPathCallback(schema))
            .load();

        // Flyway 10+ skips baselineOnMigrate for empty schemas — explicitly baseline first so V1
        // (shared public.tenants registry) and V2 (shared template_ schema with non-idempotent
        // CREATE INDEX) are both recorded as already-applied and never run here.
        // baseline() is idempotent: if flyway_schema_history already exists it's a no-op.
        flyway.baseline();
        flyway.migrate();
        log.info("Tenant schema '{}' migrated to current version", schema);
    }

    /**
     * Ensures the pgcrypto extension is installed in the {@code public} schema before running
     * per-tenant migrations.
     *
     * <p>V24 does {@code CREATE EXTENSION IF NOT EXISTS pgcrypto} without a target schema. If that
     * statement runs for the first tenant with search_path set to the tenant schema, pgcrypto ends
     * up in the tenant schema — not in {@code public}. For every subsequent tenant, the
     * {@code IF NOT EXISTS} skips re-installation but the functions are unreachable (they're in a
     * different tenant's schema), causing "function pgp_sym_encrypt does not exist". By
     * pre-installing pgcrypto into {@code public} before any migration runs, V24's
     * {@code CREATE EXTENSION IF NOT EXISTS pgcrypto} always finds it already present, skips
     * idempotently, and the functions are always accessible through the {@code public} component of
     * every tenant's search_path.
     */
    private void ensurePgcryptoInPublic() {
        try (var conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to ensure pgcrypto in public schema", e);
        }
    }

    /**
     * Re-pins search_path to {@code "<tenant>", public} before each migration script.
     *
     * <p>The tenant schema is placed first so unqualified DDL lands in the correct schema.
     * {@code public} is second so extensions installed there (pgcrypto, etc.) are always
     * resolvable. This also neutralises any {@code SET/RESET search_path} inside a migration
     * script (e.g. the trailing {@code RESET search_path} in V2, which was itself moved to
     * {@code template_}): the callback fires again before the next script, restoring the correct
     * path.
     */
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
                st.execute("SET search_path TO \"" + schema + "\", public");
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to set search_path to " + schema, e);
            }
        }
        @Override public String getCallbackName() {
            return "tenant-search-path-" + schema;
        }
    }
}
