package com.nubeero.cia.storage;

import com.nubeero.cia.common.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class StorageTenantGuard {

    private static final Set<String> DEV_OR_TEST_PROFILES = Set.of("dev", "test");

    private final Environment environment;

    public String requireAllowedTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessRuleException(
                    "STORAGE_TENANT_REQUIRED",
                    "Tenant context is required for document storage");
        }
        String normalizedTenant = tenantId.trim();
        if ("public".equalsIgnoreCase(normalizedTenant) && !isDevOrTestOnly()) {
            throw new BusinessRuleException(
                    "PUBLIC_TENANT_STORAGE_FORBIDDEN",
                    "The public storage tenant is not allowed outside dev/test profiles");
        }
        return normalizedTenant;
    }

    private boolean isDevOrTestOnly() {
        Set<String> activeProfiles = Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.trim().toLowerCase(Locale.ROOT))
                .filter(profile -> !profile.isBlank())
                .collect(Collectors.toSet());
        return !activeProfiles.isEmpty()
                && activeProfiles.stream().allMatch(DEV_OR_TEST_PROFILES::contains);
    }
}
