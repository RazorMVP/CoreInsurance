package com.nubeero.cia.finance;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.finance.pdf.ReceiptPdfGenerator;
import com.nubeero.cia.storage.DocumentStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

    public ReceiptService(ReceiptRepository receiptRepository,
                          DebitNoteService debitNoteService,
                          FinanceNumberService numberService,
                          AuditService auditService,
                          ReceiptPdfGenerator pdfGenerator,
                          DocumentStorageService storage) {
        this.receiptRepository = receiptRepository;
        this.debitNoteService = debitNoteService;
        this.numberService = numberService;
        this.auditService = auditService;
        this.pdfGenerator = pdfGenerator;
        this.storage = storage;
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
                r.getPdfPath());
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
