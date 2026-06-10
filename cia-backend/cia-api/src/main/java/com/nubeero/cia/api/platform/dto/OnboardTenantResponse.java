package com.nubeero.cia.api.platform.dto;

/** Response returned after a successful tenant onboard. The temporary password is returned ONCE. */
public record OnboardTenantResponse(TenantSummary tenant, FirstAdmin firstAdmin) {

    /** Credentials for the first admin user created in the provisioned Keycloak realm. */
    public record FirstAdmin(String username, String email, String temporaryPassword) {}
}
