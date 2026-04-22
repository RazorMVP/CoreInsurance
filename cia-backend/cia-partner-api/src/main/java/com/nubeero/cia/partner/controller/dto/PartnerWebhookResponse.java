package com.nubeero.cia.partner.controller.dto;

import com.nubeero.cia.partner.webhook.WebhookRegistration;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Registered webhook endpoint details. The signing secret is never returned after creation.")
public class PartnerWebhookResponse {

    @Schema(description = "Unique webhook registration ID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID id;

    @Schema(description = "URL that receives webhook POST requests", example = "https://your-app.com/webhooks/cia")
    private String targetUrl;

    @Schema(description = "Event types this webhook is subscribed to",
            example = "[\"policy.bound\", \"claim.approved\", \"claim.settled\"]")
    private List<String> eventTypes;

    @Schema(description = "Whether this webhook is actively receiving deliveries", example = "true")
    private boolean active;

    @Schema(description = "Timestamp when the webhook was registered", example = "2026-04-20T12:00:00Z")
    private Instant createdAt;

    @Schema(description = "Timestamp of the last update", example = "2026-04-20T12:00:00Z")
    private Instant updatedAt;

    public static PartnerWebhookResponse from(WebhookRegistration w) {
        List<String> events = w.getEventTypes() == null || w.getEventTypes().isBlank()
                ? List.of()
                : Arrays.asList(w.getEventTypes().split(","));
        return PartnerWebhookResponse.builder()
                .id(w.getId())
                .targetUrl(w.getTargetUrl())
                .eventTypes(events)
                .active(w.isActive())
                .createdAt(w.getCreatedAt())
                .updatedAt(w.getUpdatedAt())
                .build();
    }
}
