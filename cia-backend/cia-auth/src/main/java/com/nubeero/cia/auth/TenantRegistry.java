package com.nubeero.cia.auth;

import java.util.Optional;

public interface TenantRegistry {

    Optional<String> resolveActiveTenantSchema(String tenantClaim);
}
