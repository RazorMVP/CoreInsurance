package com.nubeero.cia.setup.org;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.org.dto.RelationshipManagerRequest;
import com.nubeero.cia.setup.org.dto.RelationshipManagerResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RelationshipManagerService {

    private final RelationshipManagerRepository repository;
    private final BranchRepository branchRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<RelationshipManagerResponse> list(Pageable pageable) {
        return repository.findAllByDeletedAtIsNull(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public List<RelationshipManagerResponse> listByBranch(UUID branchId) {
        return repository.findAllByBranchIdAndDeletedAtIsNull(branchId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public RelationshipManagerResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public RelationshipManagerResponse create(RelationshipManagerRequest request) {
        RelationshipManager entity = RelationshipManager.builder()
                .name(request.getName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .branch(resolveBranch(request.getBranchId()))
                .build();
        RelationshipManager saved = repository.save(entity);
        auditService.log("RelationshipManager", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public RelationshipManagerResponse update(UUID id, RelationshipManagerRequest request) {
        RelationshipManager entity = findOrThrow(id);
        entity.setName(request.getName());
        entity.setEmail(request.getEmail());
        entity.setPhone(request.getPhone());
        entity.setBranch(resolveBranch(request.getBranchId()));
        RelationshipManager saved = repository.save(entity);
        auditService.log("RelationshipManager", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        RelationshipManager entity = findOrThrow(id);
        entity.softDelete();
        repository.save(entity);
        auditService.log("RelationshipManager", id.toString(), AuditAction.DELETE, entity, null);
    }

    private Branch resolveBranch(UUID branchId) {
        if (branchId == null) return null;
        return branchRepository.findById(branchId)
                .filter(b -> b.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Branch", branchId));
    }

    private RelationshipManager findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("RelationshipManager", id));
    }

    private RelationshipManagerResponse toResponse(RelationshipManager e) {
        return RelationshipManagerResponse.builder()
                .id(e.getId()).name(e.getName()).email(e.getEmail()).phone(e.getPhone())
                .branchId(e.getBranch() != null ? e.getBranch().getId() : null)
                .branchName(e.getBranch() != null ? e.getBranch().getName() : null)
                .createdAt(e.getCreatedAt()).updatedAt(e.getUpdatedAt())
                .build();
    }
}
