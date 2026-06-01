package com.nubeero.cia.auth;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsing helpers for Keycloak issuer URLs. A Keycloak realm issuer has the
 * shape {@code {server}/realms/{realm}} (optionally with a trailing slash or
 * further {@code /protocol/...} path). The realm segment is the tenant id
 * under the realm-per-tenant model.
 */
public final class KeycloakRealms {

    private KeycloakRealms() {}

    // Capture the segment immediately after "/realms/", up to the next "/" or end.
    private static final Pattern REALM = Pattern.compile(".*/realms/([^/]+)(?:/.*)?$");

    /**
     * Extracts the realm segment from a Keycloak issuer URL, or {@code null}
     * if the string has no non-empty {@code /realms/{realm}} segment.
     */
    public static String realmOf(String issuer) {
        if (issuer == null || issuer.isBlank()) {
            return null;
        }
        Matcher m = REALM.matcher(issuer);
        if (!m.matches()) {
            return null;
        }
        String realm = m.group(1);
        return realm.isBlank() ? null : realm;
    }
}
