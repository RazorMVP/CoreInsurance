package com.nubeero.cia.partner.webhook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RegisterWebhookRequest(
        @NotBlank String targetUrl,
        @NotBlank @Size(min = 16) String secret,
        @NotEmpty List<String> eventTypes
) {}
