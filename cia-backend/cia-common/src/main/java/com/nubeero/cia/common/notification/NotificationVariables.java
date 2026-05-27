package com.nubeero.cia.common.notification;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The variable allowlist for each (template_type, channel) combination.
 * Tenant-edited templates may only reference variables in this set; the
 * validation gate fires both at save time (NotificationTemplateService)
 * and at render time (MustacheTemplateRenderer with throw-on-missing).
 *
 * Adding a new template type later requires adding an enum value +
 * an ALLOWLIST entry here + JAR default template files under
 * cia-documents/src/main/resources/templates/notifications/{channel}/.
 */
public final class NotificationVariables {

    private NotificationVariables() {}

    public record Key(NotificationTemplateType type, NotificationChannel channel) {}

    private static final Map<Key, Set<String>> ALLOWLIST = Map.of(
            new Key(NotificationTemplateType.RECEIPT, NotificationChannel.EMAIL),
                Set.of("customerName", "amount", "paymentDate",
                       "receiptNumber", "debitNoteNumber", "companyName"),

            new Key(NotificationTemplateType.PAYMENT_VOUCHER, NotificationChannel.EMAIL),
                Set.of("beneficiaryName", "amount", "paymentDate",
                       "paymentNumber", "creditNoteNumber", "companyName"),

            new Key(NotificationTemplateType.RECEIPT, NotificationChannel.SMS),
                Set.of("customerName", "amount", "receiptNumber"),

            new Key(NotificationTemplateType.PAYMENT_VOUCHER, NotificationChannel.SMS),
                Set.of("beneficiaryName", "amount", "paymentNumber")
    );

    public static Set<String> allowlistFor(NotificationTemplateType type, NotificationChannel channel) {
        return Optional.ofNullable(ALLOWLIST.get(new Key(type, channel)))
                .orElseThrow(() -> new IllegalStateException(
                        "No allowlist registered for " + type + "/" + channel));
    }
}
