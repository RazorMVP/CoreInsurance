package com.nubeero.cia.tenant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TenantProvisionRequest(
        @NotBlank
        @Pattern(regexp = "[a-z][a-z0-9_]{0,62}", message = "schemaName must be a safe PostgreSQL schema name")
        String schemaName,

        @NotBlank
        @Pattern(regexp = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", message = "subdomain must be a safe DNS label")
        String subdomain,

        @NotBlank
        @Size(max = 255)
        String name
) {
}
