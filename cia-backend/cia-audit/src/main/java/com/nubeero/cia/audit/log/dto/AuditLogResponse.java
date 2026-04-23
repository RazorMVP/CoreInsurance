package com.nubeero.cia.audit.log.dto;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditLog;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class AuditLogResponse {

    private UUID id;
    private String entityType;
    private String entityId;
    private AuditAction action;
    private String userId;
    private String userName;
    private Instant timestamp;
    private String oldValue;
    private String newValue;
    private String ipAddress;
    private String sessionId;
    private BigDecimal approvalAmount;

    public static AuditLogResponse from(AuditLog log) {
        return AuditLogResponse.builder()
                .id(log.getId())
                .entityType(log.getEntityType())
                .entityId(log.getEntityId())
                .action(log.getAction())
                .userId(log.getUserId())
                .userName(log.getUserName())
                .timestamp(log.getTimestamp())
                .oldValue(log.getOldValue())
                .newValue(log.getNewValue())
                .ipAddress(log.getIpAddress())
                .sessionId(log.getSessionId())
                .approvalAmount(log.getApprovalAmount())
                .build();
    }
}
