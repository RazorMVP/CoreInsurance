package com.nubeero.cia.setup.keycloak;

import java.util.List;

/** The partner-portal realm's realm-role set — the Insurtech developer authority. Deliberately
 *  distinct from {@link BootstrapRoles} (tenant realms) and {@link PlatformRoles} (cross-tenant
 *  super-admin realm), so no tenant user and no platform super-admin ever holds it, and a
 *  partner-portal user never holds SUPER_ADMIN or any tenant role. */
public final class PartnerPortalRoles {
    private PartnerPortalRoles() {}

    /** Realm role name. Spring authority after JwtAuthConverter = {@code ROLE_PARTNER_DEVELOPER}. */
    public static final String PARTNER_DEVELOPER = "PARTNER_DEVELOPER";

    public static final List<String> ALL = List.of(PARTNER_DEVELOPER);
}
