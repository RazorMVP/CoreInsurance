package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.event.FacDerecognisedEvent;
import com.nubeero.cia.common.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FacCoverService}'s outward-cancel precondition.
 *
 * <p>Final-review Minor 2: the outward cancel path must reject a second
 * cancellation symmetrically with the inward {@code RiFacInwardService.cancel}
 * (which throws {@code INVALID_FAC_INWARD_STATUS} — see {@code
 * RiFacInwardServiceIT.cancel_rejectsWhenAlreadyCancelled}). Without the guard,
 * a double-cancel would re-emit {@link FacDerecognisedEvent} and re-save a
 * {@code CANCELLED} row. This proves the guard short-circuits BEFORE the save
 * and the event publish.
 */
@ExtendWith(MockitoExtension.class)
class FacCoverServiceTest {

    @Mock private RiFacCoverRepository facCoverRepository;
    @Mock private com.nubeero.cia.setup.org.ReinsuranceCompanyRepository reinsuranceCompanyRepository;
    @Mock private RiNumberService numberService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private FacCoverService service() {
        return new FacCoverService(facCoverRepository, reinsuranceCompanyRepository,
                numberService, eventPublisher, Clock.systemUTC());
    }

    @Test
    void cancel_rejectsWhenAlreadyCancelled_noEventReemitted_noResave() {
        UUID id = UUID.randomUUID();
        RiFacCover alreadyCancelled = RiFacCover.builder()
                .facReference("FAC-OUT-DBLCANCEL")
                .status(FacCoverStatus.CANCELLED)
                .build();
        when(facCoverRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(alreadyCancelled));

        assertThatThrownBy(() -> service().cancel(id, "second cancellation"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already cancelled");

        // The guard must run BEFORE any side effect — no re-save, no re-published derecognition event.
        verify(facCoverRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(FacDerecognisedEvent.class));
    }

    @Test
    void cancel_onConfirmedCover_setsCancelledAndPublishesDerecognitionOnce() {
        UUID id = UUID.randomUUID();
        RiFacCover confirmed = RiFacCover.builder()
                .facReference("FAC-OUT-OK")
                .status(FacCoverStatus.CONFIRMED)
                .build();
        when(facCoverRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(confirmed));
        when(facCoverRepository.save(any(RiFacCover.class))).thenAnswer(inv -> inv.getArgument(0));

        RiFacCover cancelled = service().cancel(id, "reinsurer withdrew");

        assertThat(cancelled.getStatus()).isEqualTo(FacCoverStatus.CANCELLED);
        assertThat(cancelled.getCancellationReason()).isEqualTo("reinsurer withdrew");
        verify(eventPublisher).publishEvent(any(FacDerecognisedEvent.class));
    }
}
