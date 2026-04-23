package com.nubeero.cia.audit.report;

import com.nubeero.cia.audit.log.dto.AuditLogResponse;
import com.nubeero.cia.audit.login.LoginAuditService;
import com.nubeero.cia.audit.login.dto.LoginAuditLogResponse;
import com.nubeero.cia.audit.report.dto.UserActivitySummary;
import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditLog;
import com.nubeero.cia.common.audit.AuditLogRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditReportService {

    private final AuditLogRepository auditLogRepository;
    private final LoginAuditService loginAuditService;

    /** Report 1: All actions by a specific user in a date range. */
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> actionsByUser(String userId, Instant from, Instant to, Pageable pageable) {
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("userId"), userId));
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.get("timestamp"), from));
            if (to != null) p.add(cb.lessThanOrEqualTo(root.get("timestamp"), to));
            if (query != null) query.orderBy(cb.desc(root.get("timestamp")));
            return cb.and(p.toArray(new Predicate[0]));
        };
        return auditLogRepository.findAll(spec, pageable).map(AuditLogResponse::from);
    }

    /** Report 2: All actions within a specific module (entity type prefix). */
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> actionsByModule(String entityType, Instant from, Instant to, Pageable pageable) {
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("entityType"), entityType));
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.get("timestamp"), from));
            if (to != null) p.add(cb.lessThanOrEqualTo(root.get("timestamp"), to));
            if (query != null) query.orderBy(cb.desc(root.get("timestamp")));
            return cb.and(p.toArray(new Predicate[0]));
        };
        return auditLogRepository.findAll(spec, pageable).map(AuditLogResponse::from);
    }

    /** Report 3: All approvals and rejections across all modules. */
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> approvalAuditTrail(Instant from, Instant to, Pageable pageable) {
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(root.get("action").in(AuditAction.APPROVE, AuditAction.REJECT));
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.get("timestamp"), from));
            if (to != null) p.add(cb.lessThanOrEqualTo(root.get("timestamp"), to));
            if (query != null) query.orderBy(cb.desc(root.get("timestamp")));
            return cb.and(p.toArray(new Predicate[0]));
        };
        return auditLogRepository.findAll(spec, pageable).map(AuditLogResponse::from);
    }

    /** Report 4: Full change history for a specific entity. */
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> dataChanges(String entityType, String entityId, Pageable pageable) {
        return auditLogRepository
                .findByEntityTypeAndEntityId(entityType, entityId, pageable)
                .map(AuditLogResponse::from);
    }

    /** Report 5: Login and security events for a user in a date range. */
    @Transactional(readOnly = true)
    public Page<LoginAuditLogResponse> loginSecurityReport(Instant from, Instant to, Pageable pageable) {
        return loginAuditService.listByDateRange(from, to, pageable);
    }

    /** Report 6: Ranked user activity summary for a date range. */
    @Transactional(readOnly = true)
    public List<UserActivitySummary> userActivitySummary(Instant from, Instant to) {
        return auditLogRepository.findUserActivitySummary(from, to).stream()
                .map(p -> UserActivitySummary.builder()
                        .userId(p.getUserId())
                        .userName(p.getUserName())
                        .actionCount(p.getActionCount())
                        .build())
                .toList();
    }
}
