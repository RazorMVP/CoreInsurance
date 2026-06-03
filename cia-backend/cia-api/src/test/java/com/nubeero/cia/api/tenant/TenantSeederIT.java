package com.nubeero.cia.api.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantSeederIT extends TenantProvisioningItSupport {

    @Test
    void seedsSensibleDefaultsIdempotently() {
        TenantSchemaMigrator migrator = new TenantSchemaMigrator(dataSource());
        TenantSeeder seeder = new TenantSeeder(dataSource());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        UUID adminGroupId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        migrator.ensureSchema("tenant_seed");
        migrator.migrate("tenant_seed");

        seeder.seed("tenant_seed", adminGroupId);
        seeder.seed("tenant_seed", adminGroupId); // idempotent — second run must not duplicate

        jdbc.execute("SET search_path TO tenant_seed");
        assertThat(count(jdbc, "access_groups WHERE id = '" + adminGroupId + "'")).isEqualTo(1);
        assertThat(count(jdbc, "access_group_permissions WHERE access_group_id = '" + adminGroupId + "'"))
            .isGreaterThan(0);
        assertThat(count(jdbc, "currencies WHERE code = 'NGN' AND is_default = TRUE")).isEqualTo(1);
        assertThat(count(jdbc, "customer_number_format")).isEqualTo(1);
    }

    private static int count(JdbcTemplate jdbc, String tail) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + tail, Integer.class);
        return n == null ? 0 : n;
    }
}
