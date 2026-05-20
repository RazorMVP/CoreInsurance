package com.nubeero.cia.claims;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.claims.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Claims (Module 5)",
     description = "Full claims lifecycle — registration → processing (reserves + surveyor + inspection) → submission → approval / rejection / withdrawal → DV generation → DV execution → settlement. CLAIM_APPROVED and CLAIM_SETTLED events drive SubledgerPostingService cascades (CreditNote + JE).")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class ClaimController {

    private final ClaimService                service;
    private final ClaimInspectionService      inspectionService;
    private final ClaimDocumentService        documentService;
    private final ClaimRequiredDocumentService requiredDocumentService;

    @GetMapping
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    @Operation(summary = "List claims (paginated, filterable)",
               description = "Filter by any combination of policyId, status, customerId. Returns claim summaries (no nested reserves/expenses/documents).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Claim page",
            content = @Content(schema = @Schema(implementation = ClaimResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_VIEW", content = @Content)
    })
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
    @Operation(summary = "Search claims by free text",
               description = "Matches against claim number, customer name, policy number, nature of loss. Case-insensitive substring search.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Matching claims",
            content = @Content(schema = @Schema(implementation = ClaimResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_VIEW", content = @Content)
    })
    public ApiResponse<Page<ClaimResponse>> search(
            @RequestParam String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(service.search(q, pageable).map(this::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    @Operation(summary = "Get claim detail",
               description = "Returns the full claim including current reserve, approved amount, DV state, surveyor assignment, and rejection/withdrawal context.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Claim found",
            content = @Content(schema = @Schema(implementation = ClaimResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Claim not found", content = @Content)
    })
    public ApiResponse<ClaimResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.findOrThrow(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CLAIMS_CREATE')")
    @Operation(summary = "Register a new claim",
               description = "Creates a claim against a policy in REGISTERED state. Validates: policy is ACTIVE, incident date falls within cover period, nature/cause of loss are valid for the product. Initial reserveAmount may be set later via /reserve.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Claim registered",
            content = @Content(schema = @Schema(implementation = ClaimResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error (policy inactive, date outside cover, etc.)", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_CREATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Policy not found", content = @Content)
    })
    public ApiResponse<ClaimResponse> register(
            @Valid @RequestBody RegisterClaimRequest req) {
        return ApiResponse.success(toResponse(service.register(req)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('CLAIMS_UPDATE')")
    @Operation(summary = "Update editable claim fields",
               description = "Partial update of nature/cause of loss, contact details, description, estimated loss. Only allowed while status is REGISTERED or PROCESSING.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Claim updated",
            content = @Content(schema = @Schema(implementation = ClaimResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Claim not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Claim in terminal state — cannot edit", content = @Content)
    })
    public ApiResponse<ClaimResponse> update(
            @PathVariable UUID id,
            @RequestBody UpdateClaimRequest req) {
        return ApiResponse.success(toResponse(service.updateDetails(id, req)));
    }

    @PostMapping("/{id}/assign-surveyor")
    @PreAuthorize("hasRole('CLAIMS_UPDATE')")
    @Operation(summary = "Assign a surveyor to the claim",
               description = "Surveyor must be active in setup → surveyors. Triggers an email notification to the surveyor. Independent of the inspection workflow under /inspection.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Surveyor assigned",
            content = @Content(schema = @Schema(implementation = ClaimResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Surveyor inactive or unknown", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Claim or surveyor not found", content = @Content)
    })
    public ApiResponse<ClaimResponse> assignSurveyor(
            @PathVariable UUID id,
            @Valid @RequestBody AssignSurveyorRequest req) {
        return ApiResponse.success(toResponse(service.assignSurveyor(id, req.surveyorId())));
    }

    @PostMapping("/{id}/reserve")
    @PreAuthorize("hasRole('CLAIMS_UPDATE')")
    @Operation(summary = "Update the claim reserve",
               description = "Sets a new reserve amount. The previous amount is preserved as a ClaimReserve history row (audit-grade), with a mandatory reason. Used by IFRS 17 PAA LIC engine to roll forward LIC totals.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Reserve updated",
            content = @Content(schema = @Schema(implementation = ClaimResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error (negative reserve, reason missing)", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Claim not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Cannot adjust reserve on SETTLED claim", content = @Content)
    })
    public ApiResponse<ClaimResponse> setReserve(
            @PathVariable UUID id,
            @Valid @RequestBody SetReserveRequest req) {
        return ApiResponse.success(toResponse(service.setReserve(id, req)));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('CLAIMS_CREATE')")
    @Operation(summary = "Submit claim for approval",
               description = "Transitions REGISTERED/PROCESSING → PENDING_APPROVAL. Validates required documents are uploaded (per product's claim_document_requirements). Starts the ClaimApprovalWorkflow Temporal workflow.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Submitted for approval",
            content = @Content(schema = @Schema(implementation = ClaimResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Missing required documents", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_CREATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Claim not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Claim not in a submittable state", content = @Content)
    })
    public ApiResponse<ClaimResponse> submit(
            @PathVariable UUID id,
            @RequestBody(required = false) SubmitClaimRequest req) {
        return ApiResponse.success(toResponse(service.submitForApproval(id,
                req != null ? req : new SubmitClaimRequest(null))));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('CLAIMS_APPROVE')")
    @Operation(summary = "Approve a claim",
               description = "Transitions PENDING_APPROVAL → APPROVED. Fires CLAIM_APPROVED event → SubledgerPostingService cascade (CreditNote + JE for the approved amount). Required before DV generation. Period-lock check applies.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Approved",
            content = @Content(schema = @Schema(implementation = ClaimResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Claim not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Claim not in PENDING_APPROVAL state", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "Target period is closed", content = @Content)
    })
    public ApiResponse<ClaimResponse> approve(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.approve(id)));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('CLAIMS_APPROVE')")
    @Operation(summary = "Reject a claim",
               description = "Transitions PENDING_APPROVAL → REJECTED with a mandatory reason. Reason surfaces on the claim detail and in customer notifications. No GL impact.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Rejected",
            content = @Content(schema = @Schema(implementation = ClaimResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Reason missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Claim not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Claim not in a rejectable state", content = @Content)
    })
    public ApiResponse<ClaimResponse> reject(
            @PathVariable UUID id,
            @Valid @RequestBody RejectClaimRequest req) {
        return ApiResponse.success(toResponse(service.reject(id, req.reason())));
    }

    @PostMapping("/{id}/withdraw")
    @PreAuthorize("hasRole('CLAIMS_UPDATE')")
    @Operation(summary = "Withdraw a claim (customer-initiated)",
               description = "Marks the claim WITHDRAWN with a reason — used when the policyholder decides not to pursue the claim. Allowed in pre-approval states only.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Withdrawn",
            content = @Content(schema = @Schema(implementation = ClaimResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Reason missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Claim not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Claim already approved or rejected", content = @Content)
    })
    public ApiResponse<ClaimResponse> withdraw(
            @PathVariable UUID id,
            @Valid @RequestBody WithdrawClaimRequest req) {
        return ApiResponse.success(toResponse(service.withdraw(id, req.reason())));
    }

    @PostMapping("/{id}/settle")
    @PreAuthorize("hasRole('CLAIMS_APPROVE')")
    @Operation(summary = "Mark claim settled",
               description = "Final state. Fires CLAIM_SETTLED event → final GL reconciliation. Typically called after the cascaded payment has cleared in Finance.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Settled",
            content = @Content(schema = @Schema(implementation = ClaimResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Claim not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Claim not APPROVED or DV not executed", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "Target period is closed", content = @Content)
    })
    public ApiResponse<ClaimResponse> settle(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.markSettled(id)));
    }

    @PostMapping("/{id}/dv/generate")
    @PreAuthorize("hasRole('CLAIMS_APPROVE')")
    @Operation(summary = "Generate a Discharge Voucher (DV)",
               description = "Renders the DV PDF (own-damage / third-party / ex-gratia type) using the configured template and the approved amount. Stores the artifact via DocumentStorageService and updates dvType + dvDocumentPath on the claim. Allowed after APPROVED.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "DV generated",
            content = @Content(schema = @Schema(implementation = ClaimResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "DV type missing or invalid amount", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Claim not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Claim not APPROVED — DV cannot be generated", content = @Content)
    })
    public ApiResponse<ClaimResponse> generateDv(
            @PathVariable UUID id,
            @Valid @RequestBody GenerateDvRequest req) {
        return ApiResponse.success(toResponse(service.generateDv(id, req)));
    }

    @PostMapping("/{id}/dv/execute")
    @PreAuthorize("hasRole('CLAIMS_APPROVE')")
    @Operation(summary = "Execute the DV (customer accepted)",
               description = "Records that the customer has signed/accepted the DV (sets dvExecutedAt). Settlement (/settle) is allowed after this; the cascaded payment can then be processed in Finance.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "DV executed",
            content = @Content(schema = @Schema(implementation = ClaimResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Claim not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DV not yet generated, or already executed", content = @Content)
    })
    public ApiResponse<ClaimResponse> executeDv(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.executeDv(id)));
    }

    // ─── Required documents (B12) ────────────────────────────────────────

    @GetMapping("/{id}/required-documents")
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    @Operation(summary = "Get required-documents checklist for the claim",
               description = "Derives the checklist at request time from the product's claim_document_requirements setup joined with currently-uploaded claim_documents. Used by the UI's missing-docs badge and by /submit's pre-flight validation.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Checklist with satisfied/missing flags",
            content = @Content(schema = @Schema(implementation = com.nubeero.cia.claims.dto.ClaimRequiredDocumentResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Claim not found", content = @Content)
    })
    public ApiResponse<java.util.List<com.nubeero.cia.claims.dto.ClaimRequiredDocumentResponse>>
            listRequiredDocuments(@PathVariable UUID id) {
        return ApiResponse.success(requiredDocumentService.list(id));
    }

    @GetMapping("/{id}/reserves")
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    @Operation(summary = "List reserve history for the claim",
               description = "Returns every reserve adjustment with previous amount, new amount, reason, author, timestamp. The reserve table is append-only (history is the audit trail).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Reserve history",
            content = @Content(schema = @Schema(implementation = ClaimReserveResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Claim not found", content = @Content)
    })
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
    @Operation(summary = "Get the claim's inspection record",
               description = "Returns the single inspection state for the claim — inspector assignment, submitted report, approve/decline/override outcome. Returns 404 if no inspection has been initiated.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Inspection state",
            content = @Content(schema = @Schema(implementation = ClaimInspectionResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No inspection exists for this claim", content = @Content)
    })
    public ApiResponse<ClaimInspectionResponse> getInspection(@PathVariable UUID id) {
        return ApiResponse.success(inspectionService.get(id));
    }

    @PostMapping("/{id}/inspection/assign")
    @PreAuthorize("hasRole('CLAIMS_UPDATE')")
    @Operation(summary = "Assign inspector + initiate inspection",
               description = "Creates the inspection record (state: ASSIGNED) and assigns an internal or external inspector. Triggers an email notification to the inspector. Idempotent re-assignment supported.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Inspector assigned",
            content = @Content(schema = @Schema(implementation = ClaimInspectionResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Inspector inactive or unknown", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Claim not found", content = @Content)
    })
    public ApiResponse<ClaimInspectionResponse> assignInspector(
            @PathVariable UUID id,
            @Valid @RequestBody AssignInspectorRequest request) {
        return ApiResponse.success(inspectionService.assignInspector(id, request));
    }

    @PostMapping("/{id}/inspection/report")
    @PreAuthorize("hasRole('CLAIMS_UPDATE')")
    @Operation(summary = "Submit the inspection report",
               description = "Transitions ASSIGNED → REPORT_SUBMITTED. Captures the inspector's findings, recommended loss amount, and signature. The next steps are approval/decline/override.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Report submitted",
            content = @Content(schema = @Schema(implementation = ClaimInspectionResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Inspection not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Inspection not in ASSIGNED state", content = @Content)
    })
    public ApiResponse<ClaimInspectionResponse> submitInspectionReport(
            @PathVariable UUID id,
            @Valid @RequestBody InspectionReportRequest request) {
        return ApiResponse.success(inspectionService.submitReport(id, request));
    }

    @PostMapping("/{id}/inspection/approve")
    @PreAuthorize("hasRole('CLAIMS_APPROVE')")
    @Operation(summary = "Approve the inspection report",
               description = "Transitions REPORT_SUBMITTED → APPROVED. Optionally records reviewer notes. Required before claim approval if the product configures inspection-mandatory.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Inspection approved",
            content = @Content(schema = @Schema(implementation = ClaimInspectionResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Inspection not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Inspection not in REPORT_SUBMITTED state", content = @Content)
    })
    public ApiResponse<ClaimInspectionResponse> approveInspection(
            @PathVariable UUID id,
            @RequestBody(required = false) ApproveInspectionRequest request) {
        return ApiResponse.success(inspectionService.approve(id, request));
    }

    @PostMapping("/{id}/inspection/decline")
    @PreAuthorize("hasRole('CLAIMS_APPROVE')")
    @Operation(summary = "Decline the inspection report",
               description = "Transitions REPORT_SUBMITTED → DECLINED with a mandatory reason. Sends the inspection back for re-work; new report can be submitted.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Inspection declined",
            content = @Content(schema = @Schema(implementation = ClaimInspectionResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Reason missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Inspection not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Inspection not in REPORT_SUBMITTED state", content = @Content)
    })
    public ApiResponse<ClaimInspectionResponse> declineInspection(
            @PathVariable UUID id,
            @Valid @RequestBody DeclineInspectionRequest request) {
        return ApiResponse.success(inspectionService.decline(id, request));
    }

    @PostMapping("/{id}/inspection/override")
    @PreAuthorize("hasRole('CLAIMS_APPROVE')")
    @Operation(summary = "Override the inspection outcome",
               description = "Manager-level override (CLAIMS_APPROVE) that bypasses normal approve/decline flow. Records overrideReason for audit. Allowed from any non-terminal inspection state.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Inspection overridden",
            content = @Content(schema = @Schema(implementation = ClaimInspectionResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Reason missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Inspection not found", content = @Content)
    })
    public ApiResponse<ClaimInspectionResponse> overrideInspection(
            @PathVariable UUID id,
            @Valid @RequestBody OverrideInspectionRequest request) {
        return ApiResponse.success(inspectionService.override(id, request));
    }

    @GetMapping("/{id}/inspection/documents/bundle")
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    @Operation(summary = "Download inspection-report documents as a zip",
               description = "Zips every SURVEY_REPORT document on the claim and streams the archive. Underlying docs live in DocumentStorageService; the zip is composed in memory (claim-doc volumes are small in practice).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Zip stream"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Claim not found", content = @Content)
    })
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
