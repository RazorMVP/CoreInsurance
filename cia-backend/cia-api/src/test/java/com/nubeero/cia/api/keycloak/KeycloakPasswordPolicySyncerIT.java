package com.nubeero.cia.api.keycloak;

import com.nubeero.cia.setup.company.PasswordPolicy;
import com.nubeero.cia.setup.keycloak.KeycloakPasswordPolicySyncer;
import com.nubeero.cia.setup.user.KeycloakAdminProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RealmRepresentation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT — drains {@code F4-sync-tests} for {@link KeycloakPasswordPolicySyncer}.
 * Verifies the V3-DDL-to-Keycloak-DSL translation end-to-end against a real
 * Keycloak 24 container:
 * <ul>
 *   <li>Each combination of character-class flags produces the expected DSL
 *       string. {@link KeycloakPolicyDslTest} already pins the string format;
 *       this IT pins the realm-attribute write path.</li>
 *   <li>{@code bruteForceProtected = true} + {@code failureFactor =
 *       maxFailedAttempts} are written separately (Keycloak owns brute-force
 *       protection on the realm root, not inside the policy DSL).</li>
 *   <li>Pre-existing realm attributes (other than the three the syncer
 *       writes) are preserved across the read-modify-write cycle.</li>
 * </ul>
 *
 * <p>The syncer is constructed manually (no Spring context) so the IT stays
 * fast — the underlying Keycloak container is already the cold-start tax;
 * spinning up the full Boot context per test class would add another ~5
 * seconds for no extra coverage. {@link UserControllerKeycloakIT} carries
 * the Spring-wired path validation.
 */
class KeycloakPasswordPolicySyncerIT extends KeycloakItSupport {

    private static Keycloak ADMIN;
    private static KeycloakPasswordPolicySyncer SYNCER;

    @BeforeAll
    static void buildSyncer() {
        ensureTestRealm();
        ADMIN = KEYCLOAK.getKeycloakAdminClient();

        KeycloakAdminProperties props = new KeycloakAdminProperties();
        props.setEnabled(true);
        props.setServerUrl(KEYCLOAK.getAuthServerUrl());
        props.setAdminRealm("master");
        props.setClientId("admin-cli");
        props.setUsername(KEYCLOAK.getAdminUsername());
        props.setPassword(KEYCLOAK.getAdminPassword());
        props.setTargetRealm(TEST_REALM);

        SYNCER = new KeycloakPasswordPolicySyncer(new StaticObjectProvider<>(ADMIN), props);
    }

    /**
     * Reset the realm to a known-baseline state between tests so each test
     * is independent. Specifically clear {@code passwordPolicy} and brute-
     * force settings; leave realm metadata (name, enabled, etc.) intact.
     */
    @AfterEach
    void resetRealm() {
        RealmResource realm = ADMIN.realm(TEST_REALM);
        RealmRepresentation rep = realm.toRepresentation();
        rep.setPasswordPolicy(null);
        rep.setBruteForceProtected(false);
        rep.setFailureFactor(0);
        realm.update(rep);
    }

    @Test
    @DisplayName("sync — writes passwordPolicy DSL + brute-force settings")
    void syncWritesPolicyDsl() {
        PasswordPolicy policy = PasswordPolicy.builder()
                .minLength(12).maxLength(64)
                .requireUppercase(true).requireLowercase(true)
                .requireNumbers(true).requireSpecial(true)
                .expiryDays(90).maxFailedAttempts(5)
                .build();

        SYNCER.sync(policy);

        RealmRepresentation rep = ADMIN.realm(TEST_REALM).toRepresentation();
        assertThat(rep.getPasswordPolicy())
                .isEqualTo("length(12) and upperCase(1) and lowerCase(1) and digits(1) and specialChars(1) and forceExpiredPasswordChange(90)");
        assertThat(rep.isBruteForceProtected()).isTrue();
        assertThat(rep.getFailureFactor()).isEqualTo(5);
    }

    @Test
    @DisplayName("sync — minimal policy emits length-only DSL")
    void syncMinimalPolicy() {
        PasswordPolicy policy = PasswordPolicy.builder()
                .minLength(8).maxLength(128)
                .requireUppercase(false).requireLowercase(false)
                .requireNumbers(false).requireSpecial(false)
                .expiryDays(0).maxFailedAttempts(3)
                .build();

        SYNCER.sync(policy);

        RealmRepresentation rep = ADMIN.realm(TEST_REALM).toRepresentation();
        assertThat(rep.getPasswordPolicy()).isEqualTo("length(8)");
        assertThat(rep.isBruteForceProtected()).isTrue();
        assertThat(rep.getFailureFactor()).isEqualTo(3);
    }

    @Test
    @DisplayName("sync — re-running with a different policy replaces the previous DSL")
    void syncIsIdempotent() {
        PasswordPolicy first = PasswordPolicy.builder()
                .minLength(8).maxLength(128)
                .requireUppercase(true).requireLowercase(false)
                .requireNumbers(false).requireSpecial(false)
                .expiryDays(30).maxFailedAttempts(3)
                .build();
        PasswordPolicy second = PasswordPolicy.builder()
                .minLength(16).maxLength(64)
                .requireUppercase(true).requireLowercase(true)
                .requireNumbers(true).requireSpecial(true)
                .expiryDays(0).maxFailedAttempts(10)
                .build();

        SYNCER.sync(first);
        SYNCER.sync(second);

        RealmRepresentation rep = ADMIN.realm(TEST_REALM).toRepresentation();
        assertThat(rep.getPasswordPolicy())
                .isEqualTo("length(16) and upperCase(1) and lowerCase(1) and digits(1) and specialChars(1)")
                .doesNotContain("forceExpiredPasswordChange");
        assertThat(rep.getFailureFactor()).isEqualTo(10);
    }

    @Test
    @DisplayName("sync — preserves realm metadata not owned by the password policy")
    void syncPreservesUnrelatedRealmAttributes() {
        // Touch a realm attribute the syncer must not stomp.
        RealmResource realm = ADMIN.realm(TEST_REALM);
        RealmRepresentation original = realm.toRepresentation();
        original.setDisplayName("SyncPreservationTest");
        original.setRegistrationAllowed(true);
        realm.update(original);

        PasswordPolicy policy = PasswordPolicy.builder()
                .minLength(10).maxLength(128)
                .requireUppercase(true).requireLowercase(false)
                .requireNumbers(false).requireSpecial(false)
                .expiryDays(0).maxFailedAttempts(5)
                .build();
        SYNCER.sync(policy);

        RealmRepresentation after = realm.toRepresentation();
        assertThat(after.getDisplayName()).isEqualTo("SyncPreservationTest");
        assertThat(after.isRegistrationAllowed()).isTrue();
        assertThat(after.getPasswordPolicy()).isEqualTo("length(10) and upperCase(1)");
    }
}
