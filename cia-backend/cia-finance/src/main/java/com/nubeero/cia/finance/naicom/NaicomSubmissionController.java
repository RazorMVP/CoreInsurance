package com.nubeero.cia.finance.naicom;

import com.nubeero.cia.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST surface for the NAICOM submission state machine.
 *
 * <p>Module 12 Phase 4 Slice 4.9. Endpoints under
 * {@code /api/v1/finance/naicom/submissions}.
 *
 * <h2>RBAC</h2>
 * <ul>
 *   <li>Reads ({@code GET}) — {@code FINANCE_VIEW}.</li>
 *   <li>Writes ({@code POST}) — {@code FINANCE_APPROVE}: generation +
 *       state transitions are auditor-visible regulator-bound actions
 *       and share the same bar as the IFRS 9 / PAA engine endpoints.</li>
 * </ul>
 *
 * <p>The {@code actor} on every write is resolved from
 * {@code Authentication.getName()} — never trusted from the request
 * body. {@code reason} comes from the body and lands in
 * {@code naicom_submission_event.reason}.
 */
@RestController
@RequestMapping("/api/v1/finance/naicom/submissions")
@Tag(name = "NAICOM Submissions",
     description = "Monthly recap submissions to NAICOM (N01–N08). Period must be HARD_CLOSED before generation. Submissions follow a DRAFT → SUBMITTED → ACKNOWLEDGED → ARCHIVED state machine with a RETRACTED branch from SUBMITTED. All state transitions append-only via naicom_submission_event (Type-2 SCD).")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class NaicomSubmissionController {

    private final NaicomSubmissionService submissionService;
    private final SubmissionArtifactService artifactService;

    // ── Generation + read ──────────────────────────────────────────────

    @PostMapping("/generate")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    @Operation(
        summary = "Generate or re-generate a submission",
        description = "Computes the payload for the given (submissionType, periodId) via the registered NaicomSubmissionEngine. " +
                      "Idempotent under (submissionType, period_id, tenant_id) WHERE deleted_at IS NULL. " +
                      "Re-running for an existing DRAFT row updates the payload in place; once SUBMITTED, the payload is frozen and re-generation throws PayloadFrozenException (409). " +
                      "Period must be HARD_CLOSED (422 PeriodNotHardClosedException otherwise).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Submission generated (DRAFT) — payload returned",
            content = @Content(schema = @Schema(implementation = NaicomSubmissionResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error (missing submissionType or periodId)", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized — JWT missing or invalid", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "PayloadFrozenException — SUBMITTED row cannot be re-generated", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "PeriodNotHardClosedException — generation requires HARD_CLOSED period", content = @Content)
    })
    public ApiResponse<NaicomSubmissionResponse> generate(
            @Valid @RequestBody GenerateRequest request,
            Authentication authentication) {
        NaicomSubmission row = submissionService.generate(
            request.submissionType(), request.periodId(),
            request.reason(), actor(authentication));
        return ApiResponse.success(NaicomSubmissionResponse.withPayload(row));
    }

    @GetMapping
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(
        summary = "List submissions filtered by period and/or state",
        description = "At least one of (periodId, state) must be supplied — both omitted is rejected to avoid a full-table scan. " +
                      "When both are supplied, results are the intersection. Returns summary projections (payload omitted).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Submissions matching the filter",
            content = @Content(schema = @Schema(implementation = NaicomSubmissionResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Both filters omitted", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content)
    })
    public ApiResponse<List<NaicomSubmissionResponse>> list(
            @Parameter(description = "Filter by fiscal period UUID") @RequestParam(required = false) UUID periodId,
            @Parameter(description = "Filter by submission state (DRAFT | SUBMITTED | ACKNOWLEDGED | ARCHIVED | RETRACTED)") @RequestParam(required = false) NaicomSubmissionState state) {
        // Two of the three filter combinations the orchestrator natively
        // supports — both filters omitted is rejected to avoid full-table
        // scans by accident.
        if (periodId == null && state == null) {
            throw new IllegalArgumentException(
                "At least one of (periodId, state) must be supplied.");
        }
        List<NaicomSubmission> rows = periodId != null
            ? submissionService.findByPeriod(periodId)
            : submissionService.findByState(state);
        // When both filters were supplied, intersect — the service does not
        // expose a combined repo method to keep the projection tight.
        if (periodId != null && state != null) {
            rows = rows.stream().filter(s -> s.getState() == state).toList();
        }
        return ApiResponse.success(rows.stream()
            .map(NaicomSubmissionResponse::summary)
            .toList());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(
        summary = "Get a submission with its payload",
        description = "Returns the full submission including the rendered payload (engine output).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Submission found",
            content = @Content(schema = @Schema(implementation = NaicomSubmissionResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "NaicomSubmissionNotFoundException", content = @Content)
    })
    public ApiResponse<NaicomSubmissionResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(
            NaicomSubmissionResponse.withPayload(submissionService.findById(id)));
    }

    @GetMapping("/{id}/events")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(
        summary = "List state-transition events for a submission",
        description = "Returns the append-only Type-2 SCD history of state transitions on this submission (DRAFT → SUBMITTED, RETRACTED, etc.). The row sequence IS the audit history; there is no separate history table.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Event list (chronological)",
            content = @Content(schema = @Schema(implementation = NaicomSubmissionEventResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Submission not found", content = @Content)
    })
    public ApiResponse<List<NaicomSubmissionEventResponse>> events(@PathVariable UUID id) {
        return ApiResponse.success(submissionService.findEvents(id).stream()
            .map(NaicomSubmissionEventResponse::from)
            .toList());
    }

    // ── Transitions ────────────────────────────────────────────────────

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    @Operation(
        summary = "Transition DRAFT → SUBMITTED",
        description = "Marks the submission as sent to NAICOM. After this the payload is frozen (re-generation rejected with 409 PayloadFrozenException). The reason field is optional and lands in naicom_submission_event.reason.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "State transitioned to SUBMITTED",
            content = @Content(schema = @Schema(implementation = NaicomSubmissionResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Submission not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "IllegalSubmissionStateException — only DRAFT can transition to SUBMITTED", content = @Content)
    })
    public ApiResponse<NaicomSubmissionResponse> submit(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) ReasonOnlyRequest body,
            Authentication authentication) {
        String reason = body == null ? null : body.reason();
        return ApiResponse.success(NaicomSubmissionResponse.withPayload(
            submissionService.submit(id, reason, actor(authentication))));
    }

    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    @Operation(
        summary = "Transition SUBMITTED → ACKNOWLEDGED",
        description = "Records that NAICOM acknowledged the submission. Requires the naicomUid returned by the regulator.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "State transitioned to ACKNOWLEDGED",
            content = @Content(schema = @Schema(implementation = NaicomSubmissionResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "naicomUid missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Submission not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "IllegalSubmissionStateException — only SUBMITTED can transition to ACKNOWLEDGED", content = @Content)
    })
    public ApiResponse<NaicomSubmissionResponse> acknowledge(
            @PathVariable UUID id,
            @Valid @RequestBody AcknowledgeRequest body,
            Authentication authentication) {
        return ApiResponse.success(NaicomSubmissionResponse.withPayload(
            submissionService.acknowledge(id, body.naicomUid(), actor(authentication))));
    }

    @PostMapping("/{id}/retract")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    @Operation(
        summary = "Transition SUBMITTED → RETRACTED (audit branch)",
        description = "Soft-deletes the row to vacate the (submission_type, period_id) UNIQUE slot, allowing a fresh corrected submission. The retracted row survives as soft-deleted audit evidence. Reason is mandatory.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "State transitioned to RETRACTED",
            content = @Content(schema = @Schema(implementation = NaicomSubmissionResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "reason missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Submission not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "IllegalSubmissionStateException — only SUBMITTED can transition to RETRACTED", content = @Content)
    })
    public ApiResponse<NaicomSubmissionResponse> retract(
            @PathVariable UUID id,
            @Valid @RequestBody RetractRequest body,
            Authentication authentication) {
        return ApiResponse.success(NaicomSubmissionResponse.withPayload(
            submissionService.retract(id, body.reason(), actor(authentication))));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    @Operation(
        summary = "Transition ACKNOWLEDGED → ARCHIVED",
        description = "Terminal state. Indicates the submission and its NAICOM acknowledgement have been moved to long-term audit retention.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "State transitioned to ARCHIVED",
            content = @Content(schema = @Schema(implementation = NaicomSubmissionResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Submission not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "IllegalSubmissionStateException — only ACKNOWLEDGED can transition to ARCHIVED", content = @Content)
    })
    public ApiResponse<NaicomSubmissionResponse> archive(
            @PathVariable UUID id,
            Authentication authentication) {
        return ApiResponse.success(NaicomSubmissionResponse.withPayload(
            submissionService.archive(id, actor(authentication))));
    }

    // ── Artifacts (Slice 4.10) ─────────────────────────────────────────

    /**
     * Render an artifact in the requested {@link ArtifactFormat}.
     * Replaces any existing live artifact for the same (submission,
     * format) — the prior row is soft-deleted (V41
     * uq_naicom_submission_artifact_format).
     */
    @PostMapping("/{id}/artifacts/{format}")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    @Operation(
        summary = "Render an artifact in the requested format (JSON | CSV | PDF)",
        description = "Renders the submission payload to bytes via JsonArtifactRenderer / CsvArtifactRenderer / PdfArtifactRenderer; stores via DocumentStorageService; records SHA-256 hash for tamper evidence. " +
                      "Replaces any existing live artifact for the same (submission, format) — the prior row is soft-deleted (V41 uq_naicom_submission_artifact_format).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Artifact rendered and stored",
            content = @Content(schema = @Schema(implementation = SubmissionArtifactResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Submission not found", content = @Content)
    })
    public ApiResponse<SubmissionArtifactResponse> renderArtifact(
            @PathVariable UUID id,
            @Parameter(description = "Artifact format (JSON | CSV | PDF)") @PathVariable ArtifactFormat format,
            Authentication authentication) {
        return ApiResponse.success(SubmissionArtifactResponse.from(
            artifactService.render(id, format, actor(authentication))));
    }

    @GetMapping("/{id}/artifacts")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(
        summary = "List artifacts for a submission",
        description = "Returns metadata for every live and soft-deleted artifact ever rendered for this submission. Bytes are NOT streamed by this endpoint — use the download endpoint for that.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Artifact metadata",
            content = @Content(schema = @Schema(implementation = SubmissionArtifactResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Submission not found", content = @Content)
    })
    public ApiResponse<List<SubmissionArtifactResponse>> listArtifacts(@PathVariable UUID id) {
        return ApiResponse.success(artifactService.findBySubmission(id).stream()
            .map(SubmissionArtifactResponse::from)
            .toList());
    }

    @GetMapping("/{id}/artifacts/{format}/download")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(
        summary = "Download the live artifact bytes",
        description = "Streams the artifact bytes via DocumentStorageService. Returns 404 if no LIVE artifact exists for the (submission, format) pair — soft-deleted artifacts are not downloadable.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Artifact bytes streamed (Content-Type set per format)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ArtifactNotFoundException — no live artifact for this (submission, format)", content = @Content)
    })
    public ResponseEntity<InputStreamResource> downloadArtifact(
            @PathVariable UUID id,
            @Parameter(description = "Artifact format (JSON | CSV | PDF)") @PathVariable ArtifactFormat format) {
        SubmissionArtifactService.ArtifactDownload download =
            artifactService.openDownload(id, format);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(download.mimeType()))
            .contentLength(download.sizeBytes())
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + download.filename() + "\"")
            .body(new InputStreamResource(download.stream()));
    }

    private static String actor(Authentication authentication) {
        return authentication == null ? "system" : authentication.getName();
    }

    // ── Request DTOs ───────────────────────────────────────────────────

    public record GenerateRequest(
        @NotNull NaicomSubmissionType submissionType,
        @NotNull UUID periodId,
        String reason
    ) {}

    public record ReasonOnlyRequest(String reason) {}

    public record AcknowledgeRequest(
        @NotBlank String naicomUid,
        String reason
    ) {}

    public record RetractRequest(
        @NotBlank String reason
    ) {}
}
