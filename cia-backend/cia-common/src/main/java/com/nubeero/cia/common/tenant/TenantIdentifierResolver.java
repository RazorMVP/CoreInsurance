package com.nubeero.cia.common.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

    private static final String DEFAULT_SCHEMA = "public";

    private final Environment environment;

    public TenantIdentifierResolver(Environment environment) {
        this.environment = environment;
    }

    @Override
    public String resolveCurrentTenantIdentifier() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null && !tenantId.isBlank()) {
            return tenantId;
        }
        if (allowsPublicFallback()) {
            return DEFAULT_SCHEMA;
        }
        throw new TenantResolutionException("Tenant context is required outside dev/test profiles");
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }

    private boolean allowsPublicFallback() {
        return environment.acceptsProfiles(Profiles.of("dev", "test"));
    }
}
