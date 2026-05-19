package com.nubeero.cia.finance.naicom;

import com.nubeero.cia.common.api.ApiResponse;
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
@RequiredArgsConstructor
public class NaicomSubmissionController {

    private final NaicomSubmissionService submissionService;
    private final SubmissionArtifactService artifactService;

    // ── Generation + read ──────────────────────────────────────────────

    @PostMapping("/generate")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
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
    public ApiResponse<List<NaicomSubmissionResponse>> list(
            @RequestParam(required = false) UUID periodId,
            @RequestParam(required = false) NaicomSubmissionState state) {
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
    public ApiResponse<NaicomSubmissionResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(
            NaicomSubmissionResponse.withPayload(submissionService.findById(id)));
    }

    @GetMapping("/{id}/events")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ApiResponse<List<NaicomSubmissionEventResponse>> events(@PathVariable UUID id) {
        return ApiResponse.success(submissionService.findEvents(id).stream()
            .map(NaicomSubmissionEventResponse::from)
            .toList());
    }

    // ── Transitions ────────────────────────────────────────────────────

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
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
    public ApiResponse<NaicomSubmissionResponse> acknowledge(
            @PathVariable UUID id,
            @Valid @RequestBody AcknowledgeRequest body,
            Authentication authentication) {
        return ApiResponse.success(NaicomSubmissionResponse.withPayload(
            submissionService.acknowledge(id, body.naicomUid(), actor(authentication))));
    }

    @PostMapping("/{id}/retract")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    public ApiResponse<NaicomSubmissionResponse> retract(
            @PathVariable UUID id,
            @Valid @RequestBody RetractRequest body,
            Authentication authentication) {
        return ApiResponse.success(NaicomSubmissionResponse.withPayload(
            submissionService.retract(id, body.reason(), actor(authentication))));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
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
    public ApiResponse<SubmissionArtifactResponse> renderArtifact(
            @PathVariable UUID id,
            @PathVariable ArtifactFormat format,
            Authentication authentication) {
        return ApiResponse.success(SubmissionArtifactResponse.from(
            artifactService.render(id, format, actor(authentication))));
    }

    /** Metadata listing — does not stream artifact bytes. */
    @GetMapping("/{id}/artifacts")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ApiResponse<List<SubmissionArtifactResponse>> listArtifacts(@PathVariable UUID id) {
        return ApiResponse.success(artifactService.findBySubmission(id).stream()
            .map(SubmissionArtifactResponse::from)
            .toList());
    }

    /**
     * Stream the live artifact bytes. Returns 404 if no live artifact
     * exists for the (submission, format) pair (via
     * {@code ArtifactNotFoundException}'s @ResponseStatus).
     */
    @GetMapping("/{id}/artifacts/{format}/download")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ResponseEntity<InputStreamResource> downloadArtifact(
            @PathVariable UUID id,
            @PathVariable ArtifactFormat format) {
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
