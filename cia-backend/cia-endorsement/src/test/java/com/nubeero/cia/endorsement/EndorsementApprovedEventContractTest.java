package com.nubeero.cia.endorsement;

import com.nubeero.cia.common.event.EndorsementApprovedEvent;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.documents.DocumentGenerationService;
import com.nubeero.cia.policy.PolicyRepository;
import io.temporal.client.WorkflowClient;
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
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Slice T1 — upstream contract test for {@link EndorsementApprovedEvent}.
 *
 * <p>Guards the contract that Module 12's {@code SubledgerPostingService}
 * consumes when posting the endorsement JE (Dr/Cr 2110 depending on
 * {@code premiumAdjustment} sign): <strong>{@link EndorsementService#approve}
 * publishes an {@link EndorsementApprovedEvent} populated from the saved
 * endorsement on the happy path, and publishes nothing when the status guard
 * rejects the call.</strong>
 *
 * <p>The endorsement's {@code premiumAdjustment} sign drives whether the GL
 * sees a debit (positive — additional premium) or credit (negative — premium
 * refund) movement. Both signs are exercised — a regression that swaps the
 * sign on the event vs the entity would silently flip every endorsement JE
 * after the next deploy.
 *
 * <p>Pure Mockito unit test, no Spring context, no DB.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EndorsementApprovedEventContractTest {

    @Mock private EndorsementRepository endorsementRepository;
    @Mock private EndorsementNumberService numberService;
    @Mock private PolicyRepository policyRepository;
    @Mock private WorkflowClient workflowClient;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private DocumentGenerationService documentGenerationService;

    private EndorsementService service;

    @BeforeEach
    void setUp() {
        service = new EndorsementService(
                endorsementRepository, numberService, policyRepository,
                workflowClient, eventPublisher, documentGenerationService);
    }

    @Test
    @DisplayName("approve() publishes EndorsementApprovedEvent with every field populated from the saved endorsement")
    void approve_publishesEventWithCompletePayload() {
        UUID endorsementId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID brokerId = UUID.randomUUID();
        BigDecimal premiumAdjustment = new BigDecimal("45000.00");

        Endorsement pending = pendingEndorsement(endorsementId, policyId, customerId, brokerId,
                premiumAdjustment, "NGN", "END-2026-00007", "POL-2026-00102",
                "Acme Logistics Ltd", "Lagos Brokers", "Marine Cargo Open Cover");

        when(endorsementRepository.findByIdAndDeletedAtIsNull(endorsementId)).thenReturn(Optional.of(pending));
        when(endorsementRepository.save(any(Endorsement.class))).thenAnswer(inv -> inv.getArgument(0));

        service.approve(endorsementId, "ok");

        ArgumentCaptor<EndorsementApprovedEvent> captor = ArgumentCaptor.forClass(EndorsementApprovedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        EndorsementApprovedEvent event = captor.getValue();

        assertThat(event.endorsementId()).as("endorsementId").isEqualTo(endorsementId);
        assertThat(event.endorsementNumber()).as("endorsementNumber").isEqualTo("END-2026-00007");
        assertThat(event.policyId()).as("policyId").isEqualTo(policyId);
        assertThat(event.policyNumber()).as("policyNumber").isEqualTo("POL-2026-00102");
        assertThat(event.customerId()).as("customerId").isEqualTo(customerId);
        assertThat(event.customerName()).as("customerName").isEqualTo("Acme Logistics Ltd");
        assertThat(event.brokerId()).as("brokerId").isEqualTo(brokerId);
        assertThat(event.brokerName()).as("brokerName").isEqualTo("Lagos Brokers");
        assertThat(event.productName()).as("productName").isEqualTo("Marine Cargo Open Cover");
        assertThat(event.premiumAdjustment())
                .as("premiumAdjustment — positive sign drives a Dr to 2110 in SubledgerPostingService")
                .isEqualByComparingTo(premiumAdjustment);
        assertThat(event.currencyCode()).as("currencyCode").isEqualTo("NGN");
    }

    @Test
    @DisplayName("approve() preserves the negative sign of a refund endorsement — JE direction must not flip")
    void approve_preservesNegativePremiumAdjustment() {
        UUID endorsementId = UUID.randomUUID();
        BigDecimal refundAdjustment = new BigDecimal("-12500.00");

        Endorsement pending = pendingEndorsement(endorsementId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                refundAdjustment, "NGN", "END-2026-00099", "POL-2026-00050",
                "Refund Customer", "Refund Broker", "Refund Product");

        when(endorsementRepository.findByIdAndDeletedAtIsNull(endorsementId)).thenReturn(Optional.of(pending));
        when(endorsementRepository.save(any(Endorsement.class))).thenAnswer(inv -> inv.getArgument(0));

        service.approve(endorsementId, "refund");

        ArgumentCaptor<EndorsementApprovedEvent> captor = ArgumentCaptor.forClass(EndorsementApprovedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        assertThat(captor.getValue().premiumAdjustment())
                .as("refund endorsement must propagate the negative sign to the JE")
                .isEqualByComparingTo(refundAdjustment)
                .isNegative();
    }

    @Test
    @DisplayName("approve() does NOT publish when endorsement is not PENDING_APPROVAL — status guard regression")
    void approve_doesNotPublishWhenStatusIsWrong() {
        UUID endorsementId = UUID.randomUUID();
        Endorsement approved = Endorsement.builder().status(EndorsementStatus.APPROVED).build();
        approved.setId(endorsementId);

        when(endorsementRepository.findByIdAndDeletedAtIsNull(endorsementId)).thenReturn(Optional.of(approved));

        assertThatThrownBy(() -> service.approve(endorsementId, "ok"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("PENDING_APPROVAL");

        verify(eventPublisher, never()).publishEvent(Mockito.<EndorsementApprovedEvent>any());
    }

    private static Endorsement pendingEndorsement(
            UUID id, UUID policyId, UUID customerId, UUID brokerId,
            BigDecimal premiumAdjustment, String currencyCode,
            String endorsementNumber, String policyNumber,
            String customerName, String brokerName, String productName) {
        Endorsement e = Endorsement.builder()
                .status(EndorsementStatus.PENDING_APPROVAL)
                .endorsementNumber(endorsementNumber)
                .endorsementType(EndorsementType.ADDITIONAL_PREMIUM)
                .policyId(policyId)
                .policyNumber(policyNumber)
                .customerId(customerId)
                .customerName(customerName)
                .brokerId(brokerId)
                .brokerName(brokerName)
                .productName(productName)
                .productId(UUID.randomUUID())
                .classOfBusinessId(UUID.randomUUID())
                .premiumAdjustment(premiumAdjustment)
                .currencyCode(currencyCode)
                .effectiveDate(LocalDate.of(2026, 4, 1))
                .policyEndDate(LocalDate.of(2026, 12, 31))
                .description("Sum-insured uplift")
                .build();
        e.setId(id);
        return e;
    }
}
