package com.nubeero.cia.api.finance.event;

import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.finance.gl.PeriodReopenedEvent;
import com.nubeero.cia.finance.gl.TenantReopenRecipient;
import com.nubeero.cia.finance.gl.TenantReopenRecipientRepository;
import com.nubeero.cia.notifications.email.EmailMessage;
import com.nubeero.cia.notifications.email.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Bridges the {@link PeriodReopenedEvent} (published by {@code cia-finance})
 * to the {@code EmailService} (in {@code cia-notifications}) for the
 * mandatory CFO + compliance email on every reopen of a HARD-closed period.
 *
 * <p>Slice 1.7 reads recipients from a single Spring property
 * {@code cia.finance.period-reopen-recipients} (comma-separated). A
 * per-tenant CFO config table is a follow-up (Slice 1.7c) — until then,
 * deployments configure one platform-wide recipient list.
 *
 * <p>The listener is intentionally minimal: it does not retry, batch, or
 * persist a delivery log. The active {@code EmailService} implementation
 * (SendGrid / SMTP / log) handles delivery semantics. The
 * {@code PeriodReopenedLogListener} in {@code cia-finance} writes an
 * unconditional WARN log line so the event is auditable even when no email
 * recipients are configured.
 *
 * @since Module 12, Slice 1.7
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PeriodReopenedNotificationListener {

    private final EmailService emailService;
    // Slice 1.7c — per-tenant recipient list takes precedence over the
    // legacy CSV Spring property. The property remains as a fallback for
    // environments that haven't migrated their config to the DB yet.
    private final TenantReopenRecipientRepository recipientRepository;

    @Value("${cia.finance.period-reopen-recipients:}")
    private String recipientsCsv;

    @EventListener
    public void onReopen(PeriodReopenedEvent event) {
        // DB-first, CSV-fallback per Slice 1.7c. The DB query is fast (single
        // unique-indexed sort) and falls through to the property only when
        // the tenant hasn't seeded the table.
        List<String> recipients = recipientRepository
            .findAllByActiveTrueAndDeletedAtIsNullOrderByRecipientAsc()
            .stream()
            .map(TenantReopenRecipient::getRecipient)
            .toList();
        if (recipients.isEmpty()) {
            recipients = parseRecipients(recipientsCsv);
        }
        if (recipients.isEmpty()) {
            log.info("PeriodReopenedEvent fired but no recipients configured "
                + "(tenant_reopen_recipient empty and cia.finance.period-reopen-recipients unset) "
                + "— skipping email dispatch");
            return;
        }

        String tenantId = TenantContext.getTenantId();
        String subject = "[NubSure] Period reopened — %s".formatted(event.getPeriodLabel());
        String body = """
            A hard-closed fiscal period has been reopened.

            Period:       %s
            Reopened by:  %s
            Reason:       %s
            Tenant:       %s

            This action requires the FINANCE_REOPEN_PERIOD role and is recorded
            in audit_log with action=REOPEN and a corresponding period_lock
            release row. Auditors will request the full lock history for this
            period at next sample review.

            — NubSure (automated notification)
            """.formatted(event.getPeriodLabel(), event.getReopenedBy(), event.getReason(),
                tenantId != null ? tenantId : "(unset)");

        for (String recipient : recipients) {
            try {
                emailService.sendEmail(EmailMessage.of(recipient, subject, body));
            } catch (Exception ex) {
                log.error("Failed to send period-reopen notification to {}: {}", recipient, ex.getMessage(), ex);
            }
        }
    }

    private List<String> parseRecipients(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }
}
