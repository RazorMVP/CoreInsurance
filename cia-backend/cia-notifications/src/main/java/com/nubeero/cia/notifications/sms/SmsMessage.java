package com.nubeero.cia.notifications.sms;

/**
 * Value type for an outbound SMS message.
 *
 * @since R7 — SMS SPI
 */
public record SmsMessage(String toPhone, String body) {}
