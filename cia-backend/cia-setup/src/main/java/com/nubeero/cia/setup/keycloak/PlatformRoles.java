package com.nubeero.cia.setup.keycloak;

import java.util.List;

/** The platform realm's realm-role set — the cross-tenant super-admin authority. Deliberately
 *  distinct from {@link BootstrapRoles} (tenant realms), so no tenant user can ever hold it. */
public final class PlatformRoles {
    private PlatformRoles() {}

    /** Realm role name. Spring authority after JwtAuthConverter = {@code ROLE_SUPER_ADMIN}. */
    public static final String SUPER_ADMIN = "SUPER_ADMIN";

    public static final List<String> ALL = List.of(SUPER_ADMIN);
}
