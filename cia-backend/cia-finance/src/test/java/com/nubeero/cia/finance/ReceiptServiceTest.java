package com.nubeero.cia.finance;

import com.nubeero.cia.common.exception.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                mock(FinanceNumberService.class));
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
}
