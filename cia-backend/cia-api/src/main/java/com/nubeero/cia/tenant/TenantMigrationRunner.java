package com.nubeero.cia.tenant;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class TenantMigrationRunner implements ApplicationRunner {

    private final ActiveTenantMigrationService activeTenantMigrationService;
    private final Runnable closeApplication;
    private final boolean migrationOnly;

    public TenantMigrationRunner(
            ActiveTenantMigrationService activeTenantMigrationService,
            ConfigurableApplicationContext applicationContext,
            @Value("${cia.migration-only:false}") boolean migrationOnly) {
        this(activeTenantMigrationService, applicationContext::close, migrationOnly);
    }

    TenantMigrationRunner(
            ActiveTenantMigrationService activeTenantMigrationService,
            Runnable closeApplication,
            boolean migrationOnly) {
        this.activeTenantMigrationService = activeTenantMigrationService;
        this.closeApplication = closeApplication;
        this.migrationOnly = migrationOnly;
    }

    @Override
    public void run(ApplicationArguments args) {
        activeTenantMigrationService.migrateActiveTenants();
        if (migrationOnly) {
            closeApplication.run();
        }
    }
}
