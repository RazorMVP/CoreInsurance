package com.nubeero.cia.audit.log.dto;

import com.nubeero.cia.common.audit.AuditAction;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;

@Data
public class AuditLogFilter {
    private String entityType;
    private String entityId;
    private String userId;
    private AuditAction action;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private Instant from;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private Instant to;
}
