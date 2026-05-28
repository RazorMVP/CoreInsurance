package com.nubeero.cia.notifications.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Logging-only SMS provider. Default when no other provider is wired
 * ({@code cia.notifications.sms.provider} is unset or set to {@code logging}).
 *
 * <p>Production prod-impls (Termii / Twilio) will replace this via the
 * {@code cia.notifications.sms.provider} property in their backlog pickups
 * (R7-termii-prod / R7-twilio-prod).
 *
 * @since R7 — SMS SPI
 */
@Slf4j
@Service
@ConditionalOnProperty(
        name = "cia.notifications.sms.provider",
        havingValue = "logging",
        matchIfMissing = true)
public class LoggingSmsService implements SmsService {

    @Override
    public void sendSms(SmsMessage message) {
        log.info("[SMS STUB] to={} bodyLength={}",
                message.toPhone(),
                message.body() == null ? 0 : message.body().length());
    }
}
