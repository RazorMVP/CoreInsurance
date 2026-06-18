package com.nubeero.cia.common.upload;

import java.util.Set;

/**
 * Per-upload-site validation policy: the allowed content types and the maximum size.
 * {@code label} is used in 422 messages (e.g. "KYC document").
 */
public record FileUploadPolicy(String label, Set<String> allowedContentTypes, long maxSizeBytes) {

    private static final Set<String> IMAGES_AND_PDF =
            Set.of("application/pdf", "image/jpeg", "image/png");
    private static final Set<String> HTML_AND_PDF =
            Set.of("text/html", "application/pdf");

    public static FileUploadPolicy imagesAndPdf(String label, long maxSizeBytes) {
        return new FileUploadPolicy(label, IMAGES_AND_PDF, maxSizeBytes);
    }

    public static FileUploadPolicy htmlAndPdf(String label, long maxSizeBytes) {
        return new FileUploadPolicy(label, HTML_AND_PDF, maxSizeBytes);
    }

    public static long mb(long n) {
        return n * 1024 * 1024;
    }
}
