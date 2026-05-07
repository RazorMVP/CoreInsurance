package com.nubeero.cia.finance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreditNoteServiceTest {

    private CreditNoteRepository creditNoteRepository;
    private CreditNoteService service;

    @BeforeEach
    void setUp() {
        creditNoteRepository = mock(CreditNoteRepository.class);
        service = new CreditNoteService(
                creditNoteRepository,
                mock(FinanceNumberService.class));
    }

    @Test
    void recalculateStatusKeepsUnpaidCreditNoteOutstanding() {
        UUID creditNoteId = UUID.randomUUID();
        CreditNote creditNote = creditNote(creditNoteId);
        when(creditNoteRepository.findByIdAndDeletedAtIsNull(creditNoteId))
                .thenReturn(Optional.of(creditNote));

        service.recalculateStatus(creditNoteId, BigDecimal.ZERO);

        assertThat(creditNote.getPaidAmount()).isEqualByComparingTo("0.00");
        assertThat(creditNote.getStatus()).isEqualTo(CreditNoteStatus.OUTSTANDING);
        verify(creditNoteRepository).save(creditNote);
    }

    @Test
    void recalculateStatusMarksPartiallyPaidCreditNotePartial() {
        UUID creditNoteId = UUID.randomUUID();
        CreditNote creditNote = creditNote(creditNoteId);
        when(creditNoteRepository.findByIdAndDeletedAtIsNull(creditNoteId))
                .thenReturn(Optional.of(creditNote));

        service.recalculateStatus(creditNoteId, new BigDecimal("400.00"));

        assertThat(creditNote.getPaidAmount()).isEqualByComparingTo("400.00");
        assertThat(creditNote.getStatus()).isEqualTo(CreditNoteStatus.PARTIAL);
        verify(creditNoteRepository).save(creditNote);
    }

    @Test
    void recalculateStatusMarksFullyPaidCreditNoteSettled() {
        UUID creditNoteId = UUID.randomUUID();
        CreditNote creditNote = creditNote(creditNoteId);
        when(creditNoteRepository.findByIdAndDeletedAtIsNull(creditNoteId))
                .thenReturn(Optional.of(creditNote));

        service.recalculateStatus(creditNoteId, new BigDecimal("1000.00"));

        assertThat(creditNote.getPaidAmount()).isEqualByComparingTo("1000.00");
        assertThat(creditNote.getStatus()).isEqualTo(CreditNoteStatus.SETTLED);
        verify(creditNoteRepository).save(creditNote);
    }

    private CreditNote creditNote(UUID id) {
        CreditNote creditNote = new CreditNote();
        creditNote.setId(id);
        creditNote.setTotalAmount(new BigDecimal("1000.00"));
        creditNote.setPaidAmount(BigDecimal.ZERO);
        creditNote.setStatus(CreditNoteStatus.OUTSTANDING);
        return creditNote;
    }
}
