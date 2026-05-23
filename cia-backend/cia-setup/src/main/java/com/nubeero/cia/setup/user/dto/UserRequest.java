package com.nubeero.cia.setup.user.dto;

import com.nubeero.cia.setup.user.UserStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * Body for POST /api/v1/setup/users (create) and PUT /api/v1/setup/users/{id}
 * (update). Email is immutable on update — Keycloak treats it as the
 * effective username and we don't want to break existing JWTs.
 */
@Data
public class UserRequest {

    @NotBlank
    private String firstName;

    @NotBlank
    private String lastName;

    @NotBlank
    @Email
    private String email;

    @NotNull
    private UUID accessGroupId;

    /**
     * Only honoured on update. Create always lands as ACTIVE — Keycloak
     * sends a verification email separately.
     */
    private UserStatus status;
}
