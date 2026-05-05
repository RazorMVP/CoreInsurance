package com.nubeero.cia.claims;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.claims.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/claims")
@RequiredArgsConstructor
public class ClaimController {

    private final ClaimService                service;
    private final ClaimInspectionService      inspectionService;
    private final ClaimDocumentService        documentService;
    private final ClaimRequiredDocumentService requiredDocumentService;

    @GetMapping
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    public ApiResponse<Page<ClaimResponse>> list(
            @RequestParam(required = false) UUID policyId,
            @RequestParam(required = false) ClaimStatus status,
            @RequestParam(required = false) UUID customerId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(
                service.list(policyId, status, customerId, pageable).map(this::toResponse));
    }

    @GetMapping("/search")
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    public ApiResponse<Page<ClaimResponse>> search(
            @RequestParam String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(service.search(q, pageable).map(this::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    public ApiResponse<ClaimResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.findOrThrow(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CLAIMS_CREATE')")
    public ApiResponse<ClaimResponse> register(
            @Valid @RequestBody RegisterClaimRequest req) {
        return ApiResponse.success(toResponse(service.register(req)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('CLAIMS_UPDATE')")
    public ApiResponse<ClaimResponse> update(
            @PathVariable UUID id,
            @RequestBody UpdateClaimRequest req) {
        return ApiResponse.success(toResponse(service.updateDetails(id, req)));
    }

    @PostMapping("/{id}/assign-surveyor")
    @PreAuthorize("hasRole('CLAIMS_UPDATE')")
    public ApiResponse<ClaimResponse> assignSurveyor(
            @PathVariable UUID id,
            @Valid @RequestBody AssignSurveyorRequest req) {
        return ApiResponse.success(toResponse(service.assignSurveyor(id, req.surveyorId())));
    }

    @PostMapping("/{id}/reserve")
    @PreAuthorize("hasRole('CLAIMS_UPDATE')")
    public ApiResponse<ClaimResponse> setReserve(
            @PathVariable UUID id,
            @Valid @RequestBody SetReserveRequest req) {
        return ApiResponse.success(toResponse(service.setReserve(id, req)));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('CLAIMS_CREATE')")
    public ApiResponse<ClaimResponse> submit(
            @PathVariable UUID id,
            @RequestBody(required = false) SubmitClaimRequest req) {
        return ApiResponse.success(toResponse(service.submitForApproval(id,
                req != null ? req : new SubmitClaimRequest(null))));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('CLAIMS_APPROVE')")
    public ApiResponse<ClaimResponse> approve(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.approve(id)));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('CLAIMS_APPROVE')")
    public ApiResponse<ClaimResponse> reject(
            @PathVariable UUID id,
            @Valid @RequestBody RejectClaimRequest req) {
        return ApiResponse.success(toResponse(service.reject(id, req.reason())));
    }

    @PostMapping("/{id}/withdraw")
    @PreAuthorize("hasRole('CLAIMS_UPDATE')")
    public ApiResponse<ClaimResponse> withdraw(
            @PathVariable UUID id,
            @Valid @RequestBody WithdrawClaimRequest req) {
        return ApiResponse.success(toResponse(service.withdraw(id, req.reason())));
    }

    @PostMapping("/{id}/settle")
    @PreAuthorize("hasRole('CLAIMS_APPROVE')")
    public ApiResponse<ClaimResponse> settle(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.markSettled(id)));
    }

    @PostMapping("/{id}/dv/generate")
    @PreAuthorize("hasRole('CLAIMS_APPROVE')")
    public ApiResponse<ClaimResponse> generateDv(
            @PathVariable UUID id,
            @Valid @RequestBody GenerateDvRequest req) {
        return ApiResponse.success(toResponse(service.generateDv(id, req)));
    }

    @PostMapping("/{id}/dv/execute")
    @PreAuthorize("hasRole('CLAIMS_APPROVE')")
    public ApiResponse<ClaimResponse> executeDv(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.executeDv(id)));
    }

    // ─── Required documents (B12) ────────────────────────────────────────

    /**
     * Per-claim required-document checklist — derived at request time from
     * the product's {@code claim_document_requirements} setup joined with
     * uploaded {@code claim_documents}. See {@link ClaimRequiredDocumentService}.
     */
    @GetMapping("/{id}/required-documents")
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    public ApiResponse<java.util.List<com.nubeero.cia.claims.dto.ClaimRequiredDocumentResponse>>
            listRequiredDocuments(@PathVariable UUID id) {
        return ApiResponse.success(requiredDocumentService.list(id));
    }

    @GetMapping("/{id}/reserves")
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    public ApiResponse<Page<ClaimReserveResponse>> reserves(
            @PathVariable UUID id,
            @PageableDefault(size = 20) Pageable pageable) {
        Claim claim = service.findOrThrow(id);
        return ApiResponse.success(
                claim.getReserves().stream()
                        .filter(r -> r.getDeletedAt() == null)
                        .map(r -> new ClaimReserveResponse(
                                r.getId(), r.getAmount(), r.getPreviousAmount(),
                                r.getReason(), r.getCreatedBy(), r.getCreatedAt()))
                        .collect(java.util.stream.Collectors.collectingAndThen(
                                java.util.stream.Collectors.toList(),
                                list -> new org.springframework.data.domain.PageImpl<>(
                                        list, pageable, list.size()))));
    }

    // ─── Post-loss inspection workflow (B6) ───────────────────────────────

    @GetMapping("/{id}/inspection")
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    public ApiResponse<ClaimInspectionResponse> getInspection(@PathVariable UUID id) {
        return ApiResponse.success(inspectionService.get(id));
    }

    @PostMapping("/{id}/inspection/assign")
    @PreAuthorize("hasRole('CLAIMS_UPDATE')")
    public ApiResponse<ClaimInspectionResponse> assignInspector(
            @PathVariable UUID id,
            @Valid @RequestBody AssignInspectorRequest request) {
        return ApiResponse.success(inspectionService.assignInspector(id, request));
    }

    @PostMapping("/{id}/inspection/report")
    @PreAuthorize("hasRole('CLAIMS_UPDATE')")
    public ApiResponse<ClaimInspectionResponse> submitInspectionReport(
            @PathVariable UUID id,
            @Valid @RequestBody InspectionReportRequest request) {
        return ApiResponse.success(inspectionService.submitReport(id, request));
    }

    @PostMapping("/{id}/inspection/approve")
    @PreAuthorize("hasRole('CLAIMS_APPROVE')")
    public ApiResponse<ClaimInspectionResponse> approveInspection(
            @PathVariable UUID id,
            @RequestBody(required = false) ApproveInspectionRequest request) {
        return ApiResponse.success(inspectionService.approve(id, request));
    }

    @PostMapping("/{id}/inspection/decline")
    @PreAuthorize("hasRole('CLAIMS_APPROVE')")
    public ApiResponse<ClaimInspectionResponse> declineInspection(
            @PathVariable UUID id,
            @Valid @RequestBody DeclineInspectionRequest request) {
        return ApiResponse.success(inspectionService.decline(id, request));
    }

    @PostMapping("/{id}/inspection/override")
    @PreAuthorize("hasRole('CLAIMS_APPROVE')")
    public ApiResponse<ClaimInspectionResponse> overrideInspection(
            @PathVariable UUID id,
            @Valid @RequestBody OverrideInspectionRequest request) {
        return ApiResponse.success(inspectionService.override(id, request));
    }

    /**
     * Zip + stream every SURVEY_REPORT document for the claim. Underlying
     * docs live in object storage; the service composes a zip in memory
     * (claim-doc volumes are small in practice).
     */
    @GetMapping("/{id}/inspection/documents/bundle")
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    public ResponseEntity<Resource> downloadInspectionBundle(@PathVariable UUID id) {
        ClaimDocumentService.DocumentDownload dl = documentService.streamInspectionBundle(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + dl.filename() + "\"")
                .body(new InputStreamResource(dl.content()));
    }

    // ─── Mapping ──────────────────────────────────────────────────────────

    private ClaimResponse toResponse(Claim c) {
        return new ClaimResponse(
                c.getId(), c.getClaimNumber(), c.getStatus(),
                c.getPolicyId(), c.getPolicyNumber(),
                c.getPolicyStartDate(), c.getPolicyEndDate(),
                c.getCustomerId(), c.getCustomerName(),
                c.getProductName(), c.getClassOfBusinessName(),
                c.getBrokerId(), c.getBrokerName(),
                c.getIncidentDate(), c.getReportedDate(),
                c.getLossLocation(),
                c.getNatureOfLoss(), c.getCauseOfLoss(),
                c.getContactName(), c.getContactPhone(),
                c.getDescription(),
                c.getEstimatedLoss(), c.getReserveAmount(), c.getApprovedAmount(),
                c.getCurrencyCode(),
                c.getSurveyorId(), c.getSurveyorName(), c.getSurveyorAssignedAt(),
                c.getApprovedBy(), c.getApprovedAt(),
                c.getRejectedBy(), c.getRejectedAt(), c.getRejectionReason(),
                c.getWithdrawnBy(), c.getWithdrawnAt(), c.getWithdrawalReason(),
                c.getSettledAt(),
                c.getDvType(), c.getDvAmount(),
                c.getDvGeneratedAt(), c.getDvExecutedAt(), c.getDvDocumentPath(),
                c.getNotes(), c.getCreatedAt()
        );
    }
}
