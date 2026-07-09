package com.nubeero.cia.documents;

import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.storage.DocumentStorageService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Render smoke test for {@link DocumentGenerationServiceImpl#generateInwardFacGuaranty}
 * — mirrors {@link HtmlToPdfConverterFontIT}'s plain-instantiation style (no Spring
 * context needed; the module has no {@code @SpringBootTest} doc-gen IT to mirror
 * more directly). Wires the same {@link SpringTemplateEngine} +
 * {@link StringTemplateResolver} combination as {@link DocumentEngineConfig}, a
 * Mockito {@link DocumentTemplateRepository} stub that reports no tenant override
 * (forcing the classpath default {@code inward-fac-guaranty-default.html} to load),
 * and an in-memory fake {@link DocumentStorageService} so no MinIO/S3 is required.
 */
class InwardFacGuarantyRenderIT {

    private final Map<String, byte[]> uploaded = new HashMap<>();

    private DocumentGenerationServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId("test-tenant");

        DocumentTemplateRepository templateRepository = Mockito.mock(DocumentTemplateRepository.class);
        when(templateRepository.findBestMatch(any(), any(), any())).thenReturn(List.of());

        DocumentStorageService storageService = new DocumentStorageService() {
            @Override
            public String upload(String tenantId, String path, InputStream content, String mimeType) {
                try {
                    uploaded.put(path, content.readAllBytes());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return path;
            }

            @Override
            public InputStream download(String tenantId, String path) {
                return new ByteArrayInputStream(uploaded.get(path));
            }

            @Override
            public void delete(String tenantId, String path) {
                uploaded.remove(path);
            }

            @Override
            public String presignedUrl(String tenantId, String path, long expirySeconds) {
                return "https://stub/" + path;
            }
        };

        HtmlToPdfConverter pdfConverter = new HtmlToPdfConverter();

        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCacheable(false);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        TemplateEngine templateEngine = engine;

        service = new DocumentGenerationServiceImpl(templateRepository, storageService, pdfConverter, templateEngine);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("generateInwardFacGuaranty renders the classpath default template and stores a non-null PDF path")
    void generatesGuarantyDocument() throws Exception {
        UUID facInwardId = UUID.randomUUID();
        InwardFacGuarantyContext ctx = new InwardFacGuarantyContext(
                facInwardId,
                "FAC-IN-2026-000001",
                UUID.randomUUID(),
                "Acme Ceding Co",
                "Fire",
                "Warehouse fire risk, Lagos",
                new BigDecimal("500000000"),
                new BigDecimal("25"),
                new BigDecimal("125000000"),
                new BigDecimal("2500000"),
                new BigDecimal("250000"),
                new BigDecimal("2250000"),
                "NGN",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31)
        );

        String path = service.generateInwardFacGuaranty(ctx);

        assertThat(path)
                .as("doc gen must never throw and must return a non-null storage path on success")
                .isNotNull()
                .isEqualTo("documents/ri-fac-inwards/" + facInwardId + "/guaranty.pdf");

        byte[] pdfBytes = uploaded.get(path);
        assertThat(pdfBytes).as("PDF bytes must have been stored").isNotEmpty();

        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text)
                    .as("rendered PDF must contain the FAC reference and the Guaranty heading")
                    .contains("FAC-IN-2026-000001")
                    .containsIgnoringCase("Guaranty")
                    .contains("Acme Ceding Co");
        }
    }

    @Test
    @DisplayName("generateInwardFacGuaranty never throws — returns null on internal failure")
    void neverThrowsOnFailure() {
        // A ctx with a null required numeric field triggers a NullPointerException inside
        // the try block (ctx.sumInsured().toPlainString()) — the method must swallow it.
        InwardFacGuarantyContext badCtx = new InwardFacGuarantyContext(
                UUID.randomUUID(), "FAC-IN-2026-000002", UUID.randomUUID(),
                "Acme Ceding Co", "Fire", "desc",
                null, new BigDecimal("25"), new BigDecimal("1"),
                new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("1"),
                "NGN", LocalDate.now(), LocalDate.now()
        );

        String path = service.generateInwardFacGuaranty(badCtx);

        assertThat(path).isNull();
    }
}
