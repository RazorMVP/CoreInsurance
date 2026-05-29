package com.nubeero.cia.policy;

import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.event.PolicyApprovedEvent;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.customer.CustomerService;
import com.nubeero.cia.documents.DocumentGenerationService;
import com.nubeero.cia.policy.dto.PolicyApprovalRequest;
import com.nubeero.cia.quotation.QuoteService;
import com.nubeero.cia.setup.org.AgentRepository;
import com.nubeero.cia.setup.org.BrokerRepository;
import com.nubeero.cia.setup.org.RelationshipManagerRepository;
import com.nubeero.cia.setup.product.CommissionSetupRepository;
import com.nubeero.cia.setup.org.InsuranceCompanyRepository;
import com.nubeero.cia.setup.product.ClassOfBusinessRepository;
import com.nubeero.cia.setup.product.PolicyNumberFormatService;
import com.nubeero.cia.setup.product.ProductRepository;
import com.nubeero.cia.storage.DocumentStorageService;
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
 * Slice T1 — upstream contract test for {@link PolicyApprovedEvent}.
 *
 * <p>Guards the contract that Module 12's {@code SubledgerPostingService} and
 * Phase 2's {@code ContractGroupingService} both depend on:
 * <strong>{@link PolicyService#approve(UUID, PolicyApprovalRequest)} publishes
 * a {@link PolicyApprovedEvent} populated from the saved policy on the happy
 * path, and publishes nothing when the status guard rejects the call.</strong>
 *
 * <p>Field-by-field assertion is intentional. If anyone removes or renames a
 * field on the event record without updating downstream consumers, this test
 * fails first — before {@code SubledgerPostingServiceIT} would surface the
 * same gap as a cascade of NPEs deep inside the JE-gateway. Module 12 reads
 * {@code productId}, {@code classOfBusinessId}, {@code policyStartDate},
 * {@code policyEndDate}, {@code totalSumInsured}, and {@code netPremium}
 * specifically — those are the fields most worth protecting.
 *
 * <p>Tests run against a pure Mockito-mocked service; no Spring context, no
 * Testcontainers. The Spring event-bus mechanics are already exercised end-to-
 * end by {@code SubledgerPostingServiceIT} in cia-api.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PolicyApprovedEventContractTest {

    @Mock private PolicyRepository repository;
    @Mock private PolicyNumberFormatService policyNumberFormatService;
    @Mock private CustomerService customerService;
    @Mock private QuoteService quoteService;
    @Mock private ProductRepository productRepository;
    @Mock private CommissionSetupRepository commissionSetupRepository;
    @Mock private BrokerRepository brokerRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private RelationshipManagerRepository relationshipManagerRepository;
    @Mock private InsuranceCompanyRepository insuranceCompanyRepository;
    @Mock private ClassOfBusinessRepository classOfBusinessRepository;
    @Mock private AuditService auditService;
    @Mock private WorkflowClient workflowClient;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private DocumentGenerationService documentGenerationService;
    @Mock private DocumentStorageService documentStorageService;
    @Mock private PolicySurveyService policySurveyService;

    private PolicyService service;

    @BeforeEach
    void setUp() {
        service = new PolicyService(
                repository, policyNumberFormatService, customerService, quoteService,
                productRepository, commissionSetupRepository, brokerRepository, agentRepository,
                relationshipManagerRepository,
                insuranceCompanyRepository,
                classOfBusinessRepository, auditService, workflowClient, eventPublisher,
                documentGenerationService, documentStorageService, policySurveyService);
    }

    @Test
    @DisplayName("approve() publishes PolicyApprovedEvent with every field populated from the saved policy")
    void approve_publishesEventWithCompletePayload() {
        UUID policyId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID brokerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID classOfBusinessId = UUID.randomUUID();
        LocalDate startDate = LocalDate.of(2026, 1, 1);
        LocalDate endDate = LocalDate.of(2026, 12, 31);
        BigDecimal netPremium = new BigDecimal("250000.00");
        BigDecimal totalSumInsured = new BigDecimal("10000000.00");

        Policy pending = Policy.builder()
                .status(PolicyStatus.PENDING_APPROVAL)
                .customerId(customerId)
                .customerName("Acme Logistics Ltd")
                .brokerId(brokerId)
                .brokerName("Lagos Brokers")
                .productId(productId)
                .productName("Marine Cargo Open Cover")
                .classOfBusinessId(classOfBusinessId)
                .classOfBusinessName("Marine")
                .netPremium(netPremium)
                .totalSumInsured(totalSumInsured)
                .policyStartDate(startDate)
                .policyEndDate(endDate)
                .niidRequired(false)
                .build();
        pending.setId(policyId);

        when(repository.findById(policyId)).thenReturn(Optional.of(pending));
        when(repository.save(any(Policy.class))).thenAnswer(inv -> inv.getArgument(0));
        when(policyNumberFormatService.generateNext(productId)).thenReturn("MAR/2026/00042");

        service.approve(policyId, requestWithComments("ok"));

        ArgumentCaptor<PolicyApprovedEvent> captor = ArgumentCaptor.forClass(PolicyApprovedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        PolicyApprovedEvent event = captor.getValue();

        assertThat(event.policyId()).as("policyId").isEqualTo(policyId);
        assertThat(event.policyNumber()).as("policyNumber from format service").isEqualTo("MAR/2026/00042");
        assertThat(event.customerId()).as("customerId").isEqualTo(customerId);
        assertThat(event.customerName()).as("customerName").isEqualTo("Acme Logistics Ltd");
        assertThat(event.brokerId()).as("brokerId").isEqualTo(brokerId);
        assertThat(event.brokerName()).as("brokerName").isEqualTo("Lagos Brokers");
        assertThat(event.productName()).as("productName").isEqualTo("Marine Cargo Open Cover");
        assertThat(event.netPremium()).as("netPremium").isEqualByComparingTo(netPremium);
        assertThat(event.currencyCode())
                .as("currencyCode is currently hard-coded to NGN in PolicyService.approve — "
                        + "this assertion is the failure point when multi-currency lands")
                .isEqualTo("NGN");
        assertThat(event.policyEndDate()).as("policyEndDate").isEqualTo(endDate);

        // RI allocation + Phase 2 ContractGroupingService dependencies
        assertThat(event.productId()).as("productId — read by ContractGroupingService").isEqualTo(productId);
        assertThat(event.classOfBusinessId())
                .as("classOfBusinessId — read by ContractGroupingService for portfolio lookup")
                .isEqualTo(classOfBusinessId);
        assertThat(event.totalSumInsured())
                .as("totalSumInsured — read by RI allocation")
                .isEqualByComparingTo(totalSumInsured);
        assertThat(event.policyStartDate())
                .as("policyStartDate — read by ContractGroupingService for §22 grouping")
                .isEqualTo(startDate);
    }

    @Test
    @DisplayName("approve() does NOT publish when policy is not PENDING_APPROVAL — status guard regression")
    void approve_doesNotPublishWhenStatusIsWrong() {
        UUID policyId = UUID.randomUUID();
        Policy active = Policy.builder()
                .status(PolicyStatus.ACTIVE)
                .customerName("Already Approved")
                .build();
        active.setId(policyId);

        when(repository.findById(policyId)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.approve(policyId, requestWithComments("ok")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Cannot approve");

        verify(eventPublisher, never()).publishEvent(Mockito.<PolicyApprovedEvent>any());
    }

    private static PolicyApprovalRequest requestWithComments(String comments) {
        PolicyApprovalRequest r = new PolicyApprovalRequest();
        r.setComments(comments);
        return r;
    }
}
