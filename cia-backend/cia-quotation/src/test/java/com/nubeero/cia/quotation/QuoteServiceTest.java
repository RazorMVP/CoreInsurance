package com.nubeero.cia.quotation;

import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.customer.Customer;
import com.nubeero.cia.customer.CustomerService;
import com.nubeero.cia.customer.CustomerType;
import com.nubeero.cia.quotation.dto.QuoteRequest;
import com.nubeero.cia.quotation.dto.QuoteResponse;
import com.nubeero.cia.quotation.dto.QuoteRiskRequest;
import com.nubeero.cia.setup.org.BrokerRepository;
import com.nubeero.cia.setup.org.InsuranceCompanyRepository;
import com.nubeero.cia.setup.product.ClassOfBusiness;
import com.nubeero.cia.setup.product.Product;
import com.nubeero.cia.setup.product.ProductRepository;
import com.nubeero.cia.setup.product.ProductType;
import com.nubeero.cia.setup.quote.CalcSequence;
import com.nubeero.cia.setup.quote.QuoteConfig;
import com.nubeero.cia.setup.quote.QuoteConfigService;
import com.nubeero.cia.setup.quote.QuoteDiscountTypeRepository;
import com.nubeero.cia.setup.quote.QuoteLoadingTypeRepository;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuoteServiceTest {

    private QuoteRepository repository;
    private QuoteNumberService quoteNumberService;
    private CustomerService customerService;
    private ProductRepository productRepository;
    private QuoteConfigService quoteConfigService;
    private QuoteService service;

    @BeforeEach
    void setUp() {
        repository = mock(QuoteRepository.class);
        quoteNumberService = mock(QuoteNumberService.class);
        customerService = mock(CustomerService.class);
        productRepository = mock(ProductRepository.class);
        quoteConfigService = mock(QuoteConfigService.class);

        service = new QuoteService(
                repository,
                quoteNumberService,
                customerService,
                productRepository,
                mock(BrokerRepository.class),
                mock(InsuranceCompanyRepository.class),
                mock(AuditService.class),
                mock(WorkflowClient.class),
                quoteConfigService,
                mock(QuoteDiscountTypeRepository.class),
                mock(QuoteLoadingTypeRepository.class));
    }

    @Test
    void createCalculatesGrossPremiumFromPercentageRate() {
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
        when(quoteConfigService.fetchConfig()).thenReturn(QuoteConfig.builder()
                .validityDays(30)
                .calcSequence(CalcSequence.LOADING_FIRST)
                .build());
        when(quoteNumberService.nextQuoteNumber()).thenReturn("Q-0001");
        when(repository.save(any(Quote.class))).thenAnswer(invocation -> {
            Quote quote = invocation.getArgument(0);
            quote.setId(UUID.randomUUID());
            return quote;
        });

        QuoteResponse response = service.create(quoteRequest(customerId, productId));

        assertThat(response.getTotalSumInsured()).isEqualByComparingTo("1000000.00");
        assertThat(response.getTotalGrossPremium()).isEqualByComparingTo("25000.00");
        assertThat(response.getTotalNetPremium()).isEqualByComparingTo("25000.00");
        assertThat(response.getRisks()).hasSize(1);
        assertThat(response.getRisks().getFirst().getGrossPremium()).isEqualByComparingTo("25000.00");
        assertThat(response.getRisks().getFirst().getPremium()).isEqualByComparingTo("25000.00");
    }

    private QuoteRequest quoteRequest(UUID customerId, UUID productId) {
        QuoteRiskRequest risk = new QuoteRiskRequest();
        risk.setDescription("Warehouse stock");
        risk.setSumInsured(new BigDecimal("1000000.00"));
        risk.setRate(new BigDecimal("2.50"));

        QuoteRequest request = new QuoteRequest();
        request.setCustomerId(customerId);
        request.setProductId(productId);
        request.setBusinessType(BusinessType.DIRECT);
        request.setPolicyStartDate(LocalDate.of(2026, 1, 1));
        request.setPolicyEndDate(LocalDate.of(2027, 1, 1));
        request.setRisks(List.of(risk));
        return request;
    }
}
