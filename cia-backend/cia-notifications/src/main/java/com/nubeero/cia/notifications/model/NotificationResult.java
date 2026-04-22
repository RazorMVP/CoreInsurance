package com.nubeero.cia.notifications.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NotificationResult {
    private boolean success;
    private String providerId;
    private String errorMessage;
}
