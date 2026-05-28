package com.nubeero.cia.finance.sms;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import com.nubeero.cia.finance.CreditNote;
import com.nubeero.cia.finance.DebitNote;
import com.nubeero.cia.finance.Payment;
import com.nubeero.cia.finance.PaymentRepository;
import com.nubeero.cia.finance.Receipt;
import com.nubeero.cia.finance.ReceiptRepository;
import com.nubeero.cia.finance.notification.ComposedMessage;
import com.nubeero.cia.finance.notification.NotificationComposer;
import com.nubeero.cia.notifications.sms.SmsMessage;
import com.nubeero.cia.notifications.sms.SmsService;
import io.temporal.failure.ApplicationFailure;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Steps inside the Temporal activities for sending receipt and payment-voucher
 * SMS notifications.
 *
 * <p>Failures fall into two classes:
 * <ul>
 *   <li>Non-retryable application failures — {@code RECEIPT_NOT_FOUND} /
 *       {@code RECEIPT_RECIPIENT_PHONE_UNRESOLVED} /
 *       {@code PAYMENT_NOT_FOUND} / {@code PAYMENT_RECIPIENT_PHONE_UNRESOLVED}.
 *       Service-layer preflight should catch these before the workflow starts;
 *       activity-level check is defence-in-depth.</li>
 *   <li>SMS provider failures — bubble out as runtime exceptions for Temporal
 *       exponential retry. The audit row is written only after a successful
 *       delivery, so 3 fails + 1 success = exactly 1 SEND row.</li>
 * </ul>
 *
 * <p>Methods are {@code @Transactional} so lazy JPA proxies
 * ({@code receipt.getDebitNote()}, {@code payment.getCreditNote()}) resolve
 * inside an active Hibernate session — without this the activity throws
 * {@code LazyInitializationException} wrapped in
 * {@code ApplicationFailure(nonRetryable=false)} and retries indefinitely.
 *
 * @since R7 — SMS Temporal workflows, Tasks 8.1–8.3
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmsActivitiesImpl implements SmsActivities {

    private final ReceiptRepository                  receiptRepository;
    private final PaymentRepository                  paymentRepository;
    private final NotificationComposer               notificationComposer;
    private final SmsService                         smsService;
    private final AuditService                       auditService;
    private final BeneficiaryPhoneResolverDispatcher phoneDispatcher;
    private final JdbcTemplate                       jdbc;

    @Override
    @Transactional
    public void deliverReceiptSms(String tenantId, UUID receiptId, String requestedBy) {
        Receipt receipt = receiptRepository.findById(receiptId)
            .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure(
                "Receipt not found: " + receiptId, "RECEIPT_NOT_FOUND"));

        DebitNote dn = receipt.getDebitNote();

        // Phone re-resolved at activity entry (mirrors email: fresh resolution
        // regardless of what preflight checked, in case data changed between
        // preflight and activity execution).
        String toPhone = resolveReceiptPhone(receipt);

        // Compose SMS body (subject is null for SMS channel)
        Map<String, Object> fields = new HashMap<>();
        fields.put("customerName", dn.getCustomerName());
        fields.put("receiptNumber", receipt.getReceiptNumber());
        fields.put("amount", "₦" + receipt.getAmount().toPlainString());
        ComposedMessage msg = notificationComposer.compose(
            NotificationTemplateType.RECEIPT, NotificationChannel.SMS, fields);

        // Send (provider errors bubble for Temporal retry)
        smsService.sendSms(new SmsMessage(toPhone, msg.body()));

        // Persist sms_sent_at + sms_sent_to via direct JDBC — mirrors
        // deliverPaymentVoucherSms which also uses jdbc.update() instead of
        // receiptRepository.save(). Using JPA save() is safe for the receipt path
        // today (receipts touch only DN → Customer, no @OneToMany cascade collections),
        // but JDBC is used here for consistency + latent-safety: if a future entity
        // loaded earlier in this activity gains a cascade collection, the double-flush
        // "Found shared references" bug would surface silently. Both deliver methods
        // now persist sms_sent_* identically via parameterised jdbc.update().
        jdbc.update(
            "UPDATE receipts SET sms_sent_at = NOW(), sms_sent_to = ? WHERE id = ?",
            toPhone, receiptId);

        // Audit row — written exactly once per successful send
        Map<String, Object> newValue = new HashMap<>();
        newValue.put("channel", "SMS");
        newValue.put("recipient", maskPhone(toPhone));
        newValue.put("requestedBy", requestedBy);
        auditService.log("Receipt", receiptId.toString(), AuditAction.SEND, null, newValue);

        log.info("SmsActivities.deliverReceiptSms: ok receiptId={} to={}",
                 receiptId, maskPhone(toPhone));
    }

    @Override
    @Transactional
    public void deliverPaymentVoucherSms(String tenantId, UUID paymentId, String requestedBy) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure(
                "Payment not found: " + paymentId, "PAYMENT_NOT_FOUND"));

        CreditNote cn = payment.getCreditNote();

        // Phone resolved via dispatcher (CLAIM / COMMISSION / REINSURANCE / ENDORSEMENT)
        String toPhone = phoneDispatcher.resolve(cn)
            .filter(p -> p != null && !p.isBlank())
            .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure(
                "Beneficiary phone unavailable for credit note " + cn.getId(),
                "PAYMENT_RECIPIENT_PHONE_UNRESOLVED"));

        // Compose SMS body (subject is null for SMS channel)
        Map<String, Object> fields = new HashMap<>();
        fields.put("beneficiaryName", cn.getBeneficiaryName());
        fields.put("paymentNumber", payment.getPaymentNumber());
        fields.put("amount", "₦" + payment.getAmount().toPlainString());
        ComposedMessage msg = notificationComposer.compose(
            NotificationTemplateType.PAYMENT_VOUCHER, NotificationChannel.SMS, fields);

        // Send (provider errors bubble for Temporal retry)
        smsService.sendSms(new SmsMessage(toPhone, msg.body()));

        // Persist sms_sent_at + sms_sent_to via direct JDBC — mirrors
        // SendPaymentVoucherEmailActivitiesImpl which also uses jdbc.update() instead of
        // paymentRepository.save(). Using JPA save() here triggers a Hibernate flush of
        // all entities in the session (including Claim / Endorsement entities loaded by the
        // BeneficiaryPhoneResolverDispatcher). These entities carry @Builder.Default @OneToMany
        // cascade=ALL collections; the pre-query flush inside NotificationComposer.compose()
        // processes those collections once, and the post-send save() flush processes them
        // again, causing Hibernate to throw "Found shared references to a collection".
        // JDBC bypasses the Hibernate session entirely and avoids the double-flush cascade.
        jdbc.update(
            "UPDATE payments SET sms_sent_at = NOW(), sms_sent_to = ? WHERE id = ?",
            toPhone, paymentId);

        // Audit row — written exactly once per successful send
        Map<String, Object> newValue = new HashMap<>();
        newValue.put("channel", "SMS");
        newValue.put("recipient", maskPhone(toPhone));
        newValue.put("requestedBy", requestedBy);
        auditService.log("Payment", paymentId.toString(), AuditAction.SEND, null, newValue);

        log.info("SmsActivities.deliverPaymentVoucherSms: ok paymentId={} to={}",
                 paymentId, maskPhone(toPhone));
    }

    /**
     * Resolves the customer phone for a receipt via JDBC, mirroring
     * {@link com.nubeero.cia.finance.email.SendReceiptEmailActivitiesImpl}'s
     * customer email lookup pattern. Throws a non-retryable
     * {@code ApplicationFailure} if the phone is absent or blank.
     */
    private String resolveReceiptPhone(Receipt receipt) {
        String phone = jdbc.queryForObject(
            "SELECT phone FROM customers WHERE id = ?",
            String.class, receipt.getDebitNote().getCustomerId());
        if (phone == null || phone.isBlank()) {
            throw ApplicationFailure.newNonRetryableFailure(
                "Customer has no recorded phone", "RECEIPT_RECIPIENT_PHONE_UNRESOLVED");
        }
        return phone;
    }

    /**
     * Masks a phone number for audit log PII reduction.
     * E.g. "+234 *** *** 5678" — keeps first 4 and last 4 chars.
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 8) return phone;
        int n = phone.length();
        return phone.substring(0, Math.min(4, n)) + " *** *** " + phone.substring(n - 4);
    }
}
