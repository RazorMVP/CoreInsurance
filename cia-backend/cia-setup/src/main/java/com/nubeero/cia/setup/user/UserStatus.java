package com.nubeero.cia.setup.user;

/**
 * Front-of-house user states. Maps onto Keycloak's two underlying flags:
 *
 * <ul>
 *   <li>{@code ACTIVE}   — {@code enabled = true}, not brute-force locked</li>
 *   <li>{@code INACTIVE} — {@code enabled = false} (admin-disabled)</li>
 *   <li>{@code LOCKED}   — {@code enabled = true} but brute-force protection
 *                          has temporarily locked the account</li>
 * </ul>
 *
 * Distinguishing INACTIVE from LOCKED matters in the UI — admin action
 * required for INACTIVE, automatic recovery for LOCKED.
 */
public enum UserStatus { ACTIVE, INACTIVE, LOCKED }
