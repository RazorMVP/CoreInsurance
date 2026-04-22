package com.nubeero.cia.common.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

    private static final String DEFAULT_SCHEMA = "public";

    @Override
    public String resolveCurrentTenantIdentifier() {
        String tenantId = TenantContext.getTenantId();
        return (tenantId != null) ? tenantId : DEFAULT_SCHEMA;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
