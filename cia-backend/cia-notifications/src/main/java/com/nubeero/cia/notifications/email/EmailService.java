package com.nubeero.cia.notifications.email;

/**
 * Email-channel-specific service. Three impls (LoggingEmailService,
 * SmtpEmailService, SendGridEmailService) are gated by
 * {@code cia.notifications.email.provider} — only one is active per JVM.
 *
 * <p>Failures bubble as runtime exceptions so the caller (typically a
 * Temporal activity) can let them propagate for retry. Impls MUST NOT
 * swallow delivery errors.
 *
 * @since Slice γ — F7 email transmission
 */
public interface EmailService {
    /**
     * Deliver an email synchronously.
     *
     * @throws RuntimeException if the provider rejects the message (SMTP
     *         error, SendGrid 4xx/5xx, etc.). Caller handles retry.
     */
    void sendEmail(EmailMessage message);
}
