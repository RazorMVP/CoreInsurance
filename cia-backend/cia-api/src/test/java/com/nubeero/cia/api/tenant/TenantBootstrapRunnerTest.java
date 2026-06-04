package com.nubeero.cia.api.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static org.mockito.Mockito.*;

class TenantBootstrapRunnerTest {

    @Test
    void provisionsConfiguredTenantsThenSweepsActiveRegistry() throws Exception {
        var props = new TenantBootstrapProperties();
        props.setEnabled(true);
        var spec = new TenantBootstrapProperties.TenantSpec();
        spec.setSchema("tenant_one"); spec.setRealm("tenant_one");
        spec.setDisplayName("One"); spec.setSubdomain("one");
        spec.setAdminUsername("admin"); spec.setAdminEmail("a@one.example");
        spec.setAdminTempPassword("T!1");
        props.setTenants(List.of(spec));

        var service = mock(TenantProvisioningService.class);
        var migrator = mock(TenantSchemaMigrator.class);
        var registry = mock(TenantRegistry.class);
        when(registry.findActiveSchemas()).thenReturn(List.of("tenant_one", "tenant_two"));

        var runner = new TenantBootstrapRunner(props, service, migrator, registry);
        runner.run(new DefaultApplicationArguments());

        verify(service).provision(spec);                       // config tenant provisioned
        verify(migrator).migrate("tenant_one");                // sweep migrates every active schema
        verify(migrator).migrate("tenant_two");
    }

    @Test
    void failFastPropagatesProvisioningError() {
        var props = new TenantBootstrapProperties();
        props.setEnabled(true);
        var spec = new TenantBootstrapProperties.TenantSpec();
        spec.setSchema("tenant_bad"); spec.setRealm("tenant_bad");
        spec.setAdminUsername("admin"); spec.setAdminEmail("a@bad.example");
        spec.setAdminTempPassword("T!1"); spec.setDisplayName("Bad"); spec.setSubdomain("bad");
        props.setTenants(List.of(spec));

        var service = mock(TenantProvisioningService.class);
        doThrow(new IllegalStateException("boom")).when(service).provision(spec);
        var migrator = mock(TenantSchemaMigrator.class);
        var registry = mock(TenantRegistry.class);

        var runner = new TenantBootstrapRunner(props, service, migrator, registry);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> runner.run(new DefaultApplicationArguments()))
            .isInstanceOf(IllegalStateException.class);
    }
}
