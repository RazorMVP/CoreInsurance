package com.nubeero.cia.finance;

import com.nubeero.cia.common.event.EndorsementApprovedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class EndorsementApprovedEventListenerTest {

    private DebitNoteService debitNoteService;
    private CreditNoteService creditNoteService;
    private EndorsementApprovedEventListener listener;

    @BeforeEach
    void setUp() {
        debitNoteService = mock(DebitNoteService.class);
        creditNoteService = mock(CreditNoteService.class);
        listener = new EndorsementApprovedEventListener(debitNoteService, creditNoteService);
    }

    @Test
    void positiveAdjustmentCreatesDebitNote() {
        EndorsementApprovedEvent event = event(new BigDecimal("2000.00"));

        listener.onEndorsementApproved(event);

        verify(debitNoteService).createForEndorsement(event);
        verify(creditNoteService, never()).create(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void negativeAdjustmentCreatesCreditNoteForAbsoluteReturnPremium() {
        EndorsementApprovedEvent event = event(new BigDecimal("-2000.00"));

        listener.onEndorsementApproved(event);

        verify(debitNoteService, never()).createForEndorsement(any());
        verify(creditNoteService).create(
                eq(FinanceEntityType.ENDORSEMENT),
                eq(event.endorsementId()),
                eq(event.endorsementNumber()),
                eq(event.customerId()),
                eq(event.customerName()),
                eq("Return premium for endorsement END-0001 on policy POL-0001"),
                eq(new BigDecimal("2000.00")),
                eq(BigDecimal.ZERO),
                eq("NGN"));
    }

    @Test
    void zeroAdjustmentCreatesNoFinancialDocument() {
        listener.onEndorsementApproved(event(BigDecimal.ZERO));

        verify(debitNoteService, never()).createForEndorsement(any());
        verify(creditNoteService, never()).create(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private EndorsementApprovedEvent event(BigDecimal premiumAdjustment) {
        return new EndorsementApprovedEvent(
                UUID.randomUUID(),
                "END-0001",
                UUID.randomUUID(),
                "POL-0001",
                UUID.randomUUID(),
                "Acme Insurance Buyer",
                null,
                null,
                "Fire Standard",
                premiumAdjustment,
                "NGN");
    }
}
