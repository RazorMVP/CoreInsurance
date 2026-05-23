package com.nubeero.cia.setup.keycloak;

import com.nubeero.cia.setup.company.PasswordPolicy;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure helper translating a {@link PasswordPolicy} into Keycloak's
 * realm-attribute {@code passwordPolicy} DSL.
 *
 * <p>Keycloak DSL reference (subset we cover):
 * <pre>
 *   length(N)                          — minimum password length
 *   upperCase(N) / lowerCase(N)        — require ≥ N upper/lower chars
 *   digits(N)                          — require ≥ N digits
 *   specialChars(N)                    — require ≥ N special chars
 *   forceExpiredPasswordChange(days)   — require change after N days
 * </pre>
 *
 * <p>Notes on translation gaps:
 * <ul>
 *   <li>{@code maxLength} has no Keycloak counterpart — Keycloak's policy
 *       DSL is minimum-only. Stored for tenant bookkeeping; not synced.</li>
 *   <li>{@code maxFailedAttempts} is brute-force protection, not the
 *       password policy. Synced separately by
 *       {@link KeycloakPasswordPolicySyncer} via realm-level
 *       {@code bruteForceProtected} + {@code failureFactor}.</li>
 *   <li>{@code expiryDays == 0} ⇒ skip the expiry clause entirely
 *       (Keycloak interprets it as "never expire").</li>
 * </ul>
 *
 * <p>Pure function — no Spring, no Keycloak admin-client types. Unit-testable
 * in isolation, and crucially keeps the Keycloak class graph out of the
 * caller's bytecode at compile time.
 */
public final class KeycloakPolicyDsl {

    private KeycloakPolicyDsl() {}

    public static String toDsl(PasswordPolicy p) {
        List<String> parts = new ArrayList<>();
        parts.add("length(" + p.getMinLength() + ")");
        if (p.isRequireUppercase()) parts.add("upperCase(1)");
        if (p.isRequireLowercase()) parts.add("lowerCase(1)");
        if (p.isRequireNumbers())   parts.add("digits(1)");
        if (p.isRequireSpecial())   parts.add("specialChars(1)");
        if (p.getExpiryDays() > 0)  parts.add("forceExpiredPasswordChange(" + p.getExpiryDays() + ")");
        return String.join(" and ", parts);
    }
}
