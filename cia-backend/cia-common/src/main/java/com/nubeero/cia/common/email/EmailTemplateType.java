package com.nubeero.cia.common.email;

/**
 * Discriminator for {@link com.nubeero.cia.finance.email.EmailBodyComposer}
 * templates and slice-δ tenant overrides.
 *
 * <p>Lives in {@code cia-common} (not {@code cia-setup}, not
 * {@code cia-finance}) so both modules can reference it without a
 * cross-business-module dependency — slice γ creates this enum here;
 * slice δ's {@code EmailTemplate} entity in {@code cia-setup} uses it.
 *
 * @since Slice γ — F7 email transmission
 */
public enum EmailTemplateType {
    RECEIPT_EMAIL,
    PAYMENT_VOUCHER_EMAIL
}
