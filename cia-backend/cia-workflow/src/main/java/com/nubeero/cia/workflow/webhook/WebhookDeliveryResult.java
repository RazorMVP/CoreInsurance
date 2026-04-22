package com.nubeero.cia.workflow.webhook;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WebhookDeliveryResult {
    private boolean success;
    private int httpStatus;
    private String responseBody;
    private String errorMessage;
}
