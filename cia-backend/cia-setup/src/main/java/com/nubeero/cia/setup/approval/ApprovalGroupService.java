package com.nubeero.cia.setup.approval;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.approval.dto.ApprovalGroupRequest;
import com.nubeero.cia.setup.approval.dto.ApprovalGroupResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ApprovalGroupService {

    private final ApprovalGroupRepository repository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<ApprovalGroupResponse> list(Pageable pageable) {
        return repository.findAllByDeletedAtIsNull(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public List<ApprovalGroupResponse> listByEntityType(String entityType) {
        return repository.findAllByEntityTypeAndDeletedAtIsNull(entityType)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ApprovalGroupResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public ApprovalGroupResponse create(ApprovalGroupRequest request) {
        ApprovalGroup group = ApprovalGroup.builder()
                .name(request.getName())
                .entityType(request.getEntityType().toUpperCase())
                .levels(new ArrayList<>())
                .build();
        addLevels(group, request.getLevels());
        ApprovalGroup saved = repository.save(group);
        auditService.log("ApprovalGroup", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public ApprovalGroupResponse update(UUID id, ApprovalGroupRequest request) {
        ApprovalGroup group = findOrThrow(id);
        group.setName(request.getName());
        group.setEntityType(request.getEntityType().toUpperCase());
        group.getLevels().clear();
        addLevels(group, request.getLevels());
        ApprovalGroup saved = repository.save(group);
        auditService.log("ApprovalGroup", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        ApprovalGroup group = findOrThrow(id);
        group.softDelete();
        repository.save(group);
        auditService.log("ApprovalGroup", id.toString(), AuditAction.DELETE, group, null);
    }

    private void addLevels(ApprovalGroup group, List<ApprovalGroupRequest.ApprovalLevelRequest> levelRequests) {
        levelRequests.forEach(lr -> group.getLevels().add(ApprovalGroupLevel.builder()
                .approvalGroup(group)
                .levelOrder(lr.getLevelOrder())
                .approverUserId(lr.getApproverUserId())
                .approverName(lr.getApproverName())
                .maxAmount(lr.getMaxAmount())
                .build()));
    }

    private ApprovalGroup findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(g -> g.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("ApprovalGroup", id));
    }

    private ApprovalGroupResponse toResponse(ApprovalGroup g) {
        List<ApprovalGroupResponse.ApprovalLevelResponse> levels = g.getLevels() == null ? List.of() :
                g.getLevels().stream()
                        .filter(l -> l.getDeletedAt() == null)
                        .map(l -> ApprovalGroupResponse.ApprovalLevelResponse.builder()
                                .id(l.getId())
                                .levelOrder(l.getLevelOrder())
                                .approverUserId(l.getApproverUserId())
                                .approverName(l.getApproverName())
                                .maxAmount(l.getMaxAmount())
                                .build())
                        .toList();
        return ApprovalGroupResponse.builder()
                .id(g.getId()).name(g.getName()).entityType(g.getEntityType())
                .levels(levels).createdAt(g.getCreatedAt()).updatedAt(g.getUpdatedAt())
                .build();
    }
}
