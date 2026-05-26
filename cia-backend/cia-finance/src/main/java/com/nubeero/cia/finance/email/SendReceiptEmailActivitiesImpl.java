package com.nubeero.cia.finance.email;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.email.EmailTemplateType;
import com.nubeero.cia.finance.DebitNote;
import com.nubeero.cia.finance.Receipt;
import com.nubeero.cia.finance.ReceiptRepository;
import com.nubeero.cia.notifications.email.Attachment;
import com.nubeero.cia.notifications.email.EmailMessage;
import com.nubeero.cia.notifications.email.EmailService;
import com.nubeero.cia.storage.DocumentStorageService;
import io.temporal.failure.ApplicationFailure;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Steps inside the Temporal activity for sending a receipt email.
 *
 * <p>Failures fall into two classes:
 * <ul>
 *   <li>Non-retryable application failures — {@code RECEIPT_PDF_UNAVAILABLE}
 *       (pdfPath is null) or {@code RECEIPT_RECIPIENT_UNRESOLVED} (customer
 *       email missing). Service-layer preflight should catch these before
 *       the workflow starts; activity-level catch is defense-in-depth.</li>
 *   <li>SMTP/SendGrid failures — bubble out as runtime exceptions for
 *       Temporal exponential retry. The audit row is written only after a
 *       successful delivery, so 3 fails + 1 success = exactly 1 SEND row.</li>
 * </ul>
 *
 * @since Slice γ — Task 19, F7 email transmission
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SendReceiptEmailActivitiesImpl implements SendReceiptEmailActivities {

    private final ReceiptRepository      receiptRepository;
    private final DocumentStorageService storage;
    private final EmailBodyComposer      bodyComposer;
    private final EmailService           emailService;
    private final AuditService           auditService;
    private final JdbcTemplate           jdbc;

    @Override
    public void deliver(String tenantId, UUID receiptId, String requestedBy) {
        Receipt receipt = receiptRepository.findById(receiptId)
            .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure(
                "Receipt not found: " + receiptId, "RECEIPT_NOT_FOUND"));

        if (receipt.getPdfPath() == null) {
            throw ApplicationFailure.newNonRetryableFailure(
                "Receipt PDF unavailable", "RECEIPT_PDF_UNAVAILABLE");
        }

        DebitNote dn = receipt.getDebitNote();

        // Customer.email lookup via JDBC — keeps cia-finance light on JPA chain.
        String customerEmail = jdbc.queryForObject(
            "SELECT email FROM customers WHERE id = ?",
            String.class, dn.getCustomerId());
        if (customerEmail == null || customerEmail.isBlank()) {
            throw ApplicationFailure.newNonRetryableFailure(
                "Customer has no recorded email", "RECEIPT_RECIPIENT_UNRESOLVED");
        }

        // Download PDF bytes from MinIO
        byte[] pdfBytes;
        try (InputStream in = storage.download(tenantId, receipt.getPdfPath())) {
            pdfBytes = in.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to download receipt PDF from storage", e);
        }

        // Compose subject + body
        String customerName = dn.getCustomerName();
        Map<String, Object> fields = new HashMap<>();
        fields.put("customerName", customerName);
        fields.put("receiptNumber", receipt.getReceiptNumber());
        fields.put("amount", "₦" + receipt.getAmount().toPlainString());
        fields.put("paymentDate", receipt.getPaymentDate().toString());
        fields.put("debitNoteNumber", dn.getDebitNoteNumber());
        fields.put("companyName", "Your Insurance Company"); // δ moves to tenant config
        EmailContent content = bodyComposer.compose(EmailTemplateType.RECEIPT_EMAIL, fields);

        // Build EmailMessage + send (SMTP errors bubble for retry)
        EmailMessage msg = new EmailMessage(
            customerEmail,
            content.subject(),
            content.bodyHtml(),
            List.of(new Attachment(
                "REC-" + receipt.getReceiptNumber() + ".pdf",
                "application/pdf",
                pdfBytes)));
        emailService.sendEmail(msg);

        // Persist email_sent_at + email_sent_to via direct JDBC
        jdbc.update(
            "UPDATE receipts SET email_sent_at = NOW(), email_sent_to = ? WHERE id = ?",
            customerEmail, receiptId);

        // Audit row — one per successful send
        Map<String, Object> newValue = new HashMap<>();
        newValue.put("recipient", customerEmail);
        newValue.put("attachmentBytes", pdfBytes.length);
        newValue.put("requestedBy", requestedBy);
        auditService.log("Receipt", receiptId.toString(), AuditAction.SEND, null, newValue);

        log.info("SendReceiptEmailActivities.deliver: ok receiptId={} to={} attachmentBytes={}",
                 receiptId, customerEmail, pdfBytes.length);
    }
}
