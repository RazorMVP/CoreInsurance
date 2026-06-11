package com.nubeero.cia.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.nubeero.cia.api.platform.RegistryTenantActivationLookup;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RegistryTenantActivationLookupIT extends TenantProvisioningItSupport {

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS public.tenants ("
                + " id uuid primary key default gen_random_uuid(),"
                + " schema_name varchar(63) unique not null,"
                + " name varchar(255) not null,"
                + " subdomain varchar(63) unique not null,"
                + " active boolean not null default true,"
                + " created_at timestamptz not null default now(),"
                + " updated_at timestamptz not null default now())");
            st.execute("DELETE FROM public.tenants");
            st.execute("INSERT INTO public.tenants(schema_name, name, subdomain, active) VALUES"
                + " ('tenant_live', 'Live', 'live', true),"
                + " ('tenant_susp', 'Susp', 'susp', false)");
        }
    }

    @Test
    void readsActiveFlagAndEvicts() {
        var lookup = new RegistryTenantActivationLookup(new JdbcTemplate(dataSource()), 60);
        assertThat(lookup.isActive("tenant_live")).isTrue();
        assertThat(lookup.isActive("tenant_susp")).isFalse();
        assertThat(lookup.isActive("tenant_missing")).isFalse();   // unknown -> inactive

        // flip + evict -> fresh read
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("UPDATE public.tenants SET active=false WHERE schema_name='tenant_live'");
        } catch (Exception e) { throw new RuntimeException(e); }
        assertThat(lookup.isActive("tenant_live")).isTrue();       // still cached
        lookup.evict("tenant_live");
        assertThat(lookup.isActive("tenant_live")).isFalse();      // re-read after eviction
    }
}
