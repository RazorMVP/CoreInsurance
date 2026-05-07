package com.nubeero.cia.finance;

import com.nubeero.cia.common.exception.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReceiptServiceTest {

    private ReceiptRepository receiptRepository;
    private DebitNoteService debitNoteService;
    private ReceiptService service;

    @BeforeEach
    void setUp() {
        receiptRepository = mock(ReceiptRepository.class);
        debitNoteService = mock(DebitNoteService.class);
        service = new ReceiptService(
                receiptRepository,
                debitNoteService,
                numberService());
    }

    @Test
    void postRejectsReceiptAboveOutstandingDebitNoteBalance() {
        UUID debitNoteId = UUID.randomUUID();
        DebitNote debitNote = new DebitNote();
        debitNote.setId(debitNoteId);
        debitNote.setStatus(DebitNoteStatus.PARTIAL);
        debitNote.setTotalAmount(new BigDecimal("1000.00"));

        Receipt postedReceipt = new Receipt();
        postedReceipt.setAmount(new BigDecimal("600.00"));

        when(debitNoteService.findForPosting(debitNoteId)).thenReturn(debitNote);
        when(receiptRepository.findAllByDebitNote_IdAndStatusAndDeletedAtIsNull(
                debitNoteId, TransactionStatus.POSTED)).thenReturn(List.of(postedReceipt));

        assertThatThrownBy(() -> service.post(
                debitNoteId,
                new BigDecimal("500.00"),
                LocalDate.of(2026, 1, 15),
                PaymentMethod.BANK_TRANSFER,
                null,
                null,
                null,
                "Over receipt"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Receipt amount cannot exceed outstanding debit note balance");

        verify(receiptRepository, never()).save(org.mockito.ArgumentMatchers.any(Receipt.class));
        verify(debitNoteService, never()).recalculateStatus(
                org.mockito.ArgumentMatchers.eq(debitNoteId),
                org.mockito.ArgumentMatchers.any(BigDecimal.class));
    }

    @Test
    void postPartialReceiptRecalculatesPartialOutstandingBalance() {
        UUID debitNoteId = UUID.randomUUID();
        DebitNote debitNote = debitNote(debitNoteId, DebitNoteStatus.OUTSTANDING);

        when(debitNoteService.findForPosting(debitNoteId)).thenReturn(debitNote);
        when(receiptRepository.findAllByDebitNote_IdAndStatusAndDeletedAtIsNull(
                debitNoteId, TransactionStatus.POSTED)).thenReturn(List.of());
        when(receiptRepository.save(any(Receipt.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Receipt receipt = service.post(
                debitNoteId,
                new BigDecimal("400.00"),
                LocalDate.of(2026, 1, 15),
                PaymentMethod.BANK_TRANSFER,
                null,
                null,
                null,
                "Partial receipt");

        assertThat(receipt.getReceiptNumber()).isEqualTo("RCT-2026-000001");
        assertThat(receipt.getAmount()).isEqualByComparingTo("400.00");
        assertThat(receipt.getStatus()).isEqualTo(TransactionStatus.POSTED);
        verify(debitNoteService).recalculateStatus(debitNoteId, new BigDecimal("400.00"));
    }

    @Test
    void postFinalReceiptRecalculatesFullyPaidBalance() {
        UUID debitNoteId = UUID.randomUUID();
        DebitNote debitNote = debitNote(debitNoteId, DebitNoteStatus.PARTIAL);
        Receipt postedReceipt = receipt(debitNote, new BigDecimal("400.00"), TransactionStatus.POSTED);

        when(debitNoteService.findForPosting(debitNoteId)).thenReturn(debitNote);
        when(receiptRepository.findAllByDebitNote_IdAndStatusAndDeletedAtIsNull(
                debitNoteId, TransactionStatus.POSTED)).thenReturn(List.of(postedReceipt));
        when(receiptRepository.save(any(Receipt.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.post(
                debitNoteId,
                new BigDecimal("600.00"),
                LocalDate.of(2026, 1, 15),
                PaymentMethod.BANK_TRANSFER,
                null,
                null,
                null,
                "Final receipt");

        verify(debitNoteService).recalculateStatus(debitNoteId, new BigDecimal("1000.00"));
    }

    @Test
    void reverseRecalculatesOutstandingFromRemainingPostedReceipts() {
        UUID receiptId = UUID.randomUUID();
        UUID debitNoteId = UUID.randomUUID();
        DebitNote debitNote = debitNote(debitNoteId, DebitNoteStatus.SETTLED);
        Receipt receiptToReverse = receipt(debitNote, new BigDecimal("600.00"), TransactionStatus.POSTED);
        receiptToReverse.setId(receiptId);
        Receipt remainingReceipt = receipt(debitNote, new BigDecimal("400.00"), TransactionStatus.POSTED);

        when(receiptRepository.findByIdAndDeletedAtIsNull(receiptId))
                .thenReturn(Optional.of(receiptToReverse));
        when(receiptRepository.findAllByDebitNote_IdAndStatusAndDeletedAtIsNull(
                debitNoteId, TransactionStatus.POSTED)).thenReturn(List.of(remainingReceipt));

        service.reverse(receiptId, "Duplicate receipt");

        assertThat(receiptToReverse.getStatus()).isEqualTo(TransactionStatus.REVERSED);
        assertThat(receiptToReverse.getReversalReason()).isEqualTo("Duplicate receipt");
        assertThat(receiptToReverse.getReversedAt()).isNotNull();
        verify(receiptRepository).save(receiptToReverse);
        verify(debitNoteService).recalculateStatus(debitNoteId, new BigDecimal("400.00"));
    }

    private FinanceNumberService numberService() {
        FinanceNumberService numberService = mock(FinanceNumberService.class);
        when(numberService.nextReceiptNumber()).thenReturn("RCT-2026-000001");
        return numberService;
    }

    private DebitNote debitNote(UUID id, DebitNoteStatus status) {
        DebitNote debitNote = new DebitNote();
        debitNote.setId(id);
        debitNote.setStatus(status);
        debitNote.setTotalAmount(new BigDecimal("1000.00"));
        return debitNote;
    }

    private Receipt receipt(DebitNote debitNote, BigDecimal amount, TransactionStatus status) {
        Receipt receipt = new Receipt();
        receipt.setDebitNote(debitNote);
        receipt.setAmount(amount);
        receipt.setStatus(status);
        return receipt;
    }
}
