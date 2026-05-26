package com.nubeero.cia.notifications.email.impl;

import com.nubeero.cia.notifications.email.Attachment;
import com.nubeero.cia.notifications.email.EmailMessage;
import com.nubeero.cia.notifications.email.EmailService;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Attachments;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Base64;

/**
 * Email service that delivers via the SendGrid API. Active when
 * {@code cia.notifications.email.provider=sendgrid}. Reads the API key
 * from {@code cia.notifications.email.sendgrid.api-key} (env var
 * {@code SENDGRID_API_KEY}). Reads sender from
 * {@code cia.notifications.email.from} (default
 * {@code noreply@cia.local}).
 *
 * <p>Throws {@link RuntimeException} on any non-2xx response so the
 * Temporal email activity can retry.
 *
 * @since Slice γ — F7 email transmission
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "cia.notifications.email.provider", havingValue = "sendgrid")
public class SendGridEmailService implements EmailService {

    private final SendGrid sendGrid;
    private final String   fromAddress;

    public SendGridEmailService(
            @Value("${cia.notifications.email.sendgrid.api-key}") String apiKey,
            @Value("${cia.notifications.email.from:noreply@cia.local}") String fromAddress) {
        this.sendGrid    = new SendGrid(apiKey);
        this.fromAddress = fromAddress;
    }

    @Override
    public void sendEmail(EmailMessage message) {
        Email from    = new Email(fromAddress);
        Email to      = new Email(message.to());
        Content body  = new Content("text/html", message.bodyHtml());
        Mail mail     = new Mail(from, message.subject(), to, body);

        for (Attachment a : message.attachments()) {
            Attachments att = new Attachments();
            att.setContent(Base64.getEncoder().encodeToString(a.content()));
            att.setType(a.contentType());
            att.setFilename(a.filename());
            att.setDisposition("attachment");
            mail.addAttachments(att);
        }

        Request req = new Request();
        try {
            req.setMethod(Method.POST);
            req.setEndpoint("mail/send");
            req.setBody(mail.build());
            Response resp = sendGrid.api(req);
            if (resp.getStatusCode() < 200 || resp.getStatusCode() >= 300) {
                throw new RuntimeException("SendGrid rejected the message: status=" + resp.getStatusCode()
                                            + " body=" + resp.getBody());
            }
            log.info("SendGridEmailService: delivered to={} attachments={} status={}",
                     message.to(), message.attachments().size(), resp.getStatusCode());
        } catch (IOException e) {
            throw new RuntimeException("SendGrid API call failed", e);
        }
    }
}
