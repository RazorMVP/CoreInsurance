package com.nubeero.cia.setup.keycloak;

import java.util.UUID;

/** Parameters for the bootstrap first-admin user created in a freshly provisioned tenant realm. */
public record FirstAdminSpec(
        String username,
        String email,
        String firstName,
        String lastName,
        String tempPassword,
        UUID accessGroupId) {
}
