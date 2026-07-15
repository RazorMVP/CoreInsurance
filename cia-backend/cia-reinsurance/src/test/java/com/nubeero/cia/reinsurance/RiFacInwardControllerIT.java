package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.exception.GlobalExceptionHandler;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.storage.DocumentStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-surface slice IT for {@link RiFacInwardController} at
 * {@code /api/v1/ri/fac-inwards}.
 *
 * <h2>Harness choice: {@code @WebMvcTest} slice, not a full lifecycle IT</h2>
 * {@code cia-reinsurance} carries no web/security test infrastructure of its
 * own — {@link ReinsuranceTestApplication} is a {@code @DataJpaTest}-only
 * fixture (no MVC dispatcher, no {@code SecurityFilterChain}), and this
 * module does not depend on {@code cia-auth} (that's where the real
 * {@code SecurityConfig} + {@code TenantIssuerJwtAuthenticationManagerResolver}
 * live — see {@code cia-api}'s {@code FinanceWebItSupport}-style full-context
 * ITs). Standing up a live {@code @SpringBootTest} security context here
 * would mean inventing new module-wide security test infrastructure (a
 * filter chain, a method-security config, a live Testcontainers Postgres)
 * solely to re-prove HTTP wiring that a mocked-service slice proves just as
 * well. The business LOGIC this brief also asks for (renew marks the source
 * RENEWED, extend's pro-rata math, cancel's reason persistence, create's
 * amount computation) is already exhaustively covered by
 * {@link RiFacInwardServiceIT} (T5); V75 entity↔schema drift is covered by
 * {@code RiFacInwardSchemaIT} (cia-api). This test's job is narrower and
 * complementary: prove the HTTP layer — routing, status codes, RBAC gates,
 * and JSON request/response mapping — is wired correctly.
 *
 * <p>{@code @EnableMethodSecurity} is enabled locally (nested
 * {@link MethodSecurityConfig}) so {@code @PreAuthorize} on the controller
 * is genuinely evaluated (not a no-op), and {@code @WithMockUser} supplies
 * the authenticated principal. {@code addFilters = false} skips the servlet
 * security filter chain (there is no {@code SecurityFilterChain} bean in
 * this slice, and none is needed — {@code @WithMockUser} populates the
 * {@code SecurityContextHolder} directly via a
 * {@code TestExecutionListener}, independent of the filter chain), mirroring
 * {@code GlobalExceptionHandlerMvcTest} (cia-common) which uses the same
 * addFilters=false + explicit {@code @Import(GlobalExceptionHandler.class)}
 * pattern for the same reason: {@code GlobalExceptionHandler} lives outside
 * this module's {@code com.nubeero.cia.reinsurance} scanned package, so it
 * must be imported explicitly to translate {@code CiaException} /
 * {@code AccessDeniedException} into the standard {@code ApiResponse}
 * envelope + status code.
 */
@WebMvcTest(controllers = RiFacInwardController.class)
@Import({RiFacInwardController.class, GlobalExceptionHandler.class, RiFacInwardControllerIT.MethodSecurityConfig.class})
@AutoConfigureMockMvc(addFilters = false)
class RiFacInwardControllerIT {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    @Autowired
    MockMvc mockMvc;

    @MockBean
    RiFacInwardService service;

    @MockBean
    DocumentStorageService storageService;

    private static final UUID CEDING_ID = UUID.randomUUID();
    private static final UUID CLASS_ID = UUID.randomUUID();

    private RiFacInward sampleCover(UUID id, RiFacInwardStatus status, String guarantyPath) {
        // id/createdAt live on BaseEntity (a @MappedSuperclass with @Getter/@Setter,
        // not @Builder), so Lombok's @Builder on RiFacInward doesn't expose builder
        // methods for them — set via the inherited setters after build().
        RiFacInward cover = RiFacInward.builder()
                .facInwardReference("FAC-IN-2026-000001")
                .cedingCompanyId(CEDING_ID)
                .cedingCompanyName("Sample Ceding Insurer Ltd")
                .classOfBusinessId(CLASS_ID)
                .classOfBusinessName("Fire")
                .riskDescription("Warehouse fire risk")
                .sumInsured(new BigDecimal("20000000.00"))
                .ourSharePct(new BigDecimal("30.0000"))
                .acceptedSumInsured(new BigDecimal("6000000.00"))
                .premiumRate(new BigDecimal("0.750000"))
                .grossPremium(new BigDecimal("45000.00"))
                .commissionRate(new BigDecimal("10.0000"))
                .commissionAmount(new BigDecimal("4500.00"))
                .netPremium(new BigDecimal("40500.00"))
                .currencyCode("NGN")
                .coverFrom(LocalDate.of(2026, 1, 1))
                .coverTo(LocalDate.of(2026, 12, 31))
                .status(status)
                .guarantyDocumentPath(guarantyPath)
                .build();
        cover.setId(id);
        cover.setCreatedAt(Instant.now());
        return cover;
    }

    private String createRequestJson() {
        return """
                {
                  "cedingCompanyId": "%s",
                  "classOfBusinessId": "%s",
                  "riskDescription": "Warehouse fire risk",
                  "sumInsured": 20000000,
                  "ourSharePct": 30,
                  "premiumRate": 0.75,
                  "commissionRate": 10,
                  "currencyCode": "NGN",
                  "coverFrom": "2026-01-01",
                  "coverTo": "2026-12-31"
                }
                """.formatted(CEDING_ID, CLASS_ID);
    }

    // ─────────────────────────────────────────────── create

    @Test
    @WithMockUser(roles = "REINSURANCE_CREATE")
    void create_returns201WithActiveStatus() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.create(any())).thenReturn(sampleCover(id, RiFacInwardStatus.ACTIVE, null));

        mockMvc.perform(post("/api/v1/ri/fac-inwards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(id.toString()))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.facInwardReference").value("FAC-IN-2026-000001"))
                .andExpect(jsonPath("$.data.cedingCompanyId").value(CEDING_ID.toString()))
                .andExpect(jsonPath("$.data.grossPremium").value(45000.00));
    }

    @Test
    @WithMockUser(roles = "REINSURANCE_VIEW")
    void create_rejectedWithoutCreateRole() throws Exception {
        mockMvc.perform(post("/api/v1/ri/fac-inwards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson()))
                .andExpect(status().isForbidden());
    }

    // ─────────────────────────────────────────────── list

    @Test
    @WithMockUser(roles = "REINSURANCE_VIEW")
    void list_returnsRowWithPaginationMeta() throws Exception {
        UUID id = UUID.randomUUID();
        var page = new PageImpl<>(List.of(sampleCover(id, RiFacInwardStatus.ACTIVE, null)),
                PageRequest.of(0, 2000), 1);
        when(service.list(nullable(UUID.class), nullable(UUID.class), nullable(RiFacInwardStatus.class), any()))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/ri/fac-inwards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(id.toString()))
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(2000));
    }

    @Test
    @WithMockUser(roles = "REINSURANCE_CREATE")
    void list_rejectedWithoutViewRole() throws Exception {
        mockMvc.perform(get("/api/v1/ri/fac-inwards"))
                .andExpect(status().isForbidden());
    }

    // ─────────────────────────────────────────────── get

    @Test
    @WithMockUser(roles = "REINSURANCE_VIEW")
    void get_unknownId_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.findOrThrow(id)).thenThrow(new ResourceNotFoundException("RiFacInward", id));

        mockMvc.perform(get("/api/v1/ri/fac-inwards/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
    }

    // ─────────────────────────────────────────────── renew

    @Test
    @WithMockUser(roles = "REINSURANCE_CREATE")
    void renew_returnsNewActiveCoverLinkedToSource() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID renewedId = UUID.randomUUID();
        RiFacInward renewed = sampleCover(renewedId, RiFacInwardStatus.ACTIVE, null);
        renewed.setRenewedFromId(sourceId);
        when(service.renew(eq(sourceId), any())).thenReturn(renewed);

        mockMvc.perform(post("/api/v1/ri/fac-inwards/{id}/renew", sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"coverFrom": "2027-01-01", "coverTo": "2027-12-31"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(renewedId.toString()))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.renewedFromId").value(sourceId.toString()));
        // Source-side ACTIVE→RENEWED transition is a RiFacInwardService concern,
        // already asserted end-to-end (real DB) by
        // RiFacInwardServiceIT#renew_linksNewCoverToSourceAndMarksSourceRenewed.
        // This slice only proves the HTTP request reaches service.renew(sourceId, req)
        // and the JSON response reflects whatever the service returns.
    }

    @Test
    @WithMockUser(roles = "REINSURANCE_UPDATE")
    void renew_rejectedWithoutCreateRole() throws Exception {
        mockMvc.perform(post("/api/v1/ri/fac-inwards/{id}/renew", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"coverFrom": "2027-01-01", "coverTo": "2027-12-31"}
                                """))
                .andExpect(status().isForbidden());
    }

    // ─────────────────────────────────────────────── extend

    @Test
    @WithMockUser(roles = "REINSURANCE_UPDATE")
    void extend_returnsCoverWithMovedCoverTo() throws Exception {
        UUID id = UUID.randomUUID();
        RiFacInward extended = sampleCover(id, RiFacInwardStatus.ACTIVE, null);
        extended.setCoverTo(LocalDate.of(2027, 1, 31));
        when(service.extend(eq(id), any())).thenReturn(extended);

        mockMvc.perform(post("/api/v1/ri/fac-inwards/{id}/extend", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newCoverTo": "2027-01-31"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coverTo").value("2027-01-31"));
    }

    @Test
    @WithMockUser(roles = "REINSURANCE_CREATE")
    void extend_rejectedWithoutUpdateRole() throws Exception {
        mockMvc.perform(post("/api/v1/ri/fac-inwards/{id}/extend", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newCoverTo": "2027-01-31"}
                                """))
                .andExpect(status().isForbidden());
    }

    // ─────────────────────────────────────────────── cancel

    @Test
    @WithMockUser(roles = "REINSURANCE_UPDATE")
    void cancel_withReason_returnsCancelled() throws Exception {
        UUID id = UUID.randomUUID();
        RiFacInward cancelled = sampleCover(id, RiFacInwardStatus.CANCELLED, null);
        cancelled.setCancellationReason("Ceding company withdrew");
        cancelled.setCancelledAt(Instant.now());
        cancelled.setCancelledBy("alice");
        when(service.cancel(eq(id), eq("Ceding company withdrew"))).thenReturn(cancelled);

        mockMvc.perform(post("/api/v1/ri/fac-inwards/{id}/cancel", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "Ceding company withdrew"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancellationReason").value("Ceding company withdrew"));
    }

    @Test
    @WithMockUser(roles = "REINSURANCE_UPDATE")
    void cancel_blankReason_returns400ValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/ri/fac-inwards/{id}/cancel", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "REINSURANCE_CREATE")
    void cancel_rejectedWithoutUpdateRole() throws Exception {
        mockMvc.perform(post("/api/v1/ri/fac-inwards/{id}/cancel", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "test"}
                                """))
                .andExpect(status().isForbidden());
    }

    // ─────────────────────────────────────────────── document

    @Test
    @WithMockUser(roles = "REINSURANCE_VIEW")
    void document_noGuarantyPath_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.findOrThrow(id)).thenReturn(sampleCover(id, RiFacInwardStatus.ACTIVE, null));

        mockMvc.perform(get("/api/v1/ri/fac-inwards/{id}/document", id))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "REINSURANCE_VIEW")
    void document_withGuarantyPath_streamsPdf() throws Exception {
        UUID id = UUID.randomUUID();
        RiFacInward cover = sampleCover(id, RiFacInwardStatus.ACTIVE, "documents/ri-fac-inwards/x/guaranty.pdf");
        when(service.findOrThrow(id)).thenReturn(cover);
        byte[] pdfBytes = "%PDF-1.4 fake".getBytes();
        // nullable(), not anyString(): this @WebMvcTest slice never runs TenantContextFilter,
        // so TenantContext.getTenantId() is null on this thread — anyString() excludes null
        // and would leave the mock unstubbed, returning null and NPE-ing the controller.
        when(storageService.download(nullable(String.class), eq("documents/ri-fac-inwards/x/guaranty.pdf")))
                .thenReturn(new ByteArrayInputStream(pdfBytes));

        mockMvc.perform(get("/api/v1/ri/fac-inwards/{id}/document", id))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"FAC-IN-2026-000001-guaranty.pdf\""))
                .andExpect(content().bytes(pdfBytes));
    }

    @Test
    @WithMockUser(roles = "REINSURANCE_CREATE")
    void document_rejectedWithoutViewRole() throws Exception {
        mockMvc.perform(get("/api/v1/ri/fac-inwards/{id}/document", UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }
}
