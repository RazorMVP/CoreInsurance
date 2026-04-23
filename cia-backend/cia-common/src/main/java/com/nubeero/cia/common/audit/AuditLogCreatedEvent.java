package com.nubeero.cia.common.audit;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class AuditLogCreatedEvent extends ApplicationEvent {

    private final AuditLog auditLog;

    public AuditLogCreatedEvent(Object source, AuditLog auditLog) {
        super(source);
        this.auditLog = auditLog;
    }
}
