package com.nubeero.cia.auth;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcTenantRegistryTest {

    private final CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
    private final JdbcTenantRegistry registry = new JdbcTenantRegistry(jdbcTemplate);

    @Test
    void resolvesActiveTenantSchemaFromRegistry() {
        jdbcTemplate.schemas = List.of("tenant_acme");

        assertThat(registry.resolveActiveTenantSchema("acme")).contains("tenant_acme");
        assertThat(jdbcTemplate.args).containsExactly("acme", "acme", "acme");
    }

    @Test
    void rejectsUnsafeTenantClaimBeforeQueryingDatabase() {
        assertThat(registry.resolveActiveTenantSchema("tenant_acme';drop schema tenant_beta;--")).isEmpty();

        assertThat(jdbcTemplate.called).isFalse();
    }

    @Test
    void rejectsUnsafeSchemaReturnedByRegistry() {
        jdbcTemplate.schemas = List.of("Tenant-Acme");

        assertThat(registry.resolveActiveTenantSchema("acme")).isEmpty();
    }

    private static class CapturingJdbcTemplate extends JdbcTemplate {

        private boolean called;
        private Object[] args = new Object[0];
        private List<String> schemas = List.of();

        @Override
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            this.called = true;
            this.args = args;
            return schemas.stream()
                    .map(elementType::cast)
                    .toList();
        }
    }
}
