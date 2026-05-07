package com.nubeero.cia.endorsement;

import com.nubeero.cia.common.event.EndorsementApprovedEvent;
import com.nubeero.cia.documents.DocumentGenerationService;
import com.nubeero.cia.endorsement.dto.CreateEndorsementRequest;
import com.nubeero.cia.policy.Policy;
import com.nubeero.cia.policy.PolicyRepository;
import com.nubeero.cia.policy.PolicyStatus;
import com.nubeero.cia.quotation.BusinessType;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EndorsementServiceTest {

    private EndorsementRepository endorsementRepository;
    private EndorsementNumberService numberService;
    private PolicyRepository policyRepository;
    private ApplicationEventPublisher eventPublisher;
    private EndorsementService service;

    @BeforeEach
    void setUp() {
        endorsementRepository = mock(EndorsementRepository.class);
        numberService = mock(EndorsementNumberService.class);
        policyRepository = mock(PolicyRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new EndorsementService(
                endorsementRepository,
                numberService,
                policyRepository,
                mock(WorkflowClient.class),
                eventPublisher,
                mock(DocumentGenerationService.class));
    }

    @Test
    void createCalculatesAdditionalPremiumAdjustmentForRemainingCover() {
        UUID policyId = UUID.randomUUID();
        Policy policy = activePolicy(policyId, new BigDecimal("10000.00"));

        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));
        when(numberService.next()).thenReturn("END-0001");
        when(endorsementRepository.save(any(Endorsement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Endorsement endorsement = service.create(request(policyId, new BigDecimal("12000.00")));

        assertThat(endorsement.getEndorsementType()).isEqualTo(EndorsementType.ADDITIONAL_PREMIUM);
        assertThat(endorsement.getRemainingDays()).isEqualTo(365);
        assertThat(endorsement.getOldNetPremium()).isEqualByComparingTo("10000.00");
        assertThat(endorsement.getNewNetPremium()).isEqualByComparingTo("12000.00");
        assertThat(endorsement.getPremiumAdjustment()).isEqualByComparingTo("2000.00");
    }

    @Test
    void createCalculatesReturnPremiumAdjustmentForRemainingCover() {
        UUID policyId = UUID.randomUUID();
        Policy policy = activePolicy(policyId, new BigDecimal("10000.00"));

        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));
        when(numberService.next()).thenReturn("END-0002");
        when(endorsementRepository.save(any(Endorsement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Endorsement endorsement = service.create(request(policyId, new BigDecimal("8000.00")));

        assertThat(endorsement.getEndorsementType()).isEqualTo(EndorsementType.RETURN_PREMIUM);
        assertThat(endorsement.getRemainingDays()).isEqualTo(365);
        assertThat(endorsement.getOldNetPremium()).isEqualByComparingTo("10000.00");
        assertThat(endorsement.getNewNetPremium()).isEqualByComparingTo("8000.00");
        assertThat(endorsement.getPremiumAdjustment()).isEqualByComparingTo("-2000.00");
    }

    @Test
    void approvePublishesPremiumAdjustmentForFinance() {
        UUID endorsementId = UUID.randomUUID();
        Endorsement endorsement = pendingEndorsement(endorsementId, new BigDecimal("2000.00"));

        when(endorsementRepository.findByIdAndDeletedAtIsNull(endorsementId))
                .thenReturn(Optional.of(endorsement));
        when(endorsementRepository.save(any(Endorsement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.approve(endorsementId, "Approved");

        org.mockito.ArgumentCaptor<EndorsementApprovedEvent> event =
                org.mockito.ArgumentCaptor.forClass(EndorsementApprovedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().endorsementId()).isEqualTo(endorsementId);
        assertThat(event.getValue().endorsementNumber()).isEqualTo("END-0003");
        assertThat(event.getValue().policyNumber()).isEqualTo("POL-0001");
        assertThat(event.getValue().customerName()).isEqualTo("Acme Insurance Buyer");
        assertThat(event.getValue().productName()).isEqualTo("Fire Standard");
        assertThat(event.getValue().premiumAdjustment()).isEqualByComparingTo("2000.00");
        assertThat(event.getValue().currencyCode()).isEqualTo("NGN");
    }

    private CreateEndorsementRequest request(UUID policyId, BigDecimal newNetPremium) {
        return new CreateEndorsementRequest(
                policyId,
                LocalDate.of(2026, 7, 1),
                "Premium adjustment",
                null,
                new BigDecimal("1000000.00"),
                newNetPremium,
                null);
    }

    private Policy activePolicy(UUID policyId, BigDecimal netPremium) {
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
                .businessType(BusinessType.DIRECT)
                .policyStartDate(LocalDate.of(2026, 1, 1))
                .policyEndDate(LocalDate.of(2027, 7, 1))
                .totalSumInsured(new BigDecimal("1000000.00"))
                .totalPremium(netPremium)
                .netPremium(netPremium)
                .build();
        policy.setId(policyId);
        return policy;
    }

    private Endorsement pendingEndorsement(UUID endorsementId, BigDecimal adjustment) {
        Endorsement endorsement = Endorsement.builder()
                .endorsementNumber("END-0003")
                .status(EndorsementStatus.PENDING_APPROVAL)
                .endorsementType(EndorsementType.ADDITIONAL_PREMIUM)
                .policyId(UUID.randomUUID())
                .policyNumber("POL-0001")
                .customerId(UUID.randomUUID())
                .customerName("Acme Insurance Buyer")
                .productId(UUID.randomUUID())
                .productName("Fire Standard")
                .productCode("FIRE-STD")
                .productRate(new BigDecimal("2.50"))
                .classOfBusinessId(UUID.randomUUID())
                .classOfBusinessName("Fire")
                .effectiveDate(LocalDate.of(2026, 7, 1))
                .policyEndDate(LocalDate.of(2027, 7, 1))
                .remainingDays(365)
                .oldSumInsured(new BigDecimal("1000000.00"))
                .newSumInsured(new BigDecimal("1000000.00"))
                .oldNetPremium(new BigDecimal("10000.00"))
                .newNetPremium(new BigDecimal("12000.00"))
                .premiumAdjustment(adjustment)
                .description("Premium adjustment")
                .build();
        endorsement.setId(endorsementId);
        return endorsement;
    }
}
