package com.nubeero.cia.finance;

import com.nubeero.cia.common.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final CreditNoteService creditNoteService;
    private final FinanceNumberService numberService;

    public PaymentService(PaymentRepository paymentRepository,
                          CreditNoteService creditNoteService,
                          FinanceNumberService numberService) {
        this.paymentRepository = paymentRepository;
        this.creditNoteService = creditNoteService;
        this.numberService = numberService;
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

        return saved;
    }

    @Transactional
    public void reverse(UUID paymentId, String reversalReason) {
        Payment payment = findOrThrow(paymentId);
        if (payment.getStatus() == TransactionStatus.REVERSED) {
            throw new IllegalStateException("Payment is already reversed");
        }
        payment.setStatus(TransactionStatus.REVERSED);
        payment.setReversalReason(reversalReason);
        payment.setReversedAt(Instant.now());
        payment.setReversedBy(currentUser());
        paymentRepository.save(payment);

        UUID creditNoteId = payment.getCreditNote().getId();
        BigDecimal newPaid = sumPostedPayments(creditNoteId);
        creditNoteService.recalculateStatus(creditNoteId, newPaid);
    }

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
}
