package com.nubeero.cia.portal.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PKCE (RFC 7636) verifier + S256 challenge generation for the BFF's server-side Auth Code + PKCE
 * flow ({@link PortalAuthController}). The verifier never leaves the server except transiently in
 * the short-lived {@code cia_portal_login_state} cookie between {@code /portal/auth/login} and
 * {@code /portal/auth/callback}; it is never persisted in {@link
 * com.nubeero.cia.portal.session.PortalSession} — PKCE only protects the code exchange itself.
 */
public final class PkceGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private PkceGenerator() {
    }

    /** A generated PKCE pair: the secret {@code verifier} and its public S256 {@code challenge}. */
    public record Pkce(String verifier, String challenge) {
    }

    /** Generates a fresh 256-bit random verifier and its {@code code_challenge_method=S256} challenge. */
    public static Pkce generate() {
        String verifier = randomUrlSafeToken(32);
        return new Pkce(verifier, challengeFor(verifier));
    }

    /** {@code BASE64URL(SHA256(verifier))}, no padding, per RFC 7636 §4.2. */
    static String challengeFor(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JDK algorithm — cannot happen on a conforming JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** A cryptographically random, URL-safe token of {@code byteLength} bytes of entropy. */
    static String randomUrlSafeToken(int byteLength) {
        byte[] bytes = new byte[byteLength];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
