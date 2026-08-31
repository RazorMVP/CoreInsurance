package com.nubeero.cia.portal.apps;

import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads a tenant's display name from {@code public.tenants} (V1) for {@code GET /portal/apps}'s
 * {@code tenantLabel} field. Mirrors {@code cia-api}'s {@code TenantRegistry} (this module cannot
 * depend on {@code cia-api}, which owns that class — the dependency direction runs the other way).
 *
 * <p>Every statement fully qualifies {@code public.tenants} rather than relying on {@code
 * TenantContext}/{@code search_path} — unlike the {@code PartnerApp} read in {@link
 * PortalAppsService} (a JPA entity resolved via the connection's search_path), this is a plain
 * {@link JdbcTemplate} query against a schema-qualified table name, so it works correctly
 * regardless of which tenant (if any) {@code TenantContext} currently points at.
 */
@Component
public class PortalTenantLabelLookup {

    private final JdbcTemplate jdbc;

    public PortalTenantLabelLookup(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public Optional<String> labelFor(String schema) {
        List<String> names = jdbc.queryForList(
                "SELECT name FROM public.tenants WHERE schema_name = ?", String.class, schema);
        return names.isEmpty() ? Optional.empty() : Optional.of(names.get(0));
    }
}
