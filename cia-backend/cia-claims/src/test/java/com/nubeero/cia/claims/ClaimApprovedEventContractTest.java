package com.nubeero.cia.claims;

import com.nubeero.cia.common.event.ClaimApprovedEvent;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.documents.DocumentGenerationService;
import com.nubeero.cia.policy.PolicyRepository;
import com.nubeero.cia.setup.org.SurveyorRepository;
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
 * Slice T1 — upstream contract test for {@link ClaimApprovedEvent}.
 *
 * <p>Guards the contract that Module 12's {@code SubledgerPostingService}
 * consumes when posting the claim approval JE — the event populates the
 * LIC (loss-component) reserve account when a claim moves to APPROVED.
 *
 * <p>Tests:
 * <ol>
 *   <li>Happy path: every field on the 11-field event record asserted.</li>
 *   <li>Status guard: approve() on a non-PENDING_APPROVAL claim throws and
 *       publishes nothing.</li>
 * </ol>
 *
 * <p>Pure Mockito unit test, no Spring context, no DB.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClaimApprovedEventContractTest {

    @Mock private ClaimRepository claimRepository;
    @Mock private ClaimNumberService numberService;
    @Mock private PolicyRepository policyRepository;
    @Mock private SurveyorRepository surveyorRepository;
    @Mock private WorkflowClient workflowClient;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private DocumentGenerationService documentGenerationService;

    private ClaimService service;

    @BeforeEach
    void setUp() {
        service = new ClaimService(
                claimRepository, numberService, policyRepository, surveyorRepository,
                workflowClient, eventPublisher, documentGenerationService);
    }

    @Test
    @DisplayName("approve() publishes ClaimApprovedEvent with every field populated from the saved claim")
    void approve_publishesEventWithCompletePayload() {
        UUID claimId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID brokerId = UUID.randomUUID();
        BigDecimal approvedAmount = new BigDecimal("750000.00");

        Claim pending = pendingClaim(claimId, policyId, customerId, brokerId,
                approvedAmount, "NGN", "CLM-2026-00041", "POL-2026-00099",
                "Beneficiary Customer", "Lagos Brokers", "Motor Comprehensive");

        when(claimRepository.findByIdAndDeletedAtIsNull(claimId)).thenReturn(Optional.of(pending));
        when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));

        service.approve(claimId);

        ArgumentCaptor<ClaimApprovedEvent> captor = ArgumentCaptor.forClass(ClaimApprovedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        ClaimApprovedEvent event = captor.getValue();

        assertThat(event.claimId()).as("claimId").isEqualTo(claimId);
        assertThat(event.claimNumber()).as("claimNumber").isEqualTo("CLM-2026-00041");
        assertThat(event.policyId()).as("policyId").isEqualTo(policyId);
        assertThat(event.policyNumber()).as("policyNumber").isEqualTo("POL-2026-00099");
        assertThat(event.customerId()).as("customerId").isEqualTo(customerId);
        assertThat(event.customerName()).as("customerName").isEqualTo("Beneficiary Customer");
        assertThat(event.brokerId()).as("brokerId").isEqualTo(brokerId);
        assertThat(event.brokerName()).as("brokerName").isEqualTo("Lagos Brokers");
        assertThat(event.productName()).as("productName").isEqualTo("Motor Comprehensive");
        assertThat(event.approvedAmount())
                .as("approvedAmount — drives LIC reserve booking in SubledgerPostingService")
                .isEqualByComparingTo(approvedAmount);
        assertThat(event.currencyCode()).as("currencyCode").isEqualTo("NGN");
    }

    @Test
    @DisplayName("approve() does NOT publish when claim is not PENDING_APPROVAL — status guard regression")
    void approve_doesNotPublishWhenStatusIsWrong() {
        UUID claimId = UUID.randomUUID();
        Claim alreadyApproved = Claim.builder().status(ClaimStatus.APPROVED).build();
        alreadyApproved.setId(claimId);

        when(claimRepository.findByIdAndDeletedAtIsNull(claimId)).thenReturn(Optional.of(alreadyApproved));

        assertThatThrownBy(() -> service.approve(claimId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("PENDING_APPROVAL");

        verify(eventPublisher, never()).publishEvent(Mockito.<ClaimApprovedEvent>any());
    }

    private static Claim pendingClaim(
            UUID id, UUID policyId, UUID customerId, UUID brokerId,
            BigDecimal approvedAmount, String currencyCode,
            String claimNumber, String policyNumber,
            String customerName, String brokerName, String productName) {
        Claim c = Claim.builder()
                .status(ClaimStatus.PENDING_APPROVAL)
                .claimNumber(claimNumber)
                .policyId(policyId)
                .policyNumber(policyNumber)
                .customerId(customerId)
                .customerName(customerName)
                .brokerId(brokerId)
                .brokerName(brokerName)
                .productName(productName)
                .productId(UUID.randomUUID())
                .classOfBusinessId(UUID.randomUUID())
                .approvedAmount(approvedAmount)
                .currencyCode(currencyCode)
                .incidentDate(LocalDate.of(2026, 3, 5))
                .description("Front-end collision write-off")
                .build();
        c.setId(id);
        return c;
    }
}
