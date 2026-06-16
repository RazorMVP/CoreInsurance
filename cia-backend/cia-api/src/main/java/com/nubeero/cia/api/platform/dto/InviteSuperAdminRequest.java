package com.nubeero.cia.api.platform.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Request body to invite a new platform super-admin. */
public record InviteSuperAdminRequest(
        @NotBlank String username,
        @NotBlank @Email String email) {}
