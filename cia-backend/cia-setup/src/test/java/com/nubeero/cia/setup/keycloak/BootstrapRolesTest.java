package com.nubeero.cia.setup.keycloak;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapRolesTest {

    @Test
    void adminPermissionsUseExactModuleColonActionConvention() {
        assertThat(BootstrapRoles.ADMIN_PERMISSIONS).contains(
            "setup:view", "claims:approve", "customer:update",
            "notification_templates:view", "notification_templates:update",
            "reports:create_custom", "reports:export_csv",
            "reports:export_pdf", "reports:manage_access");
        // No malformed split-in-the-middle-of-a-module-name strings.
        assertThat(BootstrapRoles.ADMIN_PERMISSIONS).noneMatch(p -> p.startsWith("notification:"));
    }

    @Test
    void roleNamesAndPermissionsAreDerivedConsistently() {
        assertThat(BootstrapRoles.PATTERN_A).contains(
            "notification_templates_view", "reports_create_custom", "setup_view");
        assertThat(BootstrapRoles.ALL).containsAll(BootstrapRoles.PATTERN_A);
        assertThat(BootstrapRoles.ALL).containsAll(BootstrapRoles.PATTERN_B);
    }
}
