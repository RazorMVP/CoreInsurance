package com.nubeero.cia.notifications.email;

import java.util.List;

/**
 * Rich email message — multiple recipients, HTML body, optional attachments.
 *
 * <p>The dedicated email-channel model. Separate from
 * {@link com.nubeero.cia.notifications.model.NotificationRequest} (which
 * stays for channel-agnostic routing of SMS / in-app notifications)
 * because attachments + HTML body are inherently email-specific.
 *
 * <p>{@code EmailMessage.of(to, subject, bodyHtml)} is the back-compat
 * shortcut for callers that don't need attachments — defaults to
 * {@code List.of()}.
 *
 * @since Slice γ — F7 email transmission
 */
public record EmailMessage(
        String to,
        String subject,
        String bodyHtml,
        List<Attachment> attachments
) {
    public static EmailMessage of(String to, String subject, String bodyHtml) {
        return new EmailMessage(to, subject, bodyHtml, List.of());
    }
}
