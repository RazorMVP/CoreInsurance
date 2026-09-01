package com.nubeero.cia.portal.token;

import com.nubeero.cia.auth.KeycloakProperties;
import java.time.Instant;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Production {@link ClientCredentialsTokenGrantor} — performs the OAuth2
 * client-credentials grant against {@code {server-url}/realms/{tenantRealm}/
 * protocol/openid-connect/token}. Mirrors {@code KeycloakPortalOAuthClient}'s
 * plain {@code RestClient} usage for the human-login authorization-code
 * exchange in this same module.
 *
 * <p>The {@code client_secret} form parameter is sent over the wire (the
 * grant requires it) but never appears in a log line — only {@code
 * tenantRealm} / {@code clientId} do.
 *
 * <p><b>Timeouts:</b> an unresponsive Keycloak token endpoint must not hang the calling request
 * thread indefinitely — this is now on the same request path as Task 8's try-it proxy (a
 * developer's {@code GET /portal/apps/{id}/try/**} call mints a token via this class before
 * forwarding to {@code /partner/v1/**}). 5s connect / 30s read, same budget as {@code
 * PartnerApiProxyClient}.
 */
@Slf4j
@Component
public class KeycloakClientCredentialsTokenGrantor implements ClientCredentialsTokenGrantor {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private final KeycloakProperties keycloakProperties;
    private final RestClient restClient;

    public KeycloakClientCredentialsTokenGrantor(KeycloakProperties keycloakProperties) {
        this.keycloakProperties = keycloakProperties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public MintedToken grant(String tenantRealm, String clientId, String clientSecret) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        Instant requestedAt = Instant.now();
        Map<String, Object> tokenResponse;
        try {
            tokenResponse = restClient.post()
                    .uri(realmBaseUrl(tenantRealm) + "/protocol/openid-connect/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException e) {
            log.warn("Partner-app client-credentials grant failed for tenant realm '{}', client '{}': {}",
                    tenantRealm, clientId, e.getMessage());
            throw new PartnerAppTokenException("PARTNER_APP_TOKEN_GRANT_FAILED",
                    "Client-credentials grant failed for partner app '" + clientId + "'", e);
        }
        if (tokenResponse == null || tokenResponse.get("access_token") == null) {
            throw new PartnerAppTokenException("PARTNER_APP_TOKEN_GRANT_FAILED",
                    "Token endpoint returned no access_token for partner app '" + clientId + "'");
        }

        String accessToken = (String) tokenResponse.get("access_token");
        Number expiresIn = (Number) tokenResponse.get("expires_in");
        Instant expiry = requestedAt.plusSeconds(expiresIn != null ? expiresIn.longValue() : 60L);
        return new MintedToken(accessToken, expiry);
    }

    private String realmBaseUrl(String tenantRealm) {
        return keycloakProperties.normalisedServerUrl() + "/realms/" + tenantRealm;
    }
}
