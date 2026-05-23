package com.nubeero.cia.setup.keycloak;

import com.nubeero.cia.setup.company.PasswordPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-function tests for the password-policy DSL translator. Deliberately
 * avoids Spring + Keycloak admin-client class graph entirely — runs in
 * single-millisecond range and adds zero IT-suite risk.
 */
class KeycloakPolicyDslTest {

    @Test
    void minimalPolicy_emitsLengthOnly() {
        PasswordPolicy p = PasswordPolicy.builder()
                .minLength(8).maxLength(128)
                .requireUppercase(false).requireLowercase(false)
                .requireNumbers(false).requireSpecial(false)
                .expiryDays(0).maxFailedAttempts(5)
                .build();

        assertThat(KeycloakPolicyDsl.toDsl(p)).isEqualTo("length(8)");
    }

    @Test
    void allCharacterFlags_emitInDeterministicOrder() {
        PasswordPolicy p = PasswordPolicy.builder()
                .minLength(12).maxLength(128)
                .requireUppercase(true).requireLowercase(true)
                .requireNumbers(true).requireSpecial(true)
                .expiryDays(0).maxFailedAttempts(5)
                .build();

        // Order: length → upper → lower → digits → special — matches the
        // KeycloakPolicyDsl source order. Pinning the exact string here
        // catches accidental reordering that would otherwise be invisible.
        assertThat(KeycloakPolicyDsl.toDsl(p))
                .isEqualTo("length(12) and upperCase(1) and lowerCase(1) and digits(1) and specialChars(1)");
    }

    @Test
    void expiryZero_omitsExpiryClause() {
        // Keycloak interprets a missing forceExpiredPasswordChange as
        // "never expire" — that's exactly what expiryDays == 0 means in
        // CIA's policy semantics, so omitting the clause is the correct
        // translation (not emitting "forceExpiredPasswordChange(0)").
        PasswordPolicy p = PasswordPolicy.builder()
                .minLength(8).maxLength(128)
                .requireUppercase(true).requireLowercase(false)
                .requireNumbers(false).requireSpecial(false)
                .expiryDays(0).maxFailedAttempts(5)
                .build();

        assertThat(KeycloakPolicyDsl.toDsl(p))
                .isEqualTo("length(8) and upperCase(1)")
                .doesNotContain("forceExpiredPasswordChange");
    }

    @Test
    void expiryPositive_emitsExpiryClauseLast() {
        PasswordPolicy p = PasswordPolicy.builder()
                .minLength(10).maxLength(128)
                .requireUppercase(false).requireLowercase(false)
                .requireNumbers(true).requireSpecial(false)
                .expiryDays(90).maxFailedAttempts(5)
                .build();

        assertThat(KeycloakPolicyDsl.toDsl(p))
                .isEqualTo("length(10) and digits(1) and forceExpiredPasswordChange(90)");
    }

    @Test
    void maxLengthNotPartOfDsl() {
        // Keycloak's password-policy DSL is minimum-only. maxLength is stored
        // for tenant bookkeeping but never surfaces in the synced DSL — this
        // test pins that gap so a future Keycloak DSL extension doesn't
        // silently leak the field.
        PasswordPolicy p = PasswordPolicy.builder()
                .minLength(8).maxLength(64)
                .requireUppercase(false).requireLowercase(false)
                .requireNumbers(false).requireSpecial(false)
                .expiryDays(0).maxFailedAttempts(5)
                .build();

        assertThat(KeycloakPolicyDsl.toDsl(p)).doesNotContain("64");
    }

    @Test
    void permissionToRoleName_replacesColonWithUnderscore() {
        // Cross-checks the role-name mapping invariant the JwtAuthConverter
        // depends on: setup:view → setup_view → ROLE_SETUP_VIEW (after
        // converter uppercases) → matches @PreAuthorize("hasRole('SETUP_VIEW')").
        assertThat(KeycloakRealmRoleSyncer.permissionToRoleName("setup:view"))
                .isEqualTo("setup_view");
        assertThat(KeycloakRealmRoleSyncer.permissionToRoleName("claims:approve"))
                .isEqualTo("claims_approve");
        assertThat(KeycloakRealmRoleSyncer.permissionToRoleName("audit:view"))
                .isEqualTo("audit_view");
    }
}
