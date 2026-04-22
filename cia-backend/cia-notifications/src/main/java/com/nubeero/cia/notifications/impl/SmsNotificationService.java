package com.nubeero.cia.notifications.impl;

import com.nubeero.cia.notifications.NotificationService;
import com.nubeero.cia.notifications.model.NotificationChannel;
import com.nubeero.cia.notifications.model.NotificationRequest;
import com.nubeero.cia.notifications.model.NotificationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

// Replace with Termii/Infobip/Twilio client when SMS provider is confirmed
@Slf4j
@Service
@ConditionalOnProperty(name = "cia.notifications.sms.enabled", havingValue = "true", matchIfMissing = true)
public class SmsNotificationService implements NotificationService {

    @Override
    public boolean supports(NotificationChannel channel) {
        return channel == NotificationChannel.SMS;
    }

    @Override
    public NotificationResult send(NotificationRequest request) {
        if (request.getChannel() != NotificationChannel.SMS) {
            throw new UnsupportedOperationException("SmsNotificationService only handles SMS channel");
        }
        log.info("[SMS STUB] To={} Body={}", request.getRecipient(), request.getBody());
        return NotificationResult.builder().success(true).build();
    }
}
