package com.nubeero.cia.finance.notification;

/**
 * Result of {@link NotificationComposer#compose}. {@code subject} is null for SMS.
 */
public record ComposedMessage(String subject, String body) {}
