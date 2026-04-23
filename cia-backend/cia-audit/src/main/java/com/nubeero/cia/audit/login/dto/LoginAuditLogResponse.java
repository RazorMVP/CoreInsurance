package com.nubeero.cia.audit.login.dto;

import com.nubeero.cia.audit.login.LoginAuditLog;
import com.nubeero.cia.audit.login.LoginEventType;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class LoginAuditLogResponse {

    private UUID id;
    private LoginEventType eventType;
    private String userId;
    private String userName;
    private String ipAddress;
    private String userAgent;
    private Instant timestamp;
    private boolean success;
    private String failureReason;

    public static LoginAuditLogResponse from(LoginAuditLog log) {
        return LoginAuditLogResponse.builder()
                .id(log.getId())
                .eventType(log.getEventType())
                .userId(log.getUserId())
                .userName(log.getUserName())
                .ipAddress(log.getIpAddress())
                .userAgent(log.getUserAgent())
                .timestamp(log.getTimestamp())
                .success(log.isSuccess())
                .failureReason(log.getFailureReason())
                .build();
    }
}
