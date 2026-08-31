package com.nubeero.cia.portal.developer.dto;

import com.nubeero.cia.portal.grant.GrantRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Request body for {@code POST /api/v1/partner-apps/{id}/developers}. */
public record InviteDeveloperRequest(
        @Email @NotBlank String email,
        @NotNull GrantRole role
) {}
