package com.nubeero.cia.api.tenant;

import com.nubeero.cia.api.platform.dto.TenantSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/**
 * JdbcTemplate access to the public.tenants registry (V1). No JPA entity exists for this table;
 * it is shared infrastructure. Every statement fully qualifies {@code public.tenants} so the
 * component never depends on the connection's search_path (avoids the pooled-connection
 * search_path race that affects SET-search_path-then-write patterns).
 */
@Slf4j
@Component
public class TenantRegistry {

    private final JdbcTemplate jdbc;

    public TenantRegistry(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Upsert keyed on schema_name; safe to call on every boot. */
    public void upsert(String schema, String displayName, String subdomain) {
        jdbc.update("""
            INSERT INTO public.tenants (id, schema_name, name, subdomain, active, created_at, updated_at)
            VALUES (gen_random_uuid(), ?, ?, ?, TRUE, NOW(), NOW())
            ON CONFLICT (schema_name) DO UPDATE
              SET name = EXCLUDED.name, subdomain = EXCLUDED.subdomain, updated_at = NOW()
            """, schema, displayName, subdomain);
        log.info("Tenant registry upserted: schema={} subdomain={}", schema, subdomain);
    }

    /** All active tenant schema names — the set swept and re-migrated on every boot. */
    public List<String> findActiveSchemas() {
        return jdbc.queryForList(
            "SELECT schema_name FROM public.tenants WHERE active = TRUE ORDER BY schema_name",
            String.class);
    }

    /** All tenants (active and inactive), ordered by creation time. */
    public List<TenantSummary> findAll() {
        return jdbc.query(
            "SELECT schema_name, name, subdomain, active, created_at FROM public.tenants ORDER BY created_at",
            (rs, i) -> new TenantSummary(
                rs.getString("schema_name"),
                rs.getString("name"),
                rs.getString("subdomain"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant()));
    }

    /** Look up a single tenant by schema name. Returns empty if not found. */
    public Optional<TenantSummary> find(String schema) {
        List<TenantSummary> rows = jdbc.query(
            "SELECT schema_name, name, subdomain, active, created_at FROM public.tenants WHERE schema_name = ?",
            (rs, i) -> new TenantSummary(
                rs.getString("schema_name"),
                rs.getString("name"),
                rs.getString("subdomain"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant()),
            schema);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Flip the {@code active} flag for a tenant.
     *
     * @return {@code true} if the row was found and updated; {@code false} if no such schema exists.
     */
    public boolean setActive(String schema, boolean active) {
        int rows = jdbc.update(
            "UPDATE public.tenants SET active = ?, updated_at = NOW() WHERE schema_name = ?",
            active, schema);
        return rows > 0;
    }

    /** Returns {@code true} if a tenant row exists for the given schema name. */
    public boolean exists(String schema) {
        List<String> rows = jdbc.queryForList(
            "SELECT 1 FROM public.tenants WHERE schema_name = ? LIMIT 1",
            String.class, schema);
        return !rows.isEmpty();
    }

    /** Returns {@code true} if any tenant already uses the given subdomain. */
    public boolean subdomainTaken(String subdomain) {
        List<String> rows = jdbc.queryForList(
            "SELECT 1 FROM public.tenants WHERE subdomain = ? LIMIT 1",
            String.class, subdomain);
        return !rows.isEmpty();
    }
}
