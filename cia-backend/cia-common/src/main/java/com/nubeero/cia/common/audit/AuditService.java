package com.nubeero.cia.common.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.hibernate6.Hibernate6Module;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Dedicated mapper for the audit JSONB columns. Copied from the shared/primary
     * {@link ObjectMapper} (so it inherits its config) and given {@link Hibernate6Module},
     * which serializes an uninitialized Hibernate lazy proxy as {@code null} instead of
     * throwing. Without it, auditing an entity with a {@code @ManyToOne/@OneToOne(LAZY)}
     * association (e.g. {@code PolicyNumberFormat.product}) failed serialization, the old
     * fallback wrote a non-JSON {@code toString()} into the JSONB column, and the resulting
     * "invalid input syntax for type json" aborted the transaction → 500. Registered here
     * only, so API-response serialization (the primary mapper) is unaffected.
     */
    ObjectMapper auditMapper;   // package-private: asserted by AuditServiceTest

    @PostConstruct
    void configureAuditMapper() {
        // objectMapper is always injected in production; a couple of IT stubs
        // construct AuditService with a null mapper and override log() to no-op,
        // so guard the null rather than NPE at bean init.
        ObjectMapper base = (objectMapper != null) ? objectMapper.copy() : new ObjectMapper();
        this.auditMapper = base
                .registerModule(new Hibernate6Module())
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    // @Transactional(REQUIRES_NEW) on every public entry point. Three reasons:
    //   1) Production semantics — audit logs survive business-transaction
    //      rollbacks. Today's behaviour (where audit_log lives or dies with
    //      the business txn) loses precisely the rows auditors will sample
    //      for cause analysis.
    //   2) Avoids the "There are delayed insert actions before operation"
    //      Hibernate error when AuditService is called from inside a flush
    //      (e.g. PeriodLockInterceptor.recordOverride — fires during the
    //      JE's own onFlushDirty). The new transaction gets its own
    //      EntityManager so the save isn't queued behind the in-flight flush.
    //   3) Eliminates the LockableByPeriod recursion: AuditLog is not lockable
    //      but the auto-flush before save() inside the outer transaction
    //      could otherwise trigger an interceptor pass on the original entity.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String entityType, String entityId, AuditAction action,
                    Object oldValue, Object newValue) {
        log(entityType, entityId, action, oldValue, newValue, null, null, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String entityType, String entityId, AuditAction action,
                    Object oldValue, Object newValue, String ipAddress, String sessionId) {
        log(entityType, entityId, action, oldValue, newValue, ipAddress, sessionId, null, null);
    }

    /**
     * Log a "reasoned" action — DELETE / REJECT / OVERRIDE / REOPEN_PERIOD,
     * where WHY the action happened is auditor-relevant. The reason is
     * stored on {@code audit_log.reason} (V47). Existing 5-arg / 7-arg
     * overloads stay null on the new column.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logWithReason(String entityType, String entityId, AuditAction action,
                              Object oldValue, Object newValue, String reason) {
        log(entityType, entityId, action, oldValue, newValue, null, null, null, reason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logWithAmount(String entityType, String entityId, AuditAction action,
                              Object oldValue, Object newValue, BigDecimal approvalAmount) {
        log(entityType, entityId, action, oldValue, newValue, null, null, approvalAmount, null);
    }

    private void log(String entityType, String entityId, AuditAction action,
                     Object oldValue, Object newValue,
                     String ipAddress, String sessionId, BigDecimal approvalAmount, String reason) {
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
                    .reason(reason)
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
            return auditMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Could not serialise audit value to JSON ({}): {}",
                    value.getClass().getName(), e.getMessage());
            return fallbackJson(value, e);
        }
    }

    /**
     * Last-resort serialisation that ALWAYS returns valid JSON. A serialisation
     * failure must never write a malformed value into the {@code jsonb} audit
     * columns — that errors on INSERT and aborts the (audit) transaction (the
     * old code returned a non-JSON {@code toString()}). Captures the type + cause
     * for the auditor instead.
     */
    private String fallbackJson(Object value, Exception cause) {
        try {
            return auditMapper.writeValueAsString(Map.of(
                    "_unserialized", value.getClass().getName(),
                    "_error", String.valueOf(cause.getMessage())));
        } catch (Exception ignored) {
            return "{\"_unserialized\":\"unknown\"}";
        }
    }
}
