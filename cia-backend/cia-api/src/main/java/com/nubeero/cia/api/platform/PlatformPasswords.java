package com.nubeero.cia.api.platform;

import java.security.SecureRandom;
import java.util.Base64;

/** Server-side one-time temporary-password generator shared by tenant onboard + super-admin invite. */
final class PlatformPasswords {
    private PlatformPasswords() {}
    private static final SecureRandom RNG = new SecureRandom();

    /** ≥24 chars with upper+lower+digit+special guaranteed by the {@code "Aa1!"} prefix. */
    static String generateTempPassword() {
        byte[] b = new byte[18];
        RNG.nextBytes(b);
        return "Aa1!" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
