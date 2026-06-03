package com.nubeero.cia.setup.keycloak;

import java.util.List;

/**
 * Canonical Keycloak realm-role set assigned to a freshly provisioned tenant's first admin.
 *
 * <p>Pattern A roles ({@code module_action}) mirror the {@code module:action} permission model
 * synced by {@link KeycloakRealmRoleSyncer}; Pattern B roles (SCREAMING_CASE) are hardcoded
 * authorities used directly in {@code @PreAuthorize} on the finance / platform-admin surfaces and
 * are NOT derived from the permission model.
 *
 * <p>Drift is enforced by {@code BootstrapRolesDriftTest}, which fails the build if any controller
 * references an authority absent from {@link #ALL}.
 */
public final class BootstrapRoles {

    private BootstrapRoles() {}

    /** Pattern A — created with a "CIA-managed:" description to match the role syncer's convention. */
    public static final List<String> PATTERN_A = List.of(
        "setup_view", "setup_create", "setup_update", "setup_delete",
        "claims_view", "claims_create", "claims_update", "claims_approve",
        "customer_view", "customer_create", "customer_update",
        "underwriting_view", "underwriting_create", "underwriting_update", "underwriting_approve",
        "quotation_view", "quotation_create", "quotation_update", "quotation_approve",
        "reinsurance_view", "reinsurance_create", "reinsurance_update", "reinsurance_approve",
        "audit_view",
        "notification_templates_view", "notification_templates_update",
        "reports_view", "reports_create_custom", "reports_export_csv",
        "reports_export_pdf", "reports_manage_access"
    );

    /** Pattern B — hardcoded realm roles, not syncer-managed. */
    public static final List<String> PATTERN_B = List.of(
        "FINANCE_VIEW", "FINANCE_CREATE", "FINANCE_UPDATE", "FINANCE_APPROVE",
        "FINANCE_APPROVE_PPA", "FINANCE_REOPEN_PERIOD", "FINANCE_OVERRIDE_LOCK",
        "PLATFORM_ADMIN"
    );

    /** Every role the bootstrap admin must hold. */
    public static final List<String> ALL =
        java.util.stream.Stream.concat(PATTERN_A.stream(), PATTERN_B.stream()).toList();

    /** The {@code module:action} permission strings seeded into the Administrators access group. */
    public static final List<String> ADMIN_PERMISSIONS = PATTERN_A.stream()
        .map(r -> r.replaceFirst("_", ":"))   // setup_view -> setup:view (first underscore only)
        .toList();
}
