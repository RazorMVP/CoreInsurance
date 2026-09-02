package com.nubeero.cia.portal.auth;

import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.portal.session.PortalSession;
import com.nubeero.cia.portal.session.PortalSessionStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the opaque {@code cia_portal_session} cookie, loads the {@link PortalSession} it names,
 * and — if present and unexpired — sets a {@link PortalAuthenticationToken}(carrying {@link
 * PortalPrincipal}) as the request's {@code SecurityContext} authentication. Absent/expired ⇒ no
 * authentication is set, and {@code PortalSecurityConfig}'s {@code anyRequest().authenticated()}
 * rejects the request with 401 for every {@code /portal/**} path except the two permitted
 * {@code /portal/auth/{login,callback}} endpoints.
 *
 * <h2>CSRF double-submit</h2>
 * For state-changing methods (POST/PUT/PATCH/DELETE) on a request that resolves a session, the
 * {@code X-CSRF-Token} header must equal {@link PortalSession#csrfToken()} (compared via {@link
 * ConstantTimeEquals}, not {@code String.equals}, so response timing can't leak how much of the
 * token a guess got right) — the {@code SameSite=Strict} session cookie alone stops cross-site
 * *submission*, this stops a same-site XSS payload from replaying a state-changing call with only
 * the ambient cookie.
 *
 * <h2>Cross-tenant public-schema scoping</h2>
 * {@code /portal/**} carries no tenant — the session principal is a partner developer, not a
 * tenant user, so nothing upstream of this filter sets {@link TenantContext}. Every
 * {@code /portal/**} read (e.g. {@code /portal/auth/me}'s {@code PartnerPortalGrantRepository}
 * query) needs *some* tenant identifier resolved before {@code MultiTenantConnectionProvider} can
 * borrow a connection (see {@code TenantIdentifierResolver}); this filter explicitly pins it to
 * {@code "public"} for the duration of the request — mirroring exactly how {@code
 * TenantContextFilter} scopes a platform-realm token to the registry schema — rather than relying
 * on {@code TenantIdentifierResolver}'s null-fallback default (also {@code "public"}, but implicit
 * and thread-pool-fragile if some other code path ever left a stale identifier set).
 */
@Component
public class PortalSessionFilter extends OncePerRequestFilter {

    public static final String SESSION_COOKIE_NAME = "cia_portal_session";
    public static final String CSRF_HEADER_NAME = "X-CSRF-Token";

    /** The tenant identifier every {@code /portal/**} request resolves to — the registry schema. */
    static final String REGISTRY_TENANT_ID = "public";

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final PortalSessionStore sessionStore;

    public PortalSessionFilter(PortalSessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        TenantContext.setTenantId(REGISTRY_TENANT_ID);
        try {
            String sessionId = PortalCookies.read(request, SESSION_COOKIE_NAME);
            if (sessionId != null) {
                Optional<PortalSession> maybeSession = sessionStore.get(sessionId);
                if (maybeSession.isPresent()) {
                    PortalSession session = maybeSession.get();
                    if (isMutating(request) && !csrfHeaderMatches(request, session)) {
                        writeJsonError(response, HttpServletResponse.SC_FORBIDDEN,
                                "CSRF_TOKEN_MISMATCH", "Missing or invalid X-CSRF-Token header");
                        return;
                    }
                    sessionStore.touch(sessionId);
                    SecurityContextHolder.getContext().setAuthentication(
                            new PortalAuthenticationToken(
                                    new PortalPrincipal(session.partnerUserId(), session.email(), session.csrfToken())));
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private static boolean isMutating(HttpServletRequest request) {
        return MUTATING_METHODS.contains(request.getMethod());
    }

    private static boolean csrfHeaderMatches(HttpServletRequest request, PortalSession session) {
        return ConstantTimeEquals.equals(request.getHeader(CSRF_HEADER_NAME), session.csrfToken());
    }

    private static void writeJsonError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"errors\":[{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}]}");
    }
}
