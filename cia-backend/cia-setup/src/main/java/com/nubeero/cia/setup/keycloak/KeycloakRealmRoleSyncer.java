package com.nubeero.cia.setup.keycloak;

import com.nubeero.cia.setup.access.AccessGroup;
import com.nubeero.cia.setup.access.AccessGroupPermission;
import com.nubeero.cia.setup.user.KeycloakAdminProperties;
import jakarta.ws.rs.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * F1e-sync: writes the user's Keycloak realm-role assignment so that it
 * matches the {@link AccessGroup}'s permission list.
 *
 * <p>This class deliberately encapsulates every Keycloak admin-client type
 * reference. {@link com.nubeero.cia.setup.user.UserService} only sees this
 * {@code @Service} as a plain Spring bean — no {@code Keycloak},
 * {@code RolesResource}, or {@code RoleRepresentation} symbol appears in its
 * bytecode. Session 112's first F1e-sync attempt failed because adding
 * role-management types directly to {@code UserService} polluted JVM
 * classloader state in a way that broke {@code ContractGroupingServiceIT} in
 * the full failsafe suite. Keeping those types confined here avoids the
 * regression while preserving the user-create / user-update call-site
 * delegation pattern.
 *
 * <p>Boundary rule. The syncer ONLY manages roles whose description starts
 * with {@link #DESC_PREFIX}. Roles created by a Keycloak admin directly in
 * the console (e.g. {@code realm-admin}, custom groups) are left untouched
 * regardless of whether they're assigned to the user. This makes the sync
 * safe to run against realms where humans also manage roles.
 *
 * <p>Mapping. CIA permission strings are colon-form ({@code setup:view},
 * {@code claims:create}). Keycloak realm role names use underscore-form
 * ({@code setup_view}) so that the {@code JwtAuthConverter}'s
 * {@code "ROLE_" + role.toUpperCase()} produces a Spring authority
 * {@code ROLE_SETUP_VIEW} matching the {@code @PreAuthorize("hasRole('SETUP_VIEW')")}
 * call sites across the API.
 *
 * <p>This bean is conditional on {@code cia.keycloak.admin.enabled=true}.
 * In tests and dev-without-Keycloak that property is false, the bean is
 * absent, and {@code UserService} receives an empty {@link ObjectProvider}
 * (its {@code getIfAvailable()} returns null and the call no-ops).
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "cia.keycloak.admin", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class KeycloakRealmRoleSyncer {

    /** Description prefix marking a role as managed by CIA's sync. */
    static final String DESC_PREFIX = "CIA-managed: ";

    private final ObjectProvider<Keycloak>  keycloak;
    private final KeycloakAdminProperties   props;

    /**
     * Reconciles {@code userId}'s Keycloak realm-role assignment with the
     * permission list on {@code group}. Idempotent; safe to call on every
     * user create/update.
     *
     * <p>If the Keycloak admin client is unavailable at runtime (the
     * conditional kept the bean alive but the client itself failed to
     * resolve — rare, but possible after a Keycloak restart), the call
     * logs a warning and returns. The user's CIA-side record is the source
     * of truth; a transient Keycloak outage doesn't fail the parent
     * transaction.
     */
    public void syncFor(String userId, AccessGroup group) {
        Keycloak client = keycloak.getIfAvailable();
        if (client == null) {
            log.warn("Keycloak admin client unavailable — skipping realm-role sync for user {}", userId);
            return;
        }

        RealmResource realm = client.realm(props.getTargetRealm());
        RolesResource roles = realm.roles();
        UserResource  user  = realm.users().get(userId);

        Set<String> desiredNames = new HashSet<>();
        for (AccessGroupPermission p : group.getPermissions()) {
            if (p.getDeletedAt() != null) continue;
            desiredNames.add(permissionToRoleName(p.getPermission()));
        }

        for (String name : desiredNames) {
            ensureManagedRole(roles, name);
        }

        List<RoleRepresentation> currentAll = user.roles().realmLevel().listAll();
        Set<String> currentManaged = new HashSet<>();
        for (RoleRepresentation r : currentAll) {
            if (isCiaManaged(r)) currentManaged.add(r.getName());
        }

        Set<String> toAdd    = new HashSet<>(desiredNames);    toAdd.removeAll(currentManaged);
        Set<String> toRemove = new HashSet<>(currentManaged);  toRemove.removeAll(desiredNames);

        if (!toAdd.isEmpty()) {
            List<RoleRepresentation> add = toAdd.stream()
                    .map(n -> roles.get(n).toRepresentation())
                    .toList();
            user.roles().realmLevel().add(add);
        }
        if (!toRemove.isEmpty()) {
            List<RoleRepresentation> remove = toRemove.stream()
                    .map(n -> roles.get(n).toRepresentation())
                    .toList();
            user.roles().realmLevel().remove(remove);
        }

        log.info("Synced realm roles for user {} — +{}/-{}", userId, toAdd.size(), toRemove.size());
    }

    private void ensureManagedRole(RolesResource roles, String name) {
        try {
            RoleRepresentation existing = roles.get(name).toRepresentation();
            // Adopt unmanaged role names that happen to match (e.g. seeded
            // by a previous schema). Tagging it makes it eligible for
            // future removal if the access group changes — explicit
            // hand-over, no silent leak.
            if (!isCiaManaged(existing)) {
                existing.setDescription(DESC_PREFIX + safeDesc(existing.getDescription()));
                roles.get(name).update(existing);
            }
        } catch (NotFoundException nfe) {
            RoleRepresentation rep = new RoleRepresentation();
            rep.setName(name);
            rep.setDescription(DESC_PREFIX + "Synced from CIA permission " + name);
            roles.create(rep);
        }
    }

    private boolean isCiaManaged(RoleRepresentation role) {
        String desc = role.getDescription();
        return desc != null && desc.startsWith(DESC_PREFIX);
    }

    private static String safeDesc(String d) {
        return d == null ? "" : d;
    }

    /**
     * Maps a CIA permission string (colon-form) to a Keycloak realm role
     * name (underscore-form). Visible for unit testing.
     */
    static String permissionToRoleName(String permission) {
        return permission.replace(':', '_');
    }
}
