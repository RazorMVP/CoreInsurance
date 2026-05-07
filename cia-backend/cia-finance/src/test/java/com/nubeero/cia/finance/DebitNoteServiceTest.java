package com.nubeero.cia.finance;

import com.nubeero.cia.common.event.PolicyApprovedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DebitNoteServiceTest {

    private DebitNoteRepository debitNoteRepository;
    private DebitNoteService service;

    @BeforeEach
    void setUp() {
        debitNoteRepository = mock(DebitNoteRepository.class);
        FinanceNumberService numberService = mock(FinanceNumberService.class);
        when(numberService.nextDebitNoteNumber()).thenReturn("DN-2026-000001");
        service = new DebitNoteService(debitNoteRepository, numberService);
    }

    @Test
    void createForPolicyUsesApprovedPolicyNetPremiumAsReceivable() {
        when(debitNoteRepository.save(any(DebitNote.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UUID policyId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        PolicyApprovedEvent event = new PolicyApprovedEvent(
                policyId,
                "POL-ISSUE-001",
                customerId,
                "Acme Insurance Buyer",
                null,
                null,
                "Fire Standard",
                new BigDecimal("27500.00"),
                "NGN",
                LocalDate.of(2027, 1, 1),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("1000000.00"),
                LocalDate.of(2026, 1, 1));

        DebitNote debitNote = service.createForPolicy(event);

        assertThat(debitNote.getDebitNoteNumber()).isEqualTo("DN-2026-000001");
        assertThat(debitNote.getEntityType()).isEqualTo(FinanceEntityType.POLICY);
        assertThat(debitNote.getEntityId()).isEqualTo(policyId);
        assertThat(debitNote.getEntityReference()).isEqualTo("POL-ISSUE-001");
        assertThat(debitNote.getCustomerId()).isEqualTo(customerId);
        assertThat(debitNote.getCustomerName()).isEqualTo("Acme Insurance Buyer");
        assertThat(debitNote.getProductName()).isEqualTo("Fire Standard");
        assertThat(debitNote.getAmount()).isEqualByComparingTo("27500.00");
        assertThat(debitNote.getTaxAmount()).isEqualByComparingTo("0.00");
        assertThat(debitNote.getTotalAmount()).isEqualByComparingTo("27500.00");
        assertThat(debitNote.getPaidAmount()).isEqualByComparingTo("0.00");
        assertThat(debitNote.getCurrencyCode()).isEqualTo("NGN");
        assertThat(debitNote.getDueDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(debitNote.getStatus()).isEqualTo(DebitNoteStatus.OUTSTANDING);
    }

    @Test
    void recalculateStatusKeepsUnpaidDebitNoteOutstanding() {
        UUID debitNoteId = UUID.randomUUID();
        DebitNote debitNote = debitNote(debitNoteId);
        when(debitNoteRepository.findByIdAndDeletedAtIsNull(debitNoteId))
                .thenReturn(Optional.of(debitNote));

        service.recalculateStatus(debitNoteId, BigDecimal.ZERO);

        assertThat(debitNote.getPaidAmount()).isEqualByComparingTo("0.00");
        assertThat(debitNote.getStatus()).isEqualTo(DebitNoteStatus.OUTSTANDING);
        verify(debitNoteRepository).save(debitNote);
    }

    @Test
    void recalculateStatusMarksPartiallyPaidDebitNotePartial() {
        UUID debitNoteId = UUID.randomUUID();
        DebitNote debitNote = debitNote(debitNoteId);
        when(debitNoteRepository.findByIdAndDeletedAtIsNull(debitNoteId))
                .thenReturn(Optional.of(debitNote));

        service.recalculateStatus(debitNoteId, new BigDecimal("400.00"));

        assertThat(debitNote.getPaidAmount()).isEqualByComparingTo("400.00");
        assertThat(debitNote.getStatus()).isEqualTo(DebitNoteStatus.PARTIAL);
        verify(debitNoteRepository).save(debitNote);
    }

    @Test
    void recalculateStatusMarksFullyPaidDebitNoteSettled() {
        UUID debitNoteId = UUID.randomUUID();
        DebitNote debitNote = debitNote(debitNoteId);
        when(debitNoteRepository.findByIdAndDeletedAtIsNull(debitNoteId))
                .thenReturn(Optional.of(debitNote));

        service.recalculateStatus(debitNoteId, new BigDecimal("1000.00"));

        assertThat(debitNote.getPaidAmount()).isEqualByComparingTo("1000.00");
        assertThat(debitNote.getStatus()).isEqualTo(DebitNoteStatus.SETTLED);
        verify(debitNoteRepository).save(debitNote);
    }

    private DebitNote debitNote(UUID id) {
        DebitNote debitNote = new DebitNote();
        debitNote.setId(id);
        debitNote.setTotalAmount(new BigDecimal("1000.00"));
        debitNote.setPaidAmount(BigDecimal.ZERO);
        debitNote.setStatus(DebitNoteStatus.OUTSTANDING);
        return debitNote;
    }
}
