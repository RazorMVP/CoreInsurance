package com.nubeero.cia.workflow.webhook;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class WebhookDispatchRequest {
    private String webhookRegistrationId;
    private String tenantId;
    private String eventType;
    private String payloadJson;
    private Instant timestamp;
}
