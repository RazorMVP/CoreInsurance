package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.event.FacPremiumCededEvent;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.setup.org.ReinsuranceCompanyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
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
 * Slice T1 — upstream contract test for {@link FacPremiumCededEvent}.
 *
 * <p>Guards the contract that Module 12's {@code SubledgerPostingService}
 * consumes when an outward facultative cover is confirmed — drives the
 * cession JE (Dr 5xxx FAC outward premium / Cr 2xxx reinsurer payable for
 * {@code netPremium}, with the commission flowing as a separate ledger
 * movement).
 *
 * <p>Premium-arithmetic invariant worth a dedicated assertion:
 * <strong>{@code netPremium == premiumCeded − commissionAmount}.</strong> The
 * three amount fields all live on the event; if {@code netPremium} drifts
 * out of agreement with {@code premiumCeded} and {@code commissionAmount},
 * the GL would book a cession that doesn't reconcile against the reinsurer's
 * statement of account. The test asserts the relationship explicitly so a
 * refactor that "helpfully" recomputes one field can't silently break it.
 *
 * <p>Pure Mockito unit test, no Spring context, no DB.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FacPremiumCededEventContractTest {

    @Mock private RiFacCoverRepository facCoverRepository;
    @Mock private ReinsuranceCompanyRepository reinsuranceCompanyRepository;
    @Mock private RiNumberService numberService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private FacCoverService service;

    @BeforeEach
    void setUp() {
        service = new FacCoverService(facCoverRepository, reinsuranceCompanyRepository,
                numberService, eventPublisher, Clock.systemUTC());
    }

    @Test
    @DisplayName("confirm() publishes FacPremiumCededEvent with all 10 fields populated and the premium arithmetic preserved")
    void confirm_publishesEventWithCompletePayload() {
        UUID facCoverId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID reinsurerId = UUID.randomUUID();
        BigDecimal premiumCeded = new BigDecimal("500000.00");
        BigDecimal commissionAmount = new BigDecimal("50000.00");
        BigDecimal netPremium = premiumCeded.subtract(commissionAmount);

        RiFacCover pending = pendingFacCover(facCoverId, policyId, reinsurerId,
                premiumCeded, commissionAmount, netPremium, "NGN",
                "FAC-2026-00007", "POL-2026-00102", "Africa Re");

        when(facCoverRepository.findByIdAndDeletedAtIsNull(facCoverId)).thenReturn(Optional.of(pending));
        when(facCoverRepository.save(any(RiFacCover.class))).thenAnswer(inv -> inv.getArgument(0));

        service.confirm(facCoverId);

        ArgumentCaptor<FacPremiumCededEvent> captor = ArgumentCaptor.forClass(FacPremiumCededEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        FacPremiumCededEvent event = captor.getValue();

        assertThat(event.facCoverId()).as("facCoverId").isEqualTo(facCoverId);
        assertThat(event.facReference()).as("facReference").isEqualTo("FAC-2026-00007");
        assertThat(event.policyId()).as("policyId").isEqualTo(policyId);
        assertThat(event.policyNumber()).as("policyNumber").isEqualTo("POL-2026-00102");
        assertThat(event.reinsuranceCompanyId()).as("reinsuranceCompanyId").isEqualTo(reinsurerId);
        assertThat(event.reinsuranceCompanyName()).as("reinsuranceCompanyName").isEqualTo("Africa Re");
        assertThat(event.premiumCeded()).as("premiumCeded").isEqualByComparingTo(premiumCeded);
        assertThat(event.commissionAmount()).as("commissionAmount").isEqualByComparingTo(commissionAmount);
        assertThat(event.netPremiumCeded()).as("netPremiumCeded").isEqualByComparingTo(netPremium);
        assertThat(event.currencyCode()).as("currencyCode").isEqualTo("NGN");

        // Load-bearing arithmetic invariant — GL reconciliation depends on this
        assertThat(event.netPremiumCeded())
                .as("netPremiumCeded MUST equal premiumCeded − commissionAmount (cession arithmetic)")
                .isEqualByComparingTo(event.premiumCeded().subtract(event.commissionAmount()));
    }

    @Test
    @DisplayName("confirm() does NOT publish when FAC cover is not PENDING — status guard regression")
    void confirm_doesNotPublishWhenStatusIsWrong() {
        UUID facCoverId = UUID.randomUUID();
        RiFacCover alreadyConfirmed = RiFacCover.builder().status(FacCoverStatus.CONFIRMED).build();
        alreadyConfirmed.setId(facCoverId);

        when(facCoverRepository.findByIdAndDeletedAtIsNull(facCoverId)).thenReturn(Optional.of(alreadyConfirmed));

        assertThatThrownBy(() -> service.confirm(facCoverId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("PENDING");

        verify(eventPublisher, never()).publishEvent(Mockito.<FacPremiumCededEvent>any());
    }

    private static RiFacCover pendingFacCover(
            UUID id, UUID policyId, UUID reinsurerId,
            BigDecimal premiumCeded, BigDecimal commissionAmount, BigDecimal netPremium,
            String currencyCode, String facReference, String policyNumber, String reinsurerName) {
        RiFacCover f = RiFacCover.builder()
                .status(FacCoverStatus.PENDING)
                .facReference(facReference)
                .policyId(policyId)
                .policyNumber(policyNumber)
                .reinsuranceCompanyId(reinsurerId)
                .reinsuranceCompanyName(reinsurerName)
                .premiumCeded(premiumCeded)
                .commissionAmount(commissionAmount)
                .netPremium(netPremium)
                .currencyCode(currencyCode)
                .build();
        f.setId(id);
        return f;
    }
}
