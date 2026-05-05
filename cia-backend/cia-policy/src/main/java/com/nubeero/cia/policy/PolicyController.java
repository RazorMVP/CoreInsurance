package com.nubeero.cia.policy;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.policy.dto.*;
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
@RequestMapping("/api/v1/policies")
@RequiredArgsConstructor
public class PolicyController {

    private final PolicyService       service;
    private final PolicySurveyService surveyService;

    @GetMapping
    @PreAuthorize("hasRole('UNDERWRITING_VIEW')")
    public ApiResponse<Page<PolicySummaryResponse>> list(
            @RequestParam(required = false) PolicyStatus status,
            @RequestParam(required = false) UUID customerId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(service.list(status, customerId, pageable));
    }

    @GetMapping("/search")
    @PreAuthorize("hasRole('UNDERWRITING_VIEW')")
    public ApiResponse<Page<PolicySummaryResponse>> search(
            @RequestParam String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(service.search(q, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('UNDERWRITING_VIEW')")
    public ApiResponse<PolicyResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping("/bind-from-quote/{quoteId}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('UNDERWRITING_CREATE')")
    public ApiResponse<PolicyResponse> bindFromQuote(@PathVariable UUID quoteId) {
        return ApiResponse.success(service.bindFromQuote(quoteId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('UNDERWRITING_CREATE')")
    public ApiResponse<PolicyResponse> create(@Valid @RequestBody PolicyRequest request) {
        return ApiResponse.success(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    public ApiResponse<PolicyResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody PolicyUpdateRequest request) {
        return ApiResponse.success(service.update(id, request));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    public ApiResponse<PolicyResponse> submit(@PathVariable UUID id) {
        return ApiResponse.success(service.submit(id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('UNDERWRITING_APPROVE')")
    public ApiResponse<PolicyResponse> approve(
            @PathVariable UUID id,
            @RequestBody(required = false) PolicyApprovalRequest request) {
        return ApiResponse.success(service.approve(id, request));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('UNDERWRITING_APPROVE')")
    public ApiResponse<PolicyResponse> reject(
            @PathVariable UUID id,
            @RequestBody(required = false) PolicyApprovalRequest request) {
        return ApiResponse.success(service.reject(id, request));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    public ApiResponse<PolicyResponse> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody PolicyCancellationRequest request) {
        return ApiResponse.success(service.cancel(id, request));
    }

    @PostMapping("/{id}/reinstate")
    @PreAuthorize("hasRole('UNDERWRITING_APPROVE')")
    public ApiResponse<PolicyResponse> reinstate(@PathVariable UUID id) {
        return ApiResponse.success(service.reinstate(id));
    }

    @PostMapping("/{id}/naicom-upload")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    public ApiResponse<PolicyResponse> triggerNaicomUpload(@PathVariable UUID id) {
        return ApiResponse.success(service.triggerNaicomUpload(id));
    }

    @PostMapping("/{id}/niid-upload")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    public ApiResponse<PolicyResponse> triggerNiidUpload(@PathVariable UUID id) {
        return ApiResponse.success(service.triggerNiidUpload(id));
    }

    // ─── Risks (DRAFT-only — submission locks the risk schedule) ──────────

    @PutMapping("/{id}/risks/{riskId}")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    public ApiResponse<PolicyResponse> updateRisk(
            @PathVariable UUID id,
            @PathVariable UUID riskId,
            @Valid @RequestBody PolicyRiskRequest request) {
        return ApiResponse.success(service.updateRisk(id, riskId, request));
    }

    @PostMapping("/{id}/risks/bulk")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    public ApiResponse<PolicyResponse> addRisksBulk(
            @PathVariable UUID id,
            @Valid @RequestBody java.util.List<PolicyRiskRequest> requests) {
        return ApiResponse.success(service.addRisksBulk(id, requests));
    }

    // ─── Policy document delivery / acknowledgement / download ────────────

    @PostMapping("/{id}/document/send")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    public ApiResponse<PolicyResponse> sendPolicyDocument(@PathVariable UUID id) {
        return ApiResponse.success(service.sendPolicyDocument(id));
    }

    @PostMapping("/{id}/document/acknowledge")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    public ApiResponse<PolicyResponse> acknowledgePolicyDocument(@PathVariable UUID id) {
        return ApiResponse.success(service.acknowledgePolicyDocument(id));
    }

    @GetMapping("/{id}/document")
    @PreAuthorize("hasRole('UNDERWRITING_VIEW')")
    public ResponseEntity<Resource> downloadPolicyDocument(@PathVariable UUID id) {
        PolicyService.PolicyDocumentDownload download = service.downloadPolicyDocument(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + download.filename() + "\"")
                .body(new InputStreamResource(download.content()));
    }

    // ─── Pre-loss survey workflow (DRAFT or PENDING_APPROVAL only) ────────

    @GetMapping("/{id}/survey")
    @PreAuthorize("hasRole('UNDERWRITING_VIEW')")
    public ApiResponse<PolicySurveyResponse> getSurvey(@PathVariable UUID id) {
        return ApiResponse.success(surveyService.get(id));
    }

    @PostMapping("/{id}/survey/assign")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    public ApiResponse<PolicySurveyResponse> assignSurveyor(
            @PathVariable UUID id,
            @Valid @RequestBody AssignSurveyorRequest request) {
        return ApiResponse.success(surveyService.assignSurveyor(id, request));
    }

    @PostMapping("/{id}/survey/report")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    public ApiResponse<PolicySurveyResponse> submitSurveyReport(
            @PathVariable UUID id,
            @Valid @RequestBody SurveyReportRequest request) {
        return ApiResponse.success(surveyService.submitReport(id, request));
    }

    @PostMapping("/{id}/survey/approve")
    @PreAuthorize("hasRole('UNDERWRITING_APPROVE')")
    public ApiResponse<PolicySurveyResponse> approveSurvey(
            @PathVariable UUID id,
            @RequestBody(required = false) ApproveSurveyRequest request) {
        return ApiResponse.success(surveyService.approve(id, request));
    }

    @PostMapping("/{id}/survey/override")
    @PreAuthorize("hasRole('UNDERWRITING_APPROVE')")
    public ApiResponse<PolicySurveyResponse> overrideSurvey(
            @PathVariable UUID id,
            @Valid @RequestBody OverrideSurveyRequest request) {
        return ApiResponse.success(surveyService.override(id, request));
    }
}
