package com.nubeero.cia.tenant;

import java.util.UUID;

public record TenantProvisionResponse(
        UUID id,
        String schemaName,
        String subdomain,
        String name,
        boolean active
) {
}
