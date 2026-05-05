package com.nubeero.cia.claims;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.claims.dto.ClaimDocumentResponse;
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
@RequestMapping("/api/v1/claims/{claimId}/documents")
@RequiredArgsConstructor
public class ClaimDocumentController {

    private final ClaimDocumentService service;

    @GetMapping
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    public ApiResponse<Page<ClaimDocumentResponse>> list(
            @PathVariable UUID claimId,
            @RequestParam(required = false) ClaimDocumentType documentType,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<ClaimDocument> page = documentType != null
                ? service.findByClaimIdAndType(claimId, documentType, pageable)
                : service.findByClaimId(claimId, pageable);
        return ApiResponse.success(page.map(this::toResponse));
    }

    /** Stream the document file (PDF/image/etc) for inline display or download. */
    @GetMapping("/{id}/content")
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
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
    public ApiResponse<ClaimDocumentResponse> get(
            @PathVariable UUID claimId,
            @PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.findOrThrow(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CLAIMS_CREATE')")
    public ApiResponse<ClaimDocumentResponse> upload(
            @PathVariable UUID claimId,
            @RequestParam ClaimDocumentType documentType,
            @RequestParam String fileName,
            @RequestParam String filePath,
            @RequestParam(required = false) Long fileSize) {
        return ApiResponse.success(toResponse(
                service.upload(claimId, documentType, fileName, filePath, fileSize)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('CLAIMS_UPDATE')")
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
