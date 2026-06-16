package com.nubeero.cia.setup.keycloak;

import java.util.List;
import java.util.stream.Stream;

/**
 * Canonical Keycloak realm-role set assigned to a freshly provisioned tenant's first admin.
 *
 * <p>Pattern A roles are derived from (module, action) pairs: the Keycloak role name is
 * {@code module_action} and the access-group permission string is {@code module:action}.
 * Deriving both from the same pairs avoids the positional ambiguity that bites when a module
 * name (notification_templates) OR an action (create_custom) contains an underscore.
 *
 * <p>Pattern B roles (SCREAMING_CASE) are hardcoded authorities used directly in
 * {@code @PreAuthorize} on the finance / platform-admin surfaces; they are NOT permission-derived.
 *
 * <p>Drift is enforced by {@code BootstrapRolesDriftTest}.
 */
public final class BootstrapRoles {

    private BootstrapRoles() {}

    /** (module, action) pairs — single source of truth for Pattern-A roles + permissions. */
    private record Perm(String module, String action) {
        String roleName()   { return module + "_" + action; }   // notification_templates_view
        String permission() { return module + ":" + action; }   // notification_templates:view
    }

    private static final List<Perm> PATTERN_A_PERMS = List.of(
        new Perm("setup", "view"), new Perm("setup", "create"),
        new Perm("setup", "update"), new Perm("setup", "delete"),
        new Perm("claims", "view"), new Perm("claims", "create"),
        new Perm("claims", "update"), new Perm("claims", "approve"),
        new Perm("customer", "view"), new Perm("customer", "create"), new Perm("customer", "update"),
        new Perm("underwriting", "view"), new Perm("underwriting", "create"),
        new Perm("underwriting", "update"), new Perm("underwriting", "approve"),
        new Perm("quotation", "view"), new Perm("quotation", "create"),
        new Perm("quotation", "update"), new Perm("quotation", "approve"),
        new Perm("reinsurance", "view"), new Perm("reinsurance", "create"),
        new Perm("reinsurance", "update"), new Perm("reinsurance", "approve"),
        new Perm("audit", "view"),
        new Perm("notification_templates", "view"), new Perm("notification_templates", "update"),
        new Perm("reports", "view"), new Perm("reports", "create_custom"),
        new Perm("reports", "export_csv"), new Perm("reports", "export_pdf"),
        new Perm("reports", "manage_access")
    );

    /** Pattern A — module_action role names (syncer-managed; "CIA-managed:" description). */
    public static final List<String> PATTERN_A = PATTERN_A_PERMS.stream().map(Perm::roleName).toList();

    /** Pattern B — hardcoded SCREAMING_CASE realm roles, not syncer-managed. */
    public static final List<String> PATTERN_B = List.of(
        "FINANCE_VIEW", "FINANCE_CREATE", "FINANCE_UPDATE", "FINANCE_APPROVE",
        "FINANCE_APPROVE_PPA", "FINANCE_REOPEN_PERIOD", "FINANCE_OVERRIDE_LOCK",
        "PLATFORM_ADMIN", "DATA_PROTECTION"
    );

    /** Every realm role the bootstrap admin must hold. */
    public static final List<String> ALL = Stream.concat(PATTERN_A.stream(), PATTERN_B.stream()).toList();

    /** module:action permission strings seeded into the Administrators access group. */
    public static final List<String> ADMIN_PERMISSIONS =
        PATTERN_A_PERMS.stream().map(Perm::permission).toList();
}
