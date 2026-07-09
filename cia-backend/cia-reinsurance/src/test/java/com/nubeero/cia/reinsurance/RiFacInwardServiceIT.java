package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.config.CiaCommonAutoConfiguration;
import com.nubeero.cia.documents.DocumentGenerationService;
import com.nubeero.cia.documents.InwardFacGuarantyContext;
import com.nubeero.cia.reinsurance.dto.CreateFacInwardRequest;
import com.nubeero.cia.reinsurance.dto.ExtendFacInwardRequest;
import com.nubeero.cia.reinsurance.dto.RenewFacInwardRequest;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.setup.org.InsuranceCompany;
import com.nubeero.cia.setup.org.InsuranceCompanyRepository;
import com.nubeero.cia.setup.product.ClassOfBusiness;
import com.nubeero.cia.setup.product.ClassOfBusinessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Full-context lifecycle IT for {@link RiFacInwardService} against a real PostgreSQL
 * container (Docker via Testcontainers). Scope: create → renew → extend → cancel
 * business LOGIC (status transitions, linking, amounts) — <strong>not</strong> a
 * schema-drift guard (see {@code RiFacInwardReferenceIT}'s javadoc for why
 * {@code create-drop} is acceptable at this module's isolated test scope; the real
 * V75-migration drift guard is {@code RiFacInwardSchemaIT} in {@code cia-api}).
 *
 * <p>{@link ReinsuranceTestApplication} only default-scans {@code com.nubeero.cia.reinsurance}
 * (see its javadoc), but {@code create()} needs {@link InsuranceCompany} /
 * {@link ClassOfBusiness} rows to resolve the ceding company + class-of-business snapshot.
 * This test widens the entity + repository scan explicitly (via {@code basePackageClasses},
 * scoped to this test class only — the shared fixture is untouched so every other IT in this
 * module keeps its narrower default) to also cover {@code com.nubeero.cia.setup.org} and
 * {@code com.nubeero.cia.setup.product}.
 *
 * <p>{@link DocumentGenerationService} is {@code @MockBean}-stubbed to a fixed non-null path —
 * the guaranty PDF render itself is covered by {@code InwardFacGuarantyRenderIT} (T4); this test
 * only needs {@code generateGuaranty()} to not throw and to persist a path.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackageClasses = {RiFacInward.class, InsuranceCompany.class, ClassOfBusiness.class})
@EnableJpaRepositories(basePackageClasses = {
        RiFacInwardRepository.class, InsuranceCompanyRepository.class, ClassOfBusinessRepository.class})
@Import({CiaCommonAutoConfiguration.class, RiNumberService.class, RiFacInwardService.class})
class RiFacInwardServiceIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ciatest")
                    .withUsername("ciatest")
                    .withPassword("ciatest");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired
    RiFacInwardService service;

    @Autowired
    InsuranceCompanyRepository insuranceCompanyRepository;

    @Autowired
    ClassOfBusinessRepository classOfBusinessRepository;

    @MockBean
    DocumentGenerationService documentGenerationService;

    private UUID cedingCompanyId;
    private UUID classOfBusinessId;

    @BeforeEach
    void seedMasterData() {
        when(documentGenerationService.generateInwardFacGuaranty(any(InwardFacGuarantyContext.class)))
                .thenReturn("documents/ri-fac-inwards/x/guaranty.pdf");

        InsuranceCompany ceding = insuranceCompanyRepository.save(InsuranceCompany.builder()
                .name("Sample Ceding Insurer Ltd")
                .rcNumber("RC-123456")
                .build());
        cedingCompanyId = ceding.getId();

        ClassOfBusiness cob = classOfBusinessRepository.save(ClassOfBusiness.builder()
                .name("Fire")
                .code("FIRE")
                .build());
        classOfBusinessId = cob.getId();
    }

    private CreateFacInwardRequest createRequest(LocalDate coverFrom, LocalDate coverTo) {
        return new CreateFacInwardRequest(
                cedingCompanyId, classOfBusinessId, "Warehouse fire risk",
                new BigDecimal("20000000"), new BigDecimal("30"),
                new BigDecimal("0.75"), new BigDecimal("10"),
                "NGN", coverFrom, coverTo);
    }

    @Test
    void create_setsActiveStatusReferenceAndAmounts() {
        RiFacInward saved = service.create(createRequest(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getFacInwardReference()).matches("FAC-IN-\\d{4}-\\d{6}");
        assertThat(saved.getStatus()).isEqualTo(RiFacInwardStatus.ACTIVE);
        assertThat(saved.getCedingCompanyId()).isEqualTo(cedingCompanyId);
        assertThat(saved.getCedingCompanyName()).isEqualTo("Sample Ceding Insurer Ltd");
        assertThat(saved.getClassOfBusinessId()).isEqualTo(classOfBusinessId);
        assertThat(saved.getClassOfBusinessName()).isEqualTo("Fire");
        assertThat(saved.getRenewedFromId()).isNull();

        // SI 20,000,000 × 30% = 6,000,000 accepted; rate 0.75% → 45,000 gross;
        // commission 10% → 4,500; net 40,500.
        assertThat(saved.getAcceptedSumInsured()).isEqualByComparingTo("6000000.00");
        assertThat(saved.getGrossPremium()).isEqualByComparingTo("45000.00");
        assertThat(saved.getCommissionAmount()).isEqualByComparingTo("4500.00");
        assertThat(saved.getNetPremium()).isEqualByComparingTo("40500.00");

        assertThat(saved.getGuarantyDocumentPath()).isEqualTo("documents/ri-fac-inwards/x/guaranty.pdf");
    }

    @Test
    void renew_linksNewCoverToSourceAndMarksSourceRenewed() {
        RiFacInward source = service.create(createRequest(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));

        RiFacInward renewed = service.renew(source.getId(), new RenewFacInwardRequest(
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31)));

        assertThat(renewed.getRenewedFromId()).isEqualTo(source.getId());
        assertThat(renewed.getStatus()).isEqualTo(RiFacInwardStatus.ACTIVE);
        assertThat(renewed.getCoverFrom()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(renewed.getCoverTo()).isEqualTo(LocalDate.of(2027, 12, 31));
        assertThat(renewed.getFacInwardReference()).isNotEqualTo(source.getFacInwardReference());
        // Premium terms carried over from source and recomputed identically.
        assertThat(renewed.getGrossPremium()).isEqualByComparingTo(source.getGrossPremium());

        RiFacInward reloadedSource = service.findOrThrow(source.getId());
        assertThat(reloadedSource.getStatus()).isEqualTo(RiFacInwardStatus.RENEWED);
    }

    @Test
    void renew_rejectsNonActiveSource() {
        RiFacInward source = service.create(createRequest(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
        service.cancel(source.getId(), "test cancel before renew");

        assertThatThrownBy(() -> service.renew(source.getId(),
                new RenewFacInwardRequest(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ACTIVE");
    }

    @Test
    void extend_movesCoverToAndPreservesOriginalPremiumFields() {
        RiFacInward cover = service.create(createRequest(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
        BigDecimal originalGross = cover.getGrossPremium();

        RiFacInward extended = service.extend(cover.getId(),
                new ExtendFacInwardRequest(LocalDate.of(2027, 1, 31)));

        assertThat(extended.getCoverTo()).isEqualTo(LocalDate.of(2027, 1, 31));
        // Original premium fields are unchanged by extend (per the brief's delta semantics).
        assertThat(extended.getGrossPremium()).isEqualByComparingTo(originalGross);
        assertThat(extended.getCoverFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void extend_rejectsNewCoverToNotAfterCurrentCoverTo() {
        RiFacInward cover = service.create(createRequest(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));

        assertThatThrownBy(() -> service.extend(cover.getId(),
                new ExtendFacInwardRequest(LocalDate.of(2026, 12, 31))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("newCoverTo");
    }

    @Test
    void cancel_setsStatusAndPersistsReason() {
        RiFacInward cover = service.create(createRequest(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));

        RiFacInward cancelled = service.cancel(cover.getId(), "Ceding company withdrew");

        assertThat(cancelled.getStatus()).isEqualTo(RiFacInwardStatus.CANCELLED);
        assertThat(cancelled.getCancellationReason()).isEqualTo("Ceding company withdrew");
        assertThat(cancelled.getCancelledAt()).isNotNull();
        assertThat(cancelled.getCancelledBy()).isNotNull();
    }

    @Test
    void cancel_rejectsWhenAlreadyCancelled() {
        RiFacInward cover = service.create(createRequest(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
        service.cancel(cover.getId(), "first cancellation");

        assertThatThrownBy(() -> service.cancel(cover.getId(), "second cancellation"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already cancelled");
    }

    @Test
    void findOrThrow_throwsResourceNotFoundForUnknownId() {
        assertThatThrownBy(() -> service.findOrThrow(UUID.randomUUID()))
                .isInstanceOf(com.nubeero.cia.common.exception.ResourceNotFoundException.class);
    }

    @Test
    void list_filtersByCedingCompanyClassAndStatus() {
        service.create(createRequest(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));

        var page = service.list(cedingCompanyId, classOfBusinessId, RiFacInwardStatus.ACTIVE,
                org.springframework.data.domain.PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getCedingCompanyId()).isEqualTo(cedingCompanyId);

        var noMatch = service.list(cedingCompanyId, classOfBusinessId, RiFacInwardStatus.CANCELLED,
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(noMatch.getTotalElements()).isZero();
    }
}
