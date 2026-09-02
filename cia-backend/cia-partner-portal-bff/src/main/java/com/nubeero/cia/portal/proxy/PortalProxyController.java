package com.nubeero.cia.portal.proxy;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.portal.auth.PortalPrincipal;
import com.nubeero.cia.portal.proxy.dto.PortalAppCredentialsResponse;
import com.nubeero.cia.portal.proxy.dto.PortalAppCredentialsRotateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Partner Portal's management + "try it" surface — the BFF forwards these requests to the
 * app's real {@code /partner/v1/**} API with a server-side-minted Bearer token attached, so the
 * portal behaves EXACTLY like a genuine external integration: the same scope errors, the same
 * 429s, the same response shapes. See {@link PartnerApiProxyClient} for why this is a real HTTP
 * call rather than a direct call into the partner domain services.
 *
 * <p>Every endpoint is gated by {@link com.nubeero.cia.portal.apps.GrantAuthorizationService} —
 * reads via {@code assertGrant} (any active grant), mutations via {@code assertManager} (MANAGER
 * role only) — enforced inside {@link PortalProxyService}, not here.
 *
 * <p>{@code /credentials} and {@code /credentials/rotate} are NOT proxied — {@code
 * /partner/v1/**} has no partner-facing credentials endpoint (client_id/secret management is a
 * Setup-module/Keycloak-admin concern). Everything else here (webhooks CRUD, try-it) IS proxied.
 */
@RestController
@RequestMapping("/portal/apps/{appId}")
@Tag(name = "Partner Portal App Management", description = "Webhooks, credentials, and the try-it proxy for a single Partner App")
@RequiredArgsConstructor
public class PortalProxyController {

    /**
     * Headers stripped from the relayed upstream response — see {@link #toResponseEntity}.
     * {@code transfer-encoding}/{@code connection} are hop-by-hop headers that must never be
     * relayed verbatim over a fresh response. {@code set-cookie}/{@code set-cookie2} are stripped
     * defensively so an upstream {@code /partner/v1} {@code Set-Cookie} could never flow into a
     * {@code /portal/**} response and interact with the portal's own {@code cia_portal_session}
     * cookie — {@code /partner/v1} sets none today, but the proxy must not assume that stays true.
     */
    private static final Set<String> STRIPPED_RESPONSE_HEADERS =
            Set.of("transfer-encoding", "connection", "set-cookie", "set-cookie2");

    private final PortalProxyService proxyService;

    // ── Webhooks CRUD — proxied to /partner/v1/webhooks[...] ──────────────────────────────

    @GetMapping("/webhooks")
    @Operation(summary = "List registered webhooks", description = "Proxies to GET /partner/v1/webhooks")
    public ResponseEntity<byte[]> listWebhooks(@PathVariable UUID appId,
            @AuthenticationPrincipal PortalPrincipal principal, HttpServletRequest request) {
        ProxyResult result = proxyService.proxyAsGrantee(principal.partnerUserId(), appId,
                HttpMethod.GET, "/webhooks", request.getQueryString(), null, null);
        return toResponseEntity(result);
    }

    @PostMapping("/webhooks")
    @Operation(summary = "Register a webhook endpoint", description = "Proxies to POST /partner/v1/webhooks — MANAGER only")
    public ResponseEntity<byte[]> createWebhook(@PathVariable UUID appId,
            @AuthenticationPrincipal PortalPrincipal principal, HttpServletRequest request) {
        ProxyResult result = proxyService.proxyAsManager(principal.partnerUserId(), appId,
                HttpMethod.POST, "/webhooks", request.getQueryString(), request.getContentType(), bodyOf(request));
        return toResponseEntity(result);
    }

    @DeleteMapping("/webhooks/{webhookId}")
    @Operation(summary = "Remove a webhook registration", description = "Proxies to DELETE /partner/v1/webhooks/{id} — MANAGER only")
    public ResponseEntity<byte[]> deleteWebhook(@PathVariable UUID appId, @PathVariable String webhookId,
            @AuthenticationPrincipal PortalPrincipal principal, HttpServletRequest request) {
        ProxyResult result = proxyService.proxyAsManager(principal.partnerUserId(), appId,
                HttpMethod.DELETE, "/webhooks/" + webhookId, request.getQueryString(), null, null);
        return toResponseEntity(result);
    }

    // ── Credentials — BFF-local, never proxied (no /partner/v1 equivalent) ────────────────

    @GetMapping("/credentials")
    @Operation(summary = "View this app's client_id and granted scopes", description = "NEVER returns the client_secret")
    public ResponseEntity<ApiResponse<PortalAppCredentialsResponse>> credentials(
            @PathVariable UUID appId, @AuthenticationPrincipal PortalPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                proxyService.credentials(principal.partnerUserId(), appId)));
    }

    @PostMapping("/credentials/rotate")
    @Operation(summary = "Rotate this app's client_secret", description =
            "MANAGER only. Returns the new secret exactly once — it is never stored or logged, "
                    + "and the old secret stops working immediately.")
    public ResponseEntity<ApiResponse<PortalAppCredentialsRotateResponse>> rotateCredentials(
            @PathVariable UUID appId, @AuthenticationPrincipal PortalPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                proxyService.rotateCredentials(principal.partnerUserId(), appId)));
    }

    // ── Try-it — forwards ANY method/path to /partner/v1/{path} ───────────────────────────

    private static final Set<String> MUTATING_METHODS =
            Set.of(RequestMethod.POST.name(), RequestMethod.PUT.name(),
                    RequestMethod.PATCH.name(), RequestMethod.DELETE.name());

    @RequestMapping(value = "/try/{*path}",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
                      RequestMethod.PATCH, RequestMethod.DELETE})
    @Operation(summary = "Forward any request to /partner/v1/{path}", description =
            "The 'try it' console — proxies verbatim to the app's own /partner/v1/** API with a "
                    + "minted Bearer token, so the response (including scope-denied 403s and "
                    + "rate-limit 429s) is exactly what a real external integration would see. "
                    + "GET is gated by assertGrant; every other method by assertManager.")
    public ResponseEntity<byte[]> tryIt(@PathVariable UUID appId, @PathVariable String path,
            @AuthenticationPrincipal PortalPrincipal principal, HttpServletRequest request) {
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        byte[] body = MUTATING_METHODS.contains(request.getMethod()) ? bodyOf(request) : null;
        ProxyResult result = MUTATING_METHODS.contains(request.getMethod())
                ? proxyService.proxyAsManager(principal.partnerUserId(), appId, method, path,
                        request.getQueryString(), request.getContentType(), body)
                : proxyService.proxyAsGrantee(principal.partnerUserId(), appId, method, path,
                        request.getQueryString(), request.getContentType(), body);
        return toResponseEntity(result);
    }

    // ── Shared plumbing ─────────────────────────────────────────────────────────────────

    private static byte[] bodyOf(HttpServletRequest request) {
        try {
            return request.getInputStream().readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the request body to proxy", e);
        }
    }

    private static ResponseEntity<byte[]> toResponseEntity(ProxyResult result) {
        HttpHeaders headers = new HttpHeaders();
        result.headers().forEach((name, values) -> {
            if (!STRIPPED_RESPONSE_HEADERS.contains(name.toLowerCase())) {
                headers.put(name, values);
            }
        });
        return ResponseEntity.status(result.status()).headers(headers).body(result.body());
    }
}
