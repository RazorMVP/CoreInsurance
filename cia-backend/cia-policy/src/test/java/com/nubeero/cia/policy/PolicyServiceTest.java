package com.nubeero.cia.policy;

import com.nubeero.cia.common.event.PolicyApprovedEvent;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.customer.Customer;
import com.nubeero.cia.customer.CustomerService;
import com.nubeero.cia.customer.CustomerType;
import com.nubeero.cia.documents.DocumentGenerationService;
import com.nubeero.cia.policy.dto.PolicyApprovalRequest;
import com.nubeero.cia.policy.dto.PolicyRequest;
import com.nubeero.cia.policy.dto.PolicyResponse;
import com.nubeero.cia.policy.dto.PolicyRiskRequest;
import com.nubeero.cia.quotation.BusinessType;
import com.nubeero.cia.quotation.Quote;
import com.nubeero.cia.quotation.QuoteRisk;
import com.nubeero.cia.quotation.QuoteService;
import com.nubeero.cia.quotation.QuoteStatus;
import com.nubeero.cia.setup.org.BrokerRepository;
import com.nubeero.cia.setup.org.InsuranceCompanyRepository;
import com.nubeero.cia.setup.product.ClassOfBusiness;
import com.nubeero.cia.setup.product.ClassOfBusinessRepository;
import com.nubeero.cia.setup.product.PolicyNumberFormatService;
import com.nubeero.cia.setup.product.Product;
import com.nubeero.cia.setup.product.ProductRepository;
import com.nubeero.cia.setup.product.ProductType;
import com.nubeero.cia.storage.DocumentStorageService;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PolicyServiceTest {

    private PolicyRepository repository;
    private PolicyNumberFormatService policyNumberFormatService;
    private CustomerService customerService;
    private QuoteService quoteService;
    private ProductRepository productRepository;
    private ClassOfBusinessRepository classOfBusinessRepository;
    private ApplicationEventPublisher eventPublisher;
    private PolicySurveyService policySurveyService;
    private PolicyService service;

    @BeforeEach
    void setUp() {
        repository = mock(PolicyRepository.class);
        policyNumberFormatService = mock(PolicyNumberFormatService.class);
        customerService = mock(CustomerService.class);
        quoteService = mock(QuoteService.class);
        productRepository = mock(ProductRepository.class);
        classOfBusinessRepository = mock(ClassOfBusinessRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        policySurveyService = mock(PolicySurveyService.class);

        service = new PolicyService(
                repository,
                policyNumberFormatService,
                customerService,
                quoteService,
                productRepository,
                mock(BrokerRepository.class),
                mock(InsuranceCompanyRepository.class),
                classOfBusinessRepository,
                mock(AuditService.class),
                mock(WorkflowClient.class),
                eventPublisher,
                mock(DocumentGenerationService.class),
                mock(DocumentStorageService.class),
                policySurveyService);
    }

    @Test
    void createCalculatesDirectPolicyPremiumFromProductPercentageRate() {
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Customer customer = Customer.builder()
                .customerType(CustomerType.CORPORATE)
                .companyName("Acme Insurance Buyer")
                .build();
        customer.setId(customerId);

        ClassOfBusiness classOfBusiness = ClassOfBusiness.builder()
                .name("Fire")
                .code("FIRE")
                .build();
        classOfBusiness.setId(UUID.randomUUID());

        Product product = Product.builder()
                .name("Fire Standard")
                .code("FIRE-STD")
                .classOfBusiness(classOfBusiness)
                .type(ProductType.SINGLE_RISK)
                .rate(new BigDecimal("2.50"))
                .minPremium(BigDecimal.ZERO)
                .active(true)
                .build();
        product.setId(productId);

        when(customerService.findOrThrow(customerId)).thenReturn(customer);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(repository.save(any(Policy.class))).thenAnswer(invocation -> {
            Policy policy = invocation.getArgument(0);
            policy.setId(UUID.randomUUID());
            return policy;
        });
        when(policySurveyService.getOrNull(any())).thenReturn(null);

        PolicyResponse response = service.create(policyRequest(customerId, productId));

        assertThat(response.getTotalSumInsured()).isEqualByComparingTo("1000000.00");
        assertThat(response.getTotalPremium()).isEqualByComparingTo("25000.00");
        assertThat(response.getDiscount()).isEqualByComparingTo("5000.00");
        assertThat(response.getNetPremium()).isEqualByComparingTo("20000.00");
        assertThat(response.getRisks()).hasSize(1);
        assertThat(response.getRisks().getFirst().getPremium()).isEqualByComparingTo("25000.00");
    }

    @Test
    void approveDirectCreatedPolicyPublishesFinanceEventWithNetPremium() {
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Customer customer = Customer.builder()
                .customerType(CustomerType.CORPORATE)
                .companyName("Acme Insurance Buyer")
                .build();
        customer.setId(customerId);

        ClassOfBusiness classOfBusiness = classOfBusiness();
        Product product = product(productId, classOfBusiness);
        AtomicReference<Policy> savedPolicy = new AtomicReference<>();

        when(customerService.findOrThrow(customerId)).thenReturn(customer);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(repository.save(any(Policy.class))).thenAnswer(invocation -> {
            Policy policy = invocation.getArgument(0);
            if (policy.getId() == null) {
                policy.setId(UUID.randomUUID());
            }
            savedPolicy.set(policy);
            return policy;
        });
        when(policySurveyService.getOrNull(any())).thenReturn(null);

        PolicyResponse created = service.create(policyRequest(customerId, productId));
        Policy policy = savedPolicy.get();
        policy.setStatus(PolicyStatus.PENDING_APPROVAL);
        when(repository.findById(created.getId())).thenReturn(Optional.of(policy));
        when(policyNumberFormatService.generateNext(productId)).thenReturn("POL-DIRECT-001");

        service.approve(created.getId(), new PolicyApprovalRequest());

        org.mockito.ArgumentCaptor<PolicyApprovedEvent> event =
                org.mockito.ArgumentCaptor.forClass(PolicyApprovedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertPolicyApprovedEvent(event.getValue(), created.getId(), "POL-DIRECT-001",
                customerId, productId, classOfBusiness.getId(), "20000.00");
    }

    @Test
    void approveQuoteBoundPolicyPublishesFinanceEventWithQuoteNetPremium() {
        UUID quoteId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        ClassOfBusiness classOfBusiness = classOfBusiness();
        AtomicReference<Policy> savedPolicy = new AtomicReference<>();

        Quote quote = approvedQuote(quoteId, customerId, productId, classOfBusiness);
        when(quoteService.findOrThrow(quoteId)).thenReturn(quote);
        when(repository.findByQuoteIdAndDeletedAtIsNull(quoteId)).thenReturn(Optional.empty());
        when(classOfBusinessRepository.findById(classOfBusiness.getId()))
                .thenReturn(Optional.of(classOfBusiness));
        when(repository.save(any(Policy.class))).thenAnswer(invocation -> {
            Policy policy = invocation.getArgument(0);
            if (policy.getId() == null) {
                policy.setId(UUID.randomUUID());
            }
            savedPolicy.set(policy);
            return policy;
        });
        when(policySurveyService.getOrNull(any())).thenReturn(null);

        PolicyResponse bound = service.bindFromQuote(quoteId);
        Policy policy = savedPolicy.get();
        policy.setStatus(PolicyStatus.PENDING_APPROVAL);
        when(repository.findById(bound.getId())).thenReturn(Optional.of(policy));
        when(policyNumberFormatService.generateNext(productId)).thenReturn("POL-QUOTE-001");

        service.approve(bound.getId(), new PolicyApprovalRequest());

        org.mockito.ArgumentCaptor<PolicyApprovedEvent> event =
                org.mockito.ArgumentCaptor.forClass(PolicyApprovedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertPolicyApprovedEvent(event.getValue(), bound.getId(), "POL-QUOTE-001",
                customerId, productId, classOfBusiness.getId(), "27500.00");
        verify(quoteService).markConverted(quoteId);
    }

    private PolicyRequest policyRequest(UUID customerId, UUID productId) {
        PolicyRiskRequest risk = new PolicyRiskRequest();
        risk.setDescription("Warehouse stock");
        risk.setSumInsured(new BigDecimal("1000000.00"));

        PolicyRequest request = new PolicyRequest();
        request.setCustomerId(customerId);
        request.setProductId(productId);
        request.setBusinessType(BusinessType.DIRECT);
        request.setPolicyStartDate(LocalDate.of(2026, 1, 1));
        request.setPolicyEndDate(LocalDate.of(2027, 1, 1));
        request.setDiscount(new BigDecimal("5000.00"));
        request.setRisks(List.of(risk));
        return request;
    }

    private ClassOfBusiness classOfBusiness() {
        ClassOfBusiness classOfBusiness = ClassOfBusiness.builder()
                .name("Fire")
                .code("FIRE")
                .build();
        classOfBusiness.setId(UUID.randomUUID());
        return classOfBusiness;
    }

    private Product product(UUID productId, ClassOfBusiness classOfBusiness) {
        Product product = Product.builder()
                .name("Fire Standard")
                .code("FIRE-STD")
                .classOfBusiness(classOfBusiness)
                .type(ProductType.SINGLE_RISK)
                .rate(new BigDecimal("2.50"))
                .minPremium(BigDecimal.ZERO)
                .active(true)
                .build();
        product.setId(productId);
        return product;
    }

    private Quote approvedQuote(UUID quoteId, UUID customerId, UUID productId,
            ClassOfBusiness classOfBusiness) {
        Quote quote = Quote.builder()
                .quoteNumber("Q-ISSUE-001")
                .status(QuoteStatus.APPROVED)
                .customerId(customerId)
                .customerName("Acme Insurance Buyer")
                .productId(productId)
                .productName("Fire Standard")
                .productCode("FIRE-STD")
                .productRate(new BigDecimal("2.50"))
                .classOfBusinessId(classOfBusiness.getId())
                .classOfBusinessName(classOfBusiness.getName())
                .businessType(BusinessType.DIRECT)
                .policyStartDate(LocalDate.of(2026, 1, 1))
                .policyEndDate(LocalDate.of(2027, 1, 1))
                .totalSumInsured(new BigDecimal("1000000.00"))
                .totalPremium(new BigDecimal("30000.00"))
                .discount(new BigDecimal("2500.00"))
                .netPremium(new BigDecimal("27500.00"))
                .build();
        quote.setId(quoteId);
        QuoteRisk risk = QuoteRisk.builder()
                .quote(quote)
                .description("Warehouse stock")
                .sumInsured(new BigDecimal("1000000.00"))
                .rate(new BigDecimal("3.00"))
                .grossPremium(new BigDecimal("30000.00"))
                .premium(new BigDecimal("30000.00"))
                .orderNo(1)
                .build();
        quote.getRisks().add(risk);
        return quote;
    }

    private void assertPolicyApprovedEvent(PolicyApprovedEvent event, UUID policyId,
            String policyNumber, UUID customerId, UUID productId, UUID classOfBusinessId,
            String netPremium) {
        assertThat(event.policyId()).isEqualTo(policyId);
        assertThat(event.policyNumber()).isEqualTo(policyNumber);
        assertThat(event.customerId()).isEqualTo(customerId);
        assertThat(event.customerName()).isEqualTo("Acme Insurance Buyer");
        assertThat(event.productName()).isEqualTo("Fire Standard");
        assertThat(event.netPremium()).isEqualByComparingTo(netPremium);
        assertThat(event.currencyCode()).isEqualTo("NGN");
        assertThat(event.policyStartDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(event.policyEndDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(event.productId()).isEqualTo(productId);
        assertThat(event.classOfBusinessId()).isEqualTo(classOfBusinessId);
        assertThat(event.totalSumInsured()).isEqualByComparingTo("1000000.00");
    }
}
