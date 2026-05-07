package com.nubeero.cia.claims;

import com.nubeero.cia.claims.dto.RegisterClaimRequest;
import com.nubeero.cia.claims.dto.SetReserveRequest;
import com.nubeero.cia.claims.dto.SubmitClaimRequest;
import com.nubeero.cia.common.event.ClaimApprovedEvent;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.documents.DocumentGenerationService;
import com.nubeero.cia.policy.Policy;
import com.nubeero.cia.policy.PolicyRepository;
import com.nubeero.cia.policy.PolicyStatus;
import com.nubeero.cia.setup.org.SurveyorRepository;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClaimServiceTest {

    private ClaimRepository claimRepository;
    private ClaimNumberService numberService;
    private PolicyRepository policyRepository;
    private ApplicationEventPublisher eventPublisher;
    private ClaimService service;

    @BeforeEach
    void setUp() {
        claimRepository = mock(ClaimRepository.class);
        numberService = mock(ClaimNumberService.class);
        policyRepository = mock(PolicyRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new ClaimService(
                claimRepository,
                numberService,
                policyRepository,
                mock(SurveyorRepository.class),
                mock(WorkflowClient.class),
                eventPublisher,
                mock(DocumentGenerationService.class));
    }

    @Test
    void claimLifecycleMovesFromRegisteredThroughSettlement() {
        UUID policyId = UUID.randomUUID();
        Policy policy = activePolicy(policyId);
        AtomicReference<Claim> savedClaim = new AtomicReference<>();

        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));
        when(numberService.next()).thenReturn("CLM-0001");
        when(claimRepository.save(any(Claim.class))).thenAnswer(invocation -> {
            Claim claim = invocation.getArgument(0);
            if (claim.getId() == null) {
                claim.setId(UUID.randomUUID());
            }
            savedClaim.set(claim);
            return claim;
        });

        Claim claim = service.register(registerRequest(policyId));
        when(claimRepository.findByIdAndDeletedAtIsNull(claim.getId()))
                .thenAnswer(invocation -> Optional.of(savedClaim.get()));

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REGISTERED);

        Claim reserved = service.setReserve(claim.getId(),
                new SetReserveRequest(new BigDecimal("7500.00"), "Initial reserve"));
        assertThat(reserved.getStatus()).isEqualTo(ClaimStatus.RESERVED);
        assertThat(reserved.getReserveAmount()).isEqualByComparingTo("7500.00");

        Claim pending = service.submitForApproval(claim.getId(), new SubmitClaimRequest(null));
        assertThat(pending.getStatus()).isEqualTo(ClaimStatus.PENDING_APPROVAL);
        assertThat(pending.getApprovedAmount()).isEqualByComparingTo("7500.00");

        Claim approved = service.approve(claim.getId());
        assertThat(approved.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        org.mockito.ArgumentCaptor<ClaimApprovedEvent> approvedEvent =
                org.mockito.ArgumentCaptor.forClass(ClaimApprovedEvent.class);
        verify(eventPublisher).publishEvent(approvedEvent.capture());
        assertThat(approvedEvent.getValue().claimId()).isEqualTo(claim.getId());
        assertThat(approvedEvent.getValue().approvedAmount()).isEqualByComparingTo("7500.00");

        Claim settled = service.markSettled(claim.getId());
        assertThat(settled.getStatus()).isEqualTo(ClaimStatus.SETTLED);
        assertThat(settled.getSettledAt()).isNotNull();
    }

    @Test
    void submitForApprovalRejectsUnreservedClaim() {
        Claim claim = claimWithStatus(ClaimStatus.REGISTERED);
        when(claimRepository.findByIdAndDeletedAtIsNull(claim.getId())).thenReturn(Optional.of(claim));

        assertThatThrownBy(() -> service.submitForApproval(
                claim.getId(), new SubmitClaimRequest(new BigDecimal("1000.00"))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Only RESERVED claims can be submitted");
    }

    @Test
    void approveRejectsClaimOutsidePendingApproval() {
        Claim claim = claimWithStatus(ClaimStatus.RESERVED);
        when(claimRepository.findByIdAndDeletedAtIsNull(claim.getId())).thenReturn(Optional.of(claim));

        assertThatThrownBy(() -> service.approve(claim.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Only PENDING_APPROVAL claims can be approved");
    }

    @Test
    void markSettledRejectsClaimBeforeApproval() {
        Claim claim = claimWithStatus(ClaimStatus.PENDING_APPROVAL);
        when(claimRepository.findByIdAndDeletedAtIsNull(claim.getId())).thenReturn(Optional.of(claim));

        assertThatThrownBy(() -> service.markSettled(claim.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Only APPROVED claims can be marked as settled");
    }

    @Test
    void withdrawRejectsRejectedTerminalClaim() {
        Claim claim = claimWithStatus(ClaimStatus.REJECTED);
        when(claimRepository.findByIdAndDeletedAtIsNull(claim.getId())).thenReturn(Optional.of(claim));

        assertThatThrownBy(() -> service.withdraw(claim.getId(), "Duplicate claim"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Cannot withdraw a REJECTED claim");
    }

    private RegisterClaimRequest registerRequest(UUID policyId) {
        return new RegisterClaimRequest(
                policyId,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 2),
                "Lagos",
                "Fire",
                "Electrical fault",
                "Jane Buyer",
                "08000000000",
                "Warehouse fire loss",
                new BigDecimal("8000.00"),
                null);
    }

    private Policy activePolicy(UUID policyId) {
        Policy policy = Policy.builder()
                .policyNumber("POL-0001")
                .status(PolicyStatus.ACTIVE)
                .customerId(UUID.randomUUID())
                .customerName("Acme Insurance Buyer")
                .productId(UUID.randomUUID())
                .productName("Fire Standard")
                .productCode("FIRE-STD")
                .productRate(new BigDecimal("2.50"))
                .classOfBusinessId(UUID.randomUUID())
                .classOfBusinessName("Fire")
                .classOfBusinessCode("FIRE")
                .policyStartDate(LocalDate.of(2026, 1, 1))
                .policyEndDate(LocalDate.of(2027, 1, 1))
                .totalSumInsured(new BigDecimal("1000000.00"))
                .totalPremium(new BigDecimal("25000.00"))
                .netPremium(new BigDecimal("25000.00"))
                .build();
        policy.setId(policyId);
        return policy;
    }

    private Claim claimWithStatus(ClaimStatus status) {
        Claim claim = Claim.builder()
                .claimNumber("CLM-0002")
                .status(status)
                .policyId(UUID.randomUUID())
                .policyNumber("POL-0001")
                .policyStartDate(LocalDate.of(2026, 1, 1))
                .policyEndDate(LocalDate.of(2027, 1, 1))
                .customerId(UUID.randomUUID())
                .customerName("Acme Insurance Buyer")
                .productId(UUID.randomUUID())
                .productName("Fire Standard")
                .classOfBusinessId(UUID.randomUUID())
                .classOfBusinessName("Fire")
                .incidentDate(LocalDate.of(2026, 6, 1))
                .reportedDate(LocalDate.of(2026, 6, 2))
                .description("Warehouse fire loss")
                .reserveAmount(new BigDecimal("7500.00"))
                .approvedAmount(new BigDecimal("7500.00"))
                .build();
        claim.setId(UUID.randomUUID());
        return claim;
    }
}
