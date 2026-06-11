package com.nubeero.cia.api.tenant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Ensures the configured bootstrap tenants exist, then sweeps every active tenant in the registry
 * and re-migrates it — making "all migrations run against every schema on startup" true. Gated by
 * cia.tenants.bootstrap.enabled (default false), so existing ITs and local dev are unaffected.
 * Fail-fast: any failure propagates and aborts application startup.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cia.tenants.bootstrap", name = "enabled", havingValue = "true")
public class TenantBootstrapRunner implements ApplicationRunner {

    private final TenantBootstrapProperties props;
    private final TenantProvisioningService provisioningService;
    private final TenantSchemaMigrator migrator;
    private final TenantRegistry registry;

    public TenantBootstrapRunner(TenantBootstrapProperties props,
                                 TenantProvisioningService provisioningService,
                                 TenantSchemaMigrator migrator,
                                 TenantRegistry registry) {
        this.props = props;
        this.provisioningService = provisioningService;
        this.migrator = migrator;
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Tenant bootstrap: ensuring {} configured tenant(s)", props.getTenants().size());
        for (TenantBootstrapProperties.TenantSpec spec : props.getTenants()) {
            if (spec.getAdminTempPassword() == null || spec.getAdminTempPassword().isBlank()) {
                throw new IllegalStateException(
                    "admin-temp-password must not be blank for bootstrap tenant '" + spec.getSchema() + "'");
            }
            provisioningService.provision(spec);
        }
        for (String schema : registry.findActiveSchemas()) {
            log.info("Tenant bootstrap: re-migrating registered schema '{}'", schema);
            migrator.migrate(schema);
        }
        log.info("Tenant bootstrap complete");
    }
}
