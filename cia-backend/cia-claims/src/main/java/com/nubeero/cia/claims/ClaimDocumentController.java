package com.nubeero.cia.claims;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.claims.dto.ClaimDocumentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
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
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(
                service.findByClaimId(claimId, pageable).map(this::toResponse));
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
