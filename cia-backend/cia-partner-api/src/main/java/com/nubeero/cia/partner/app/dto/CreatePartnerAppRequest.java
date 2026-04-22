package com.nubeero.cia.partner.app.dto;

import com.nubeero.cia.partner.app.PartnerPlan;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreatePartnerAppRequest(
        @NotBlank String clientId,
        @NotBlank String appName,
        @Email @NotBlank String contactEmail,
        String scopes,
        @NotNull PartnerPlan plan,
        @Positive int rateLimitRpm,
        String allowedIps
) {}
