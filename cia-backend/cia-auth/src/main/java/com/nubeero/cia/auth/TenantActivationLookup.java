package com.nubeero.cia.auth;

/**
 * Looks up whether a tenant realm is active in the registry. The real impl
 * ({@code RegistryTenantActivationLookup}, cia-api) reads {@code public.tenants} and caches;
 * the platform realm is never passed here (it is exempt from the gate).
 */
public interface TenantActivationLookup {
    boolean isActive(String realm);
    void evict(String realm);
}
