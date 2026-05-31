package com.nubeero.cia.claims;

import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.claims.dto.ClaimDocumentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/claims/{claimId}/documents")
@Tag(name = "Claim Documents",
     description = "Document attachments on a claim. Files are uploaded multipart, streamed via DocumentStorageService, and tagged with ClaimDocumentType (e.g. INCIDENT_REPORT, POLICE_REPORT, REPAIR_QUOTE). Used by the required-documents tracker on the claim summary.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class ClaimDocumentController {

    private final ClaimDocumentService service;

    @GetMapping
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    @Operation(summary = "List documents attached to a claim",
               description = "Optionally filter by documentType. Returns metadata only — use /{id}/content to stream bytes.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Document page",
            content = @Content(schema = @Schema(implementation = ClaimDocumentResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Claim not found", content = @Content)
    })
    public ApiResponse<List<ClaimDocumentResponse>> list(
            @PathVariable UUID claimId,
            @RequestParam(required = false) ClaimDocumentType documentType,
            @PageableDefault(size = 2000) Pageable pageable) {
        Page<ClaimDocument> page = documentType != null
                ? service.findByClaimIdAndType(claimId, documentType, pageable)
                : service.findByClaimId(claimId, pageable);
        return ApiResponse.success(page.map(this::toResponse).getContent(),
                ApiMeta.builder()
                        .total(page.getTotalElements())
                        .page(page.getNumber())
                        .size(page.getSize())
                        .build());
    }

    @GetMapping("/{id}/content")
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    @Operation(summary = "Stream the document bytes",
               description = "Returns the raw file (PDF / image / etc) with Content-Disposition: attachment so browsers offer a download.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Document bytes streamed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Document not found", content = @Content)
    })
    public ResponseEntity<Resource> downloadContent(
            @PathVariable UUID claimId,
            @PathVariable UUID id) {
        ClaimDocumentService.DocumentDownload dl = service.streamDocument(claimId, id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + dl.filename() + "\"")
                .body(new InputStreamResource(dl.content()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    @Operation(summary = "Get document metadata")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Document found",
            content = @Content(schema = @Schema(implementation = ClaimDocumentResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Document not found", content = @Content)
    })
    public ApiResponse<ClaimDocumentResponse> get(
            @PathVariable UUID claimId,
            @PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.findOrThrow(id)));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CLAIMS_CREATE')")
    @Operation(summary = "Upload a new document",
               description = "Multipart upload. The file is persisted via DocumentStorageService; metadata is recorded with the supplied documentType. Required-documents checklist re-evaluates on each upload.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Document uploaded",
            content = @Content(schema = @Schema(implementation = ClaimDocumentResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "File too large, MIME type not allowed, or documentType missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_CREATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Claim not found", content = @Content)
    })
    public ApiResponse<ClaimDocumentResponse> upload(
            @PathVariable UUID claimId,
            @RequestParam ClaimDocumentType documentType,
            @RequestParam("file") MultipartFile file) throws IOException {
        return ApiResponse.success(toResponse(service.upload(claimId, documentType, file)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('CLAIMS_UPDATE')")
    @Operation(summary = "Remove a document",
               description = "Soft-deletes the metadata row and removes the underlying object from storage. Required-documents checklist re-evaluates after deletion.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Document removed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Document not found", content = @Content)
    })
    public void delete(@PathVariable UUID claimId, @PathVariable UUID id) {
        service.delete(claimId, id);
    }

    private ClaimDocumentResponse toResponse(ClaimDocument d) {
        return new ClaimDocumentResponse(
                d.getId(), d.getClaim().getId(),
                d.getDocumentType(), d.getFileName(),
                d.getFilePath(), d.getFileSize(),
                d.getUploadedBy(), d.getCreatedAt()
        );
    }
}
