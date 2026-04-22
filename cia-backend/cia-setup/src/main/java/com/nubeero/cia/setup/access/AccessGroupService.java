package com.nubeero.cia.setup.access;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.access.dto.AccessGroupRequest;
import com.nubeero.cia.setup.access.dto.AccessGroupResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccessGroupService {

    private final AccessGroupRepository repository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<AccessGroupResponse> list(Pageable pageable) {
        return repository.findAllByDeletedAtIsNull(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public AccessGroupResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public AccessGroupResponse create(AccessGroupRequest request) {
        AccessGroup group = AccessGroup.builder()
                .name(request.getName())
                .description(request.getDescription())
                .build();
        addPermissions(group, request.getPermissions());
        AccessGroup saved = repository.save(group);
        auditService.log("AccessGroup", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public AccessGroupResponse update(UUID id, AccessGroupRequest request) {
        AccessGroup group = findOrThrow(id);
        group.setName(request.getName());
        group.setDescription(request.getDescription());
        group.getPermissions().clear();
        addPermissions(group, request.getPermissions());
        AccessGroup saved = repository.save(group);
        auditService.log("AccessGroup", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        AccessGroup group = findOrThrow(id);
        group.softDelete();
        repository.save(group);
        auditService.log("AccessGroup", id.toString(), AuditAction.DELETE, group, null);
    }

    private void addPermissions(AccessGroup group, List<String> permissions) {
        permissions.forEach(p -> group.getPermissions().add(
                AccessGroupPermission.builder()
                        .accessGroup(group)
                        .permission(p)
                        .build()));
    }

    private AccessGroup findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(g -> g.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("AccessGroup", id));
    }

    private AccessGroupResponse toResponse(AccessGroup g) {
        List<String> perms = g.getPermissions() == null ? List.of() :
                g.getPermissions().stream()
                        .filter(p -> p.getDeletedAt() == null)
                        .map(AccessGroupPermission::getPermission)
                        .toList();
        return AccessGroupResponse.builder()
                .id(g.getId()).name(g.getName()).description(g.getDescription())
                .permissions(perms).createdAt(g.getCreatedAt()).updatedAt(g.getUpdatedAt())
                .build();
    }
}
