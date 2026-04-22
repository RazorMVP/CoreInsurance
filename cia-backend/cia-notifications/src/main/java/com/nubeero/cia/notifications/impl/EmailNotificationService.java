package com.nubeero.cia.notifications.impl;

import com.nubeero.cia.notifications.NotificationService;
import com.nubeero.cia.notifications.model.NotificationChannel;
import com.nubeero.cia.notifications.model.NotificationRequest;
import com.nubeero.cia.notifications.model.NotificationResult;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cia.notifications.email.enabled", havingValue = "true", matchIfMissing = true)
public class EmailNotificationService implements NotificationService {

    private final JavaMailSender mailSender;

    @Override
    public boolean supports(NotificationChannel channel) {
        return channel == NotificationChannel.EMAIL;
    }

    @Override
    public NotificationResult send(NotificationRequest request) {
        if (request.getChannel() != NotificationChannel.EMAIL) {
            throw new UnsupportedOperationException("EmailNotificationService only handles EMAIL channel");
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(request.getRecipient());
            helper.setSubject(request.getSubject());
            helper.setText(request.getBody(), true);
            mailSender.send(message);
            return NotificationResult.builder().success(true).build();
        } catch (MessagingException e) {
            log.error("Failed to send email to={}", request.getRecipient(), e);
            return NotificationResult.builder().success(false).errorMessage(e.getMessage()).build();
        }
    }
}
