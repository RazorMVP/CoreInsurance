package com.nubeero.cia.finance;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.finance.pdf.PaymentVoucherPdfGenerator;
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
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final CreditNoteService creditNoteService;
    private final FinanceNumberService numberService;
    private final AuditService auditService;
    private final PaymentVoucherPdfGenerator pdfGenerator;
    private final DocumentStorageService     storage;

    public PaymentService(PaymentRepository paymentRepository,
                          CreditNoteService creditNoteService,
                          FinanceNumberService numberService,
                          AuditService auditService,
                          PaymentVoucherPdfGenerator pdfGenerator,
                          DocumentStorageService storage) {
        this.paymentRepository = paymentRepository;
        this.creditNoteService = creditNoteService;
        this.numberService = numberService;
        this.auditService = auditService;
        this.pdfGenerator = pdfGenerator;
        this.storage = storage;
    }

    public Page<PaymentListItemResponse> findAll(
            Specification<Payment> spec,
            Pageable pageable) {
        var fullSpec = (spec == null
                ? PaymentSpecs.deletedAtIsNull()
                : Specification.where(PaymentSpecs.deletedAtIsNull()).and(spec));
        return paymentRepository.findAll(fullSpec, pageable).map(this::toListItem);
    }

    private PaymentListItemResponse toListItem(Payment p) {
        CreditNote cn = p.getCreditNote();
        // CreditNote denormalises entityType + entityReference at creation time.
        // entityType (FinanceEntityType enum) → beneficiaryType string label.
        // entityReference (e.g. "CLM-001234", "BRK-007") → beneficiaryReference.
        // No Policy/Customer chain traversal needed — same pattern as ReceiptService.
        String beneficiaryType = null;
        String beneficiaryReference = null;
        if (cn != null) {
            beneficiaryType = cn.getEntityType() != null ? cn.getEntityType().name() : null;
            beneficiaryReference = cn.getEntityReference();
        }
        return new PaymentListItemResponse(
                p.getId(),
                p.getPaymentNumber(),
                cn != null ? cn.getId() : null,
                cn != null ? cn.getCreditNoteNumber() : null,
                beneficiaryType,
                beneficiaryReference,
                p.getAmount(),
                p.getPaymentMethod(),
                p.getPaymentDate(),
                p.getStatus(),
                p.getReversedAt(),
                p.getReversedBy(),
                p.getReversalReason(),
                p.getCreatedAt(),
                p.getPdfPath());
    }

    public Page<Payment> findByCreditNote(UUID creditNoteId, Pageable pageable) {
        return paymentRepository.findAllByCreditNote_IdAndDeletedAtIsNull(creditNoteId, pageable);
    }

    public Payment findOrThrow(UUID id) {
        return paymentRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", id));
    }

    @Transactional
    public Payment post(UUID creditNoteId, BigDecimal amount, LocalDate paymentDate,
                        PaymentMethod paymentMethod, UUID bankId, String bankName,
                        String bankAccountName, String bankAccountNumber, String narration) {
        CreditNote cn = creditNoteService.findOrThrow(creditNoteId);
        if (cn.getStatus() == CreditNoteStatus.SETTLED
                || cn.getStatus() == CreditNoteStatus.CANCELLED) {
            throw new IllegalStateException(
                    "Cannot post payment against a " + cn.getStatus() + " credit note");
        }

        Payment payment = new Payment();
        payment.setPaymentNumber(numberService.nextPaymentNumber());
        payment.setCreditNote(cn);
        payment.setAmount(amount);
        payment.setPaymentDate(paymentDate);
        payment.setPaymentMethod(paymentMethod);
        payment.setBankId(bankId);
        payment.setBankName(bankName);
        payment.setBankAccountName(bankAccountName);
        payment.setBankAccountNumber(bankAccountNumber);
        payment.setNarration(narration);
        payment.setPostedBy(currentUser());
        payment.setStatus(TransactionStatus.POSTED);
        payment.setCreatedBy(currentUser());
        Payment saved = paymentRepository.save(payment);

        BigDecimal newPaid = sumPostedPayments(creditNoteId);
        creditNoteService.recalculateStatus(creditNoteId, newPaid);

        generateAndPersistPdf(saved);
        return saved;
    }

    @Transactional
    public void reverse(UUID paymentId, String reversalReason) {
        Payment payment = findOrThrow(paymentId);
        if (payment.getStatus() == TransactionStatus.REVERSED) {
            throw new IllegalStateException("Payment is already reversed");
        }

        ReverseSnapshot oldValue = new ReverseSnapshot(
                payment.getStatus(), null, null, null);

        payment.setStatus(TransactionStatus.REVERSED);
        payment.setReversalReason(reversalReason);
        payment.setReversedAt(Instant.now());
        payment.setReversedBy(currentUser());
        Payment saved = paymentRepository.save(payment);

        ReverseSnapshot newValue = new ReverseSnapshot(
                saved.getStatus(),
                saved.getReversedAt(),
                saved.getReversedBy(),
                saved.getReversalReason());

        auditService.log("Payment", saved.getId().toString(),
                AuditAction.REVERSE, oldValue, newValue);

        UUID creditNoteId = saved.getCreditNote().getId();
        BigDecimal newPaid = sumPostedPayments(creditNoteId);
        creditNoteService.recalculateStatus(creditNoteId, newPaid);
    }

    private record ReverseSnapshot(
            TransactionStatus status,
            Instant reversedAt,
            String reversedBy,
            String reversalReason) {}

    private BigDecimal sumPostedPayments(UUID creditNoteId) {
        List<Payment> posted = paymentRepository
                .findAllByCreditNote_IdAndStatusAndDeletedAtIsNull(
                        creditNoteId, TransactionStatus.POSTED);
        return posted.stream()
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    /**
     * Generates the payment voucher PDF + uploads to MinIO + persists pdfPath.
     * Failure mode: log WARN, leave pdf_path null, do NOT throw — keeps the
     * post() commit intact so a PDF rendering hiccup never loses a payment.
     */
    private void generateAndPersistPdf(Payment payment) {
        byte[] pdf = pdfGenerator.generate(payment);
        if (pdf == null) {
            // Already logged inside the generator.
            return;
        }
        try {
            String tenantId = TenantContext.getTenantId();
            String path = String.format("payments/%d/%02d/%s.pdf",
                payment.getPaymentDate().getYear(),
                payment.getPaymentDate().getMonthValue(),
                payment.getId());
            storage.upload(tenantId, path,
                           new ByteArrayInputStream(pdf), "application/pdf");
            payment.setPdfPath(path);
            paymentRepository.save(payment);
        } catch (Exception e) {
            log.warn("Failed to upload generated payment voucher PDF for {}: {}",
                     payment.getId(), e.getMessage(), e);
        }
    }
}
