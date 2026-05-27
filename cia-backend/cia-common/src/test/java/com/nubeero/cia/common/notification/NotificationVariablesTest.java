package com.nubeero.cia.common.notification;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationVariablesTest {

    @Test
    void receiptEmailAllowlist() {
        var allowlist = NotificationVariables.allowlistFor(
                NotificationTemplateType.RECEIPT, NotificationChannel.EMAIL);
        assertThat(allowlist).containsExactlyInAnyOrder(
                "customerName", "amount", "paymentDate",
                "receiptNumber", "debitNoteNumber", "companyName");
    }

    @Test
    void paymentVoucherEmailAllowlist() {
        var allowlist = NotificationVariables.allowlistFor(
                NotificationTemplateType.PAYMENT_VOUCHER, NotificationChannel.EMAIL);
        assertThat(allowlist).containsExactlyInAnyOrder(
                "beneficiaryName", "amount", "paymentDate",
                "paymentNumber", "creditNoteNumber", "companyName");
    }

    @Test
    void receiptSmsAllowlist() {
        var allowlist = NotificationVariables.allowlistFor(
                NotificationTemplateType.RECEIPT, NotificationChannel.SMS);
        assertThat(allowlist).containsExactlyInAnyOrder(
                "customerName", "amount", "receiptNumber");
    }

    @Test
    void paymentVoucherSmsAllowlist() {
        var allowlist = NotificationVariables.allowlistFor(
                NotificationTemplateType.PAYMENT_VOUCHER, NotificationChannel.SMS);
        assertThat(allowlist).containsExactlyInAnyOrder(
                "beneficiaryName", "amount", "paymentNumber");
    }

    @Test
    void everyEnumPairHasAnAllowlist() {
        for (NotificationTemplateType type : NotificationTemplateType.values()) {
            for (NotificationChannel channel : NotificationChannel.values()) {
                assertThat(NotificationVariables.allowlistFor(type, channel))
                        .as("allowlist exists for " + type + "/" + channel)
                        .isNotNull();
            }
        }
    }
}
