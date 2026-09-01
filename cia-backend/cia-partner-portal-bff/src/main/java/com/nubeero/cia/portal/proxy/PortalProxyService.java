package com.nubeero.cia.portal.proxy;

import com.nubeero.cia.partner.app.PartnerApp;
import com.nubeero.cia.portal.apps.GrantAuthorizationService;
import com.nubeero.cia.portal.apps.TenantScopedPartnerAppReader;
import com.nubeero.cia.portal.grant.PartnerPortalGrant;
import com.nubeero.cia.portal.proxy.dto.PortalAppCredentialsResponse;
import com.nubeero.cia.portal.proxy.dto.PortalAppCredentialsRotateResponse;
import com.nubeero.cia.portal.token.MintedToken;
import com.nubeero.cia.portal.token.PartnerAppSecretRotator;
import com.nubeero.cia.portal.token.PartnerAppTokenService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

/**
 * The authorization + tenant-resolution + token-minting orchestration behind every {@code
 * /portal/apps/{id}/**} endpoint {@link PortalProxyController} exposes: webhooks CRUD, credentials
 * read + rotate, and the try-it proxy.
 *
 * <p>Every method starts with a {@code GrantAuthorizationService} check — {@link #proxyAsGrantee}
 * for read-shaped calls (list webhooks, try-it, view credentials), {@link #proxyAsManager} /
 * {@link #rotateCredentials} for anything that mutates the app (create/delete webhook, rotate the
 * secret). Only after that gate passes does this class resolve the app's own {@code clientId} from
 * its tenant schema ({@link TenantScopedPartnerAppReader} — the OSIV-safe raw-{@code
 * EntityManager} read Task 7 built and this task promoted to a shared component) and mint a
 * short-lived Bearer token scoped to THAT app ({@link PartnerAppTokenService}).
 */
@Service
@RequiredArgsConstructor
public class PortalProxyService {

    private final GrantAuthorizationService grantAuthorizationService;
    private final TenantScopedPartnerAppReader appReader;
    private final PartnerAppTokenService tokenService;
    private final PartnerApiProxyClient proxyClient;
    private final PartnerAppSecretRotator secretRotator;

    /** Read-shaped proxy call (list webhooks, try-it GET) — any active grant suffices. */
    public ProxyResult proxyAsGrantee(UUID partnerUserId, UUID partnerAppId, HttpMethod method,
            String pathUnderPartnerV1, String queryString, String contentType, byte[] body) {
        PartnerPortalGrant grant = grantAuthorizationService.assertGrant(partnerUserId, partnerAppId);
        return doProxy(grant, partnerAppId, method, pathUnderPartnerV1, queryString, contentType, body);
    }

    /** Mutating proxy call (create/delete webhook, try-it POST/PUT/PATCH/DELETE) — MANAGER only. */
    public ProxyResult proxyAsManager(UUID partnerUserId, UUID partnerAppId, HttpMethod method,
            String pathUnderPartnerV1, String queryString, String contentType, byte[] body) {
        PartnerPortalGrant grant = grantAuthorizationService.assertManager(partnerUserId, partnerAppId);
        return doProxy(grant, partnerAppId, method, pathUnderPartnerV1, queryString, contentType, body);
    }

    private ProxyResult doProxy(PartnerPortalGrant grant, UUID partnerAppId, HttpMethod method,
            String pathUnderPartnerV1, String queryString, String contentType, byte[] body) {
        PartnerApp app = requireApp(grant, partnerAppId);
        MintedToken token = tokenService.tokenFor(grant.getTenantSchema(), app.getClientId());
        return proxyClient.forward(method, pathUnderPartnerV1, queryString, token.accessToken(), contentType, body);
    }

    /** {@code GET /portal/apps/{id}/credentials} — client_id + scopes, NEVER the secret. */
    public PortalAppCredentialsResponse credentials(UUID partnerUserId, UUID partnerAppId) {
        PartnerPortalGrant grant = grantAuthorizationService.assertGrant(partnerUserId, partnerAppId);
        PartnerApp app = requireApp(grant, partnerAppId);
        return new PortalAppCredentialsResponse(app.getClientId(), splitScopes(app.getScopes()));
    }

    /**
     * {@code POST /portal/apps/{id}/credentials/rotate} — MANAGER only. Regenerates the Keycloak
     * {@code client_secret} (the old one stops working immediately — Keycloak's own semantics, not
     * something this service enforces) and evicts any cached {@link MintedToken} for this app so
     * the NEXT proxy call is forced to re-mint under the new secret rather than keep serving a
     * token minted while the old secret was still valid.
     */
    public PortalAppCredentialsRotateResponse rotateCredentials(UUID partnerUserId, UUID partnerAppId) {
        PartnerPortalGrant grant = grantAuthorizationService.assertManager(partnerUserId, partnerAppId);
        PartnerApp app = requireApp(grant, partnerAppId);
        String newSecret = secretRotator.rotateSecret(grant.getTenantSchema(), app.getClientId());
        tokenService.evict(grant.getTenantSchema(), app.getClientId());
        return new PortalAppCredentialsRotateResponse(app.getClientId(), newSecret);
    }

    private PartnerApp requireApp(PartnerPortalGrant grant, UUID partnerAppId) {
        PartnerApp app = appReader.read(grant.getTenantSchema(), partnerAppId);
        if (app == null) {
            throw new PortalAppNotFoundException(
                    "Partner app " + partnerAppId + " not found in tenant '" + grant.getTenantSchema() + "'");
        }
        return app;
    }

    private static List<String> splitScopes(String scopes) {
        if (scopes == null || scopes.isBlank()) {
            return List.of();
        }
        return List.of(scopes.trim().split("\\s+"));
    }
}
