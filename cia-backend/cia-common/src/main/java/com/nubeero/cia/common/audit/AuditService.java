package com.nubeero.cia.common.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public void log(String entityType, String entityId, AuditAction action,
                    Object oldValue, Object newValue) {
        log(entityType, entityId, action, oldValue, newValue, null, null, null);
    }

    public void log(String entityType, String entityId, AuditAction action,
                    Object oldValue, Object newValue, String ipAddress, String sessionId) {
        log(entityType, entityId, action, oldValue, newValue, ipAddress, sessionId, null);
    }

    public void logWithAmount(String entityType, String entityId, AuditAction action,
                              Object oldValue, Object newValue, BigDecimal approvalAmount) {
        log(entityType, entityId, action, oldValue, newValue, null, null, approvalAmount);
    }

    private void log(String entityType, String entityId, AuditAction action,
                     Object oldValue, Object newValue,
                     String ipAddress, String sessionId, BigDecimal approvalAmount) {
        try {
            AuditLog entry = AuditLog.builder()
                    .entityType(entityType)
                    .entityId(entityId)
                    .action(action)
                    .userId(resolveUserId())
                    .userName(resolveUserName())
                    .timestamp(Instant.now())
                    .oldValue(toJson(oldValue))
                    .newValue(toJson(newValue))
                    .ipAddress(ipAddress)
                    .sessionId(sessionId)
                    .approvalAmount(approvalAmount)
                    .build();

            AuditLog saved = auditLogRepository.save(entry);
            eventPublisher.publishEvent(new AuditLogCreatedEvent(this, saved));
        } catch (Exception e) {
            log.error("Failed to write audit log for entity={} id={} action={}", entityType, entityId, action, e);
        }
    }

    private String resolveUserId() {
        Jwt jwt = currentJwt();
        if (jwt == null) return "system";
        String sub = jwt.getSubject();
        return sub != null ? sub : "unknown";
    }

    private String resolveUserName() {
        Jwt jwt = currentJwt();
        if (jwt == null) return "system";
        String preferred = jwt.getClaimAsString("preferred_username");
        if (preferred != null) return preferred;
        String name = jwt.getClaimAsString("name");
        return name != null ? name : "unknown";
    }

    private Jwt currentJwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) return null;
        return jwt;
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Could not serialise audit value to JSON: {}", e.getMessage());
            return value.toString();
        }
    }
}
