package com.nubeero.cia.workflow.webhook;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookDispatchRequest {
    private String webhookRegistrationId;
    private String tenantId;
    private String eventType;
    private String payloadJson;
    private Instant timestamp;
}
