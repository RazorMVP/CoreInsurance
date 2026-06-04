package com.nubeero.cia.api.tenant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

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
}
