package com.nubeero.cia.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class JdbcTenantRegistry implements TenantRegistry {

    private static final Pattern SAFE_TENANT_CLAIM = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern SAFE_SCHEMA_NAME = Pattern.compile("[a-z][a-z0-9_]{0,62}");

    private final JdbcTemplate jdbcTemplate;

    public JdbcTenantRegistry(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<String> resolveActiveTenantSchema(String tenantClaim) {
        if (tenantClaim == null || !SAFE_TENANT_CLAIM.matcher(tenantClaim).matches()) {
            return Optional.empty();
        }

        List<String> schemas = jdbcTemplate.queryForList(
                """
                SELECT schema_name
                  FROM public.tenants
                 WHERE active = true
                   AND (schema_name = ? OR subdomain = ? OR id::text = ?)
                 LIMIT 1
                """,
                String.class,
                tenantClaim,
                tenantClaim,
                tenantClaim
        );

        return schemas.stream()
                .filter(schema -> SAFE_SCHEMA_NAME.matcher(schema).matches())
                .findFirst();
    }
}
