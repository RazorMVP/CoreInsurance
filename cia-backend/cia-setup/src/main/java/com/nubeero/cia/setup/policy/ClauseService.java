package com.nubeero.cia.setup.policy;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.clause.ClauseSnapshot;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.policy.dto.ClauseRequest;
import com.nubeero.cia.setup.policy.dto.ClauseResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ClauseService {

    private final ClauseRepository repository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<ClauseResponse> list(Pageable pageable) {
        return repository.findAllByDeletedAtIsNull(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ClauseResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public ClauseResponse create(ClauseRequest request) {
        Clause entity = Clause.builder()
                .title(request.getTitle())
                .text(request.getText())
                .type(request.getType())
                .applicability(request.getApplicability())
                .productIds(request.getProductIds() != null ? request.getProductIds() : new ArrayList<>())
                .build();
        Clause saved = repository.save(entity);
        auditService.log("Clause", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public ClauseResponse update(UUID id, ClauseRequest request) {
        Clause entity = findOrThrow(id);
        entity.setTitle(request.getTitle());
        entity.setText(request.getText());
        entity.setType(request.getType());
        entity.setApplicability(request.getApplicability());
        entity.setProductIds(request.getProductIds() != null ? request.getProductIds() : new ArrayList<>());
        Clause saved = repository.save(entity);
        auditService.log("Clause", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id, String reason) {
        Clause entity = findOrThrow(id);
        entity.softDelete();
        repository.save(entity);
        auditService.logWithReason("Clause", id.toString(), AuditAction.DELETE, entity, null, reason);
    }

    /**
     * Resolves selected clause IDs against the active clause master into frozen snapshots, in the
     * order requested. Unknown/deleted IDs are silently skipped so a stale selection never breaks a
     * quote/policy. Consumed by cia-quotation and cia-policy at create/edit time.
     */
    @Transactional(readOnly = true)
    public List<ClauseSnapshot> snapshot(List<String> clauseIds) {
        if (clauseIds == null || clauseIds.isEmpty()) {
            return List.of();
        }
        Map<String, Clause> byId = repository.findAllByDeletedAtIsNull().stream()
                .collect(Collectors.toMap(c -> c.getId().toString(), Function.identity()));
        return clauseIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(c -> new ClauseSnapshot(
                        c.getId().toString(), c.getTitle(), c.getText(), c.getType().name()))
                .toList();
    }

    private Clause findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Clause", id));
    }

    private ClauseResponse toResponse(Clause e) {
        return ClauseResponse.builder()
                .id(e.getId())
                .title(e.getTitle())
                .text(e.getText())
                .type(e.getType())
                .applicability(e.getApplicability())
                .productIds(e.getProductIds())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
