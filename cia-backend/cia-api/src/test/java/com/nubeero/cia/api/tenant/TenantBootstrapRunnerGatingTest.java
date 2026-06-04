package com.nubeero.cia.api.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the @ConditionalOnProperty(enabled=true) gate on TenantBootstrapRunner:
 * - bean ABSENT when the flag is unset (default false) — the existing IT baseline is safe
 * - bean PRESENT when the flag is explicitly true (with wired mock deps)
 */
class TenantBootstrapRunnerGatingTest {

    /** Mock dep beans reused across both test cases. */
    private static TenantBootstrapProperties propsBean() {
        return new TenantBootstrapProperties(); // enabled=false by default
    }

    private static TenantProvisioningService provisioningServiceBean() {
        return mock(TenantProvisioningService.class);
    }

    private static TenantSchemaMigrator migratorBean() {
        return mock(TenantSchemaMigrator.class);
    }

    private static TenantRegistry registryBean() {
        var r = mock(TenantRegistry.class);
        when(r.findActiveSchemas()).thenReturn(List.of());
        return r;
    }

    @Test
    void runnerBeanAbsentWhenFlagUnset() {
        new ApplicationContextRunner()
            .withBean(TenantBootstrapProperties.class, TenantBootstrapRunnerGatingTest::propsBean)
            .withBean(TenantProvisioningService.class, TenantBootstrapRunnerGatingTest::provisioningServiceBean)
            .withBean(TenantSchemaMigrator.class, TenantBootstrapRunnerGatingTest::migratorBean)
            .withBean(TenantRegistry.class, TenantBootstrapRunnerGatingTest::registryBean)
            .withUserConfiguration(TenantBootstrapRunner.class)
            // no cia.tenants.bootstrap.enabled property → @ConditionalOnProperty havingValue="true" not met
            .run(ctx -> assertThat(ctx).doesNotHaveBean(TenantBootstrapRunner.class));
    }

    @Test
    void runnerBeanPresentWhenFlagTrue() {
        new ApplicationContextRunner()
            .withBean(TenantBootstrapProperties.class, TenantBootstrapRunnerGatingTest::propsBean)
            .withBean(TenantProvisioningService.class, TenantBootstrapRunnerGatingTest::provisioningServiceBean)
            .withBean(TenantSchemaMigrator.class, TenantBootstrapRunnerGatingTest::migratorBean)
            .withBean(TenantRegistry.class, TenantBootstrapRunnerGatingTest::registryBean)
            .withUserConfiguration(TenantBootstrapRunner.class)
            .withPropertyValues("cia.tenants.bootstrap.enabled=true")
            .run(ctx -> assertThat(ctx).hasSingleBean(TenantBootstrapRunner.class));
    }
}
