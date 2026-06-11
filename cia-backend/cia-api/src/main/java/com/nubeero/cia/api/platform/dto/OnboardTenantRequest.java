package com.nubeero.cia.api.platform.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for the onboard-new-tenant operation.
 *
 * <p>{@code realm} is optional and should normally be left blank: under the realm-per-tenant
 * routing model the realm IS the tenant identifier, so it must equal {@code schema}. When null/blank
 * it defaults to {@code schema} inside {@link com.nubeero.cia.api.platform.PlatformTenantService};
 * when present it must satisfy the same safe-identifier pattern as {@code schema} AND equal it — a
 * divergent realm is rejected ({@code REALM_SCHEMA_MISMATCH}) because it would route the tenant's
 * JWTs to a nonexistent schema and break the allowlist gate. {@code @Pattern} passes on {@code null}
 * by contract, so a missing realm field is valid.
 */
public record OnboardTenantRequest(
        @NotBlank @Pattern(regexp = "[a-z_][a-z0-9_]{0,62}") String schema,
        @Pattern(regexp = "[a-z_][a-z0-9_]{0,62}") String realm,
        @NotBlank String displayName,
        @NotBlank @Pattern(regexp = "[a-z0-9-]{1,63}") String subdomain,
        @NotBlank String adminUsername,
        @NotBlank @Email String adminEmail) {}
