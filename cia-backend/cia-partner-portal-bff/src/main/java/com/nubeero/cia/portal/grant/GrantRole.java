package com.nubeero.cia.portal.grant;

/**
 * A partner human developer's authority level over a specific Partner App in the
 * {@link PartnerPortalGrant} registry.
 *
 * <ul>
 *   <li>{@code MANAGER} — full control over the app (credentials, scopes, webhooks).</li>
 *   <li>{@code VIEWER} — read-only visibility (usage, logs) with no mutating capability.</li>
 * </ul>
 */
public enum GrantRole {
    MANAGER,
    VIEWER
}
