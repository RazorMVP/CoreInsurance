package com.nubeero.cia.notifications.email.impl;

import com.nubeero.cia.notifications.email.Attachment;
import com.nubeero.cia.notifications.email.EmailMessage;
import com.nubeero.cia.notifications.email.EmailService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.ByteArrayDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Email service that delivers via {@code JavaMailSender} over SMTP.
 * Active by default ({@code matchIfMissing=true}) or when
 * {@code cia.notifications.email.provider=smtp}.
 *
 * @since Slice γ — F7 email transmission
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cia.notifications.email.provider",
                       havingValue = "smtp", matchIfMissing = true)
public class SmtpEmailService implements EmailService {

    private final JavaMailSender mailSender;

    @Override
    public void sendEmail(EmailMessage message) {
        MimeMessage mime = mailSender.createMimeMessage();
        try {
            // multipart=true is mandatory for attachments
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            helper.setText(message.bodyHtml(), true);
            for (Attachment a : message.attachments()) {
                helper.addAttachment(a.filename(),
                                     new ByteArrayDataSource(a.content(), a.contentType()));
            }
        } catch (MessagingException e) {
            throw new RuntimeException("Failed to build SMTP mime message", e);
        }
        mailSender.send(mime);
        log.info("SmtpEmailService: delivered to={} attachments={}",
                 message.to(), message.attachments().size());
    }
}
