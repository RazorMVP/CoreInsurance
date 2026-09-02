package com.nubeero.cia.portal.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.auth.KeycloakProperties;
import com.nubeero.cia.auth.PartnerPortalRealmProperties;
import com.nubeero.cia.common.exception.CiaException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Production {@link PortalOAuthClient} — talks to the real {@code partner} Keycloak realm.
 * Replaced by a stub bean in tests (see {@code PortalAuthFlowIT}) so no live Keycloak is required.
 */
@Slf4j
public class KeycloakPortalOAuthClient implements PortalOAuthClient {

    private final KeycloakProperties keycloakProperties;
    private final PartnerPortalRealmProperties portalProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public KeycloakPortalOAuthClient(KeycloakProperties keycloakProperties,
                                     PartnerPortalRealmProperties portalProperties,
                                     ObjectMapper objectMapper) {
        this.keycloakProperties = keycloakProperties;
        this.portalProperties = portalProperties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

    @Override
    public String buildAuthorizeUrl(String state, String codeChallenge, String redirectUri) {
        return UriComponentsBuilder.fromUriString(realmBaseUrl() + "/protocol/openid-connect/auth")
                .queryParam("client_id", portalProperties.getClientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", "openid email profile")
                .queryParam("state", state)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .build()
                .toUriString();
    }

    @Override
    public PortalOAuthTokens exchangeCode(String code, String codeVerifier, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", portalProperties.getClientId());
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("code_verifier", codeVerifier);

        Map<String, Object> tokenResponse;
        try {
            tokenResponse = restClient.post()
                    .uri(realmBaseUrl() + "/protocol/openid-connect/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException e) {
            log.warn("Partner-portal token exchange failed: {}", e.getMessage());
            throw new CiaException("PORTAL_TOKEN_EXCHANGE_FAILED",
                    "Failed to exchange authorization code for tokens", HttpStatus.BAD_GATEWAY, e);
        }
        if (tokenResponse == null || tokenResponse.get("access_token") == null) {
            throw new CiaException("PORTAL_TOKEN_EXCHANGE_FAILED",
                    "Token endpoint returned no access_token", HttpStatus.BAD_GATEWAY);
        }

        String accessToken = (String) tokenResponse.get("access_token");
        String refreshToken = (String) tokenResponse.get("refresh_token");
        String idToken = (String) tokenResponse.get("id_token");

        Map<String, Object> claims = idToken != null ? decodeJwtClaims(idToken) : Map.of();
        String email = (String) claims.get("email");
        String displayName = claims.containsKey("name") ? (String) claims.get("name") : email;
        if (email == null || email.isBlank()) {
            throw new CiaException("PORTAL_TOKEN_EXCHANGE_FAILED",
                    "ID token carried no email claim", HttpStatus.BAD_GATEWAY);
        }

        return new PortalOAuthTokens(accessToken, refreshToken, idToken, email, displayName);
    }

    @Override
    public String buildLogoutUrl(String idTokenHint, String postLogoutRedirectUri) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(realmBaseUrl() + "/protocol/openid-connect/logout")
                .queryParam("client_id", portalProperties.getClientId())
                .queryParam("post_logout_redirect_uri", postLogoutRedirectUri);
        if (idTokenHint != null && !idTokenHint.isBlank()) {
            builder.queryParam("id_token_hint", idTokenHint);
        }
        return builder.build().toUriString();
    }

    private String realmBaseUrl() {
        return keycloakProperties.normalisedServerUrl() + "/realms/" + portalProperties.getRealm();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decodeJwtClaims(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            return objectMapper.readValue(new String(payload, StandardCharsets.UTF_8), Map.class);
        } catch (Exception e) {
            throw new CiaException("PORTAL_TOKEN_EXCHANGE_FAILED",
                    "Failed to decode ID token claims", HttpStatus.BAD_GATEWAY, e);
        }
    }
}
