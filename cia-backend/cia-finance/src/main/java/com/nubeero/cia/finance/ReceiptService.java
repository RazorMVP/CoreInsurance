package com.nubeero.cia.finance;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.finance.notification.NotificationPreflightException;
import com.nubeero.cia.finance.email.SendReceiptEmailWorkflow;
import com.nubeero.cia.finance.sms.SendReceiptSmsWorkflow;
import com.nubeero.cia.finance.pdf.ReceiptPdfGenerator;
import com.nubeero.cia.storage.DocumentStorageService;
import com.nubeero.cia.workflow.TemporalQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ReceiptService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptService.class);

    private final ReceiptRepository receiptRepository;
    private final DebitNoteService debitNoteService;
    private final FinanceNumberService numberService;
    private final AuditService auditService;
    private final ReceiptPdfGenerator    pdfGenerator;
    private final DocumentStorageService storage;
    private final JdbcTemplate           jdbc;
    private final WorkflowClient         workflowClient;

    public ReceiptService(ReceiptRepository receiptRepository,
                          DebitNoteService debitNoteService,
                          FinanceNumberService numberService,
                          AuditService auditService,
                          ReceiptPdfGenerator pdfGenerator,
                          DocumentStorageService storage,
                          JdbcTemplate jdbc,
                          WorkflowClient workflowClient) {
        this.receiptRepository = receiptRepository;
        this.debitNoteService = debitNoteService;
        this.numberService = numberService;
        this.auditService = auditService;
        this.pdfGenerator = pdfGenerator;
        this.storage = storage;
        this.jdbc = jdbc;
        this.workflowClient = workflowClient;
    }

    public Page<Receipt> findByDebitNote(UUID debitNoteId, Pageable pageable) {
        return receiptRepository.findAllByDebitNote_IdAndDeletedAtIsNull(debitNoteId, pageable);
    }

    public Page<ReceiptListItemResponse> findAll(Specification<Receipt> spec, Pageable pageable) {
        // Always exclude soft-deleted rows, regardless of what the caller passes.
        var fullSpec = (spec == null
                ? ReceiptSpecs.deletedAtIsNull()
                : Specification.where(ReceiptSpecs.deletedAtIsNull()).and(spec));

        return receiptRepository.findAll(fullSpec, pageable).map(this::toListItem);
    }

    private ReceiptListItemResponse toListItem(Receipt r) {
        DebitNote dn = r.getDebitNote();
        // DebitNote denormalises customerName and entityReference at creation time
        // (see PolicyApprovedEventListener / EndorsementApprovedEventListener).
        // entityReference holds the policy/endorsement/claim number depending on
        // entityType — for POLICY DNs it IS the policy number, which is exactly
        // what the list table needs. No cross-module join required.
        String policyNumber = null;
        String customerName = null;
        if (dn != null) {
            customerName = dn.getCustomerName();
            if (dn.getEntityType() == FinanceEntityType.POLICY) {
                policyNumber = dn.getEntityReference();
            }
        }
        // Slice γ — pre-resolve customer email for the Email button.
        // Task 6.2 — pre-resolve customer phone for the SMS button.
        // N+1: one JDBC SELECT per row. Acceptable for v1 (typical page
        // size ≤ 50); switch to a batch IN query if a perf concern surfaces.
        String recipientEmail = null;
        String recipientPhone = null;
        if (dn != null && dn.getCustomerId() != null) {
            try {
                recipientEmail = jdbc.queryForObject(
                    "SELECT email FROM customers WHERE id = ?",
                    String.class, dn.getCustomerId());
                if (recipientEmail != null && recipientEmail.isBlank()) {
                    recipientEmail = null;
                }
            } catch (org.springframework.dao.EmptyResultDataAccessException ignored) {
                // Customer not found — leave recipientEmail null.
            }
            try {
                recipientPhone = jdbc.queryForObject(
                    "SELECT phone FROM customers WHERE id = ?",
                    String.class, dn.getCustomerId());
                if (recipientPhone != null && recipientPhone.isBlank()) {
                    recipientPhone = null;
                }
            } catch (org.springframework.dao.EmptyResultDataAccessException ignored) {
                // Customer not found — leave recipientPhone null.
            }
        }
        return new ReceiptListItemResponse(
                r.getId(),
                r.getReceiptNumber(),
                dn != null ? dn.getId() : null,
                dn != null ? dn.getDebitNoteNumber() : null,
                policyNumber,
                customerName,
                r.getAmount(),
                r.getPaymentMethod(),
                r.getPaymentDate(),
                r.getStatus(),
                r.getReversedAt(),
                r.getReversedBy(),
                r.getReversalReason(),
                r.getCreatedAt(),
                r.getPdfPath(),
                recipientEmail,
                r.getEmailSentAt(),
                r.getEmailSentTo(),
                recipientPhone,
                r.getSmsSentAt(),
                r.getSmsSentTo());
    }

    public Receipt findOrThrow(UUID id) {
        return receiptRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt", id));
    }

    @Transactional
    public Receipt post(UUID debitNoteId, BigDecimal amount, LocalDate paymentDate,
                        PaymentMethod paymentMethod, UUID bankId, String bankName,
                        String chequeNumber, String narration) {
        DebitNote dn = debitNoteService.findOrThrow(debitNoteId);
        if (dn.getStatus() == DebitNoteStatus.SETTLED
                || dn.getStatus() == DebitNoteStatus.CANCELLED
                || dn.getStatus() == DebitNoteStatus.VOID) {
            throw new IllegalStateException(
                    "Cannot post receipt against a " + dn.getStatus() + " debit note");
        }

        Receipt receipt = new Receipt();
        receipt.setReceiptNumber(numberService.nextReceiptNumber());
        receipt.setDebitNote(dn);
        receipt.setAmount(amount);
        receipt.setPaymentDate(paymentDate);
        receipt.setPaymentMethod(paymentMethod);
        receipt.setBankId(bankId);
        receipt.setBankName(bankName);
        receipt.setChequeNumber(chequeNumber);
        receipt.setNarration(narration);
        receipt.setPostedBy(currentUser());
        receipt.setStatus(TransactionStatus.POSTED);
        receipt.setCreatedBy(currentUser());
        Receipt saved = receiptRepository.save(receipt);

        BigDecimal newPaid = sumPostedReceipts(debitNoteId);
        debitNoteService.recalculateStatus(debitNoteId, newPaid);

        generateAndPersistPdf(saved);
        return saved;
    }

    @Transactional
    public void reverse(UUID receiptId, String reversalReason) {
        Receipt receipt = findOrThrow(receiptId);
        if (receipt.getStatus() == TransactionStatus.REVERSED) {
            throw new IllegalStateException("Receipt is already reversed");
        }

        ReverseSnapshot oldValue = new ReverseSnapshot(
                receipt.getStatus(), null, null, null);

        receipt.setStatus(TransactionStatus.REVERSED);
        receipt.setReversalReason(reversalReason);
        receipt.setReversedAt(Instant.now());
        receipt.setReversedBy(currentUser());
        Receipt saved = receiptRepository.save(receipt);

        ReverseSnapshot newValue = new ReverseSnapshot(
                saved.getStatus(),
                saved.getReversedAt(),
                saved.getReversedBy(),
                saved.getReversalReason());

        auditService.log("Receipt", saved.getId().toString(),
                AuditAction.REVERSE, oldValue, newValue);

        UUID debitNoteId = saved.getDebitNote().getId();
        BigDecimal newPaid = sumPostedReceipts(debitNoteId);
        debitNoteService.recalculateStatus(debitNoteId, newPaid);
    }

    private record ReverseSnapshot(
            TransactionStatus status,
            Instant reversedAt,
            String reversedBy,
            String reversalReason) {}

    private BigDecimal sumPostedReceipts(UUID debitNoteId) {
        List<Receipt> posted = receiptRepository
                .findAllByDebitNote_IdAndStatusAndDeletedAtIsNull(
                        debitNoteId, TransactionStatus.POSTED);
        return posted.stream()
                .map(Receipt::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Validates the email preflight (PDF exists + recipient resolved) and starts
     * the {@link SendReceiptEmailWorkflow} on {@link TemporalQueues#NOTIFICATIONS_QUEUE}.
     *
     * @return the started workflow id ({@code "send-receipt-email-<receiptId>"}).
     * @throws NotificationPreflightException 422 with {@code RECEIPT_PDF_UNAVAILABLE} if
     *         the slice-β PDF was never generated; 422 with
     *         {@code RECEIPT_RECIPIENT_UNRESOLVED} if the customer has no
     *         recorded email.
     */
    public String requestEmail(UUID receiptId) {
        Receipt receipt = findOrThrow(receiptId);

        if (receipt.getPdfPath() == null) {
            throw new NotificationPreflightException(
                "RECEIPT_PDF_UNAVAILABLE",
                "PDF was never generated for receipt " + receiptId);
        }

        DebitNote dn = receipt.getDebitNote();
        UUID customerId = dn != null ? dn.getCustomerId() : null;
        if (customerId == null) {
            throw new NotificationPreflightException(
                "RECEIPT_RECIPIENT_UNRESOLVED",
                "Debit note has no customer reference");
        }

        String email;
        try {
            email = jdbc.queryForObject(
                "SELECT email FROM customers WHERE id = ?",
                String.class, customerId);
        } catch (EmptyResultDataAccessException e) {
            email = null;
        }
        if (email == null || email.isBlank()) {
            throw new NotificationPreflightException(
                "RECEIPT_RECIPIENT_UNRESOLVED",
                "Customer " + customerId + " has no email on file");
        }

        String tenantId    = TenantContext.getTenantId();
        String requestedBy = currentUser();
        String workflowId  = "send-receipt-email-" + receiptId;

        SendReceiptEmailWorkflow workflow = workflowClient.newWorkflowStub(
            SendReceiptEmailWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalQueues.NOTIFICATIONS_QUEUE)
                .setWorkflowId(workflowId)
                .build());
        WorkflowClient.start(workflow::send, tenantId, receiptId, requestedBy);
        log.info("ReceiptService.requestEmail: enqueued workflow {} for receiptId={} to={}",
                 workflowId, receiptId, email);
        return workflowId;
    }

    /**
     * Cancels an in-flight email workflow. Best-effort — the workflow
     * checks its cancelled flag only before dispatching to the email
     * activity, so a cancel signal arriving after dispatch lets the
     * activity (and its retries) complete normally.
     *
     * @throws NotificationPreflightException 404 with errorCode
     *         {@code WORKFLOW_NOT_FOUND} if Temporal cannot find the
     *         workflow (already finished or never started).
     */
    public void cancelEmail(UUID receiptId) {
        String workflowId = "send-receipt-email-" + receiptId;
        try {
            SendReceiptEmailWorkflow workflow = workflowClient.newWorkflowStub(
                    SendReceiptEmailWorkflow.class, workflowId);
            workflow.cancel();
        } catch (Exception e) {
            throw new NotificationPreflightException(
                "WORKFLOW_NOT_FOUND",
                "No in-flight email workflow for receipt " + receiptId);
        }

        Map<String, Object> newValue = new HashMap<>();
        newValue.put("workflowId", workflowId);
        newValue.put("cancelledBy", currentUser());
        auditService.log("Receipt", receiptId.toString(),
                AuditAction.CANCEL, null, newValue);
        log.info("ReceiptService.cancelEmail: signalled cancel on workflow {} by {}",
                 workflowId, currentUser());
    }

    /**
     * Validates the SMS preflight (phone resolved) and starts the
     * {@link SendReceiptSmsWorkflow} on {@link TemporalQueues#NOTIFICATIONS_QUEUE}.
     *
     * <p>No PDF gate — SMS delivery does not require the receipt PDF to have been
     * generated.
     *
     * @return the started workflow id ({@code "send-receipt-sms-<receiptId>"}).
     * @throws NotificationPreflightException 422 with
     *         {@code RECEIPT_RECIPIENT_PHONE_UNRESOLVED} if the customer has no
     *         recorded phone number.
     */
    public String requestSms(UUID receiptId) {
        Receipt receipt = findOrThrow(receiptId);

        DebitNote dn = receipt.getDebitNote();
        UUID customerId = dn != null ? dn.getCustomerId() : null;
        if (customerId == null) {
            throw new NotificationPreflightException(
                "RECEIPT_RECIPIENT_PHONE_UNRESOLVED",
                "Debit note has no customer reference");
        }

        String phone;
        try {
            phone = jdbc.queryForObject(
                "SELECT phone FROM customers WHERE id = ?",
                String.class, customerId);
        } catch (EmptyResultDataAccessException e) {
            phone = null;
        }
        if (phone == null || phone.isBlank()) {
            throw new NotificationPreflightException(
                "RECEIPT_RECIPIENT_PHONE_UNRESOLVED",
                "Customer " + customerId + " has no phone on file");
        }

        String tenantId    = TenantContext.getTenantId();
        String requestedBy = currentUser();
        String workflowId  = "send-receipt-sms-" + receiptId;

        SendReceiptSmsWorkflow workflow = workflowClient.newWorkflowStub(
            SendReceiptSmsWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalQueues.NOTIFICATIONS_QUEUE)
                .setWorkflowId(workflowId)
                .build());
        WorkflowClient.start(workflow::send, tenantId, receiptId, requestedBy);
        log.info("ReceiptService.requestSms: enqueued workflow {} for receiptId={} to={}",
                 workflowId, receiptId, phone);
        return workflowId;
    }

    /**
     * Cancels an in-flight SMS workflow. Best-effort — the workflow checks its
     * cancelled flag only before dispatching to the SMS activity, so a cancel
     * signal arriving after dispatch lets the activity (and its retries) complete
     * normally.
     *
     * @throws NotificationPreflightException 422 with errorCode
     *         {@code WORKFLOW_NOT_FOUND} if Temporal cannot find the workflow
     *         (already finished or never started).
     */
    public void cancelSms(UUID receiptId) {
        String workflowId = "send-receipt-sms-" + receiptId;
        try {
            SendReceiptSmsWorkflow workflow = workflowClient.newWorkflowStub(
                    SendReceiptSmsWorkflow.class, workflowId);
            workflow.cancel();
        } catch (Exception e) {
            throw new NotificationPreflightException(
                "WORKFLOW_NOT_FOUND",
                "No in-flight SMS workflow for receipt " + receiptId);
        }

        Map<String, Object> newValue = new HashMap<>();
        newValue.put("workflowId", workflowId);
        newValue.put("cancelledBy", currentUser());
        newValue.put("channel", "SMS");
        auditService.log("Receipt", receiptId.toString(),
                AuditAction.CANCEL, null, newValue);
        log.info("ReceiptService.cancelSms: signalled cancel on workflow {} by {}",
                 workflowId, currentUser());
    }

    private String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    /**
     * Generates the receipt PDF + uploads to MinIO + persists pdfPath.
     * Failure mode: log WARN, leave pdf_path null, do NOT throw — keeps the
     * post() commit intact so a PDF rendering hiccup never loses a receipt.
     */
    private void generateAndPersistPdf(Receipt receipt) {
        byte[] pdf = pdfGenerator.generate(receipt);
        if (pdf == null) {
            // Already logged inside the generator.
            return;
        }
        try {
            String tenantId = TenantContext.getTenantId();
            String path = String.format("receipts/%d/%02d/%s.pdf",
                receipt.getPaymentDate().getYear(),
                receipt.getPaymentDate().getMonthValue(),
                receipt.getId());
            storage.upload(tenantId, path,
                           new ByteArrayInputStream(pdf), "application/pdf");
            receipt.setPdfPath(path);
            receiptRepository.save(receipt);
        } catch (Exception e) {
            log.warn("Failed to upload generated receipt PDF for {}: {}",
                     receipt.getId(), e.getMessage(), e);
        }
    }
}
