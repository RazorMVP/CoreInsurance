package com.nubeero.cia.finance.email;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import com.nubeero.cia.finance.CreditNote;
import com.nubeero.cia.finance.Payment;
import com.nubeero.cia.finance.PaymentRepository;
import com.nubeero.cia.notifications.email.Attachment;
import com.nubeero.cia.notifications.email.EmailMessage;
import com.nubeero.cia.notifications.email.EmailService;
import com.nubeero.cia.storage.DocumentStorageService;
import io.temporal.failure.ApplicationFailure;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Steps inside the Temporal activity for sending a payment-voucher email.
 *
 * <p>Failures fall into two classes:
 * <ul>
 *   <li>Non-retryable application failures — {@code PAYMENT_PDF_UNAVAILABLE}
 *       (pdfPath is null) or {@code PAYMENT_RECIPIENT_UNRESOLVED} (no email
 *       resolved by {@link BeneficiaryEmailResolverDispatcher}).</li>
 *   <li>SMTP/SendGrid failures — bubble out as runtime exceptions for
 *       Temporal exponential retry. The audit row is written only after a
 *       successful delivery, so 3 fails + 1 success = exactly 1 SEND row.</li>
 * </ul>
 *
 * <p>Getter name verifications (performed pre-implementation):
 * <ul>
 *   <li>{@code Payment.getPaymentNumber()} — confirmed</li>
 *   <li>{@code Payment.getCreditNote()} — confirmed</li>
 *   <li>{@code Payment.getAmount()} — BigDecimal, confirmed</li>
 *   <li>{@code Payment.getPaymentDate()} — LocalDate, confirmed</li>
 *   <li>{@code CreditNote.getCreditNoteNumber()} — confirmed</li>
 *   <li>{@code CreditNote.getBeneficiaryName()} — confirmed</li>
 *   <li>{@code CreditNote.getId()} — inherited from BaseEntity, confirmed</li>
 * </ul>
 *
 * @since Slice γ — Task 20, F7 email transmission
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SendPaymentVoucherEmailActivitiesImpl implements SendPaymentVoucherEmailActivities {

    private final PaymentRepository                  paymentRepository;
    private final DocumentStorageService             storage;
    private final EmailBodyComposer                  bodyComposer;
    private final EmailService                       emailService;
    private final AuditService                       auditService;
    private final BeneficiaryEmailResolverDispatcher recipientDispatcher;
    private final JdbcTemplate                       jdbc;

    @Override
    @Transactional
    public void deliver(String tenantId, UUID paymentId, String requestedBy) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure(
                "Payment not found: " + paymentId, "PAYMENT_NOT_FOUND"));

        if (payment.getPdfPath() == null) {
            throw ApplicationFailure.newNonRetryableFailure(
                "Payment PDF unavailable", "PAYMENT_PDF_UNAVAILABLE");
        }

        CreditNote cn = payment.getCreditNote();

        // Recipient resolved via dispatcher (CLAIM / COMMISSION / REINSURANCE / ENDORSEMENT)
        String recipient = recipientDispatcher.resolve(cn)
            .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure(
                "No email on file for credit note " + cn.getId(),
                "PAYMENT_RECIPIENT_UNRESOLVED"));

        // Download PDF bytes from MinIO
        byte[] pdfBytes;
        try (InputStream in = storage.download(tenantId, payment.getPdfPath())) {
            pdfBytes = in.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to download payment PDF from storage", e);
        }

        // Compose subject + body
        Map<String, Object> fields = new HashMap<>();
        fields.put("beneficiaryName", cn.getBeneficiaryName());
        fields.put("paymentNumber", payment.getPaymentNumber());
        fields.put("amount", "₦" + payment.getAmount().toPlainString());
        fields.put("paymentDate", payment.getPaymentDate().toString());
        fields.put("creditNoteNumber", cn.getCreditNoteNumber());
        fields.put("companyName", "Your Insurance Company"); // δ moves to tenant config
        EmailContent content = bodyComposer.compose(NotificationTemplateType.PAYMENT_VOUCHER, fields);

        // Build EmailMessage + send (SMTP errors bubble for retry)
        EmailMessage msg = new EmailMessage(
            recipient,
            content.subject(),
            content.bodyHtml(),
            List.of(new Attachment(
                "PAY-" + payment.getPaymentNumber() + ".pdf",
                "application/pdf",
                pdfBytes)));
        emailService.sendEmail(msg);

        // Persist email_sent_at + email_sent_to via direct JDBC
        jdbc.update(
            "UPDATE payments SET email_sent_at = NOW(), email_sent_to = ? WHERE id = ?",
            recipient, paymentId);

        // Audit row — one per successful send
        Map<String, Object> newValue = new HashMap<>();
        newValue.put("recipient", recipient);
        newValue.put("attachmentBytes", pdfBytes.length);
        newValue.put("requestedBy", requestedBy);
        auditService.log("Payment", paymentId.toString(), AuditAction.SEND, null, newValue);

        log.info("SendPaymentVoucherEmailActivities.deliver: ok paymentId={} to={} attachmentBytes={}",
                 paymentId, recipient, pdfBytes.length);
    }
}
