package com.nubeero.cia.notifications.sms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the LoggingSmsService contract: calling sendSms returns silently
 * (no exception). Mirrors the LoggingEmailService test in EmailServiceIT.
 *
 * @since R7 — SMS SPI
 */
class LoggingSmsServiceTest {

    @Test
    @DisplayName("LoggingSmsService logs metadata + returns silently")
    void loggingSmsServiceReturnsSilently() {
        LoggingSmsService svc = new LoggingSmsService();
        svc.sendSms(new SmsMessage("+2349012345678", "Hello world"));
        // absence of exception is the contract — mirrors LoggingEmailService test
    }

    @Test
    @DisplayName("LoggingSmsService handles null body without throwing")
    void loggingSmsServiceHandlesNullBody() {
        LoggingSmsService svc = new LoggingSmsService();
        svc.sendSms(new SmsMessage("+2349012345678", null));
        // absence of exception is the contract
    }
}
