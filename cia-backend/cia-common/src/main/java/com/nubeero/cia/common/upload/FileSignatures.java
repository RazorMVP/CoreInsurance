package com.nubeero.cia.common.upload;

import java.util.List;
import java.util.Map;

/**
 * Magic-byte signatures for the binary content types we accept, so a spoofed
 * {@code Content-Type} cannot smuggle a different (e.g. executable) payload past the
 * allowlist. Types with no reliable signature (text/html) are absent and skip the check.
 */
public final class FileSignatures {

    private FileSignatures() {}

    // Each value is the list of acceptable leading-byte prefixes for that content type.
    private static final Map<String, List<byte[]>> SIGNATURES = Map.of(
            "application/pdf", List.of(new byte[] {0x25, 0x50, 0x44, 0x46}),               // %PDF
            "image/jpeg",      List.of(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
            "image/png",       List.of(new byte[] {
                    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}));

    /** Longest prefix we ever need to read from the stream head. */
    public static final int MAX_PREFIX = 8;

    /**
     * @return true if {@code contentType} has no known signature (nothing to check) OR
     *         {@code head} starts with one of its signatures. false only when a known
     *         signature exists and {@code head} matches none of them.
     */
    public static boolean matches(String contentType, byte[] head) {
        List<byte[]> sigs = SIGNATURES.get(contentType);
        if (sigs == null) return true; // no signature for this type (e.g. text/html)
        for (byte[] sig : sigs) {
            if (startsWith(head, sig)) return true;
        }
        return false;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }
}
