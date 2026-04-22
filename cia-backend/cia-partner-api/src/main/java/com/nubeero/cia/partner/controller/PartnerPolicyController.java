package com.nubeero.cia.partner.controller;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.partner.controller.dto.PartnerPolicyResponse;
import com.nubeero.cia.policy.PolicyService;
import com.nubeero.cia.policy.dto.PolicyResponse;
import com.nubeero.cia.storage.DocumentStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/partner/v1/policies")
@Tag(name = "Policies", description = "Policy issuance and retrieval")
@SecurityRequirement(name = "bearer-key")
@RequiredArgsConstructor
public class PartnerPolicyController {

    private final PolicyService policyService;
    private final DocumentStorageService documentStorageService;

    @PostMapping
    @Operation(summary = "Bind a policy from an approved quote",
               description = "Converts an APPROVED quote into an active policy and triggers an async NAICOM upload. " +
                             "The naicomUid field will read PENDING until the upload is confirmed.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Policy issued",
            content = @Content(schema = @Schema(implementation = PartnerPolicyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error or quote not in APPROVED state", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized — invalid or expired token", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — insufficient scope", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Quote not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded", content = @Content)
    })
    public ResponseEntity<ApiResponse<PartnerPolicyResponse>> bindFromQuote(@RequestParam UUID quoteId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        PartnerPolicyResponse.from(policyService.bindFromQuote(quoteId))));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get policy details and status")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Policy found",
            content = @Content(schema = @Schema(implementation = PartnerPolicyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized — invalid or expired token", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — insufficient scope", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Policy not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded", content = @Content)
    })
    public ResponseEntity<ApiResponse<PartnerPolicyResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(
                PartnerPolicyResponse.from(policyService.get(id))));
    }

    @GetMapping("/{id}/document")
    @Operation(summary = "Download the policy certificate PDF",
               description = "Streams the approved policy certificate as a PDF binary. " +
                             "Returns 404 if the document has not yet been generated (policy not yet approved).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "PDF document",
            content = @Content(mediaType = "application/pdf")),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized — invalid or expired token", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — insufficient scope", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Policy not found or document not yet generated", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded", content = @Content)
    })
    public ResponseEntity<InputStreamResource> downloadDocument(@PathVariable UUID id) {
        PolicyResponse policy = policyService.get(id);
        if (policy.getPolicyDocumentPath() == null) {
            return ResponseEntity.notFound().build();
        }
        String tenantId = TenantContext.getTenantId();
        var stream = documentStorageService.download(tenantId, policy.getPolicyDocumentPath());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"policy-" + policy.getPolicyNumber() + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(new InputStreamResource(stream));
    }
}
