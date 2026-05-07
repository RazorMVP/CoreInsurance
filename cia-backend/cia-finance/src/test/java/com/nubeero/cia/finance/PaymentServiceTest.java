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
                numberService());
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

    @Test
    void postPartialPaymentRecalculatesPartialOutstandingBalance() {
        UUID creditNoteId = UUID.randomUUID();
        CreditNote creditNote = creditNote(creditNoteId, CreditNoteStatus.OUTSTANDING);

        when(creditNoteService.findForPosting(creditNoteId)).thenReturn(creditNote);
        when(paymentRepository.findAllByCreditNote_IdAndStatusAndDeletedAtIsNull(
                creditNoteId, TransactionStatus.POSTED)).thenReturn(List.of());
        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Payment payment = service.post(
                creditNoteId,
                new BigDecimal("400.00"),
                LocalDate.of(2026, 1, 15),
                PaymentMethod.BANK_TRANSFER,
                null,
                null,
                null,
                null,
                "Partial payment");

        assertThat(payment.getPaymentNumber()).isEqualTo("PAY-2026-000001");
        assertThat(payment.getAmount()).isEqualByComparingTo("400.00");
        assertThat(payment.getStatus()).isEqualTo(TransactionStatus.POSTED);
        verify(creditNoteService).recalculateStatus(creditNoteId, new BigDecimal("400.00"));
    }

    @Test
    void postFinalPaymentRecalculatesFullyPaidBalance() {
        UUID creditNoteId = UUID.randomUUID();
        CreditNote creditNote = creditNote(creditNoteId, CreditNoteStatus.PARTIAL);
        Payment postedPayment = payment(creditNote, new BigDecimal("400.00"), TransactionStatus.POSTED);

        when(creditNoteService.findForPosting(creditNoteId)).thenReturn(creditNote);
        when(paymentRepository.findAllByCreditNote_IdAndStatusAndDeletedAtIsNull(
                creditNoteId, TransactionStatus.POSTED)).thenReturn(List.of(postedPayment));
        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.post(
                creditNoteId,
                new BigDecimal("600.00"),
                LocalDate.of(2026, 1, 15),
                PaymentMethod.BANK_TRANSFER,
                null,
                null,
                null,
                null,
                "Final payment");

        verify(creditNoteService).recalculateStatus(creditNoteId, new BigDecimal("1000.00"));
    }

    @Test
    void reverseRecalculatesOutstandingFromRemainingPostedPayments() {
        UUID paymentId = UUID.randomUUID();
        UUID creditNoteId = UUID.randomUUID();
        CreditNote creditNote = creditNote(creditNoteId, CreditNoteStatus.SETTLED);
        Payment paymentToReverse = payment(creditNote, new BigDecimal("600.00"), TransactionStatus.POSTED);
        paymentToReverse.setId(paymentId);
        Payment remainingPayment = payment(creditNote, new BigDecimal("400.00"), TransactionStatus.POSTED);

        when(paymentRepository.findByIdAndDeletedAtIsNull(paymentId))
                .thenReturn(Optional.of(paymentToReverse));
        when(paymentRepository.findAllByCreditNote_IdAndStatusAndDeletedAtIsNull(
                creditNoteId, TransactionStatus.POSTED)).thenReturn(List.of(remainingPayment));

        service.reverse(paymentId, "Duplicate payment");

        assertThat(paymentToReverse.getStatus()).isEqualTo(TransactionStatus.REVERSED);
        assertThat(paymentToReverse.getReversalReason()).isEqualTo("Duplicate payment");
        assertThat(paymentToReverse.getReversedAt()).isNotNull();
        verify(paymentRepository).save(paymentToReverse);
        verify(creditNoteService).recalculateStatus(creditNoteId, new BigDecimal("400.00"));
    }

    private FinanceNumberService numberService() {
        FinanceNumberService numberService = mock(FinanceNumberService.class);
        when(numberService.nextPaymentNumber()).thenReturn("PAY-2026-000001");
        return numberService;
    }

    private CreditNote creditNote(UUID id, CreditNoteStatus status) {
        CreditNote creditNote = new CreditNote();
        creditNote.setId(id);
        creditNote.setStatus(status);
        creditNote.setTotalAmount(new BigDecimal("1000.00"));
        return creditNote;
    }

    private Payment payment(CreditNote creditNote, BigDecimal amount, TransactionStatus status) {
        Payment payment = new Payment();
        payment.setCreditNote(creditNote);
        payment.setAmount(amount);
        payment.setStatus(status);
        return payment;
    }
}
