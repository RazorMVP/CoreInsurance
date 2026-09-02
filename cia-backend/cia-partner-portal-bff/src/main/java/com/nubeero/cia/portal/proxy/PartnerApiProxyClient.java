package com.nubeero.cia.portal.proxy;

import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClientException;

/**
 * Server-side HTTP client the Partner Portal BFF uses to forward a developer's {@code
 * /portal/apps/{id}/**} request to the app's OWN real {@code /partner/v1/**} API, attaching a
 * minted partner-app Bearer token.
 *
 * <h2>Why a real HTTP call, not a direct service-layer call (PINNED)</h2>
 * {@code /partner/v1/**} sits behind {@code PartnerScopeFilter} (per-scope 403) and {@code
 * PartnerRateLimitFilter} (per-client 429) — both are {@code SecurityFilterChain}/servlet-filter
 * infrastructure, not something a plain method call into the partner domain services would ever
 * exercise. Making a genuine outbound HTTP request (this class) is what puts a portal "try it" call
 * through the EXACT same filter chain a real external Insurtech integration hits, so a scope-denied
 * 403 or a rate-limited 429 the developer sees in the portal is byte-for-byte what their own app
 * would see in production — never simulated or short-circuited.
 *
 * <h2>Base URL</h2>
 * {@code cia.partner-portal.api-base-url} — defaults to this same JVM's own embedded server, using
 * Spring Boot's {@code local.server.port} property (published once the embedded web server binds,
 * before singleton beans besides very-early ones are created — see {@code
 * EmbeddedServerPortInfoApplicationContextInitializer}), falling back to {@code server.port} (8090)
 * if that property isn't set for some reason (e.g. a non-servlet context). This lets the SAME
 * production code work unmodified whether the port is the fixed dev default or a random one (tests,
 * some container platforms) — {@code cia-api} assembles {@code /portal/**} and {@code
 * /partner/v1/**} into ONE process/port, so "the app's own API" IS this JVM's own loopback address.
 * Tests may override the property to point at a stub upstream instead (see {@code PortalProxyIT}).
 *
 * <h2>Timeouts</h2>
 * 5s connect / 30s read — an unresponsive {@code /partner/v1/**} must never hang the developer's
 * request thread indefinitely (same budget as {@code KeycloakClientCredentialsTokenGrantor}, which
 * this call sequence also runs through to mint the Bearer token before this class ever fires).
 */
@Slf4j
@Component
public class PartnerApiProxyClient {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private final String apiBaseUrl;
    private final RestClient restClient;

    public PartnerApiProxyClient(
            @Value("${cia.partner-portal.api-base-url:http://localhost:${local.server.port:${server.port:8090}}}")
            String apiBaseUrl) {
        this.apiBaseUrl = trimTrailingSlash(apiBaseUrl);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * Forwards one request to {@code {apiBaseUrl}/partner/v1{pathUnderPartnerV1}}, attaching {@code
     * bearerToken} as {@code Authorization: Bearer ...}. The upstream status, headers, and body are
     * returned VERBATIM — never interpreted, retried on a non-2xx, or converted into an exception
     * (a 403/404/429 from {@code /partner/v1/**} is a normal, successful proxy round trip; only a
     * failure to reach the upstream AT ALL throws).
     *
     * @param pathUnderPartnerV1 e.g. {@code "/products"}, {@code "/webhooks"}, {@code "/webhooks/{id}"}
     *                           — always starting with {@code /}.
     * @param queryString        the raw (already-encoded) query string, or {@code null}/blank for none.
     * @param contentType        the inbound request's {@code Content-Type}, or {@code null} when
     *                           {@code body} is empty.
     * @param body                the raw request body bytes, or {@code null}/empty for a body-less request.
     * @throws PartnerApiProxyException if the upstream cannot be reached at all (connection refused,
     *                                   DNS failure, or a connect/read timeout).
     */
    public ProxyResult forward(HttpMethod method, String pathUnderPartnerV1, String queryString,
                                String bearerToken, String contentType, byte[] body) {
        String uri = apiBaseUrl + "/partner/v1" + pathUnderPartnerV1
                + (queryString != null && !queryString.isBlank() ? "?" + queryString : "");
        boolean hasBody = body != null && body.length > 0;
        try {
            return restClient.method(method)
                    .uri(uri)
                    .headers(headers -> {
                        headers.setBearerAuth(bearerToken);
                        if (hasBody && contentType != null && !contentType.isBlank()) {
                            headers.set(HttpHeaders.CONTENT_TYPE, contentType);
                        }
                    })
                    .body(hasBody ? body : new byte[0])
                    .exchange(this::toProxyResult);
        } catch (RestClientException e) {
            log.warn("Could not reach the partner API at '{}': {}", uri, e.getMessage());
            throw new PartnerApiProxyException("Could not reach the partner API at '" + uri + "'", e);
        }
    }

    private ProxyResult toProxyResult(HttpRequest request, ConvertibleClientHttpResponse response)
            throws IOException {
        return new ProxyResult(
                response.getStatusCode().value(),
                response.getHeaders(),
                response.getBody().readAllBytes());
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
