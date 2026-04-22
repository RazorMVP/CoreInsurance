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
public class ReceiptService {

    private final ReceiptRepository receiptRepository;
    private final DebitNoteService debitNoteService;
    private final FinanceNumberService numberService;

    public ReceiptService(ReceiptRepository receiptRepository,
                          DebitNoteService debitNoteService,
                          FinanceNumberService numberService) {
        this.receiptRepository = receiptRepository;
        this.debitNoteService = debitNoteService;
        this.numberService = numberService;
    }

    public Page<Receipt> findByDebitNote(UUID debitNoteId, Pageable pageable) {
        return receiptRepository.findAllByDebitNote_IdAndDeletedAtIsNull(debitNoteId, pageable);
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

        return saved;
    }

    @Transactional
    public void reverse(UUID receiptId, String reversalReason) {
        Receipt receipt = findOrThrow(receiptId);
        if (receipt.getStatus() == TransactionStatus.REVERSED) {
            throw new IllegalStateException("Receipt is already reversed");
        }
        receipt.setStatus(TransactionStatus.REVERSED);
        receipt.setReversalReason(reversalReason);
        receipt.setReversedAt(Instant.now());
        receipt.setReversedBy(currentUser());
        receiptRepository.save(receipt);

        UUID debitNoteId = receipt.getDebitNote().getId();
        BigDecimal newPaid = sumPostedReceipts(debitNoteId);
        debitNoteService.recalculateStatus(debitNoteId, newPaid);
    }

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
}
