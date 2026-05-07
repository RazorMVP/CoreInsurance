package com.nubeero.cia.policy;

import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.customer.Customer;
import com.nubeero.cia.customer.CustomerService;
import com.nubeero.cia.customer.CustomerType;
import com.nubeero.cia.documents.DocumentGenerationService;
import com.nubeero.cia.policy.dto.PolicyRequest;
import com.nubeero.cia.policy.dto.PolicyResponse;
import com.nubeero.cia.policy.dto.PolicyRiskRequest;
import com.nubeero.cia.quotation.BusinessType;
import com.nubeero.cia.quotation.QuoteService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PolicyServiceTest {

    private PolicyRepository repository;
    private CustomerService customerService;
    private ProductRepository productRepository;
    private PolicySurveyService policySurveyService;
    private PolicyService service;

    @BeforeEach
    void setUp() {
        repository = mock(PolicyRepository.class);
        customerService = mock(CustomerService.class);
        productRepository = mock(ProductRepository.class);
        policySurveyService = mock(PolicySurveyService.class);

        service = new PolicyService(
                repository,
                mock(PolicyNumberFormatService.class),
                customerService,
                mock(QuoteService.class),
                productRepository,
                mock(BrokerRepository.class),
                mock(InsuranceCompanyRepository.class),
                mock(ClassOfBusinessRepository.class),
                mock(AuditService.class),
                mock(WorkflowClient.class),
                mock(ApplicationEventPublisher.class),
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
}
