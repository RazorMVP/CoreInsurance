package com.nubeero.cia.notifications.email.impl;

import com.nubeero.cia.notifications.email.Attachment;
import com.nubeero.cia.notifications.email.EmailMessage;
import com.nubeero.cia.notifications.email.EmailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Email service that logs metadata at INFO and returns silently. Active
 * when {@code cia.notifications.email.provider=logging}. Used in dev +
 * test profiles where no real SMTP/SendGrid traffic is desired.
 *
 * @since Slice γ — F7 email transmission
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "cia.notifications.email.provider", havingValue = "logging")
public class LoggingEmailService implements EmailService {

    @Override
    public void sendEmail(EmailMessage message) {
        long totalAttachmentBytes = message.attachments().stream()
                .mapToLong(a -> a.content() == null ? 0L : a.content().length)
                .sum();
        log.info("LoggingEmailService: would deliver to={} subject=\"{}\" bodyHtmlLen={} attachments={} totalAttachmentBytes={}",
                 message.to(),
                 message.subject(),
                 message.bodyHtml() == null ? 0 : message.bodyHtml().length(),
                 message.attachments().size(),
                 totalAttachmentBytes);
        for (Attachment a : message.attachments()) {
            log.info("  attachment: filename={} contentType={} bytes={}",
                     a.filename(), a.contentType(),
                     a.content() == null ? 0 : a.content().length);
        }
    }
}
