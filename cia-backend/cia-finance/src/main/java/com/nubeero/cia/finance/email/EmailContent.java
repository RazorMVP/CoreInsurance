package com.nubeero.cia.finance.email;

/**
 * Rendered email subject + body pair returned by {@link EmailBodyComposer}.
 *
 * @since Slice γ — F7 email transmission
 */
public record EmailContent(String subject, String bodyHtml) {
}
