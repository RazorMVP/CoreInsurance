package com.nubeero.cia.setup.user;

import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.access.AccessGroup;
import com.nubeero.cia.setup.access.AccessGroupPermission;
import com.nubeero.cia.setup.access.AccessGroupRepository;
import com.nubeero.cia.setup.user.dto.UserRequest;
import com.nubeero.cia.setup.user.dto.UserResponse;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Proxy over the Keycloak admin client for the Module 1 Users surface.
 * Users live in Keycloak — there is no local {@code users} table. The only
 * frontend-shaped field that doesn't have a direct Keycloak counterpart is
 * {@code accessGroupId}, which is stored as a Keycloak user attribute.
 *
 * <p>The {@code Keycloak} bean is conditional on
 * {@code cia.keycloak.admin.enabled=true}. When absent (dev without a
 * Keycloak instance running) every method here throws a {@link
 * KeycloakAdminUnavailableException}; the controller translates that to
 * HTTP 503.
 *
 * <p>Out of scope (F1e-sync backlog row): translating {@code accessGroupId}
 * into the corresponding set of Keycloak realm roles. Today the attribute
 * is bookkeeping only — actual authorisation still flows through whatever
 * roles a Keycloak admin manually assigned in the console.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    /** Keycloak user attribute key carrying the access-group FK. */
    private static final String ATTR_ACCESS_GROUP_ID = "accessGroupId";

    private final ObjectProvider<Keycloak>  keycloak;
    private final KeycloakAdminProperties   props;
    private final AccessGroupRepository     accessGroupRepository;

    public List<UserResponse> list() {
        List<UserRepresentation> reps = realm().users().list();
        return reps.stream().map(this::toResponse).toList();
    }

    public UserResponse get(String id) {
        return toResponse(findOrThrow(id).toRepresentation());
    }

    public UserResponse create(UserRequest request) {
        AccessGroup group = resolveGroup(request.getAccessGroupId());

        UserRepresentation rep = new UserRepresentation();
        rep.setUsername(request.getEmail());
        rep.setEmail(request.getEmail());
        rep.setFirstName(request.getFirstName());
        rep.setLastName(request.getLastName());
        rep.setEnabled(true);
        rep.setEmailVerified(false);
        Map<String, List<String>> attrs = new HashMap<>();
        attrs.put(ATTR_ACCESS_GROUP_ID, List.of(group.getId().toString()));
        rep.setAttributes(attrs);

        try (Response response = realm().users().create(rep)) {
            if (response.getStatus() == 409) {
                throw new BusinessRuleException("USER_EMAIL_TAKEN",
                        "A user with this email already exists in the realm.");
            }
            if (response.getStatus() >= 300) {
                throw new BusinessRuleException("KEYCLOAK_CREATE_FAILED",
                        "Keycloak rejected the user-create request: HTTP " + response.getStatus());
            }

            String createdId = extractIdFromLocation(response);

            // Sync realm roles for the assigned access group. New users have
            // no roles by default — applying the group's permissions here is
            // what makes Spring @PreAuthorize actually grant access on the
            // user's first sign-in. (Backlog F1e-sync.)
            syncRealmRoles(realm().users().get(createdId), group);

            // Send Keycloak's own action-required email so the user sets
            // their password + verifies email on first sign-in. Failure is
            // non-fatal — the user can still be administered, just without
            // the welcome email.
            try {
                realm().users().get(createdId)
                        .executeActionsEmail(List.of("VERIFY_EMAIL", "UPDATE_PASSWORD"));
            } catch (Exception e) {
                log.warn("Welcome email failed for user {}: {}", createdId, e.getMessage());
            }
            return toResponse(realm().users().get(createdId).toRepresentation());
        }
    }

    public UserResponse update(String id, UserRequest request) {
        UserResource resource = findOrThrow(id);
        UserRepresentation rep = resource.toRepresentation();

        rep.setFirstName(request.getFirstName());
        rep.setLastName(request.getLastName());
        // Email is intentionally immutable — Keycloak treats it as the
        // effective username; rotating it would invalidate existing JWTs.

        if (request.getStatus() != null) {
            rep.setEnabled(request.getStatus() == UserStatus.ACTIVE);
        }

        AccessGroup group = resolveGroup(request.getAccessGroupId());
        Map<String, List<String>> attrs = rep.getAttributes() == null
                ? new HashMap<>() : new HashMap<>(rep.getAttributes());
        // Capture the previous accessGroupId before overwriting so we can
        // skip role-sync when nothing changed (avoids a Keycloak round-trip
        // per save on profile-only edits).
        String previousGroupId = firstAttr(rep, ATTR_ACCESS_GROUP_ID);
        attrs.put(ATTR_ACCESS_GROUP_ID, List.of(group.getId().toString()));
        rep.setAttributes(attrs);

        resource.update(rep);

        boolean groupChanged = !group.getId().toString().equals(previousGroupId);
        if (groupChanged) {
            syncRealmRoles(resource, group);
        }
        return toResponse(resource.toRepresentation());
    }

    public void resetPassword(String id) {
        // executeActionsEmail with UPDATE_PASSWORD is the canonical "reset
        // password" UX — the user gets an email link rather than us
        // generating a temporary password and shipping it out of band.
        findOrThrow(id).executeActionsEmail(List.of("UPDATE_PASSWORD"));
    }

    public UserResponse deactivate(String id) {
        return setEnabled(id, false);
    }

    public UserResponse activate(String id) {
        return setEnabled(id, true);
    }

    // ─── Internals ────────────────────────────────────────────────────────

    /**
     * Backlog F1e-sync. Replace the user's realm roles with exactly the set
     * implied by their access group's permissions. Permission names map 1:1
     * onto realm role names (the JwtAuthConverter prefixes {@code ROLE_}
     * before Spring's {@code hasRole(...)} matches, so a permission like
     * {@code SETUP_VIEW} drives a {@code @PreAuthorize("hasRole('SETUP_VIEW')")}
     * check against a Keycloak realm role of the same name).
     *
     * <p>Missing realm roles are auto-created on demand. The access group is
     * the source of truth — if a permission lands in the DB that doesn't
     * have a corresponding Keycloak role, we create it rather than 500.
     *
     * <p>Diff strategy: compute (desired, current); remove (current - desired),
     * add (desired - current). Idempotent — re-running with no group change
     * is a no-op.
     */
    private void syncRealmRoles(UserResource user, AccessGroup group) {
        RealmResource realm = realm();
        Set<String> desired = group.getPermissions().stream()
                .filter(p -> p.getDeletedAt() == null)
                .map(AccessGroupPermission::getPermission)
                .collect(Collectors.toCollection(HashSet::new));

        List<RoleRepresentation> currentRoles = user.roles().realmLevel().listAll();
        Set<String> current = currentRoles.stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toCollection(HashSet::new));

        // Only touch roles named like our permissions — never strip out
        // roles a Keycloak admin manually assigned (e.g. infrastructure
        // roles). The intersection-with-desired narrows the responsibility.
        Set<String> manageable = new HashSet<>(current);
        // Anything in `desired` is in scope; anything in `current` that
        // matches our naming convention (UPPER_SNAKE_CASE permissions —
        // mirroring the access_group_permissions seed pattern) is also
        // ours to manage. Conservatively, we only treat as ours the names
        // that look like permission identifiers.
        manageable.removeIf(name -> !looksLikePermission(name));

        Set<String> toAdd    = setDifference(desired, current);
        Set<String> toRemove = setDifference(manageable, desired);

        if (!toAdd.isEmpty()) {
            List<RoleRepresentation> reps = toAdd.stream()
                    .map(name -> ensureRealmRole(realm, name))
                    .collect(Collectors.toCollection(ArrayList::new));
            user.roles().realmLevel().add(reps);
        }
        if (!toRemove.isEmpty()) {
            List<RoleRepresentation> reps = currentRoles.stream()
                    .filter(r -> toRemove.contains(r.getName()))
                    .collect(Collectors.toCollection(ArrayList::new));
            user.roles().realmLevel().remove(reps);
        }
        log.debug("Realm-role sync for group {} → desired={}, added={}, removed={}",
                group.getName(), desired, toAdd, toRemove);
    }

    /**
     * Look up a realm role by name; create it (with the empty description
     * placeholder) if it doesn't exist. The access_group permission table
     * is the canonical list of what role names must exist.
     */
    private RoleRepresentation ensureRealmRole(RealmResource realm, String name) {
        try {
            return realm.roles().get(name).toRepresentation();
        } catch (NotFoundException nfe) {
            RoleRepresentation rep = new RoleRepresentation();
            rep.setName(name);
            rep.setDescription("Auto-created by UserService.syncRealmRoles — access_group permission.");
            realm.roles().create(rep);
            log.info("Auto-created realm role {} (missing on Keycloak; backed by access_group_permission).", name);
            return realm.roles().get(name).toRepresentation();
        }
    }

    /**
     * Permission names by convention are UPPER_SNAKE_CASE (SETUP_VIEW,
     * FINANCE_CREATE, etc.). Anything else (e.g., {@code default-roles-cia},
     * {@code offline_access}) is owned by Keycloak/admin and we leave alone.
     */
    private static boolean looksLikePermission(String name) {
        return name != null && name.matches("^[A-Z][A-Z0-9_]*$");
    }

    private static <T> Set<T> setDifference(Set<T> a, Set<T> b) {
        Set<T> result = new HashSet<>(a);
        result.removeAll(b);
        return result;
    }

    private UserResponse setEnabled(String id, boolean enabled) {
        UserResource resource = findOrThrow(id);
        UserRepresentation rep = resource.toRepresentation();
        rep.setEnabled(enabled);
        resource.update(rep);
        return toResponse(resource.toRepresentation());
    }

    private UserResource findOrThrow(String id) {
        UserResource resource = realm().users().get(id);
        try {
            // .toRepresentation() throws NotFoundException when the user
            // doesn't exist — translate into the project's domain exception.
            resource.toRepresentation();
            return resource;
        } catch (NotFoundException nfe) {
            throw new ResourceNotFoundException("User", id);
        }
    }

    private AccessGroup resolveGroup(UUID id) {
        return accessGroupRepository.findById(id)
                .filter(g -> g.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("AccessGroup", id));
    }

    private UserResponse toResponse(UserRepresentation rep) {
        String groupIdAttr = firstAttr(rep, ATTR_ACCESS_GROUP_ID);
        UUID   groupId     = parseUuid(groupIdAttr);
        String groupName   = groupId == null ? null
                : accessGroupRepository.findById(groupId)
                        .filter(g -> g.getDeletedAt() == null)
                        .map(AccessGroup::getName)
                        .orElse(null);

        return UserResponse.builder()
                .id(rep.getId())
                .email(rep.getEmail())
                .firstName(rep.getFirstName())
                .lastName(rep.getLastName())
                .status(resolveStatus(rep))
                .accessGroupId(groupIdAttr)
                .accessGroupName(groupName)
                .createdAt(rep.getCreatedTimestamp() == null
                        ? null : Instant.ofEpochMilli(rep.getCreatedTimestamp()))
                .build();
    }

    private UserStatus resolveStatus(UserRepresentation rep) {
        if (Boolean.FALSE.equals(rep.isEnabled())) return UserStatus.INACTIVE;
        // Keycloak ships brute-force-locked state on the
        // /attack-detection/brute-force/users/{id} endpoint, NOT on the
        // representation. Implementing that read here is a later
        // refinement; today, LOCKED is reserved for the explicit case.
        return UserStatus.ACTIVE;
    }

    private static String firstAttr(UserRepresentation rep, String key) {
        if (rep.getAttributes() == null) return null;
        List<String> values = rep.getAttributes().get(key);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    private static String extractIdFromLocation(Response response) {
        String location = response.getHeaderString("Location");
        if (location == null) {
            throw new BusinessRuleException("KEYCLOAK_CREATE_NO_LOCATION",
                    "Keycloak returned a 2xx but no Location header — cannot resolve created user id.");
        }
        int idx = location.lastIndexOf('/');
        return idx < 0 ? location : location.substring(idx + 1);
    }

    /**
     * Returns the realm resource, throwing 503-translatable when the admin
     * client is disabled (dev without Keycloak). The controller catches the
     * unchecked exception and shapes the 503 response.
     */
    private org.keycloak.admin.client.resource.RealmResource realm() {
        Keycloak client = keycloak.getIfAvailable();
        if (client == null) {
            throw new KeycloakAdminUnavailableException();
        }
        return client.realm(targetRealm());
    }

    /**
     * Single shared realm today — when realm-per-tenant lands this becomes
     * a {@code TenantContext.getTenantId()}-derived lookup. Centralised
     * here so the migration is a one-line change.
     */
    private String targetRealm() {
        return props.getTargetRealm();
    }

    /**
     * Thrown when the admin client isn't available (dev environment with
     * {@code cia.keycloak.admin.enabled=false}). Caught by
     * {@link UserController} and translated to HTTP 503.
     */
    public static class KeycloakAdminUnavailableException extends RuntimeException {
        public KeycloakAdminUnavailableException() {
            super("Keycloak admin client is disabled — set cia.keycloak.admin.enabled=true and configure service-account creds.");
        }
    }

    // Silence unused warning when collecting roles in future iterations.
    @SuppressWarnings("unused")
    private static <T> List<T> safe(List<T> in) {
        return in == null ? List.of() : in;
    }

    @SuppressWarnings("unused")
    private static <T> List<T> distinctList(List<T> in) {
        return in == null ? List.of() : in.stream().distinct().collect(Collectors.toList());
    }
}
