package com.nubeero.cia.tenant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantSchemaNameTest {

    @Test
    void acceptsSafeSchemaNamesOnly() {
        assertThat(TenantSchemaName.isSafeSchemaName("tenant_alpha_01")).isTrue();
        assertThat(TenantSchemaName.isSafeSchemaName("TenantAlpha")).isFalse();
        assertThat(TenantSchemaName.isSafeSchemaName("tenant-alpha")).isFalse();
        assertThat(TenantSchemaName.isSafeSchemaName("1tenant")).isFalse();
    }

    @Test
    void acceptsDnsLabelSubdomainsOnly() {
        assertThat(TenantSchemaName.isSafeSubdomain("alpha")).isTrue();
        assertThat(TenantSchemaName.isSafeSubdomain("alpha-01")).isTrue();
        assertThat(TenantSchemaName.isSafeSubdomain("a")).isTrue();
        assertThat(TenantSchemaName.isSafeSubdomain("alpha-")).isFalse();
        assertThat(TenantSchemaName.isSafeSubdomain("-alpha")).isFalse();
        assertThat(TenantSchemaName.isSafeSubdomain("Alpha")).isFalse();
        assertThat(TenantSchemaName.isSafeSubdomain("alpha_01")).isFalse();
    }
}
