package com.nubeero.cia.audit.log;

import com.nubeero.cia.audit.log.dto.AuditLogFilter;
import com.nubeero.cia.audit.log.dto.AuditLogResponse;
import com.nubeero.cia.common.audit.AuditLog;
import com.nubeero.cia.common.audit.AuditLogRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private final AuditLogRepository auditLogRepository;

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> search(AuditLogFilter filter, Pageable pageable) {
        Specification<AuditLog> spec = buildSpec(filter);
        return auditLogRepository.findAll(spec, pageable).map(AuditLogResponse::from);
    }

    private Specification<AuditLog> buildSpec(AuditLogFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.getEntityType() != null && !filter.getEntityType().isBlank()) {
                predicates.add(cb.equal(root.get("entityType"), filter.getEntityType()));
            }
            if (filter.getEntityId() != null && !filter.getEntityId().isBlank()) {
                predicates.add(cb.equal(root.get("entityId"), filter.getEntityId()));
            }
            if (filter.getUserId() != null && !filter.getUserId().isBlank()) {
                predicates.add(cb.equal(root.get("userId"), filter.getUserId()));
            }
            if (filter.getAction() != null) {
                predicates.add(cb.equal(root.get("action"), filter.getAction()));
            }
            if (filter.getFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), filter.getFrom()));
            }
            if (filter.getTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("timestamp"), filter.getTo()));
            }
            if (query != null) {
                query.orderBy(cb.desc(root.get("timestamp")));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
