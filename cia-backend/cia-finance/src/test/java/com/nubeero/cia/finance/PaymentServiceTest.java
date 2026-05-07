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

class PaymentServiceTest {

    private PaymentRepository paymentRepository;
    private CreditNoteService creditNoteService;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        creditNoteService = mock(CreditNoteService.class);
        service = new PaymentService(
                paymentRepository,
                creditNoteService,
                mock(FinanceNumberService.class));
    }

    @Test
    void postRejectsPaymentAboveOutstandingCreditNoteBalance() {
        UUID creditNoteId = UUID.randomUUID();
        CreditNote creditNote = new CreditNote();
        creditNote.setId(creditNoteId);
        creditNote.setStatus(CreditNoteStatus.PARTIAL);
        creditNote.setTotalAmount(new BigDecimal("1000.00"));

        Payment postedPayment = new Payment();
        postedPayment.setAmount(new BigDecimal("600.00"));

        when(creditNoteService.findForPosting(creditNoteId)).thenReturn(creditNote);
        when(paymentRepository.findAllByCreditNote_IdAndStatusAndDeletedAtIsNull(
                creditNoteId, TransactionStatus.POSTED)).thenReturn(List.of(postedPayment));

        assertThatThrownBy(() -> service.post(
                creditNoteId,
                new BigDecimal("500.00"),
                LocalDate.of(2026, 1, 15),
                PaymentMethod.BANK_TRANSFER,
                null,
                null,
                null,
                null,
                "Over payment"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Payment amount cannot exceed outstanding credit note balance");

        verify(paymentRepository, never()).save(org.mockito.ArgumentMatchers.any(Payment.class));
        verify(creditNoteService, never()).recalculateStatus(
                org.mockito.ArgumentMatchers.eq(creditNoteId),
                org.mockito.ArgumentMatchers.any(BigDecimal.class));
    }
}
