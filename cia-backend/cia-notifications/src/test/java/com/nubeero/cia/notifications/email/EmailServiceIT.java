package com.nubeero.cia.notifications.email;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.nubeero.cia.notifications.email.impl.LoggingEmailService;
import com.nubeero.cia.notifications.email.impl.SmtpEmailService;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the EmailService contract for the two impls that have realistic
 * test paths:
 *
 * <ul>
 *   <li>LoggingEmailService — log metadata + return silently.</li>
 *   <li>SmtpEmailService — deliver to greenmail (in-process SMTP server)
 *       + verify MimeMultipart structure + attachment filename / content.</li>
 * </ul>
 *
 * <p>SendGridEmailService is covered by a separate Mockito-based unit
 * test (the SendGrid SDK is not greenmail-compatible).
 *
 * @since Slice γ — F7 email transmission
 */
class EmailServiceIT {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @Test
    @DisplayName("LoggingEmailService logs metadata + returns silently for messages with attachments")
    void loggingEmailServiceLogsMetadata() {
        LoggingEmailService svc = new LoggingEmailService();
        svc.sendEmail(new EmailMessage(
                "alice@test.local",
                "Hello",
                "<p>Body</p>",
                List.of(new Attachment("doc.pdf", "application/pdf", new byte[]{1, 2, 3}))));
        // no assertions — Logging path doesn't deliver; absence of exception is the contract
    }

    @Test
    @DisplayName("SmtpEmailService delivers a message with attachment to greenmail SMTP server")
    void smtpEmailServiceDeliversAttachment() throws Exception {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("localhost");
        mailSender.setPort(greenMail.getSmtp().getPort());
        Properties props = mailSender.getJavaMailProperties();
        props.setProperty("mail.transport.protocol", "smtp");

        SmtpEmailService svc = new SmtpEmailService(mailSender);

        byte[] pdfBytes = "%PDF-1.4 test content".getBytes();
        svc.sendEmail(new EmailMessage(
                "bob@test.local",
                "Test subject",
                "<p>Test body</p>",
                List.of(new Attachment("test.pdf", "application/pdf", pdfBytes))));

        greenMail.waitForIncomingEmail(5000, 1);
        Message[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(1);
        Message msg = received[0];

        assertThat(msg.getSubject()).isEqualTo("Test subject");
        assertThat(msg.getContent()).isInstanceOf(Multipart.class);

        Multipart mp = (Multipart) msg.getContent();
        assertThat(mp.getCount()).isGreaterThanOrEqualTo(2); // body + attachment

        // Body part is multipart-related (HTML); attachment is the last part
        boolean foundAttachment = false;
        for (int i = 0; i < mp.getCount(); i++) {
            jakarta.mail.BodyPart part = mp.getBodyPart(i);
            String disposition = part.getDisposition();
            if ("attachment".equalsIgnoreCase(disposition)
                    && "test.pdf".equals(part.getFileName())) {
                foundAttachment = true;
                assertThat(part.getContentType()).contains("application/pdf");
            }
        }
        assertThat(foundAttachment)
            .as("Attachment named 'test.pdf' with contentType application/pdf")
            .isTrue();
    }

    @Test
    @DisplayName("SmtpEmailService sets HTML body content")
    void smtpEmailServiceSendsHtmlBody() throws Exception {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("localhost");
        mailSender.setPort(greenMail.getSmtp().getPort());

        SmtpEmailService svc = new SmtpEmailService(mailSender);
        svc.sendEmail(EmailMessage.of("eve@test.local", "Subject", "<p>HTML body</p>"));

        greenMail.waitForIncomingEmail(5000, 1);
        Message[] received = greenMail.getReceivedMessages();
        // Find the eve message
        Message msg = java.util.Arrays.stream(received)
            .filter(m -> {
                try {
                    return m.getAllRecipients()[0].toString().contains("eve");
                } catch (Exception e) {
                    return false;
                }
            })
            .findFirst()
            .orElseThrow();

        Object content = msg.getContent();
        String text = content instanceof Multipart mp
            ? extractFirstTextPart(mp)
            : content.toString();
        assertThat(text).contains("<p>HTML body</p>");
    }

    private static String extractFirstTextPart(Multipart mp) throws Exception {
        for (int i = 0; i < mp.getCount(); i++) {
            jakarta.mail.BodyPart part = mp.getBodyPart(i);
            if (part.getContent() instanceof String s) return s;
            if (part.getContent() instanceof Multipart inner) {
                String nested = extractFirstTextPart(inner);
                if (nested != null) return nested;
            }
        }
        return null;
    }
}
