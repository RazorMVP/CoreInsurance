package com.nubeero.cia.portal.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Constant-time string equality for the two secret-bearing comparisons on this flow's hot path:
 * the CSRF double-submit header ({@link PortalSessionFilter}) and the OAuth {@code state}
 * round-trip check ({@link PortalAuthController}). A plain {@code String.equals} short-circuits on
 * the first mismatched character, which — for a caller who can send many requests and measure
 * response timing — leaks how many leading characters were guessed correctly. {@link
 * MessageDigest#isEqual} compares in time independent of where (or whether) the inputs first
 * differ.
 */
final class ConstantTimeEquals {

    private ConstantTimeEquals() {
    }

    static boolean equals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
