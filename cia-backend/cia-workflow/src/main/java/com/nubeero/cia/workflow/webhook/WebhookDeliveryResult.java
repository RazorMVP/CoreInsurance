package com.nubeero.cia.workflow.webhook;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookDeliveryResult {
    private boolean success;
    private int httpStatus;
    private String responseBody;
    private String errorMessage;
}
