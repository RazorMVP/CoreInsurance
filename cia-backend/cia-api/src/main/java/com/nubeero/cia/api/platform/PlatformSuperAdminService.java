package com.nubeero.cia.api.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.api.platform.dto.InviteSuperAdminRequest;
import com.nubeero.cia.api.platform.dto.InviteSuperAdminResponse;
import com.nubeero.cia.api.platform.dto.SuperAdminSummary;
import com.nubeero.cia.auth.PlatformRealmProperties;
import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner;
import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner.SuperAdminView;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Platform super-admin lifecycle: list / invite / revoke against the platform Keycloak realm.
 *
 * <p>Drives the realm through {@link KeycloakTenantProvisioner}; enforces the self-lockout and
 * last-super-admin guards; audits INVITE/REVOKE to {@code public.platform_audit_log} (with a NULL
 * target_schema — these are user-targeted actions). When the Keycloak admin client is unavailable
 * (dev without Keycloak), every method throws {@link SuperAdminExceptions.KeycloakAdminDisabled},
 * which the controller maps to HTTP 503.
 */
@Slf4j
@Service
public class PlatformSuperAdminService {

    private final ObjectProvider<Keycloak> keycloak;
    private final KeycloakTenantProvisioner provisioner;
    private final PlatformAuditService audit;
    private final PlatformRealmProperties platformProps;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PlatformSuperAdminService(ObjectProvider<Keycloak> keycloak,
                                     KeycloakTenantProvisioner provisioner,
                                     PlatformAuditService audit,
                                     PlatformRealmProperties platformProps) {
        this.keycloak = keycloak;
        this.provisioner = provisioner;
        this.audit = audit;
        this.platformProps = platformProps;
    }

    /** Lists current super-admins. */
    public List<SuperAdminSummary> list() {
        Keycloak client = client();
        return provisioner.listSuperAdmins(client, realm()).stream()
                .map(v -> new SuperAdminSummary(v.username(), v.email(), v.enabled()))
                .toList();
    }

    /** Invites a new super-admin; returns the one-time temp password. */
    public InviteSuperAdminResponse invite(InviteSuperAdminRequest req,
                                           String actor, String actorRealm, String ip) {
        Objects.requireNonNull(actor, "actor must not be null");
        Keycloak client = client();
        String tempPassword = PlatformPasswords.generateTempPassword();
        try {
            provisioner.createSuperAdmin(client, realm(), req.username(), req.email(), tempPassword);
        } catch (KeycloakTenantProvisioner.SuperAdminExistsInRealm e) {
            throw new SuperAdminExceptions.AlreadyExists(req.username());
        }
        audit.record("INVITE_SUPER_ADMIN", null, actor, actorRealm,
                toJson(Map.of("username", req.username(), "email", req.email())), ip);
        return new InviteSuperAdminResponse(req.username(), req.email(), tempPassword);
    }

    /** Revokes a super-admin's role. Guards: cannot revoke self; cannot revoke the last one. */
    public void revoke(String username, String actor, String actorRealm, String ip) {
        Objects.requireNonNull(actor, "actor must not be null");
        if (username.equals(actor)) {
            throw new SuperAdminExceptions.CannotRevokeSelf();
        }
        Keycloak client = client();
        // One snapshot of membership backs both the last-admin and existence guards — avoids a
        // second Keycloak round trip and the TOCTOU window between separate list/count reads.
        List<SuperAdminView> admins = provisioner.listSuperAdmins(client, realm());
        if (admins.size() <= 1) {
            throw new SuperAdminExceptions.CannotRevokeLast();
        }
        boolean exists = admins.stream().anyMatch(a -> a.username().equals(username));
        if (!exists) {
            throw new SuperAdminExceptions.NotFound(username);
        }
        provisioner.removeSuperAdminRole(client, realm(), username);
        audit.record("REVOKE_SUPER_ADMIN", null, actor, actorRealm,
                toJson(Map.of("username", username)), ip);
    }

    private Keycloak client() {
        Keycloak c = keycloak.getIfAvailable();
        if (c == null) {
            throw new SuperAdminExceptions.KeycloakAdminDisabled();
        }
        return c;
    }

    private String realm() {
        return platformProps.getRealm();
    }

    private String toJson(Map<String, ?> m) {
        try {
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            log.warn("Failed to serialise super-admin audit detail; proceeding without detail", e);
            return null;
        }
    }
}
