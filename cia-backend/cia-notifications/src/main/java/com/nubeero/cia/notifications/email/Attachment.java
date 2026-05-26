package com.nubeero.cia.notifications.email;

/**
 * Single email attachment — filename, MIME content type, and raw bytes.
 *
 * <p>Used by {@link EmailMessage#attachments()}. Each {@link
 * com.nubeero.cia.notifications.email.EmailService} impl translates this
 * to its provider-specific shape (JavaMail {@code ByteArrayDataSource},
 * SendGrid {@code Attachments}, etc.).
 *
 * @since Slice γ — F7 email transmission
 */
public record Attachment(
        String filename,
        String contentType,
        byte[] content
) {
}
