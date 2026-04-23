package com.nubeero.cia.audit.alert.dto;

import com.nubeero.cia.audit.alert.AlertType;
import com.nubeero.cia.audit.alert.AuditAlert;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class AuditAlertResponse {

    private UUID id;
    private AlertType alertType;
    private String severity;
    private String userId;
    private String userName;
    private String description;
    private String metadata;
    private Instant triggeredAt;
    private boolean acknowledged;
    private String acknowledgedBy;
    private Instant acknowledgedAt;

    public static AuditAlertResponse from(AuditAlert alert) {
        return AuditAlertResponse.builder()
                .id(alert.getId())
                .alertType(alert.getAlertType())
                .severity(alert.getSeverity())
                .userId(alert.getUserId())
                .userName(alert.getUserName())
                .description(alert.getDescription())
                .metadata(alert.getMetadata())
                .triggeredAt(alert.getTriggeredAt())
                .acknowledged(alert.isAcknowledged())
                .acknowledgedBy(alert.getAcknowledgedBy())
                .acknowledgedAt(alert.getAcknowledgedAt())
                .build();
    }
}
