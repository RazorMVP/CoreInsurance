package com.nubeero.cia.notifications.sms;

/**
 * SMS-channel-specific service. Impls are gated by
 * {@code cia.notifications.sms.provider} — only one is active per JVM.
 *
 * <p>Failures bubble as runtime exceptions so the caller (typically a
 * Temporal activity) can let them propagate for retry. Impls MUST NOT
 * swallow delivery errors.
 *
 * @since R7 — SMS SPI
 */
public interface SmsService {
    /**
     * Deliver an SMS synchronously.
     *
     * @throws RuntimeException if the provider rejects the message
     *         (Termii / Twilio error, etc.). Caller handles retry.
     */
    void sendSms(SmsMessage message);
}
