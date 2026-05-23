package com.nubeero.cia.setup.keycloak;

import com.nubeero.cia.setup.company.PasswordPolicy;
import com.nubeero.cia.setup.user.KeycloakAdminProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RealmRepresentation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * F4-sync: writes the realm's {@code passwordPolicy} attribute + brute-force
 * settings so that Keycloak enforces what the tenant's {@link PasswordPolicy}
 * record describes.
 *
 * <p>Encapsulates every Keycloak admin-client type reference. The caller
 * ({@link com.nubeero.cia.setup.company.PasswordPolicyService}) only sees
 * this class as a plain {@code @Service} bean — no {@code Keycloak} or
 * {@code RealmRepresentation} symbol appears in its bytecode. Same
 * encapsulation strategy as {@link KeycloakRealmRoleSyncer} to avoid the
 * Session 112 classloader-pollution regression on the IT suite.
 *
 * <p>What gets written:
 * <ul>
 *   <li>{@code realm.passwordPolicy} — DSL string produced by
 *       {@link KeycloakPolicyDsl#toDsl(PasswordPolicy)}.</li>
 *   <li>{@code realm.bruteForceProtected = true} — enables Keycloak's
 *       built-in brute-force lockout.</li>
 *   <li>{@code realm.failureFactor = policy.maxFailedAttempts} — number of
 *       consecutive failures before lockout.</li>
 * </ul>
 *
 * <p>What is intentionally NOT written:
 * <ul>
 *   <li>{@code maxLength} — Keycloak's policy DSL is minimum-only; stored
 *       for tenant bookkeeping but not synced.</li>
 *   <li>Anything else on {@link RealmRepresentation} — we read the realm,
 *       mutate only these three fields, write back. All other realm
 *       attributes (login flow, MFA, theme, etc.) are preserved.</li>
 * </ul>
 *
 * <p>Bean conditional on {@code cia.keycloak.admin.enabled=true}. In tests
 * and dev-without-Keycloak the bean is absent and the
 * {@link ObjectProvider#getIfAvailable()} call in
 * {@code PasswordPolicyService} returns null, no-opping the sync.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "cia.keycloak.admin", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class KeycloakPasswordPolicySyncer {

    private final ObjectProvider<Keycloak>  keycloak;
    private final KeycloakAdminProperties   props;

    /**
     * Writes the policy to Keycloak. Idempotent; safe to call on every
     * {@code PasswordPolicyService.upsert()}.
     *
     * <p>Transient Keycloak failures log a warning but do not fail the
     * parent transaction — the DB record is the source of truth, and the
     * next upsert will re-attempt the sync.
     */
    public void sync(PasswordPolicy policy) {
        Keycloak client = keycloak.getIfAvailable();
        if (client == null) {
            log.warn("Keycloak admin client unavailable — skipping password-policy sync");
            return;
        }

        try {
            RealmRepresentation realm = client.realm(props.getTargetRealm()).toRepresentation();
            realm.setPasswordPolicy(KeycloakPolicyDsl.toDsl(policy));
            realm.setBruteForceProtected(true);
            realm.setFailureFactor(policy.getMaxFailedAttempts());
            client.realm(props.getTargetRealm()).update(realm);
            log.info("Synced password policy to Keycloak realm {}", props.getTargetRealm());
        } catch (RuntimeException e) {
            log.warn("Password-policy sync to Keycloak failed; DB record is the source of truth: {}",
                    e.getMessage());
        }
    }
}
