package com.nubeero.cia.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.nubeero.cia.api.platform.PlatformAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PlatformAuditServiceIT extends TenantProvisioningItSupport {

    @Test
    void writesAndReadsAuditRows() {
        var jdbc = new JdbcTemplate(dataSource());
        jdbc.execute("CREATE TABLE IF NOT EXISTS public.platform_audit_log (id uuid primary key default gen_random_uuid(),"
            + " action varchar(32), target_schema varchar(63), actor_username varchar(255), actor_realm varchar(63),"
            + " detail jsonb, source_ip varchar(64), at timestamptz default now())");
        jdbc.update("DELETE FROM public.platform_audit_log");
        var svc = new PlatformAuditService(jdbc);
        svc.record("ONBOARD", "tenant_acme", "superadmin", "platform", "{\"subdomain\":\"acme\"}", "10.0.0.1");
        var rows = svc.recent(10);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).action()).isEqualTo("ONBOARD");
        assertThat(rows.get(0).targetSchema()).isEqualTo("tenant_acme");
    }
}
