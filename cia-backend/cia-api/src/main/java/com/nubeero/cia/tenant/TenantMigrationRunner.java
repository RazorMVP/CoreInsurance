package com.nubeero.cia.tenant;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class TenantMigrationRunner implements ApplicationRunner {

    private final TenantProvisioningService tenantProvisioningService;

    public TenantMigrationRunner(TenantProvisioningService tenantProvisioningService) {
        this.tenantProvisioningService = tenantProvisioningService;
    }

    @Override
    public void run(ApplicationArguments args) {
        tenantProvisioningService.migrateActiveTenants();
    }
}
