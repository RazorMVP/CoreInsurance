package com.nubeero.cia.setup.access;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.access.dto.AccessGroupRequest;
import com.nubeero.cia.setup.access.dto.AccessGroupResponse;
import com.nubeero.cia.setup.keycloak.KeycloakRealmRoleSyncer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccessGroupService {

    private final AccessGroupRepository                     repository;
    private final AuditService                              auditService;
    /**
     * F1e-sync-AccessGroup-fanout (S118) delegate. When permissions on a
     * group change, fans out the realm-role sync to every user assigned to
     * that group. Same {@link ObjectProvider} encapsulation pattern as
     * {@code UserService} — keeps Keycloak admin-client types out of this
     * service's bytecode (the type the field exposes is our own syncer
     * class, not anything from {@code org.keycloak.admin.client.*}).
     */
    private final ObjectProvider<KeycloakRealmRoleSyncer>   roleSyncer;

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

        // Snapshot the pre-mutation permission set so we can detect whether
        // a fanout sync is actually needed (only when the set changed —
        // name/description edits alone shouldn't trigger a fanout). Using
        // Set equality means order doesn't matter.
        Set<String> oldPerms = new HashSet<>();
        for (AccessGroupPermission p : group.getPermissions()) {
            if (p.getDeletedAt() == null) oldPerms.add(p.getPermission());
        }

        group.setName(request.getName());
        group.setDescription(request.getDescription());
        group.getPermissions().clear();
        addPermissions(group, request.getPermissions());
        AccessGroup saved = repository.save(group);
        auditService.log("AccessGroup", id.toString(), AuditAction.UPDATE, null, saved);

        // F1e-sync-AccessGroup-fanout: if the permission set changed, every
        // user currently assigned to this group needs their Keycloak realm
        // roles re-reconciled. Skipped when the syncer bean isn't a
        // candidate (cia.keycloak.admin.enabled=false in tests / dev
        // without Keycloak).
        Set<String> newPerms = new HashSet<>(request.getPermissions());
        if (!oldPerms.equals(newPerms)) {
            KeycloakRealmRoleSyncer s = roleSyncer.getIfAvailable();
            if (s != null) {
                s.syncForAllInGroup(saved);
            }
        }

        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id, String reason) {
        AccessGroup group = findOrThrow(id);
        group.softDelete();
        repository.save(group);
        // V47 reasoned-soft-delete convention — the reason ends up in
        // audit_log.reason alongside the DELETE event.
        auditService.logWithReason("AccessGroup", id.toString(), AuditAction.DELETE, group, null, reason);
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
