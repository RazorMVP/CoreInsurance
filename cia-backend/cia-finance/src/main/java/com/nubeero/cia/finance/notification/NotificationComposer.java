package com.nubeero.cia.finance.notification;

import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import com.nubeero.cia.common.notification.NotificationVariables;
import com.nubeero.cia.documents.notification.DefaultTemplateLoader;
import com.nubeero.cia.documents.notification.MustacheTemplateRenderer;
import com.nubeero.cia.setup.notification.TenantNotificationTemplate;
import com.nubeero.cia.setup.notification.TenantNotificationTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * Composes a (subject, body) message for a given notification by:
 * <ol>
 *   <li>Looking up a per-tenant override row from {@link TenantNotificationTemplateRepository}.</li>
 *   <li>For each field (subject, body), using the DB override if present-and-non-blank,
 *       else falling back to the JAR default from {@link DefaultTemplateLoader}.</li>
 *   <li>Filtering the merge fields to the variable allowlist via
 *       {@link NotificationVariables#allowlistFor} — defence-in-depth before render.</li>
 *   <li>Rendering the resolved templates via {@link MustacheTemplateRenderer}.</li>
 * </ol>
 *
 * <p>Subject is {@code null} for SMS — {@link DefaultTemplateLoader#subjectFor} returns
 * {@code null} for the SMS channel, and no DB override may supply a subject for SMS
 * (enforced by the {@code ck_tnt_sms_no_subject} DB constraint).
 *
 * <p>Replaces the legacy {@code EmailBodyComposer} (deleted in Task 3.2).
 */
@Component
@RequiredArgsConstructor
public class NotificationComposer {

    private final TenantNotificationTemplateRepository repo;
    private final MustacheTemplateRenderer renderer;
    private final DefaultTemplateLoader defaults;

    /**
     * Compose a (subject, body) pair for the given notification type and channel,
     * merging the supplied fields through the override→default→allowlist→render pipeline.
     *
     * @param type         the notification template type (RECEIPT, PAYMENT_VOUCHER, …)
     * @param channel      the delivery channel (EMAIL, SMS)
     * @param mergeFields  all candidate merge fields; will be filtered to the allowlist
     * @return a {@link ComposedMessage} with a rendered subject (null for SMS) and body
     */
    @Transactional(readOnly = true)
    public ComposedMessage compose(NotificationTemplateType type,
                                   NotificationChannel channel,
                                   Map<String, Object> mergeFields) {

        Optional<TenantNotificationTemplate> override = repo.findByTemplateTypeAndChannel(type, channel);

        // For each field: use the DB override if present-and-non-blank, else the JAR default.
        String subjectTemplate = override
                .map(TenantNotificationTemplate::getSubjectTemplate)
                .filter(s -> s != null && !s.isBlank())
                .orElseGet(() -> defaults.subjectFor(type, channel)); // null for SMS

        String bodyTemplate = override
                .map(TenantNotificationTemplate::getBodyTemplate)
                .filter(s -> s != null && !s.isBlank())
                .orElseGet(() -> defaults.bodyFor(type, channel));

        // Filter merge fields to the registered allowlist BEFORE rendering.
        // Defence-in-depth: extra caller-supplied fields cannot leak into the output.
        var allowlist = NotificationVariables.allowlistFor(type, channel);
        Map<String, Object> filtered = renderer.filterByAllowlist(mergeFields, allowlist);

        String renderedSubject = (subjectTemplate == null) ? null : renderer.render(subjectTemplate, filtered);
        String renderedBody = renderer.render(bodyTemplate, filtered);

        return new ComposedMessage(renderedSubject, renderedBody);
    }
}
