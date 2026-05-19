package com.nubeero.cia.claims;

import com.nubeero.cia.common.event.ClaimSettledEvent;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Slice T1 — upstream contract test for {@link ClaimSettledEvent}.
 *
 * <p>Guards the contract that Module 12's {@code SubledgerPostingService}
 * consumes when a claim's DV is disbursed: it posts {@code Dr 2140 LIC OCR /
 * Cr 1120 Bank} for {@code settledAmount}. The {@code settledAmount} field
 * was added in Slice 1.5 specifically so the GL can post without an extra DB
 * lookup against cia-claims — meaning the producer-side guarantee that the
 * field reflects the actual {@code dvAmount} disbursed is load-bearing.
 *
 * <p>Tests:
 * <ol>
 *   <li>Happy path: every field on the 9-field event record asserted,
 *       including {@code settledAmount} which must equal the claim's
 *       {@code dvAmount} (not {@code approvedAmount}). The gap between
 *       these two figures is exactly the reserve true-up Phase 2's LIC
 *       roll-forward surfaces.</li>
 *   <li>{@code settledAt} is reasonable (within seconds of "now").</li>
 *   <li>Status guard: markSettled() on a non-APPROVED claim throws and
 *       publishes nothing.</li>
 * </ol>
 *
 * <p>Pure Mockito unit test, no Spring context, no DB.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClaimSettledEventContractTest {

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
    @DisplayName("markSettled() publishes ClaimSettledEvent carrying dvAmount (not approvedAmount) and current Instant")
    void markSettled_publishesEventWithDvAmountAndTimestamp() {
        UUID claimId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        // approvedAmount is the actuarial decision, dvAmount is what the
        // beneficiary actually receives. SubledgerPostingService posts
        // dvAmount, so the event MUST carry that.
        BigDecimal approvedAmount = new BigDecimal("750000.00");
        BigDecimal dvAmount       = new BigDecimal("712500.00");

        Claim approved = approvedClaimReadyForSettlement(claimId, policyId, customerId,
                approvedAmount, dvAmount, "NGN",
                "CLM-2026-00041", "POL-2026-00099", "Beneficiary Customer");

        when(claimRepository.findByIdAndDeletedAtIsNull(claimId)).thenReturn(Optional.of(approved));
        when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant before = Instant.now();
        service.markSettled(claimId);
        Instant after = Instant.now();

        ArgumentCaptor<ClaimSettledEvent> captor = ArgumentCaptor.forClass(ClaimSettledEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        ClaimSettledEvent event = captor.getValue();

        assertThat(event.claimId()).as("claimId").isEqualTo(claimId);
        assertThat(event.claimNumber()).as("claimNumber").isEqualTo("CLM-2026-00041");
        assertThat(event.policyId()).as("policyId").isEqualTo(policyId);
        assertThat(event.policyNumber()).as("policyNumber").isEqualTo("POL-2026-00099");
        assertThat(event.customerId()).as("customerId").isEqualTo(customerId);
        assertThat(event.customerName()).as("customerName").isEqualTo("Beneficiary Customer");
        assertThat(event.settledAmount())
                .as("settledAmount MUST be dvAmount (not approvedAmount) — that's what the bank pays out")
                .isEqualByComparingTo(dvAmount)
                .isNotEqualByComparingTo(approvedAmount);
        assertThat(event.currencyCode()).as("currencyCode").isEqualTo("NGN");
        assertThat(event.settledAt())
                .as("settledAt is the disbursement timestamp — must be between before/after the call")
                .isBetween(
                        before.minus(1, ChronoUnit.SECONDS),
                        after.plus(1, ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("markSettled() does NOT publish when claim is not APPROVED — status guard regression")
    void markSettled_doesNotPublishWhenStatusIsWrong() {
        UUID claimId = UUID.randomUUID();
        Claim pending = Claim.builder().status(ClaimStatus.PENDING_APPROVAL).build();
        pending.setId(claimId);

        when(claimRepository.findByIdAndDeletedAtIsNull(claimId)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.markSettled(claimId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("APPROVED");

        verify(eventPublisher, never()).publishEvent(Mockito.<ClaimSettledEvent>any());
    }

    private static Claim approvedClaimReadyForSettlement(
            UUID id, UUID policyId, UUID customerId,
            BigDecimal approvedAmount, BigDecimal dvAmount, String currencyCode,
            String claimNumber, String policyNumber, String customerName) {
        Claim c = Claim.builder()
                .status(ClaimStatus.APPROVED)
                .claimNumber(claimNumber)
                .policyId(policyId)
                .policyNumber(policyNumber)
                .customerId(customerId)
                .customerName(customerName)
                .approvedAmount(approvedAmount)
                .dvAmount(dvAmount)
                .currencyCode(currencyCode)
                .build();
        c.setId(id);
        return c;
    }
}
