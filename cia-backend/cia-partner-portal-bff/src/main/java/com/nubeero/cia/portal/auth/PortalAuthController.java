package com.nubeero.cia.portal.auth;

import com.nubeero.cia.auth.PartnerPortalRealmProperties;
import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.common.exception.CiaException;
import com.nubeero.cia.portal.auth.dto.PortalLogoutResponse;
import com.nubeero.cia.portal.auth.dto.PortalMeResponse;
import com.nubeero.cia.portal.developer.PartnerDeveloperService;
import com.nubeero.cia.portal.developer.dto.PartnerDeveloperGrantResponse;
import com.nubeero.cia.portal.grant.PartnerPortalGrantRepository;
import com.nubeero.cia.portal.session.PortalSession;
import com.nubeero.cia.portal.session.PortalSessionStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The Partner Portal BFF's token-handler auth flow. The BFF (not the browser) is the OAuth
 * client — see {@link PortalOAuthClient}'s javadoc — so tokens exist only server-side, inside the
 * {@link PortalSessionStore}. The browser holds one opaque {@code HttpOnly} cookie
 * ({@link PortalSessionFilter#SESSION_COOKIE_NAME}) and nothing else.
 */
@RestController
@RequestMapping("/portal/auth")
@Tag(name = "Partner Portal Auth", description = "BFF token-handler login flow for Partner Portal developers")
@RequiredArgsConstructor
public class PortalAuthController {

    private static final String LOGIN_STATE_COOKIE_NAME = "cia_portal_login_state";
    private static final Duration SESSION_ABSOLUTE_TTL = Duration.ofHours(8);

    private final PortalOAuthClient oauthClient;
    private final PortalLoginStateStore loginStateStore;
    private final PortalSessionStore sessionStore;
    private final PartnerPortalGrantRepository grantRepository;
    private final PartnerPortalRealmProperties portalProperties;

    @GetMapping("/login")
    @Operation(summary = "Start the Partner Portal login flow",
               description = "Redirects the browser to Keycloak's authorize endpoint with a "
                       + "server-generated PKCE code_challenge + anti-CSRF state. The PKCE "
                       + "verifier is stored server-side (PortalLoginStateStore), keyed by state — "
                       + "the browser only ever holds the state value itself, in a short-lived "
                       + "cookie that binds the OAuth round-trip to this browser.")
    public ResponseEntity<Void> login(HttpServletRequest request) {
        String state = PkceGenerator.randomUrlSafeToken(24);
        PkceGenerator.Pkce pkce = PkceGenerator.generate();
        loginStateStore.save(state, pkce.verifier());

        String redirectUri = callbackUrl(request);
        String authorizeUrl = oauthClient.buildAuthorizeUrl(state, pkce.challenge(), redirectUri);

        ResponseCookie loginStateCookie = ResponseCookie.from(LOGIN_STATE_COOKIE_NAME, state)
                .httpOnly(true)
                .secure(true)
                // Lax, not Strict: this cookie must survive the top-level cross-site navigation
                // Keycloak performs when it 302s the browser back to /portal/auth/callback — a
                // Strict cookie is dropped on that hop. The final session cookie (issued on the
                // callback response, used only for same-site XHR/fetch afterwards) is Strict.
                .sameSite("Lax")
                .path("/portal/auth")
                .maxAge(PortalLoginStateStore.TTL)
                .build();

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(authorizeUrl))
                .header(HttpHeaders.SET_COOKIE, loginStateCookie.toString())
                .build();
    }

    @GetMapping("/callback")
    @Operation(summary = "OAuth callback — exchanges the code for tokens server-side",
               description = "Verifies state against the cookie stashed at /login, retrieves the "
                       + "matching PKCE verifier from server-side storage (single-use), exchanges "
                       + "the authorization code for tokens directly with Keycloak (never via the "
                       + "browser), creates a server-side PortalSession, and hands the browser "
                       + "only the opaque session cookie before redirecting to the SPA.")
    public ResponseEntity<Void> callback(
            @RequestParam String code, @RequestParam String state,
            HttpServletRequest request, HttpServletResponse response) {
        String cookieState = PortalCookies.read(request, LOGIN_STATE_COOKIE_NAME);
        if (cookieState == null) {
            clearLoginStateCookie(response);
            throw new CiaException("PORTAL_LOGIN_STATE_MISSING",
                    "Login session expired or missing — please try logging in again", HttpStatus.BAD_REQUEST);
        }
        if (!ConstantTimeEquals.equals(cookieState, state)) {
            clearLoginStateCookie(response);
            throw new CiaException("PORTAL_STATE_MISMATCH", "OAuth state mismatch", HttpStatus.BAD_REQUEST);
        }
        // Single-use: a replayed callback (same state twice) misses here on the second attempt.
        Optional<String> codeVerifier = loginStateStore.consume(state);
        if (codeVerifier.isEmpty()) {
            clearLoginStateCookie(response);
            throw new CiaException("PORTAL_STATE_MISMATCH",
                    "OAuth state expired or already used — please try logging in again", HttpStatus.BAD_REQUEST);
        }
        String redirectUri = callbackUrl(request);

        PortalOAuthTokens tokens = oauthClient.exchangeCode(code, codeVerifier.get(), redirectUri);
        String email = tokens.email().trim().toLowerCase(Locale.ROOT);
        UUID partnerUserId = PartnerDeveloperService.derivePartnerUserId(email);

        Instant now = Instant.now();
        Instant absoluteExpiry = now.plus(SESSION_ABSOLUTE_TTL);
        String csrfToken = PkceGenerator.randomUrlSafeToken(24);
        PortalSession session = new PortalSession(
                UUID.randomUUID().toString(),
                partnerUserId,
                email,
                tokens.displayName(),
                tokens.accessToken(),
                tokens.refreshToken(),
                absoluteExpiry,
                now.plus(PortalSessionStore.IDLE_TTL),
                csrfToken);
        String sessionId = sessionStore.create(session);

        ResponseCookie sessionCookie = ResponseCookie.from(PortalSessionFilter.SESSION_COOKIE_NAME, sessionId)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.between(now, absoluteExpiry))
                .build();
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(portalProperties.getAppUrl()))
                .header(HttpHeaders.SET_COOKIE, sessionCookie.toString())
                .header(HttpHeaders.SET_COOKIE, buildClearLoginStateCookie().toString())
                .build();
    }

    @GetMapping("/me")
    @Operation(summary = "Current developer profile + granted Partner Apps",
               description = "401s (via PortalSecurityConfig, before this method runs) if no "
                       + "valid session cookie is present. The response never carries a token.")
    public ResponseEntity<ApiResponse<PortalMeResponse>> me(@AuthenticationPrincipal PortalPrincipal principal) {
        List<PartnerDeveloperGrantResponse> apps =
                grantRepository.findByPartnerUserIdAndDeletedAtIsNull(principal.partnerUserId()).stream()
                        .map(PartnerDeveloperGrantResponse::from)
                        .toList();
        return ResponseEntity.ok(ApiResponse.success(
                new PortalMeResponse(principal.partnerUserId(), principal.email(), principal.csrfToken(), apps)));
    }

    @PostMapping("/logout")
    @Operation(summary = "Ends the portal session",
               description = "Deletes the server-side session, expires the cookie, and returns "
                       + "the Keycloak RP-initiated logout URL for the SPA to navigate to next. "
                       + "Requires the X-CSRF-Token header (double-submit, enforced by "
                       + "PortalSessionFilter, since this is a state-changing request).")
    public ResponseEntity<ApiResponse<PortalLogoutResponse>> logout(HttpServletRequest request) {
        String sessionId = PortalCookies.read(request, PortalSessionFilter.SESSION_COOKIE_NAME);
        if (sessionId != null) {
            sessionStore.delete(sessionId);
        }
        String logoutUrl = oauthClient.buildLogoutUrl(null, portalProperties.getAppUrl());

        ResponseCookie clearSession = ResponseCookie.from(PortalSessionFilter.SESSION_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearSession.toString())
                .body(ApiResponse.success(new PortalLogoutResponse(logoutUrl)));
    }

    /**
     * Clears the (fix round 1) login-state cookie directly on the raw response, for the
     * {@code /callback} error paths — an early return via a thrown {@link CiaException} has no
     * {@link ResponseEntity} to attach a header to, but a header added to the servlet response
     * before the exception propagates still rides along on {@code GlobalExceptionHandler}'s
     * eventual error response (same underlying response object). Without this, a failed login
     * attempt would leave a dead {@code cia_portal_login_state} cookie sitting in the browser
     * until its {@link PortalLoginStateStore#TTL} expiry instead of being cleaned up immediately.
     */
    private static void clearLoginStateCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, buildClearLoginStateCookie().toString());
    }

    private static ResponseCookie buildClearLoginStateCookie() {
        return ResponseCookie.from(LOGIN_STATE_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/portal/auth")
                .maxAge(0)
                .build();
    }

    private static String callbackUrl(HttpServletRequest request) {
        return ServletUriComponentsBuilder.fromContextPath(request)
                .path("/portal/auth/callback")
                .build()
                .toUriString();
    }
}
