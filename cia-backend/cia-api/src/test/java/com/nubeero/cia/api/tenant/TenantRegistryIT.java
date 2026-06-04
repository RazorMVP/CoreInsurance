package com.nubeero.cia.api.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TenantRegistryIT extends TenantProvisioningItSupport {

    @BeforeEach
    void ensureRegistryTable() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        // The registry lives in public; create it directly (mirrors V1) for this isolated unit IT.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS public.tenants (
              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              schema_name VARCHAR(63) NOT NULL UNIQUE,
              name VARCHAR(255) NOT NULL,
              subdomain VARCHAR(63) NOT NULL UNIQUE,
              active BOOLEAN NOT NULL DEFAULT TRUE,
              created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
              updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )""");
        jdbc.update("DELETE FROM public.tenants WHERE schema_name LIKE 'tenant_reg%'");
    }

    @Test
    void upsertsIdempotentlyAndListsActiveSchemas() {
        TenantRegistry registry = new TenantRegistry(dataSource());

        registry.upsert("tenant_reg", "Reg Insurance", "reg");
        registry.upsert("tenant_reg", "Reg Insurance", "reg"); // idempotent on schema_name

        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        Integer rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM public.tenants WHERE schema_name = 'tenant_reg'", Integer.class);
        assertThat(rows).isEqualTo(1);

        List<String> active = registry.findActiveSchemas();
        assertThat(active).contains("tenant_reg");
    }

    @Test
    void findActiveSchemasExcludesInactive() {
        TenantRegistry registry = new TenantRegistry(dataSource());
        registry.upsert("tenant_reg_inactive", "Inactive Co", "reginactive");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        jdbc.update("UPDATE public.tenants SET active = FALSE WHERE schema_name = 'tenant_reg_inactive'");

        assertThat(registry.findActiveSchemas()).doesNotContain("tenant_reg_inactive");
    }
}
