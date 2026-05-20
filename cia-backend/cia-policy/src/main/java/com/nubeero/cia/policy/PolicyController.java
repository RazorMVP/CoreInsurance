package com.nubeero.cia.policy;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.policy.dto.*;
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
@RequestMapping("/api/v1/policies")
@Tag(name = "Policies (Module 3)",
     description = "Policy lifecycle — DRAFT → PENDING_APPROVAL → ACTIVE → CANCELLED / EXPIRED. Policies can be bound from an approved quote or created directly. Approval fires POLICY_APPROVED event → SubledgerPostingService → DebitNote + JE; NAICOM/NIID upload child workflows kick off asynchronously.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class PolicyController {

    private final PolicyService       service;
    private final PolicySurveyService surveyService;

    @GetMapping
    @PreAuthorize("hasRole('UNDERWRITING_VIEW')")
    @Operation(summary = "List policies (paginated, filterable)",
               description = "Filter by status and/or customerId; both omitted returns all. Returns lightweight summary projection (no risks or coinsurance participants).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Policy page",
            content = @Content(schema = @Schema(implementation = PolicySummaryResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_VIEW", content = @Content)
    })
    public ApiResponse<Page<PolicySummaryResponse>> list(
            @RequestParam(required = false) PolicyStatus status,
            @RequestParam(required = false) UUID customerId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(service.list(status, customerId, pageable));
    }

    @GetMapping("/search")
    @PreAuthorize("hasRole('UNDERWRITING_VIEW')")
    @Operation(summary = "Search policies by free text",
               description = "Matches against policy number, customer name, product name. Case-insensitive substring search.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Matching policies",
            content = @Content(schema = @Schema(implementation = PolicySummaryResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_VIEW", content = @Content)
    })
    public ApiResponse<Page<PolicySummaryResponse>> search(
            @RequestParam String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(service.search(q, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('UNDERWRITING_VIEW')")
    @Operation(summary = "Get policy detail",
               description = "Returns the full policy including risks, coinsurance participants, premium breakdown, document state, NAICOM UID, NIID reference.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Policy found",
            content = @Content(schema = @Schema(implementation = PolicyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Policy not found", content = @Content)
    })
    public ApiResponse<PolicyResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping("/bind-from-quote/{quoteId}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('UNDERWRITING_CREATE')")
    @Operation(summary = "Bind a policy from an approved quote",
               description = "Creates a new policy in DRAFT state populated from an approved quote (customer, product, dates, risks, premium). The quote is marked CONVERTED. The policy still requires submission + approval before becoming ACTIVE.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Policy created from quote",
            content = @Content(schema = @Schema(implementation = PolicyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Quote not in APPROVED state", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_CREATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Quote not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Quote already converted to a policy", content = @Content)
    })
    public ApiResponse<PolicyResponse> bindFromQuote(@PathVariable UUID quoteId) {
        return ApiResponse.success(service.bindFromQuote(quoteId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('UNDERWRITING_CREATE')")
    @Operation(summary = "Create a policy directly (without a quote)",
               description = "Used for renewals, broker-relationship-only business, or any path where a formal quote was never issued. Policy starts in DRAFT; submission + approval are separate steps.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Policy created",
            content = @Content(schema = @Schema(implementation = PolicyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_CREATE", content = @Content)
    })
    public ApiResponse<PolicyResponse> create(@Valid @RequestBody PolicyRequest request) {
        return ApiResponse.success(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    @Operation(summary = "Update a DRAFT policy",
               description = "Allows updates to the policy header (dates, premium, payment terms) while the policy is in DRAFT. Submission locks the policy from this endpoint.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Policy updated",
            content = @Content(schema = @Schema(implementation = PolicyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Policy not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Policy not in DRAFT state", content = @Content)
    })
    public ApiResponse<PolicyResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody PolicyUpdateRequest request) {
        return ApiResponse.success(service.update(id, request));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    @Operation(summary = "Submit policy for approval",
               description = "Transitions DRAFT → PENDING_APPROVAL. Validates the risk schedule (at least one risk), survey requirements (if any), and premium. Starts the PolicyApprovalWorkflow Temporal workflow.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Submitted for approval",
            content = @Content(schema = @Schema(implementation = PolicyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Required pre-loss survey missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Policy not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Policy not in DRAFT state", content = @Content)
    })
    public ApiResponse<PolicyResponse> submit(@PathVariable UUID id) {
        return ApiResponse.success(service.submit(id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('UNDERWRITING_APPROVE')")
    @Operation(summary = "Approve a policy",
               description = "Transitions PENDING_APPROVAL → ACTIVE. Fires POLICY_APPROVED event → SubledgerPostingService cascade (DebitNote + JE for the gross premium). Kicks off NAICOM upload child workflow (and NIID if motor/marine). Period-lock check applies.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Policy approved",
            content = @Content(schema = @Schema(implementation = PolicyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_APPROVE or amount exceeds approver tier", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Policy not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Policy not in PENDING_APPROVAL state", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "Target period is closed", content = @Content)
    })
    public ApiResponse<PolicyResponse> approve(
            @PathVariable UUID id,
            @RequestBody(required = false) PolicyApprovalRequest request) {
        return ApiResponse.success(service.approve(id, request));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('UNDERWRITING_APPROVE')")
    @Operation(summary = "Reject a policy",
               description = "Transitions PENDING_APPROVAL → DRAFT with rejection notes — underwriter can edit and re-submit. No GL impact.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Policy rejected",
            content = @Content(schema = @Schema(implementation = PolicyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Policy not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Policy not in PENDING_APPROVAL state", content = @Content)
    })
    public ApiResponse<PolicyResponse> reject(
            @PathVariable UUID id,
            @RequestBody(required = false) PolicyApprovalRequest request) {
        return ApiResponse.success(service.reject(id, request));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    @Operation(summary = "Cancel an active policy",
               description = "Transitions ACTIVE → CANCELLED with mandatory reason. Issues a credit-note endorsement for the unearned premium pro-rata. If the policy has claims, those remain on the original cover period.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Policy cancelled",
            content = @Content(schema = @Schema(implementation = PolicyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Reason missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Policy not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Policy not ACTIVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "Target period is closed", content = @Content)
    })
    public ApiResponse<PolicyResponse> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody PolicyCancellationRequest request) {
        return ApiResponse.success(service.cancel(id, request));
    }

    @PostMapping("/{id}/reinstate")
    @PreAuthorize("hasRole('UNDERWRITING_APPROVE')")
    @Operation(summary = "Reinstate a cancelled policy",
               description = "Reverses a cancellation: CANCELLED → ACTIVE. Reverses the credit-note endorsement issued at cancellation. Requires UNDERWRITING_APPROVE since it materially restores cover.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Policy reinstated",
            content = @Content(schema = @Schema(implementation = PolicyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Policy not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Policy not CANCELLED, or cover dates expired", content = @Content)
    })
    public ApiResponse<PolicyResponse> reinstate(@PathVariable UUID id) {
        return ApiResponse.success(service.reinstate(id));
    }

    @PostMapping("/{id}/naicom-upload")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    @Operation(summary = "Trigger NAICOM upload (manual retry)",
               description = "Starts a NaicomUploadWorkflow OR signals an existing one. Used when the post-approval automatic upload failed and the underwriter wants to retry on-demand. NAICOM UID is patched onto the policy when accepted (was PENDING).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Upload workflow signalled",
            content = @Content(schema = @Schema(implementation = PolicyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Policy not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Policy not ACTIVE, or NAICOM UID already received", content = @Content)
    })
    public ApiResponse<PolicyResponse> triggerNaicomUpload(@PathVariable UUID id) {
        return ApiResponse.success(service.triggerNaicomUpload(id));
    }

    @PostMapping("/{id}/niid-upload")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    @Operation(summary = "Trigger NIID upload (manual retry)",
               description = "Same shape as NAICOM upload, but for motor + marine policies that need NIID registration. NIID reference is patched onto the policy when accepted.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Upload workflow signalled",
            content = @Content(schema = @Schema(implementation = PolicyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Policy not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Policy not eligible (not motor/marine, or NIID already received)", content = @Content)
    })
    public ApiResponse<PolicyResponse> triggerNiidUpload(@PathVariable UUID id) {
        return ApiResponse.success(service.triggerNiidUpload(id));
    }

    // ─── Risks (DRAFT-only — submission locks the risk schedule) ──────────

    @PutMapping("/{id}/risks/{riskId}")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    @Operation(summary = "Update a single risk on the policy schedule",
               description = "Only allowed while policy is DRAFT. Updates description, sum insured, rate, motor reg no (for motor classes), or any product-specific fields.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Risk updated",
            content = @Content(schema = @Schema(implementation = PolicyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Policy or risk not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Policy not in DRAFT state", content = @Content)
    })
    public ApiResponse<PolicyResponse> updateRisk(
            @PathVariable UUID id,
            @PathVariable UUID riskId,
            @Valid @RequestBody PolicyRiskRequest request) {
        return ApiResponse.success(service.updateRisk(id, riskId, request));
    }

    @PostMapping("/{id}/risks/bulk")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    @Operation(summary = "Add multiple risks at once (bulk insert)",
               description = "Used by the bulk-upload flow on the policy detail page. All-or-nothing — any validation failure rolls back the batch. Policy must be DRAFT.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Risks added",
            content = @Content(schema = @Schema(implementation = PolicyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error on any row", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Policy not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Policy not in DRAFT state", content = @Content)
    })
    public ApiResponse<PolicyResponse> addRisksBulk(
            @PathVariable UUID id,
            @Valid @RequestBody java.util.List<PolicyRiskRequest> requests) {
        return ApiResponse.success(service.addRisksBulk(id, requests));
    }

    @DeleteMapping("/{id}/risks/{riskId}")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    @Operation(summary = "Remove a risk from the schedule",
               description = "Hard-deletes the risk row. Only allowed while policy is DRAFT — the risk schedule is locked at submission.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Risk removed",
            content = @Content(schema = @Schema(implementation = PolicyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Policy or risk not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Policy not in DRAFT state", content = @Content)
    })
    public ApiResponse<PolicyResponse> deleteRisk(
            @PathVariable UUID id,
            @PathVariable UUID riskId) {
        return ApiResponse.success(service.deleteRisk(id, riskId));
    }

    @PutMapping("/{id}/coinsurance")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    @Operation(summary = "Replace the coinsurance participant list",
               description = "Sets the full list of coinsurance participants on the policy. Participant shares must sum to 100%. Only valid for Direct-with-Coinsurance and Inward-Coinsurance business types. Policy must be DRAFT.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Coinsurance updated",
            content = @Content(schema = @Schema(implementation = PolicyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Shares do not sum to 100%, or business type does not support coinsurance", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Policy not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Policy not in DRAFT state", content = @Content)
    })
    public ApiResponse<PolicyResponse> updateCoinsurance(
            @PathVariable UUID id,
            @Valid @RequestBody java.util.List<PolicyCoinsuranceParticipantRequest> requests) {
        return ApiResponse.success(service.updateCoinsurance(id, requests));
    }

    // ─── Policy document delivery / acknowledgement / download ────────────

    @PostMapping("/{id}/document/send")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    @Operation(summary = "Email the policy document to the insured",
               description = "Triggers the email-delivery activity in PolicyApprovalWorkflow on-demand. Marks documentSentAt on the policy. Allowed for ACTIVE policies whose document has been generated.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Email dispatched",
            content = @Content(schema = @Schema(implementation = PolicyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Policy not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Policy not ACTIVE, or document not yet generated", content = @Content)
    })
    public ApiResponse<PolicyResponse> sendPolicyDocument(@PathVariable UUID id) {
        return ApiResponse.success(service.sendPolicyDocument(id));
    }

    @PostMapping("/{id}/document/acknowledge")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    @Operation(summary = "Record that the insured acknowledged the policy document",
               description = "Sets documentAcknowledgedAt on the policy. Used by the back-office to record a phone/email confirmation when the customer has not used the self-serve acknowledge link.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Acknowledgement recorded",
            content = @Content(schema = @Schema(implementation = PolicyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Policy not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Document not yet sent", content = @Content)
    })
    public ApiResponse<PolicyResponse> acknowledgePolicyDocument(@PathVariable UUID id) {
        return ApiResponse.success(service.acknowledgePolicyDocument(id));
    }

    @GetMapping("/{id}/document")
    @PreAuthorize("hasRole('UNDERWRITING_VIEW')")
    @Operation(summary = "Download the policy document PDF",
               description = "Streams the policy PDF rendered by cia-documents at approval time. Includes the embedded NAICOM certificate, clause bank, and signatures.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "PDF stream"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Policy or its document not found", content = @Content)
    })
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
    @Operation(summary = "Get the pre-loss survey record",
               description = "Returns survey state — surveyor assignment, submitted report, approve/override outcome. Returns 404 if no survey has been initiated (some products do not require one).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Survey state",
            content = @Content(schema = @Schema(implementation = PolicySurveyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No survey exists for this policy", content = @Content)
    })
    public ApiResponse<PolicySurveyResponse> getSurvey(@PathVariable UUID id) {
        return ApiResponse.success(surveyService.get(id));
    }

    @PostMapping("/{id}/survey/assign")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    @Operation(summary = "Assign surveyor (internal or external)",
               description = "Creates the survey record (state ASSIGNED). Idempotent re-assignment supported. Triggers an email notification to the surveyor.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Surveyor assigned",
            content = @Content(schema = @Schema(implementation = PolicySurveyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Surveyor inactive or unknown", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Policy not found", content = @Content)
    })
    public ApiResponse<PolicySurveyResponse> assignSurveyor(
            @PathVariable UUID id,
            @Valid @RequestBody AssignSurveyorRequest request) {
        return ApiResponse.success(surveyService.assignSurveyor(id, request));
    }

    @PostMapping("/{id}/survey/report")
    @PreAuthorize("hasRole('UNDERWRITING_UPDATE')")
    @Operation(summary = "Submit pre-loss survey report",
               description = "Transitions ASSIGNED → REPORT_SUBMITTED. Captures findings, recommended sum-insured cap, and risk-mitigation notes.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Report submitted",
            content = @Content(schema = @Schema(implementation = PolicySurveyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Survey not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Survey not in ASSIGNED state", content = @Content)
    })
    public ApiResponse<PolicySurveyResponse> submitSurveyReport(
            @PathVariable UUID id,
            @Valid @RequestBody SurveyReportRequest request) {
        return ApiResponse.success(surveyService.submitReport(id, request));
    }

    @PostMapping("/{id}/survey/approve")
    @PreAuthorize("hasRole('UNDERWRITING_APPROVE')")
    @Operation(summary = "Approve the survey report",
               description = "Transitions REPORT_SUBMITTED → APPROVED. Required before policy submission if the product configures survey-mandatory.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Survey approved",
            content = @Content(schema = @Schema(implementation = PolicySurveyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Survey not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Survey not in REPORT_SUBMITTED state", content = @Content)
    })
    public ApiResponse<PolicySurveyResponse> approveSurvey(
            @PathVariable UUID id,
            @RequestBody(required = false) ApproveSurveyRequest request) {
        return ApiResponse.success(surveyService.approve(id, request));
    }

    @PostMapping("/{id}/survey/override")
    @PreAuthorize("hasRole('UNDERWRITING_APPROVE')")
    @Operation(summary = "Override the survey requirement",
               description = "Manager-level override (UNDERWRITING_APPROVE) that bypasses the normal survey approval flow. Records overrideReason for audit. Allowed from any non-terminal survey state.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Survey overridden",
            content = @Content(schema = @Schema(implementation = PolicySurveyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Reason missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks UNDERWRITING_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Survey not found", content = @Content)
    })
    public ApiResponse<PolicySurveyResponse> overrideSurvey(
            @PathVariable UUID id,
            @Valid @RequestBody OverrideSurveyRequest request) {
        return ApiResponse.success(surveyService.override(id, request));
    }
}
